package io.github.yubinshin.resubmit

import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SubmissionSnapshotTest {
    @Test
    fun `keeps immutable sequential snapshots of submitted source`() {
        val root = createTempDirectory("resubmit-snapshot-test")
        try {
            val workspace = SessionWorkspaceFactory(root, fixedClock()).create(DemoProblem.targetNumber)
            val factory = SubmissionSnapshotFactory(fixedClock())
            workspace.solutionFile.writeText("first source")

            val first = factory.create(workspace)
            workspace.solutionFile.writeText("second source")
            val second = factory.create(workspace)

            assertEquals("first source", first.sourceFile.readText())
            assertEquals("second source", second.sourceFile.readText())
            assertEquals("${workspace.sessionId}-attempt-001", first.attemptId)
            assertEquals("${workspace.sessionId}-attempt-002", second.attemptId)
            assertNotEquals(first.sourceSha256, second.sourceSha256)
            assertEquals("2026-10-07T15:00+09:00", first.submittedAt)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun fixedClock() = java.time.Clock.fixed(
        java.time.Instant.parse("2026-10-07T06:00:00Z"),
        java.time.ZoneId.of("Asia/Seoul"),
    )
}
