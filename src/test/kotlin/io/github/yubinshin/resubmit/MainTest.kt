package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse

class MainTest {
    @Test
    fun `provider exception details never reach CLI output or history`() {
        val projectRoot = createTempDirectory("resubmit-cli-safe-error-")
        val secret = "SENTINEL_PRIVATE_HEADER"
        val history = projectRoot.resolve("history.jsonl")
        try {
            val provider = OpenAiReviewProvider(secret, "test-model", JsonHttpClient { _, _, _ ->
                throw IllegalArgumentException("invalid header value: $secret")
            })
            val result = runCli(projectRoot, "\nq", reviewProvider = provider,
                attemptRecorder = JsonlAttemptRecorder(history))
            assertEquals(0, result.exitCode)
            assertContains(result.output, "UNAVAILABLE")
            assertFalse(result.output.contains(secret))
            assertFalse(history.readText().contains(secret))
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `application banner identifies the project`() {
        assertEquals(
            "Re:Submit — Evidence-based Coding Test Coach",
            applicationBanner(),
        )
    }

    @Test
    fun `cli creates a workspace and accepts enter as submission`() {
        val projectRoot = createTempDirectory("resubmit-cli-test")
        try {
            val result = runCli(projectRoot, "")

            assertEquals(0, result.exitCode)
            assertContains(result.output, "Re:Submit / 부호 선택 합계")
            assertContains(result.output, "문제      target-number-demo")
            assertContains(result.output, "작업 파일을 만들었습니다.")
            assertContains(result.output, "실행 결과")
            assertContains(result.output, "컴파일")
            assertContains(result.output, "PASS                1 ms")
            assertContains(result.output, "이력 저장됨")
            assertContains(result.output, "[Enter] 다음 문제  [m] 메뉴  [q] 종료")
            assertEquals(false, result.output.contains("[h] 1단계 힌트"))
            assertEquals(1, result.output.split("Solution.java").size - 1)
            assertEquals(1, projectRoot.resolve(".resubmit/sessions").toFile().listFiles()?.size)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `enter after a passing solution selects the next problem`() {
        val projectRoot = createTempDirectory("resubmit-next-problem-test")
        var selections = 0
        try {
            val result = runCli(
                projectRoot,
                "\n\nq",
                problemSelector = ProblemSelector { problems -> problems[selections++] },
            )

            assertEquals(0, result.exitCode)
            assertEquals(2, selections)
            assertContains(result.output, "Re:Submit / ${ProblemCatalog.all[1].title}")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `offers next problem after tests pass even when review is unavailable`() {
        val projectRoot = createTempDirectory("resubmit-next-after-review-error-test")
        try {
            val result = runCli(
                projectRoot,
                "\nq",
                reviewProvider = ReviewProvider { error("review service unavailable") },
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "리뷰")
            assertContains(result.output, "[Enter] 다음 문제  [m] 메뉴  [q] 종료")
            assertEquals(false, result.output.contains("[e] 다시 편집"))
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workspace contains problem and solution templates`() {
        val projectRoot = createTempDirectory("resubmit-workspace-test")
        try {
            val workspace = workspaceFactory(projectRoot).create(DemoProblem.targetNumber)

            assertEquals("20261007-150000-target-number-demo", workspace.sessionId)
            assertEquals(true, workspace.problemFile.exists())
            assertEquals(true, workspace.solutionFile.exists())
            assertContains(workspace.problemFile.readText(), "# 부호 선택 합계")
            assertContains(workspace.solutionFile.readText(), "public class Solution")
            assertContains(workspace.solutionFile.readText(), "// TODO: 구현하세요.")
            assertContains(workspace.harnessFile.readText(), "public class TestHarness")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workspace creation never overwrites an existing session`() {
        val projectRoot = createTempDirectory("resubmit-collision-test")
        try {
            val factory = workspaceFactory(projectRoot)
            val first = factory.create(DemoProblem.targetNumber)
            first.solutionFile.toFile().writeText("user code")
            val second = factory.create(DemoProblem.targetNumber)

            assertNotEquals(first.directory, second.directory)
            assertEquals("user code", first.solutionFile.readText())
            assertEquals("20261007-150000-target-number-demo-2", second.sessionId)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resumes the latest unfinished session without creating another workspace`() {
        val projectRoot = createTempDirectory("resubmit-resume-test")
        try {
            val first = runCli(projectRoot, "q")
            val second = runCli(projectRoot, "r\nq")

            assertEquals(0, first.exitCode)
            assertEquals(0, second.exitCode)
            assertContains(second.output, "중단된 세션")
            assertContains(second.output, "기존 작업 파일을 이어서 사용합니다.")
            assertEquals(1, projectRoot.resolve(".resubmit/sessions").toFile().listFiles()?.size)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `creates a new workspace when user declines session resume`() {
        val projectRoot = createTempDirectory("resubmit-new-session-test")
        try {
            runCli(projectRoot, "q")
            val second = runCli(projectRoot, "n\nq")

            assertEquals(0, second.exitCode)
            assertContains(second.output, "중단된 세션")
            assertContains(second.output, "작업 파일을 만들었습니다.")
            assertEquals(2, projectRoot.resolve(".resubmit/sessions").toFile().listFiles()?.size)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `does not offer a completed session for resume`() {
        val projectRoot = createTempDirectory("resubmit-completed-session-test")
        try {
            val workspace = workspaceFactory(projectRoot).create(DemoProblem.targetNumber)
            val completed = AttemptRecord(
                sessionId = workspace.sessionId,
                attemptId = "${workspace.sessionId}-attempt-001",
                problemId = DemoProblem.targetNumber.id,
                submittedAt = "2026-10-07T15:00+09:00",
                sourceSha256 = "source",
                compilation = CompilationRecord(CompilationStatus.PASSED),
                tests = TestRecord(TestStatus.PASSED, passedCount = 4, totalCount = 4),
                review = ReviewRecord(ReviewStatus.READY),
            )

            val result = runCli(
                projectRoot,
                "q",
                attemptHistoryReader = AttemptHistoryReader { HistoryReadResult.Found(completed) },
            )

            assertEquals(0, result.exitCode)
            assertEquals(false, result.output.contains("중단된 세션이 있습니다:"))
            assertEquals(2, projectRoot.resolve(".resubmit/sessions").toFile().listFiles()?.size)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `quit describes preserved files without claiming session state was saved`() {
        val projectRoot = createTempDirectory("resubmit-quit-test")
        try {
            val result = runCli(projectRoot, "q")

            assertEquals(0, result.exitCode)
            assertContains(result.output, "[q] 종료")
            assertContains(result.output, "작업 파일을 유지하고 종료합니다.")
            assertEquals(1, result.output.split("Solution.java").size - 1)
            assertEquals(false, result.output.contains("세션을 저장했습니다"))
            assertEquals(false, result.output.contains("[q: 저장 후 종료]"))
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns to submission prompt after editor exits normally`() {
        val projectRoot = createTempDirectory("resubmit-editor-success-test")
        var openedFile: Path? = null
        try {
            val result = runCli(
                projectRoot,
                "e\nq",
                editorLauncher = EditorLauncher { file ->
                    openedFile = file
                    EditorLaunchResult.Completed(0)
                },
            )

            assertEquals(0, result.exitCode)
            assertEquals("Solution.java", openedFile?.fileName.toString())
            assertContains(result.output, "에디터가 종료되었습니다. 제출 대기 화면으로 돌아갑니다.")
            assertEquals(2, result.output.split("제출 준비").size - 1)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `editor failure preserves solution and allows manual editing`() {
        val projectRoot = createTempDirectory("resubmit-editor-failure-test")
        try {
            val result = runCli(
                projectRoot,
                "e\nq",
                editorLauncher = EditorLauncher { EditorLaunchResult.Failed("not found") },
            )
            val solutionFile = projectRoot.resolve(
                ".resubmit/sessions/20261007-150000-target-number-demo/Solution.java",
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "에디터 실행 실패: not found")
            assertContains(result.output, "수동 편집: ${solutionFile.toAbsolutePath().normalize()}")
            assertContains(solutionFile.readText(), "// TODO: 구현하세요.")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing editor configuration shows manual editing path`() {
        val projectRoot = createTempDirectory("resubmit-editor-unset-test")
        try {
            val result = runCli(projectRoot, "e\nq")

            assertEquals(0, result.exitCode)
            assertContains(result.output, "에디터가 설정되지 않았습니다.")
            assertContains(result.output, "수동 편집:")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `opens the resumed session solution through the same editor path`() {
        val projectRoot = createTempDirectory("resubmit-resumed-editor-test")
        var openedFile: Path? = null
        try {
            runCli(projectRoot, "q")
            val result = runCli(
                projectRoot,
                "r\ne\nq",
                editorLauncher = EditorLauncher { file ->
                    openedFile = file
                    EditorLaunchResult.Completed(0)
                },
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "기존 작업 파일을 이어서 사용합니다.")
            assertEquals(
                projectRoot.resolve(".resubmit/sessions/20261007-150000-target-number-demo/Solution.java")
                    .toAbsolutePath().normalize(),
                openedFile,
            )
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `review edit command uses the configured editor path`() {
        val projectRoot = createTempDirectory("resubmit-review-editor-test")
        var openCount = 0
        try {
            val result = runCli(
                projectRoot,
                "\ne\nq",
                testRunner = wrongAnswerTestRunner(),
                editorLauncher = EditorLauncher {
                    openCount += 1
                    EditorLaunchResult.Completed(0)
                },
            )

            assertEquals(0, result.exitCode)
            assertEquals(1, openCount)
            assertContains(result.output, "에디터가 종료되었습니다. 제출 대기 화면으로 돌아갑니다.")
            assertContains(result.output, "재제출 준비")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns infrastructure exit code when compiler runner fails`() {
        val projectRoot = createTempDirectory("resubmit-compiler-error-test")
        try {
            val result = runCli(
                projectRoot,
                "",
                codeRunner = CodeRunner { _, _ -> CompilationResult.RunnerError("javac unavailable") },
            )

            assertEquals(3, result.exitCode)
            assertContains(result.output, "컴파일")
            assertContains(result.output, "RUNNER_ERROR")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns infrastructure exit code when test protocol fails`() {
        val projectRoot = createTempDirectory("resubmit-test-error-test")
        try {
            val result = runCli(
                projectRoot,
                "",
                testRunner = TestRunner { _, _ -> TestRunResult.ExecutionError(0, 1, "invalid protocol") },
            )

            assertEquals(3, result.exitCode)
            assertContains(result.output, "테스트")
            assertContains(result.output, "EXECUTION_ERROR")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns infrastructure exit code when history cannot be written`() {
        val projectRoot = createTempDirectory("resubmit-history-error-test")
        try {
            val result = runCli(
                projectRoot,
                "",
                attemptRecorder = AttemptRecorder { _, _, _, _, _, _ -> HistoryWriteResult.Failed("read only") },
            )

            assertEquals(3, result.exitCode)
            assertContains(result.output, "이력 저장 실패")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `shows fixture hint only after user requests it`() {
        val projectRoot = createTempDirectory("resubmit-hint-test")
        try {
            val result = runCli(
                projectRoot,
                "\nh\nq",
                testRunner = wrongAnswerTestRunner(),
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "리뷰")
            assertContains(result.output, "READY")
            assertContains(result.output, "[h] 1단계 힌트")
            assertContains(result.output, "HINT 1/3")
            assertContains(result.output, "방향만 제시")
            assertContains(result.output, "더하기와 빼기")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reveals three hint levels in order and records each viewed level`() {
        val projectRoot = createTempDirectory("resubmit-multi-hint-test")
        val viewedLevels = mutableListOf<Int>()
        try {
            val result = runCli(
                projectRoot,
                "\nh\nh\nh\nh\nq",
                testRunner = wrongAnswerTestRunner(),
                attemptRecorder = AttemptRecorder { _, _, _, _, _, review ->
                    if (review is ReviewHistory.Ready && review.hintLevelViewed != null) {
                        viewedLevels += review.hintLevelViewed
                    }
                    HistoryWriteResult.Success(projectRoot.resolve("attempts.jsonl"))
                },
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "HINT 1/3")
            assertContains(result.output, "방향만 제시")
            assertContains(result.output, "HINT 2/3")
            assertContains(result.output, "코드의 문제 지점")
            assertContains(result.output, "HINT 3/3")
            assertContains(result.output, "의사코드와 개선 방향")
            assertContains(result.output, "현재 제공할 수 있는 힌트는 모두 확인했습니다.")
            assertEquals(listOf(1, 2, 3), viewedLevels)
            assertEquals(1, result.output.split("이력 저장됨").size - 1)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `does not print hint content when user quits review`() {
        val projectRoot = createTempDirectory("resubmit-hidden-hint-test")
        try {
            val result = runCli(
                projectRoot,
                "\nq",
                testRunner = wrongAnswerTestRunner(),
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "READY")
            assertEquals(false, result.output.contains("HINT 1/3"))
            assertEquals(false, result.output.contains("더하기와 빼기"))
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps successful execution when review provider fails`() {
        val projectRoot = createTempDirectory("resubmit-review-error-test")
        try {
            val result = runCli(
                projectRoot,
                "",
                reviewProvider = ReviewProvider { error("fixture unavailable") },
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "UNAVAILABLE")
            assertContains(result.output, "fixture unavailable")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `passes submitted source snapshot to review provider`() {
        val projectRoot = createTempDirectory("resubmit-review-source-test")
        var reviewedSource = ""
        try {
            val result = runCli(
                projectRoot,
                "\nq",
                reviewProvider = ReviewProvider { request ->
                    reviewedSource = request.sourceCode
                    ReviewResult.Unavailable("review skipped")
                },
            )

            assertEquals(0, result.exitCode)
            assertContains(reviewedSource, "public class Solution")
            assertContains(reviewedSource, "return 0")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid review input explains choices without repeating actions`() {
        val projectRoot = createTempDirectory("resubmit-review-retry-test")
        try {
            val result = runCli(
                projectRoot,
                "\n아무거나\nq",
                testRunner = wrongAnswerTestRunner(),
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "입력을 이해하지 못했습니다.")
            assertEquals(1, result.output.split("[h] 1단계 힌트").size - 1)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `accepts Korean keyboard equivalents for review commands`() {
        val projectRoot = createTempDirectory("resubmit-korean-key-test")
        try {
            val result = runCli(
                projectRoot,
                "\nㅗ\nq",
                testRunner = wrongAnswerTestRunner(),
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "HINT 1/3")
            assertContains(result.output, "방향만 제시")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps session open for editing and creates a new attempt on resubmission`() {
        val projectRoot = createTempDirectory("resubmit-session-loop-test")
        val attemptIds = mutableListOf<String>()
        val compilationDirectories = mutableListOf<Path>()
        val reviews = mutableListOf<ReviewHistory>()
        try {
            val result = runCli(
                projectRoot,
                "\nh\ne\n\nq",
                codeRunner = CodeRunner { _, outputDirectory ->
                    compilationDirectories.add(outputDirectory)
                    CompilationResult.Success(outputDirectory, 1, "", false)
                },
                testRunner = wrongAnswerTestRunner(),
                attemptRecorder = AttemptRecorder { _, snapshot, _, _, _, review ->
                    attemptIds += snapshot.attemptId
                    reviews += review
                    HistoryWriteResult.Success(projectRoot.resolve("attempts.jsonl"))
                },
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "[e] 다시 편집")
            assertContains(result.output, "재제출 준비")
            assertEquals(2, result.output.split("실행 결과").size - 1)
            assertEquals(2, result.output.split("이력 저장됨").size - 1)
            assertEquals(2, attemptIds.distinct().size)
            assertEquals(2, compilationDirectories.distinct().size)
            assertEquals("attempt-001", compilationDirectories.first().parent.fileName.toString())
            assertEquals("attempt-002", compilationDirectories.last().parent.fileName.toString())
            assertEquals(true, attemptIds.first().endsWith("attempt-001"))
            assertEquals(true, attemptIds.last().endsWith("attempt-002"))
            val finalReviews = attemptIds.zip(reviews).groupBy({ it.first }, { it.second }).values.map(List<ReviewHistory>::last)
            assertEquals(true, (finalReviews[0] as ReviewHistory.Ready).hintViewed)
            assertEquals(false, (finalReviews[1] as ReviewHistory.Ready).hintViewed)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `records completed result before waiting for review input`() {
        val projectRoot = createTempDirectory("resubmit-durable-attempt-test")
        val reviews = mutableListOf<ReviewHistory>()
        try {
            val result = runCli(
                projectRoot,
                "",
                testRunner = wrongAnswerTestRunner(),
                attemptRecorder = AttemptRecorder { _, _, _, _, _, review ->
                    reviews += review
                    HistoryWriteResult.Success(projectRoot.resolve("attempts.jsonl"))
                },
            )

            assertEquals(0, result.exitCode)
            assertEquals(ReviewHistory.NotRun, reviews.first())
            assertEquals(false, (reviews.last() as ReviewHistory.Ready).hintViewed)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `records hint view before waiting for the next command`() {
        val projectRoot = createTempDirectory("resubmit-immediate-hint-history-test")
        val reviews = mutableListOf<ReviewHistory>()
        try {
            assertFailsWith<SimulatedCrash> {
                runCli(
                    projectRoot = projectRoot,
                    command = "",
                    testRunner = wrongAnswerTestRunner(),
                    attemptRecorder = AttemptRecorder { _, _, _, _, _, review ->
                        reviews += review
                        HistoryWriteResult.Success(projectRoot.resolve("attempts.jsonl"))
                    },
                    inputOverride = CrashAfterHintInput(),
                )
            }

            assertEquals(true, (reviews.last() as ReviewHistory.Ready).hintViewed)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns infrastructure error when immediate hint history write fails`() {
        val projectRoot = createTempDirectory("resubmit-hint-history-error-test")
        var writes = 0
        try {
            val result = runCli(
                projectRoot = projectRoot,
                command = "\nh",
                testRunner = wrongAnswerTestRunner(),
                attemptRecorder = AttemptRecorder { _, _, _, _, _, _ ->
                    writes += 1
                    if (writes == 3) {
                        HistoryWriteResult.Failed("read only")
                    } else {
                        HistoryWriteResult.Success(projectRoot.resolve("attempts.jsonl"))
                    }
                },
            )

            assertEquals(3, result.exitCode)
            assertEquals(3, writes)
            assertContains(result.output, "이력 저장 실패: read only")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `shows previous attempt comparison before resubmission review`() {
        val projectRoot = createTempDirectory("resubmit-comparison-test")
        var historyReads = 0
        var testRuns = 0
        val previous = AttemptRecord(
            sessionId = "previous-session-value-is-ignored-by-stub",
            attemptId = "previous-attempt-001",
            problemId = DemoProblem.targetNumber.id,
            submittedAt = "2026-10-07T15:00+09:00",
            sourceSha256 = "previous-source",
            compilation = CompilationRecord(status = CompilationStatus.PASSED),
            tests = TestRecord(status = TestStatus.FAILED, passedCount = 1, totalCount = 4),
            review = ReviewRecord(
                status = ReviewStatus.READY,
                provider = "gemini",
                failureType = ReviewFailureType.WRONG_ANSWER,
                hintLevel = 1,
                hintViewed = true,
            ),
        )
        try {
            val result = runCli(
                projectRoot,
                "\nh\ne\n\nq",
                testRunner = TestRunner { _, testCases ->
                    testRuns += 1
                    TestRunResult.Completed(
                        cases = testCases.map { testCase ->
                            val passed = testRuns == 2
                            TestCaseResult.Value(
                                testCase.id,
                                passed,
                                testCase.expected.toString(),
                                if (passed) testCase.expected.toString() else "0",
                            )
                        },
                        durationMs = 1,
                    )
                },
                attemptHistoryReader = AttemptHistoryReader {
                    historyReads += 1
                    if (historyReads == 1) HistoryReadResult.NotFound else HistoryReadResult.Found(previous)
                },
            )

            assertEquals(0, result.exitCode)
            assertContains(result.output, "ATTEMPT 2 비교")
            assertContains(result.output, "1/4 → 4/4")
            assertContains(result.output, "FAILED → PASSED")
            assertContains(result.output, "1단계 사용")
            assertContains(result.output, "source hash 변경 확인")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    private fun runCli(
        projectRoot: Path,
        command: String,
        codeRunner: CodeRunner = successfulCodeRunner(),
        testRunner: TestRunner = successfulTestRunner(),
        reviewProvider: ReviewProvider = FixtureReviewProvider(),
        attemptHistoryReader: AttemptHistoryReader = AttemptHistoryReader { HistoryReadResult.NotFound },
        attemptRecorder: AttemptRecorder = successfulAttemptRecorder(projectRoot),
        inputOverride: BufferedReader? = null,
        editorLauncher: EditorLauncher = EnvironmentEditorLauncher(emptyMap()),
        problemSelector: ProblemSelector = ProblemSelector { it.firstOrNull() },
    ): CliResult {
        val output = StringWriter()
        val exitCode = CodingCoachCli(
            input = inputOverride ?: BufferedReader(StringReader("$command\n")),
            output = PrintWriter(output, true),
            workspaceFactory = workspaceFactory(projectRoot),
            codeRunner = codeRunner,
            testRunner = testRunner,
            snapshotFactory = SubmissionSnapshotFactory(),
            reviewProvider = reviewProvider,
            attemptHistoryReader = attemptHistoryReader,
            attemptRecorder = attemptRecorder,
            editorLauncher = editorLauncher,
            problemSelector = problemSelector,
        ).run()

        return CliResult(exitCode, output.toString())
    }

    private class CrashAfterHintInput : BufferedReader(StringReader("")) {
        private var reads = 0

        override fun readLine(): String = when (reads++) {
            0 -> ""
            1 -> "h"
            else -> throw SimulatedCrash()
        }
    }

    private class SimulatedCrash : Error()

    private fun successfulCodeRunner(): CodeRunner = CodeRunner { _, outputDirectory ->
        CompilationResult.Success(
            outputDirectory = outputDirectory,
            durationMs = 1,
            diagnostics = "",
            outputTruncated = false,
        )
    }

    private fun successfulTestRunner(): TestRunner = TestRunner { _, testCases ->
        TestRunResult.Completed(
            cases = testCases.map { testCase ->
                TestCaseResult.Value(testCase.id, true, testCase.expected.toString(), testCase.expected.toString())
            },
            durationMs = 1,
        )
    }

    private fun wrongAnswerTestRunner(): TestRunner = TestRunner { _, testCases ->
        TestRunResult.Completed(
            cases = testCases.map { testCase ->
                TestCaseResult.Value(testCase.id, false, testCase.expected.toString(), "0")
            },
            durationMs = 1,
        )
    }

    private fun successfulAttemptRecorder(projectRoot: Path): AttemptRecorder =
        AttemptRecorder { _, _, _, _, _, _ -> HistoryWriteResult.Success(projectRoot.resolve("attempts.jsonl")) }

    private fun workspaceFactory(projectRoot: Path): SessionWorkspaceFactory = SessionWorkspaceFactory(
        projectRoot = projectRoot,
        clock = Clock.fixed(
            Instant.parse("2026-10-07T06:00:00Z"),
            ZoneId.of("Asia/Seoul"),
        ),
    )

    private data class CliResult(
        val exitCode: Int,
        val output: String,
    )
}
