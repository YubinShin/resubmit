package io.github.yubinshin.resubmit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubmissionStateMachineTest {
    @Test
    fun `moves from submission through evaluation back to editing`() {
        val machine = SubmissionStateMachine()

        machine.transition(SubmissionEvent.SUBMIT)
        assertEquals(SubmissionState.EVALUATING, machine.state)

        machine.transition(SubmissionEvent.EVALUATION_COMPLETED)
        assertEquals(SubmissionState.AWAITING_ACTION, machine.state)

        machine.transition(SubmissionEvent.EDIT)
        assertEquals(SubmissionState.AWAITING_SUBMISSION, machine.state)
    }

    @Test
    fun `finishes from submission or action prompts`() {
        val beforeSubmission = SubmissionStateMachine()
        beforeSubmission.transition(SubmissionEvent.EXIT, requestedExitCode = 0)

        val afterEvaluation = SubmissionStateMachine()
        afterEvaluation.transition(SubmissionEvent.SUBMIT)
        afterEvaluation.transition(SubmissionEvent.EVALUATION_COMPLETED)
        afterEvaluation.transition(SubmissionEvent.EXIT, requestedExitCode = 0)

        assertEquals(SubmissionState.FINISHED, beforeSubmission.state)
        assertEquals(0, beforeSubmission.exitCode)
        assertEquals(SubmissionState.FINISHED, afterEvaluation.state)
        assertEquals(0, afterEvaluation.exitCode)
    }

    @Test
    fun `failure finishes an active session with infrastructure exit code`() {
        val machine = SubmissionStateMachine()
        machine.transition(SubmissionEvent.SUBMIT)

        machine.transition(SubmissionEvent.FAILURE, requestedExitCode = 3)

        assertEquals(SubmissionState.FINISHED, machine.state)
        assertEquals(3, machine.exitCode)
    }

    @Test
    fun `rejects transitions that do not belong to the current state`() {
        val machine = SubmissionStateMachine()

        assertFailsWith<IllegalStateException> {
            machine.transition(SubmissionEvent.EDIT)
        }
        assertEquals(SubmissionState.AWAITING_SUBMISSION, machine.state)
    }
}
