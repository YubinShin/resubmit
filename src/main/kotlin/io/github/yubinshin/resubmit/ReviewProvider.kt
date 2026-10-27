package io.github.yubinshin.resubmit

internal fun interface ReviewProvider {
    fun review(request: ReviewRequest): ReviewResult

    val provider: String
        get() = "unknown"
}

internal data class ReviewRequest(
    val problem: Problem,
    val tests: TestRunResult? = null,
    val sourceCode: String = "",
    val compilation: CompilationResult? = null,
    val previousAttempt: PreviousAttemptContext? = null,
    val sourceEvidence: List<SourceEvidence> = emptyList(),
)

internal data class PreviousAttemptContext(
    val record: AttemptRecord,
    val sourceCode: String?,
)

internal sealed interface ReviewResult {
    data class Ready(val review: SubmissionReview) : ReviewResult
    data class Unavailable(val message: String) : ReviewResult
}

internal data class SubmissionReview(
    val provider: String,
    val failureType: ReviewFailureType,
    val relatedSkills: List<String>,
    val evidence: List<String>,
    val hints: List<String>,
    val model: String? = null,
    val promptVersion: String = REVIEW_PROMPT_VERSION,
    val citedEvidenceIds: List<String> = emptyList(),
    val sourceEvidence: List<SourceEvidence> = emptyList(),
) {
    init {
        require(hints.size == 3 && hints.all(String::isNotBlank)) { "리뷰에는 비어 있지 않은 힌트 3개가 필요합니다." }
    }
}

internal enum class ReviewFailureType {
    COMPILE_ERROR,
    WRONG_ANSWER,
    RUNTIME_ERROR,
    TIME_LIMIT_EXCEEDED,
    PASSED,
}

internal class FixtureReviewProvider : ReviewProvider {
    override val provider: String = "fixture"

    override fun review(request: ReviewRequest): ReviewResult {
        val compilation = request.compilation
        if (compilation is CompilationResult.Failed) return compileFailureReview(compilation)
        return when (val tests = request.tests) {
        is TestRunResult.Completed -> completedReview(tests, request.problem)
        is TestRunResult.TimedOut -> ReviewResult.Ready(
            SubmissionReview(
                provider = "fixture",
                failureType = ReviewFailureType.TIME_LIMIT_EXCEEDED,
                relatedSkills = request.problem.relatedSkills,
                evidence = listOf("테스트 실행이 제한 시간을 초과했습니다."),
                hints = request.problem.optimizationHints,
            ),
        )
        is TestRunResult.ExecutionError,
        is TestRunResult.RunnerError ->
            ReviewResult.Unavailable("실행 기반이 정상적으로 완료되지 않아 리뷰를 만들 수 없습니다.")
        null -> ReviewResult.Unavailable("리뷰에 사용할 컴파일 또는 테스트 결과가 없습니다.")
        }
    }

    private fun compileFailureReview(compilation: CompilationResult.Failed): ReviewResult.Ready =
        ReviewResult.Ready(
            SubmissionReview(
                provider = provider,
                failureType = ReviewFailureType.COMPILE_ERROR,
                relatedSkills = listOf("Java 문법", "컴파일 진단 읽기"),
                evidence = listOfNotNull(
                    "javac 종료 코드 ${compilation.exitCode}",
                    compilation.diagnostics.lineSequence().firstOrNull { it.contains(": error:") }
                        ?.replace(Regex("^.*Solution\\.java:"), "Solution.java:"),
                ),
                hints = listOf(
                    "먼저 컴파일러가 표시한 오류 위치와 그 바로 앞 문장을 확인해보세요.",
                    compileHint(compilation.diagnostics),
                    "표시된 오류를 한 번에 하나씩 고친 뒤 다시 컴파일해보세요.",
                ),
            ),
        )

    private fun compileHint(diagnostics: String): String = when {
        diagnostics.contains("';' expected") -> "문장 끝에 세미콜론(;)이 빠졌는지 확인해보세요."
        diagnostics.contains("cannot find symbol") -> "이름의 철자와 변수·메서드가 선언된 범위를 확인해보세요."
        diagnostics.contains("incompatible types") -> "반환값과 메서드 선언의 타입이 서로 맞는지 확인해보세요."
        diagnostics.contains("missing return statement") -> "모든 실행 경로에서 값을 반환하는지 확인해보세요."
        else -> "오류가 난 줄과 그 직전 줄의 괄호, 타입, 선언을 차례로 확인해보세요."
    }

    private fun completedReview(tests: TestRunResult.Completed, problem: Problem): ReviewResult.Ready {
        val failedCases = tests.cases.filterNot(TestCaseResult::passed)
        val runtimeErrors = failedCases.filterIsInstance<TestCaseResult.Error>()
        return if (failedCases.isEmpty()) {
            ReviewResult.Ready(
                SubmissionReview(
                    provider = "fixture",
                    failureType = ReviewFailureType.PASSED,
                    relatedSkills = problem.relatedSkills,
                    evidence = listOf("고정 테스트 ${tests.passedCount}/${tests.cases.size}개를 통과했습니다."),
                    hints = problem.optimizationHints,
                ),
            )
        } else if (runtimeErrors.isNotEmpty()) {
            ReviewResult.Ready(
                SubmissionReview(
                    provider = "fixture",
                    failureType = ReviewFailureType.RUNTIME_ERROR,
                    relatedSkills = listOf("예외 처리", "실행 흐름"),
                    evidence = runtimeErrors.map { testCase -> "${testCase.id}: ${testCase.errorType}" },
                    hints = listOf(
                        "오류가 발생한 테스트에서 예외가 생기는 입력 조건과 실행 경로를 먼저 확인해보세요.",
                        "예외가 난 호출에서 index, 배열 길이와 현재 합이 어떤 값인지 추적해보세요.",
                        "배열에 접근하기 전에 종료 조건을 검사하고, 종료 시 target 비교 결과를 반환하는 흐름을 적어보세요.",
                    ),
                ),
            )
        } else {
            ReviewResult.Ready(
                SubmissionReview(
                    provider = "fixture",
                    failureType = ReviewFailureType.WRONG_ANSWER,
                    relatedSkills = problem.relatedSkills,
                    evidence = failedCases.map { testCase -> "${testCase.id} 실패" },
                    hints = problem.wrongAnswerHints,
                ),
            )
        }
    }
}

internal const val REVIEW_PROMPT_VERSION = "review-v4"
