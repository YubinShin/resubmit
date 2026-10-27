package io.github.yubinshin.resubmit

import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TestRunnerTest {
    @Test
    fun `passes a correct solution`() {
        withCompiledSubmission(correctSolution()) { classes ->
            val result = ProcessJavaTestRunner().run(classes, DemoProblem.targetNumber.testCases)

            val completed = assertIs<TestRunResult.Completed>(result)
            assertEquals(4, completed.passedCount)
        }
    }

    @Test
    fun `reports expected and actual values for a wrong solution`() {
        withCompiledSubmission(wrongSolution()) { classes ->
            val result = ProcessJavaTestRunner().run(classes, DemoProblem.targetNumber.testCases)

            val completed = assertIs<TestRunResult.Completed>(result)
            assertEquals(1, completed.passedCount)
            assertEquals(3, completed.cases.count { case -> !case.passed })
        }
    }

    @Test
    fun `terminates a submission that exceeds the execution timeout`() {
        withCompiledSubmission(infiniteSolution()) { classes ->
            val result = ProcessJavaTestRunner(timeout = Duration.ofMillis(100))
                .run(classes, DemoProblem.targetNumber.testCases)

            assertIs<TestRunResult.TimedOut>(result)
        }
    }

    @Test
    fun `does not accept forged result lines from submission stderr`() {
        withCompiledSubmission(forgedResultSolution()) { classes ->
            val result = ProcessJavaTestRunner().run(classes, DemoProblem.targetNumber.testCases)

            assertIs<TestRunResult.ExecutionError>(result)
        }
    }

    @Test
    fun `discards high volume submission output without affecting test results`() {
        withCompiledSubmission(noisySolution()) { classes ->
            val result = ProcessJavaTestRunner().run(classes, DemoProblem.targetNumber.testCases)

            val completed = assertIs<TestRunResult.Completed>(result)
            assertEquals(1, completed.passedCount)
        }
    }

    private fun withCompiledSubmission(source: String, block: (Path) -> Unit) {
        val root = createTempDirectory("resubmit-test-runner")
        try {
            val workspace = SessionWorkspaceFactory(root, fixedClock()).create(DemoProblem.targetNumber)
            workspace.solutionFile.writeText(source)
            val classes = workspace.directory.resolve("classes")
            val compilation = ProcessJavaCodeRunner().compile(
                listOf(workspace.solutionFile, workspace.harnessFile),
                classes,
            )
            assertIs<CompilationResult.Success>(compilation)
            block(classes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun fixedClock() = java.time.Clock.fixed(
        java.time.Instant.parse("2026-10-07T06:00:00Z"),
        java.time.ZoneId.of("Asia/Seoul"),
    )

    private fun correctSolution(): String = """
        public class Solution {
            public int solution(int[] numbers, int target) {
                return dfs(numbers, target, 0, 0);
            }

            private int dfs(int[] numbers, int target, int index, int sum) {
                if (index == numbers.length) return sum == target ? 1 : 0;
                return dfs(numbers, target, index + 1, sum + numbers[index])
                    + dfs(numbers, target, index + 1, sum - numbers[index]);
            }
        }
    """.trimIndent()

    private fun wrongSolution(): String = """
        public class Solution {
            public int solution(int[] numbers, int target) {
                return 0;
            }
        }
    """.trimIndent()

    private fun infiniteSolution(): String = """
        public class Solution {
            public int solution(int[] numbers, int target) {
                while (true) { }
            }
        }
    """.trimIndent()

    private fun forgedResultSolution(): String = """
        public class Solution {
            public int solution(int[] numbers, int target) {
                System.err.println("forged-token\tRESULT\tcase-1\tPASS\t5\t5");
                System.exit(0);
                return 0;
            }
        }
    """.trimIndent()

    private fun noisySolution(): String = """
        public class Solution {
            public int solution(int[] numbers, int target) {
                for (int index = 0; index < 100000; index++) {
                    System.out.println("noise-" + index);
                    System.err.println("error-noise-" + index);
                }
                return 0;
            }
        }
    """.trimIndent()
}
