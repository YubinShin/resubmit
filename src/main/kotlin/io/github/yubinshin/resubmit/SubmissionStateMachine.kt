package io.github.yubinshin.resubmit

internal enum class SubmissionState {
    AWAITING_SUBMISSION,
    EVALUATING,
    AWAITING_ACTION,
    FINISHED,
}

internal enum class SubmissionEvent {
    SUBMIT,
    EVALUATION_COMPLETED,
    EDIT,
    EXIT,
    FAILURE,
}

internal class SubmissionStateMachine {
    var state: SubmissionState = SubmissionState.AWAITING_SUBMISSION
        private set

    var exitCode: Int? = null
        private set

    fun transition(event: SubmissionEvent, requestedExitCode: Int? = null) {
        state = when {
            event == SubmissionEvent.FAILURE && state != SubmissionState.FINISHED -> finish(requestedExitCode)
            state == SubmissionState.AWAITING_SUBMISSION && event == SubmissionEvent.SUBMIT ->
                SubmissionState.EVALUATING
            state == SubmissionState.EVALUATING && event == SubmissionEvent.EVALUATION_COMPLETED ->
                SubmissionState.AWAITING_ACTION
            state == SubmissionState.AWAITING_ACTION && event == SubmissionEvent.EDIT ->
                SubmissionState.AWAITING_SUBMISSION
            state in setOf(SubmissionState.AWAITING_SUBMISSION, SubmissionState.AWAITING_ACTION) &&
                event == SubmissionEvent.EXIT -> finish(requestedExitCode)
            else -> error("허용되지 않은 제출 상태 전이입니다: $state + $event")
        }
    }

    private fun finish(requestedExitCode: Int?): SubmissionState {
        exitCode = requireNotNull(requestedExitCode) { "종료 상태에는 exit code가 필요합니다." }
        return SubmissionState.FINISHED
    }
}
