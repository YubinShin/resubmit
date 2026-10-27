package io.github.yubinshin.resubmit

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.io.path.writeLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AttemptHistoryTest {
    private val jsonWithDefaults = Json { encodeDefaults = true }

    @Test
    fun `appends one complete JSON object per attempt`() {
        withTempDirectory { root ->
            val workspace = SessionWorkspaceFactory(root, fixedClock()).create(DemoProblem.targetNumber)
            val historyFile = root.resolve("history/attempts.jsonl")
            val recorder = JsonlAttemptRecorder(historyFile)
            val snapshotFactory = SubmissionSnapshotFactory(fixedClock())
            val compilation = CompilationResult.Success(
                outputDirectory = workspace.directory.resolve("classes"),
                durationMs = 120,
                diagnostics = "",
                outputTruncated = false,
            )
            val tests = TestRunResult.Completed(
                cases = listOf(
                    TestCaseResult.Value("case-1", true, "5", "5"),
                    TestCaseResult.Value("case-2", false, "2", "0"),
                ),
                durationMs = 45,
            )

            assertIs<HistoryWriteResult.Success>(
                recorder.record(
                    workspace,
                    snapshotFactory.create(workspace),
                    DemoProblem.targetNumber,
                    compilation,
                    tests,
                    ReviewHistory.Ready(
                        SubmissionReview(
                            provider = "fixture",
                            failureType = ReviewFailureType.WRONG_ANSWER,
                            relatedSkills = listOf("재귀"),
                            evidence = listOf("case-2 실패"),
                            hints = listOf("방향", "문제 지점", "개선 방향"),
                            model = "fixture-model",
                            promptVersion = REVIEW_PROMPT_VERSION,
                        ),
                        hintViewed = true,
                    ),
                ),
            )
            workspace.solutionFile.writeText("public class Solution { public int changed() { return 1; } }")
            assertIs<HistoryWriteResult.Success>(
                recorder.record(
                    workspace,
                    snapshotFactory.create(workspace),
                    DemoProblem.targetNumber,
                    compilation,
                    tests,
                    ReviewHistory.Unavailable("fixture", "review failed"),
                ),
            )

            val lines = historyFile.readLines()
            assertEquals(2, lines.size)
            val record = Json.decodeFromString<AttemptRecord>(lines.first())
            val secondRecord = Json.decodeFromString<AttemptRecord>(lines.last())
            assertEquals(3, record.schemaVersion)
            assertEquals(workspace.sessionId, record.sessionId)
            assertEquals("target-number-demo", record.problemId)
            assertEquals("2026-10-07T15:00+09:00", record.submittedAt)
            assertTrue(Regex("[0-9a-f]{64}").matches(record.sourceSha256))
            assertEquals(CompilationStatus.PASSED, record.compilation.status)
            assertEquals(TestStatus.FAILED, record.tests.status)
            assertEquals(1, record.tests.passedCount)
            assertEquals(2, record.tests.totalCount)
            assertEquals(ReviewStatus.READY, record.review.status)
            assertEquals("fixture", record.review.provider)
            assertEquals(ReviewFailureType.WRONG_ANSWER, record.review.failureType)
            assertEquals(listOf("case-2 실패"), record.review.evidence)
            assertEquals(listOf("재귀"), record.review.relatedSkills)
            assertEquals(listOf("방향", "문제 지점", "개선 방향"), record.review.hints)
            assertEquals("fixture-model", record.review.model)
            assertEquals(REVIEW_PROMPT_VERSION, record.review.promptVersion)
            assertEquals(1, record.review.hintLevel)
            assertEquals(true, record.review.hintViewed)
            assertEquals(ReviewStatus.UNAVAILABLE, secondRecord.review.status)
            assertEquals("fixture", secondRecord.review.provider)
            assertEquals("review failed", secondRecord.review.errorMessage)
            assertEquals("${workspace.sessionId}-attempt-001", record.attemptId)
            assertEquals("${workspace.sessionId}-attempt-002", secondRecord.attemptId)
            assertTrue(record.sourceSha256 != secondRecord.sourceSha256)
            val latest = assertIs<HistoryReadResult.Found>(
                JsonlAttemptHistoryReader(historyFile).latest(workspace.sessionId),
            ).attempt
            assertEquals(secondRecord.attemptId, latest.attemptId)
        }
    }

    @Test
    fun `finds the latest previous attempt while skipping revisions of current attempt`() {
        withTempDirectory { root ->
            val historyFile = root.resolve("attempts.jsonl")
            val json = Json { encodeDefaults = true }
            val first = AttemptRecord(
                sessionId = "session",
                attemptId = "session-attempt-001",
                problemId = DemoProblem.targetNumber.id,
                submittedAt = "first",
                sourceSha256 = "first",
                compilation = CompilationRecord(CompilationStatus.PASSED),
                tests = TestRecord(TestStatus.FAILED, passedCount = 1, totalCount = 4),
                review = ReviewRecord(ReviewStatus.READY, hints = listOf("first hint")),
            )
            val current = first.copy(attemptId = "session-attempt-002", submittedAt = "current")
            historyFile.writeLines(listOf(json.encodeToString(first), json.encodeToString(current), json.encodeToString(current.copy(
                review = ReviewRecord(ReviewStatus.READY, hintLevel = 1, hintViewed = true),
            ))))

            val previous = assertIs<HistoryReadResult.Found>(
                JsonlAttemptHistoryReader(historyFile).previous("session", current.attemptId),
            ).attempt

            assertEquals(first.attemptId, previous.attemptId)
            assertEquals(listOf("first hint"), previous.review.hints)
        }
    }

    @Test
    fun `reads schema version two records with defaults for new review fields`() {
        withTempDirectory { root ->
            val historyFile = root.resolve("attempts.jsonl")
            val oldRecord = AttemptRecord(
                schemaVersion = 2,
                sessionId = "session-v2",
                attemptId = "session-v2-attempt-001",
                problemId = DemoProblem.targetNumber.id,
                submittedAt = "old",
                sourceSha256 = "old",
                compilation = CompilationRecord(CompilationStatus.PASSED),
                tests = TestRecord(TestStatus.PASSED),
                review = ReviewRecord(ReviewStatus.NOT_RUN),
            )
            historyFile.writeText(jsonWithDefaults.encodeToString(oldRecord))

            val result = assertIs<HistoryReadResult.Found>(JsonlAttemptHistoryReader(historyFile).latest("session-v2"))

            assertEquals(2, result.attempt.schemaVersion)
            assertEquals(emptyList(), result.attempt.review.hints)
        }
    }

    @Test
    fun `records compile failure with tests not run`() {
        withTempDirectory { root ->
            val workspace = SessionWorkspaceFactory(root, fixedClock()).create(DemoProblem.targetNumber)
            val historyFile = root.resolve("attempts.jsonl")
            val recorder = JsonlAttemptRecorder(historyFile)
            val snapshot = SubmissionSnapshotFactory(fixedClock()).create(workspace)

            recorder.record(
                workspace,
                snapshot,
                DemoProblem.targetNumber,
                CompilationResult.Failed(1, 30, "error", false),
                null,
                ReviewHistory.NotRun,
            )

            val record = Json.decodeFromString<AttemptRecord>(historyFile.readLines().single())
            assertEquals(CompilationStatus.FAILED, record.compilation.status)
            assertEquals(1, record.compilation.exitCode)
            assertEquals(TestStatus.NOT_RUN, record.tests.status)
            assertEquals(ReviewStatus.NOT_RUN, record.review.status)
            assertEquals(false, record.review.hintViewed)
        }
    }

    @Test
    fun `ignores legacy records from other sessions when current session has no attempts`() {
        withTempDirectory { root ->
            val historyFile = root.resolve("attempts.jsonl")
            historyFile.writeLines(
                listOf(
                    """{"schemaVersion":1,"sessionId":"old-session","attemptId":"old-attempt-001"}""",
                ),
            )

            val result = JsonlAttemptHistoryReader(historyFile).latest("new-session")

            assertIs<HistoryReadResult.NotFound>(result)
        }
    }

    @Test
    fun `ignores malformed and legacy records while returning the latest valid revision`() {
        withTempDirectory { root ->
            val historyFile = root.resolve("attempts.jsonl")
            val valid = AttemptRecord(
                sessionId = "current-session",
                attemptId = "current-attempt-001",
                problemId = DemoProblem.targetNumber.id,
                submittedAt = "2026-10-07T15:00+09:00",
                sourceSha256 = "source",
                compilation = CompilationRecord(CompilationStatus.PASSED),
                tests = TestRecord(TestStatus.PASSED),
                review = ReviewRecord(ReviewStatus.NOT_RUN),
            )
            historyFile.writeLines(
                listOf(
                    jsonWithDefaults.encodeToString(valid),
                    """{"schemaVersion":1,"sessionId":"current-session","attemptId":"legacy-attempt"}""",
                    "{",
                ),
            )

            val result = assertIs<HistoryReadResult.Found>(
                JsonlAttemptHistoryReader(historyFile).latest("current-session"),
            )

            assertEquals(valid.attemptId, result.attempt.attemptId)
        }
    }

    @Test
    fun `returns not found when same session contains only legacy records`() {
        withTempDirectory { root ->
            val historyFile = root.resolve("attempts.jsonl")
            historyFile.writeLines(
                listOf(
                    """{"schemaVersion":1,"sessionId":"current-session","attemptId":"legacy-attempt"}""",
                ),
            )

            assertIs<HistoryReadResult.NotFound>(
                JsonlAttemptHistoryReader(historyFile).latest("current-session"),
            )
        }
    }

    @Test
    fun `returns failure instead of hiding history write errors`() {
        withTempDirectory { root ->
            val workspace = SessionWorkspaceFactory(root, fixedClock()).create(DemoProblem.targetNumber)
            val blockedParent = root.resolve("blocked")
            blockedParent.writeText("not a directory")
            val recorder = JsonlAttemptRecorder(blockedParent.resolve("attempts.jsonl"))
            val snapshot = SubmissionSnapshotFactory(fixedClock()).create(workspace)

            val result = recorder.record(
                workspace,
                snapshot,
                DemoProblem.targetNumber,
                CompilationResult.RunnerError("runner failed"),
                null,
                ReviewHistory.NotRun,
            )

            assertIs<HistoryWriteResult.Failed>(result)
        }
    }

    private fun fixedClock(): Clock = Clock.fixed(
        Instant.parse("2026-10-07T06:00:00Z"),
        ZoneId.of("Asia/Seoul"),
    )

    private fun withTempDirectory(block: (java.nio.file.Path) -> Unit) {
        val root = createTempDirectory("resubmit-history-test")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
