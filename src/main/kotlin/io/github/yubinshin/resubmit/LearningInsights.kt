package io.github.yubinshin.resubmit

import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class ProblemObservation(
    val problemId: String,
    val passed: Boolean,
    val attempts: Int,
    val hintUsed: Boolean,
    val lastSubmittedAt: String?,
    val evidence: List<String>,
    val assessable: Boolean = true,
)

internal data class SkillObservation(val skill: String, val problems: List<ProblemObservation>) {
    val summary: String get() {
        val assessed = problems.filter { it.assessable }
        if (assessed.isEmpty()) return "관찰 부족"
        val passed = assessed.count { it.passed }
        val hinted = assessed.count { it.passed && it.hintUsed }
        return "관찰 ${assessed.size}문제 · 최근 통과 $passed · 힌트 기록 있는 통과 $hinted"
    }
}

internal data class LearningInsights(
    val problems: Map<String, ProblemObservation>,
    val skills: List<SkillObservation>,
    val notices: List<String>,
)

internal fun buildLearningInsights(
    entries: List<CatalogEntry>,
    history: HistoryRecordsReadResult,
    diagnostic: DiagnosticSessionReadResult? = null,
): LearningInsights {
    val notices = mutableListOf<String>()
    val records = when (history) {
        is HistoryRecordsReadResult.Found -> history.attempts
        is HistoryRecordsReadResult.Failed -> { notices.add("제출 이력 조회 실패: ${history.message}"); emptyList() }
        HistoryRecordsReadResult.NotFound -> emptyList()
    }
    // JSONL appends review/hint updates for the same attempt. Count an attempt only once.
    val attempts = records.groupBy { it.sessionId to it.attemptId }.values.map { updates ->
        val latest = updates.last()
        latest.copy(review = latest.review.copy(hintViewed = updates.any { it.review.hintViewed || it.review.hintLevel != null }))
    }
    val observations = attempts.groupBy(AttemptRecord::problemId).mapValues { (_, problemAttempts) ->
        val sorted = problemAttempts.sortedBy { parseSubmissionTime(it.submittedAt) ?: Instant.MIN }
        val latest = sorted.last()
        val sessionHint = sorted.any { it.sessionId == latest.sessionId && it.review.hintViewed }
        ProblemObservation(latest.problemId,
            passed = latest.compilation.status == CompilationStatus.PASSED && latest.tests.status == TestStatus.PASSED,
            attempts = sorted.size, hintUsed = sessionHint, lastSubmittedAt = latest.submittedAt,
            evidence = sorted.map {
                "제출 ${it.attemptId} · 세션 ${it.sessionId} · ${it.submittedAt} · 컴파일 ${it.compilation.status} · " +
                    "테스트 ${it.tests.status} · 힌트 ${if (it.review.hintViewed) "기록 있음" else "기록 없음"} · SHA-256 ${it.sourceSha256}"
            },
            assessable = latest.compilation.status in setOf(CompilationStatus.FAILED, CompilationStatus.TIMED_OUT) ||
                latest.tests.status in setOf(TestStatus.PASSED, TestStatus.FAILED, TestStatus.TIMED_OUT),
        )
    }.toMutableMap()
    when (diagnostic) {
        is DiagnosticSessionReadResult.Found -> diagnostic.session.observations.forEach { observation ->
            val evidence = "진단 ${diagnostic.session.sessionId} · ${observation.problemId} · ${observation.outcome.label()} · " +
                "제출 ${observation.attemptCount}회 · 힌트 ${observation.hintLevelViewed ?: "없음"}"
            val existing = observations[observation.problemId]
            if (existing != null) {
                observations[observation.problemId] = existing.copy(evidence = existing.evidence + evidence)
            } else {
                observations[observation.problemId] = ProblemObservation(observation.problemId,
                    observation.outcome == DiagnosticOutcome.PASSED, observation.attemptCount,
                    observation.hintLevelViewed != null, null, listOf(evidence),
                    assessable = observation.outcome != DiagnosticOutcome.NOT_SUBMITTED)
            }
        }
        is DiagnosticSessionReadResult.Failed -> notices.add("진단 조회 실패: ${diagnostic.message}")
        else -> Unit
    }
    val skills = entries.flatMap { it.skills }.distinct().sorted().map { skill ->
        val ids = entries.filter { skill in it.skills }.mapNotNull { it.executable?.id }.toSet()
        SkillObservation(skill, ids.mapNotNull(observations::get))
    }
    return LearningInsights(observations, skills, notices)
}

internal data class LearningRecommendation(val entry: CatalogEntry, val reason: String, val evidence: List<String>)

internal fun recommendProblems(
    entries: List<CatalogEntry>,
    insights: LearningInsights,
    clock: Clock,
    resumableIds: Set<String> = emptySet(),
    repository: RepositoryAnalysis? = null,
): List<LearningRecommendation> {
    val observedSkills = insights.skills.filter { it.problems.any { p -> p.assessable } }.map { it.skill }.toSet()
    val candidates = entries.filter { it.executable != null }.map { entry ->
        val id = requireNotNull(entry.executable).id
        val observation = insights.problems[id]
        val sourceEvidence = repository?.takeUnless { it.stale }?.files.orEmpty().flatMap { file ->
            file.patterns.filter { entry.sourceId in it.problemIds }.map {
                "소스 ${repository?.root}/${file.path}:${it.line} · ${it.label} · SHA-256 ${file.sha256}"
            }
        }
        val due = observation?.lastSubmittedAt?.let(::parseSubmissionTime)?.let {
            Duration.between(it, clock.instant()) >= Duration.ofDays(1)
        } ?: false
        val (rank, reason) = when {
            id in resumableIds -> 0 to "저장된 미완료 세션을 이어서 풀 수 있습니다."
            observation?.assessable == true && !observation.passed -> 1 to "최근 제출이 미통과여서 다시 확인할 문제입니다."
            observation?.passed == true && observation.hintUsed && due -> 2 to "힌트 기록이 있는 통과 이후 하루가 지나 복습할 문제입니다."
            observation == null && sourceEvidence.isNotEmpty() -> 3 to "연결된 풀이 코드가 있지만 수행 기록은 없어 직접 풀어 확인할 문제입니다."
            observation == null && entry.skills.any { it !in observedSkills } -> 4 to "아직 관찰하지 않은 유형을 확인할 문제입니다."
            observation == null -> 5 to "이미 연습한 유형을 다른 문제에서 확인합니다."
            observation.assessable == false -> 6 to "검증이 완료되지 않아 다시 확인할 문제입니다."
            else -> 7 to "최근 통과 기록이 있습니다. 원하면 다시 연습할 수 있습니다."
        }
        rank to LearningRecommendation(entry, reason, observation?.evidence.orEmpty() + sourceEvidence)
    }
    return candidates.sortedWith(compareBy<Pair<Int, LearningRecommendation>> { it.first }.thenBy { it.second.entry.key })
        .map { it.second }
}

private fun parseSubmissionTime(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
