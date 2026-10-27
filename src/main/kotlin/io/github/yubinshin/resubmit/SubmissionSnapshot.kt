package io.github.yubinshin.resubmit

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime

internal fun interface SnapshotFactory {
    fun create(workspace: SessionWorkspace): SubmissionSnapshot
}

internal class SubmissionSnapshotFactory(
    private val clock: Clock = Clock.systemDefaultZone(),
) : SnapshotFactory {
    override fun create(workspace: SessionWorkspace): SubmissionSnapshot {
        val submissionsDirectory = workspace.directory.resolve(".internal/submissions")
        Files.createDirectories(submissionsDirectory)

        var sequence = 1
        while (true) {
            val attemptSuffix = sequence.toString().padStart(3, '0')
            val attemptId = "${workspace.sessionId}-attempt-$attemptSuffix"
            val attemptDirectory = submissionsDirectory.resolve("attempt-$attemptSuffix")
            try {
                Files.createDirectory(attemptDirectory)
                val sourceSnapshot = attemptDirectory.resolve("Solution.java")
                Files.copy(workspace.solutionFile, sourceSnapshot)
                return SubmissionSnapshot(
                    attemptId = attemptId,
                    sourceFile = sourceSnapshot,
                    sourceSha256 = sha256(sourceSnapshot),
                    submittedAt = OffsetDateTime.now(clock).toString(),
                )
            } catch (exception: java.nio.file.FileAlreadyExistsException) {
                sequence += 1
            }
        }
    }

    private fun sha256(sourceFile: Path): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sourceFile))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal data class SubmissionSnapshot(
    val attemptId: String,
    val sourceFile: Path,
    val sourceSha256: String,
    val submittedAt: String,
)
