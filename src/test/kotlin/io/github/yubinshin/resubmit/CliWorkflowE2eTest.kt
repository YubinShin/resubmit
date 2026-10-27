package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliWorkflowE2eTest {
    @Test
    fun `search submit and profile show fresh execution evidence end to end`() {
        val root = createTempDirectory("resubmit-learning-e2e")
        val clock = fixedClock()
        val history = root.resolve(".resubmit/history/attempts.jsonl")
        val profile = JsonSelfReportStore(root.resolve(".resubmit/profile.json"))
        val output = StringWriter()
        // Home -> learning -> search -> detail -> submit -> home -> profile -> home -> exit.
        val commands = "1\n5\n목표합 인덱스\n1\n1\n\nm\n3\n6\n5\n"
        val input = object : BufferedReader(StringReader(commands)) {
            override fun readLine(): String? {
                val value = super.readLine()
                if (value == "") {
                    val workspace = SessionWorkspaceFactory(root, clock).existing(ProblemCatalog.twoSum).single()
                    workspace.solutionFile.writeText("""
                        public class Solution {
                            public int[] solution(int[] nums, int target) {
                                for (int i = 0; i < nums.length; i++)
                                    for (int j = i + 1; j < nums.length; j++)
                                        if (nums[i] + nums[j] == target) return new int[] {i, j};
                                return new int[0];
                            }
                        }
                    """.trimIndent())
                }
                return value
            }
        }
        try {
            profile.write(SelfReportProfile(programmingLanguage = "Java",
                codingExperience = CodingExperience.UNSURE, learningGoal = "연습",
                claimedSkillExperience = emptyMap(), recordedAt = clock.instant().toString()))
            val code = CodingCoachCli(input, PrintWriter(output, true), SessionWorkspaceFactory(root, clock),
                ProcessJavaCodeRunner(), ProcessJavaTestRunner(), SubmissionSnapshotFactory(clock),
                FixtureReviewProvider(), JsonlAttemptHistoryReader(history), JsonlAttemptRecorder(history),
                selfReportStore = profile, selfReportProvider = SelfReportProvider { error("저장된 프로필 사용") },
                diagnosticSessionStore = JsonDiagnosticSessionStore(root.resolve(".resubmit/diagnostic/session.json")),
                clock = clock).run()
            assertEquals(0, code)
            assertContains(output.toString(), "3 / 3 PASS")
            assertContains(output.toString(), "내 프로필")
            assertContains(output.toString(), "관찰 1문제 · 제출 1회")
            assertContains(output.toString(), "최근 통과 1")
            assertEquals(1, assertIs<HistoryRecordsReadResult.Found>(JsonlAttemptHistoryReader(history).allRecords())
                .attempts.map { it.attemptId }.distinct().size)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `completes and persists the initial diagnostic flow`() {
        val projectRoot = createTempDirectory("resubmit-diagnostic-e2e")
        val clock = fixedClock()
        val historyFile = projectRoot.resolve(".resubmit/history/attempts.jsonl")
        val profileStore = JsonSelfReportStore(projectRoot.resolve(".resubmit/profile.json"))
        val diagnosticStore = JsonDiagnosticSessionStore(projectRoot.resolve(".resubmit/diagnostic/session.json"))
        val profile = SelfReportProfile(
            programmingLanguage = "Java",
            codingExperience = CodingExperience.FOLLOWED_EXAMPLES,
            learningGoal = "코테 준비",
            claimedSkillExperience = ProblemCatalog.all.flatMap(Problem::relatedSkills).distinct()
                .associateWith { SkillExperience.UNSURE },
            recordedAt = "2026-10-07T06:00:00Z",
        )
        val commands = listOf("2") + List(9) { "" } + "4"
        val output = StringWriter()
        try {
            assertEquals(SelfReportWriteResult.Success, profileStore.write(profile))
            val exitCode = CodingCoachCli(
                input = BufferedReader(StringReader(commands.joinToString("\n", postfix = "\n"))),
                output = PrintWriter(output, true),
                workspaceFactory = SessionWorkspaceFactory(projectRoot, clock),
                codeRunner = CodeRunner { _, outputDirectory ->
                    CompilationResult.Success(outputDirectory, 1, "", false)
                },
                testRunner = TestRunner { _, testCases ->
                    TestRunResult.Completed(
                        testCases.map { TestCaseResult.Value(it.id, true, it.expected, it.expected) },
                        1,
                    )
                },
                snapshotFactory = SubmissionSnapshotFactory(clock),
                reviewProvider = FixtureReviewProvider(),
                attemptHistoryReader = JsonlAttemptHistoryReader(historyFile),
                attemptRecorder = JsonlAttemptRecorder(historyFile),
                selfReportStore = profileStore,
                selfReportProvider = SelfReportProvider { error("기존 프로필이 있어 수집하지 않아야 합니다.") },
                diagnosticSessionStore = diagnosticStore,
                clock = clock,
            ).run()

            assertEquals(0, exitCode)
            assertContains(output.toString(), "초기 진단 결과")
            assertContains(output.toString(), "다음 학습 제안")
            val saved = assertIs<DiagnosticSessionReadResult.Found>(diagnosticStore.read()).session
            assertEquals(DiagnosticStatus.COMPLETED, saved.status)
            assertEquals(4, saved.observations.size)
            assertTrue(saved.observations.all { it.outcome == DiagnosticOutcome.PASSED && it.attemptCount == 1 })
            val attempts = historyFile.readLines().map { Json.decodeFromString<AttemptRecord>(it) }
            assertEquals(8, attempts.size)
            assertEquals(4, attempts.map(AttemptRecord::attemptId).distinct().size)
            assertEquals(saved.problemSessionIds.values.toSet(), attempts.map(AttemptRecord::sessionId).toSet())
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runs a catalog problem through the generic workspace and runner`() {
        val projectRoot = createTempDirectory("resubmit-pair-sum-e2e")
        val clock = fixedClock()
        val workspace = projectRoot.resolve(".resubmit/sessions/20261007-150000-pair-sum-count-demo")
        val historyFile = projectRoot.resolve(".resubmit/history/attempts.jsonl")
        val output = StringWriter()
        val input = object : BufferedReader(StringReader("\nq\n")) {
            private var firstRead = true

            override fun readLine(): String? {
                if (firstRead) {
                    firstRead = false
                    workspace.resolve("Solution.java").writeText(
                        """
                        import java.util.HashMap;
                        public class Solution {
                            public int solution(int[] numbers, int target) {
                                HashMap<Integer, Integer> seen = new HashMap<>();
                                int count = 0;
                                for (int number : numbers) {
                                    count += seen.getOrDefault(target - number, 0);
                                    seen.put(number, seen.getOrDefault(number, 0) + 1);
                                }
                                return count;
                            }
                        }
                        """.trimIndent(),
                    )
                }
                return super.readLine()
            }
        }
        try {
            val exitCode = CodingCoachCli(
                input = input,
                output = PrintWriter(output, true),
                workspaceFactory = SessionWorkspaceFactory(projectRoot, clock),
                codeRunner = ProcessJavaCodeRunner(),
                testRunner = ProcessJavaTestRunner(),
                snapshotFactory = SubmissionSnapshotFactory(clock),
                reviewProvider = FixtureReviewProvider(),
                attemptHistoryReader = JsonlAttemptHistoryReader(historyFile),
                attemptRecorder = JsonlAttemptRecorder(historyFile),
                problemSelector = ProblemSelector { ProblemCatalog.pairSumCount },
            ).run()

            assertEquals(0, exitCode)
            assertContains(output.toString(), "인덱스 쌍 합계")
            assertContains(output.toString(), "4 / 4 PASS")
            val record = Json.decodeFromString<AttemptRecord>(historyFile.readLines().last())
            assertEquals(ProblemCatalog.pairSumCount.id, record.problemId)
            assertTrue(Files.isRegularFile(workspace.resolve(".internal/TestHarness.java")))
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runs failed submission hint edit passing resubmission and history end to end`() {
        val projectRoot = createTempDirectory("resubmit-workflow-e2e")
        val clock = fixedClock()
        val sessionId = "20261007-150000-target-number-demo"
        val solutionFile = projectRoot.resolve(".resubmit/sessions/$sessionId/Solution.java")
        val historyFile = projectRoot.resolve(".resubmit/history/attempts.jsonl")
        val output = StringWriter()
        val reviewRequests = mutableListOf<ReviewRequest>()
        try {
            val exitCode = CodingCoachCli(
                input = EditingDemoInput(solutionFile),
                output = PrintWriter(output, true),
                workspaceFactory = SessionWorkspaceFactory(projectRoot, clock),
                codeRunner = ProcessJavaCodeRunner(),
                testRunner = ProcessJavaTestRunner(),
                snapshotFactory = SubmissionSnapshotFactory(clock),
                reviewProvider = ReviewProvider { request ->
                    reviewRequests += request
                    FixtureReviewProvider().review(request)
                },
                attemptHistoryReader = JsonlAttemptHistoryReader(historyFile),
                attemptRecorder = JsonlAttemptRecorder(historyFile),
            ).run()

            assertEquals(0, exitCode)
            assertContains(output.toString(), "1 / 4 PASS")
            assertContains(output.toString(), "HINT 1/3")
            assertContains(output.toString(), "방향만 제시")
            assertContains(output.toString(), "4 / 4 PASS")
            assertContains(output.toString(), "ATTEMPT 2 비교")
            assertContains(output.toString(), "1/4 → 4/4")
            assertContains(output.toString(), "1단계 사용")

            val records = historyFile.readLines().map { line -> Json.decodeFromString<AttemptRecord>(line) }
            assertEquals(5, records.size)
            assertEquals(2, records.map(AttemptRecord::attemptId).distinct().size)
            assertEquals(true, records[2].review.hintViewed)
            assertEquals(TestStatus.PASSED, records.last().tests.status)
            assertEquals(ReviewStatus.READY, records.last().review.status)
            assertEquals(listOf("재귀", "상태 탐색"), records.last().review.relatedSkills)
            assertEquals(3, records.last().review.hints.size)
            assertEquals(records.first().attemptId, reviewRequests.last().previousAttempt?.record?.attemptId)
            assertContains(reviewRequests.last().previousAttempt?.sourceCode.orEmpty(), "return 0")
            assertTrue(Files.isRegularFile(solutionFile))
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reviews compile error then accepts a passing resubmission`() {
        val projectRoot = createTempDirectory("resubmit-compile-review-e2e")
        val clock = fixedClock()
        val sessionId = "20261007-150000-target-number-demo"
        val solutionFile = projectRoot.resolve(".resubmit/sessions/$sessionId/Solution.java")
        val historyFile = projectRoot.resolve(".resubmit/history/attempts.jsonl")
        val output = StringWriter()
        try {
            val exitCode = CodingCoachCli(
                input = CompileErrorThenPassingInput(solutionFile),
                output = PrintWriter(output, true),
                workspaceFactory = SessionWorkspaceFactory(projectRoot, clock),
                codeRunner = ProcessJavaCodeRunner(),
                testRunner = ProcessJavaTestRunner(),
                snapshotFactory = SubmissionSnapshotFactory(clock),
                reviewProvider = FixtureReviewProvider(),
                attemptHistoryReader = JsonlAttemptHistoryReader(historyFile),
                attemptRecorder = JsonlAttemptRecorder(historyFile),
            ).run()

            assertEquals(0, exitCode)
            assertContains(output.toString(), "error[COMPILE]")
            assertContains(output.toString(), "분류")
            assertContains(output.toString(), "컴파일 오류")
            assertContains(output.toString(), "HINT 1/3")
            assertContains(output.toString(), "4 / 4 PASS")
            val records = historyFile.readLines().map { Json.decodeFromString<AttemptRecord>(it) }
            val compileFailure = records.last { it.compilation.status == CompilationStatus.FAILED }
            assertEquals(TestStatus.NOT_RUN, compileFailure.tests.status)
            assertEquals(ReviewStatus.READY, compileFailure.review.status)
            assertEquals(ReviewFailureType.COMPILE_ERROR, compileFailure.review.failureType)
            assertEquals(3, compileFailure.review.hints.size)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resumes a failed session in a new cli run`() {
        val projectRoot = createTempDirectory("resubmit-resume-e2e")
        val clock = fixedClock()
        val sessionId = "20261007-150000-target-number-demo"
        val solutionFile = projectRoot.resolve(".resubmit/sessions/$sessionId/Solution.java")
        try {
            val firstOutput = StringWriter()
            val firstExitCode = runCli(
                projectRoot = projectRoot,
                clock = clock,
                input = BufferedReader(StringReader("\nq\n")),
                output = firstOutput,
            )
            solutionFile.writeText(CORRECT_SOLUTION)
            val resumedOutput = StringWriter()
            val resumedExitCode = runCli(
                projectRoot = projectRoot,
                clock = clock,
                input = BufferedReader(StringReader("r\n\nq\n")),
                output = resumedOutput,
            )

            assertEquals(0, firstExitCode)
            assertContains(firstOutput.toString(), "1 / 4 PASS")
            assertEquals(0, resumedExitCode)
            assertContains(resumedOutput.toString(), "중단된 세션")
            assertContains(resumedOutput.toString(), sessionId)
            assertContains(resumedOutput.toString(), "기존 작업 파일을 이어서 사용합니다.")
            assertContains(resumedOutput.toString(), "ATTEMPT 2 비교")
            assertContains(resumedOutput.toString(), "1/4 → 4/4")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    private fun runCli(
        projectRoot: Path,
        clock: Clock,
        input: BufferedReader,
        output: StringWriter,
    ): Int {
        val historyFile = projectRoot.resolve(".resubmit/history/attempts.jsonl")
        return CodingCoachCli(
            input = input,
            output = PrintWriter(output, true),
            workspaceFactory = SessionWorkspaceFactory(projectRoot, clock),
            codeRunner = ProcessJavaCodeRunner(),
            testRunner = ProcessJavaTestRunner(),
            snapshotFactory = SubmissionSnapshotFactory(clock),
            reviewProvider = FixtureReviewProvider(),
            attemptHistoryReader = JsonlAttemptHistoryReader(historyFile),
            attemptRecorder = JsonlAttemptRecorder(historyFile),
        ).run()
    }

    private class EditingDemoInput(
        private val solutionFile: Path,
    ) : BufferedReader(StringReader("")) {
        private val commands = listOf("", "h", "e", "", "q")
        private var index = 0

        override fun readLine(): String? {
            if (index == 3) {
                solutionFile.writeText(CORRECT_SOLUTION)
            }
            return commands.getOrNull(index++)
        }
    }

    private class CompileErrorThenPassingInput(
        private val solutionFile: Path,
    ) : BufferedReader(StringReader("")) {
        private val commands = listOf("", "h", "e", "", "q")
        private var index = 0

        override fun readLine(): String? {
            when (index) {
                0 -> solutionFile.writeText(
                    "public class Solution { public int solution(int[] numbers, int target) { return 0 } }",
                )
                3 -> solutionFile.writeText(CORRECT_SOLUTION)
            }
            return commands.getOrNull(index++)
        }
    }

    private fun fixedClock(): Clock = Clock.fixed(
        Instant.parse("2026-10-07T06:00:00Z"),
        ZoneId.of("Asia/Seoul"),
    )

    private companion object {
        val CORRECT_SOLUTION = """
            public class Solution {
                public int solution(int[] numbers, int target) {
                    return count(numbers, target, 0, 0);
                }

                private int count(int[] numbers, int target, int index, int sum) {
                    if (index == numbers.length) {
                        return sum == target ? 1 : 0;
                    }
                    return count(numbers, target, index + 1, sum + numbers[index])
                        + count(numbers, target, index + 1, sum - numbers[index]);
                }
            }
        """.trimIndent()
    }
}
