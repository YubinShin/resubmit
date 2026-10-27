package io.github.yubinshin.resubmit

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LearningInsightsTest {
    private val entries = LocalLearningCatalog().load().entries
    private val clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `hint updates do not count as new attempts and hints carry across a session`() {
        val first = record("a1", TestStatus.FAILED)
        val hintUpdate = first.copy(review = first.review.copy(hintViewed = true, hintLevel = 1))
        val passed = record("a2", TestStatus.PASSED)
        val insights = buildLearningInsights(entries, HistoryRecordsReadResult.Found(listOf(first, hintUpdate, passed)))
        val observation = insights.problems.getValue(first.problemId)
        assertEquals(2, observation.attempts)
        assertTrue(observation.passed)
        assertTrue(observation.hintUsed)
        assertEquals(2, observation.evidence.size)
        assertEquals(1, insights.skills.first { it.skill == ProblemCatalog.twoSum.relatedSkills.first() }.problems.size)
    }

    @Test
    fun `a fresh session does not inherit old hint usage`() {
        val previous = record("a1", TestStatus.PASSED).copy(review = ReviewRecord(ReviewStatus.READY, hintViewed = true))
        val current = record("a2", TestStatus.PASSED).copy(sessionId = "new-session", submittedAt = "2026-10-14T10:00:00Z")
        val insights = buildLearningInsights(entries, HistoryRecordsReadResult.Found(listOf(previous, current)))
        assertFalse(insights.problems.getValue(previous.problemId).hintUsed)
    }

    @Test
    fun `diagnostic evidence is not double counted and missing submissions are unobserved`() {
        val attempt = record("a1", TestStatus.PASSED)
        val diagnostic = DiagnosticSessionReadResult.Found(DiagnosticSessionRecord(
            "diagnostic", listOf(attempt.problemId, ProblemCatalog.targetNumber.id), status = DiagnosticStatus.COMPLETED,
            observations = listOf(
                DiagnosticObservation(attempt.problemId, DiagnosticOutcome.PASSED, 1, elapsedSeconds = 3),
                DiagnosticObservation(ProblemCatalog.targetNumber.id, DiagnosticOutcome.NOT_SUBMITTED, 0, elapsedSeconds = 2),
            ),
        ))
        val insights = buildLearningInsights(entries, HistoryRecordsReadResult.Found(listOf(attempt)), diagnostic)
        assertEquals(1, insights.problems.getValue(attempt.problemId).attempts)
        assertEquals(2, insights.problems.getValue(attempt.problemId).evidence.size)
        assertFalse(insights.problems.getValue(ProblemCatalog.targetNumber.id).assessable)
    }

    @Test
    fun `recommendations are deterministic exclude external only and prioritize resumable work`() {
        val insights = buildLearningInsights(entries, HistoryRecordsReadResult.NotFound)
        val extra = entries.first().copy(key = "external", executable = null)
        val recommended = recommendProblems(entries + extra, insights, clock, setOf(ProblemCatalog.targetNumber.id))
        assertEquals(ProblemCatalog.targetNumber, recommended.first().entry.executable)
        assertFalse(recommended.any { it.entry.key == "external" })
        assertEquals(recommended, recommendProblems((entries + extra).reversed(), insights, clock, setOf(ProblemCatalog.targetNumber.id)))
    }

    @Test
    fun `hinted pass becomes due only after configured one day interval`() {
        val hinted = record("a1", TestStatus.PASSED).copy(review = ReviewRecord(ReviewStatus.READY, hintViewed = true))
        val old = buildLearningInsights(entries, HistoryRecordsReadResult.Found(listOf(hinted)))
        assertContains(recommendProblems(entries, old, clock).first { it.entry.executable?.id == hinted.problemId }.reason, "하루")
        val recent = buildLearningInsights(entries, HistoryRecordsReadResult.Found(listOf(hinted.copy(submittedAt = "2026-10-14T11:00:00Z"))))
        assertFalse(recommendProblems(entries, recent, clock).first { it.entry.executable?.id == hinted.problemId }.reason.contains("하루"))
    }

    @Test
    fun `history corruption is surfaced instead of showing a clean empty profile`() {
        val root = createTempDirectory("learning-history")
        try {
            val file = root.resolve("history.jsonl")
            Files.writeString(file, Json.encodeToString(record("a1", TestStatus.PASSED)) + "\nnot-json\n")
            val result = JsonlAttemptHistoryReader(file).allRecords()
            assertIs<HistoryRecordsReadResult.Failed>(result)
            assertTrue(buildLearningInsights(entries, result).notices.isNotEmpty())
            Files.writeString(file, Json.encodeToString(record("a1", TestStatus.PASSED)) + "\n")
            assertEquals(1, assertIs<HistoryRecordsReadResult.Found>(JsonlAttemptHistoryReader(file).allRecords()).attempts.size)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun record(id: String, status: TestStatus) = AttemptRecord(
        sessionId = "session", attemptId = id, problemId = ProblemCatalog.twoSum.id,
        submittedAt = "2026-10-12T12:00:00Z", sourceSha256 = "hash-$id",
        compilation = CompilationRecord(CompilationStatus.PASSED), tests = TestRecord(status),
        review = ReviewRecord(ReviewStatus.NOT_RUN),
    )
}
