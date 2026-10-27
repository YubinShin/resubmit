package io.github.yubinshin.resubmit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReviewProviderTest {
    private val provider = FixtureReviewProvider()

    @Test
    fun `builds wrong answer review from failed test evidence`() {
        val result = provider.review(
            ReviewRequest(
                problem = DemoProblem.targetNumber,
                tests = TestRunResult.Completed(
                    cases = listOf(
                        TestCaseResult.Value("case-1", false, "5", "0"),
                        TestCaseResult.Value("case-2", true, "2", "2"),
                    ),
                    durationMs = 10,
                ),
            ),
        )

        val review = assertIs<ReviewResult.Ready>(result).review
        assertEquals(ReviewFailureType.WRONG_ANSWER, review.failureType)
        assertEquals("fixture", review.provider)
        assertEquals(listOf("case-1 실패"), review.evidence)
        assertEquals(3, review.hints.size)
    }

    @Test
    fun `builds timeout review without changing the execution result`() {
        val tests = TestRunResult.TimedOut(2_000)

        val review = assertIs<ReviewResult.Ready>(
            provider.review(ReviewRequest(DemoProblem.targetNumber, tests)),
        ).review

        assertEquals(ReviewFailureType.TIME_LIMIT_EXCEEDED, review.failureType)
        assertEquals(2_000, tests.durationMs)
    }

    @Test
    fun `builds compile error review from javac diagnostics`() {
        val review = assertIs<ReviewResult.Ready>(
            provider.review(
                ReviewRequest(
                    problem = DemoProblem.targetNumber,
                    compilation = CompilationResult.Failed(
                        exitCode = 1,
                        durationMs = 8,
                        diagnostics = "Solution.java:4: error: ';' expected",
                        outputTruncated = false,
                    ),
                ),
            ),
        ).review

        assertEquals(ReviewFailureType.COMPILE_ERROR, review.failureType)
        assertEquals(true, review.evidence.any { it.contains("Solution.java:4") })
        assertEquals(true, review.hints.first().contains("컴파일러"))
        assertEquals(true, review.hints[1].contains("세미콜론"))
    }

    @Test
    fun `classifies user exception as runtime error`() {
        val review = assertIs<ReviewResult.Ready>(
            provider.review(
                ReviewRequest(
                    DemoProblem.targetNumber,
                    TestRunResult.Completed(
                        cases = listOf(TestCaseResult.Error("case-1", "NullPointerException")),
                        durationMs = 10,
                    ),
                ),
            ),
        ).review

        assertEquals(ReviewFailureType.RUNTIME_ERROR, review.failureType)
        assertEquals(listOf("case-1: NullPointerException"), review.evidence)
    }

    @Test
    fun `does not review infrastructure failures`() {
        val result = provider.review(
            ReviewRequest(
                DemoProblem.targetNumber,
                TestRunResult.ExecutionError(2, 5, "protocol failure"),
            ),
        )

        assertIs<ReviewResult.Unavailable>(result)
    }
}
