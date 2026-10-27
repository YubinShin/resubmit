package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.time.Clock

internal class LearningScreens(
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val terminalUi: TerminalUi,
    private val clock: Clock,
) {
    private val menu = ChoiceMenu(input, output, terminalUi)

    fun selectProblem(catalog: CatalogSnapshot, insights: LearningInsights, resumableIds: Set<String>, repository: RepositoryAnalysis? = null): Problem? {
        val recommendations = recommendProblems(catalog.entries, insights, clock, resumableIds, repository)
        val summary = buildString {
            appendLine("${catalog.entries.size}문제 · 이 CLI에서 자동 채점 ${catalog.entries.count { it.executable != null }}문제")
            (catalog.notices + insights.notices).forEach { appendLine(it) }
            if (insights.problems.isEmpty()) appendLine("관찰 기록이 없습니다. 홈의 초기 진단으로 시작할 수 있습니다.")
            recommendations.firstOrNull()?.let { appendLine("추천: ${it.entry.title}\n${it.reason}") }
        }.trim()
        while (true) {
            val selected = menu.select("학습 시작", summary,
                listOf("추천 문제", "이어 풀기", "복습할 문제", "유형별 연습", "문제 검색", "전체 탐색", "홈으로")) ?: return null
            val entries = when (selected) {
                0 -> recommendations.map { it.entry }
                1 -> catalog.entries.filter { it.executable?.id in resumableIds }
                2 -> catalog.entries.filter { entry ->
                    val observation = insights.problems[entry.executable?.id]
                    observation?.passed == true
                }
                3 -> {
                    val skills = catalog.entries.flatMap { it.skills }.distinct().sorted()
                    val skill = choosePaged("유형별 연습", skills, { it }) ?: continue
                    catalog.entries.filter { skill in it.skills }
                }
                4 -> {
                    output.print("제목·ID·유형 검색 (빈 입력: 돌아가기): ")
                    output.flush()
                    val query = input.readLine()?.trim() ?: return null
                    if (query.isEmpty()) continue
                    catalog.entries.filter { entry ->
                        listOf(entry.title, entry.sourceId, entry.source).plus(entry.skills).any { it.contains(query, ignoreCase = true) }
                    }
                }
                5 -> catalog.entries
                else -> return null
            }
            val entry = choosePaged("문제 탐색", entries, ::entryLabel) ?: continue
            val recommendation = recommendations.firstOrNull { it.entry.key == entry.key }
            val details = buildString {
                appendLine("${entry.source} · ${entry.sourceId} · ${entry.difficulty ?: "난이도 미확인"}")
                appendLine(entry.skills.joinToString(", "))
                appendLine(entry.description)
                entry.url?.let { appendLine(it) }
                recommendation?.let { appendLine("추천 이유: ${it.reason}") }
                if (entry.executable == null) appendLine("이 CLI의 채점 자료가 없습니다. 원본 code-rpg에서 풀이하세요.")
            }.trim()
            while (true) {
                val options = if (entry.executable != null) listOf("풀이 시작", "추천 근거", "돌아가기") else listOf("돌아가기")
                when (menu.select(entry.title, details, options)) {
                    0 -> { if (entry.executable != null) return entry.executable else break }
                    1 -> showText("추천 근거", recommendation?.evidence?.joinToString("\n")?.takeIf { it.isNotEmpty() }
                        ?: "기존 제출 근거가 없습니다. 카탈로그 유형과 관찰 유무를 기준으로 추천했습니다.")
                    else -> break
                }
            }
        }
    }

    fun profile(
        catalog: CatalogSnapshot,
        insights: LearningInsights,
        profile: SelfReportReadResult?,
        diagnostic: DiagnosticSessionReadResult?,
        repository: RepositoryAnalysis? = null,
    ): ProfileAction {
        val claimed = (profile as? SelfReportReadResult.Found)?.profile
        val summary = buildString {
            appendLine("학습 목표: ${claimed?.learningGoal ?: "미입력"}")
            appendLine("선호 언어: ${claimed?.programmingLanguage ?: "미입력"}")
            appendLine("자기보고: ${claimed?.codingExperience?.label() ?: "미입력"}")
            if (profile is SelfReportReadResult.Failed) appendLine("자기보고 조회 실패: ${profile.message}")
            val observations = insights.problems.values
            appendLine("관찰 ${observations.count { it.assessable }}문제 · 제출 ${observations.sumOf { it.attempts }}회")
            appendLine("힌트 기록 없음은 외부 도움 없이 해결했다는 뜻이 아닙니다.")
            insights.notices.forEach { appendLine(it) }
            val relevant = insights.skills.filter { it.problems.isNotEmpty() }
            relevant.take(5).forEach { appendLine("${it.skill}: ${it.summary}") }
            if (relevant.isEmpty()) appendLine("유형별 관찰 부족 · 초기 진단이나 문제 풀이를 시작하세요.")
            if (relevant.size > 5) appendLine("나머지 유형은 ‘유형별 근거’에서 확인할 수 있습니다.")
            if (repository != null) {
                appendLine("저장소 분석: ${repository.files.count { it.duplicateOf == null && it.error == null }}개 Java 파일 · ${repository.analyzedAt}")
                appendLine(if (repository.stale) "소스 또는 분석 설정이 변경됐습니다. 다시 분석하세요." else "코드 패턴은 참고 근거이며 숙련도 판정에 합산하지 않습니다.")
                repository.notices.forEach { appendLine(it) }
            }
            recommendProblems(catalog.entries, insights, clock, repository = repository).firstOrNull()?.let {
                appendLine("다음 학습: ${it.entry.title} · ${it.reason}")
            }
        }.trim()
        while (true) {
            when (menu.select("내 프로필", summary,
                listOf("유형별 근거", "초기 진단 결과", "학습 목표·자기보고 수정", "추천 문제로 이동", "저장소 코드 분석", "홈으로"))) {
                0 -> {
                    val skill = choosePaged("유형별 관찰", insights.skills, { "${it.skill} · ${it.summary}" }) ?: continue
                    showText(skill.skill, buildString {
                        appendLine("자기보고: ${claimed?.claimedSkillExperience?.get(skill.skill)?.label() ?: "미입력"}")
                        appendLine("관찰: ${skill.summary}")
                        skill.problems.forEach { problem ->
                            appendLine("\n${problem.problemId}")
                            problem.evidence.forEach { appendLine(it) }
                        }
                    })
                }
                1 -> showText("초기 진단 결과", when (diagnostic) {
                    is DiagnosticSessionReadResult.Found -> buildString {
                        appendLine("${diagnostic.session.sessionId} · ${diagnostic.session.status}")
                        diagnostic.session.observations.forEach {
                            appendLine("${it.problemId}: ${it.outcome.label()} · 제출 ${it.attemptCount}회 · 힌트 ${it.hintLevelViewed ?: "없음"}")
                        }
                    }
                    is DiagnosticSessionReadResult.Failed -> "진단 조회 실패: ${diagnostic.message}"
                    else -> "저장된 진단이 없습니다. 홈에서 초기 진단을 시작하세요."
                })
                2 -> return ProfileAction.EDIT
                3 -> return ProfileAction.LEARN
                4 -> {
                    if (repository == null) return ProfileAction.ANALYZE
                    when (menu.select("저장소 코드 분석", "${repository.root} · ${repository.analyzerVersion} · Java ${repository.javaVersion}\n" +
                        "Java 구문만 분석합니다. 작성자·정답 여부·독립 해결 여부는 확인하지 않습니다.\n" +
                        "${if (repository.stale) "오래된 분석 · 다시 분석 필요" else "저장된 소스 해시 확인됨"}",
                        listOf("파일별 근거", "다시 분석", "돌아가기"))) {
                        0 -> {
                            val file = choosePaged("분석 파일", repository.files, {
                                "${it.path} · ${it.error ?: it.duplicateOf?.let { original -> "중복: $original" } ?: "패턴 ${it.patterns.size}개"}"
                            }) ?: continue
                            showText(file.path, buildString {
                                appendLine("${repository.root}/${file.path}\nSHA-256 ${file.sha256}")
                                file.error?.let { appendLine(it) }
                                file.duplicateOf?.let { appendLine("동일 내용의 파일: $it · 중복 집계 제외") }
                                if (file.patterns.isEmpty()) appendLine("표시할 패턴 없음 · 능력이 없다는 뜻이 아닙니다.")
                                file.patterns.forEach {
                                    appendLine("${it.line}행 · ${it.method} · ${it.label} · 문제 ${it.problemIds.joinToString().ifEmpty { "연결 미확인" }}")
                                }
                            })
                        }
                        1 -> return ProfileAction.ANALYZE
                    }
                }
                else -> return ProfileAction.BACK
            }
        }
    }

    private fun entryLabel(entry: CatalogEntry): String =
        "${entry.title} · ${entry.platform ?: entry.source} · ${entry.skills.joinToString(", ")} · ${if (entry.executable == null) "외부 풀이" else "자동 채점"}"

    private fun <T> choosePaged(title: String, items: List<T>, label: (T) -> String): T? {
        if (items.isEmpty()) { showText(title, "조건에 맞는 항목이 없습니다."); return null }
        var page = 0
        val pageSize = 8
        val lastPage = (items.size - 1) / pageSize
        while (true) {
            val visible = items.drop(page * pageSize).take(pageSize)
            val navigation = buildList {
                if (page < lastPage) add("다음 페이지")
                if (page > 0) add("이전 페이지")
                add("돌아가기")
            }
            val selected = menu.select(title, "${page + 1}/${lastPage + 1} 페이지 · 전체 ${items.size}개",
                visible.map(label) + navigation) ?: return null
            if (selected < visible.size) return visible[selected]
            when (navigation[selected - visible.size]) {
                "다음 페이지" -> page++
                "이전 페이지" -> page--
                else -> return null
            }
        }
    }

    private fun showText(title: String, content: String) {
        terminalUi.clearScreen(output)
        output.println(terminalUi.bold(title))
        content.lineSequence().forEach { line -> terminalUi.wrap(line).forEach(output::println) }
        output.println("\n[Enter] 돌아가기")
        output.flush()
        input.readLine()
    }
}

internal enum class ProfileAction { EDIT, LEARN, ANALYZE, BACK }
