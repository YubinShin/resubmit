package io.github.yubinshin.resubmit

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class SessionWorkspaceFactory(
    private val projectRoot: Path,
    private val clock: Clock,
) {
    fun create(problem: Problem): SessionWorkspace {
        val sessionsRoot = projectRoot.resolve(".resubmit/sessions")
        Files.createDirectories(sessionsRoot)

        val baseName = "${SESSION_TIME_FORMAT.format(ZonedDateTime.now(clock))}-${problem.id}"
        val sessionDirectory = nextAvailableDirectory(sessionsRoot, baseName)
        Files.createDirectory(sessionDirectory)

        val problemFile = sessionDirectory.resolve("Problem.md")
        val solutionFile = sessionDirectory.resolve("Solution.java")
        Files.writeString(problemFile, problem.toMarkdown())
        Files.writeString(solutionFile, solutionTemplate())

        return SessionWorkspace(
            sessionId = sessionDirectory.fileName.toString(),
            directory = sessionDirectory,
            problemFile = problemFile,
            solutionFile = solutionFile,
        )
    }

    private fun nextAvailableDirectory(sessionsRoot: Path, baseName: String): Path {
        val initial = sessionsRoot.resolve(baseName)
        if (Files.notExists(initial)) {
            return initial
        }

        var suffix = 2
        while (Files.exists(sessionsRoot.resolve("$baseName-$suffix"))) {
            suffix += 1
        }
        return sessionsRoot.resolve("$baseName-$suffix")
    }

    private fun Problem.toMarkdown(): String = """
        # $title

        $description

        ## 제출 규약

        $submissionContract
    """.trimIndent() + System.lineSeparator()

    private fun solutionTemplate(): String = """
        public class Solution {
            public int solution(int[] numbers, int target) {
                // TODO: 구현하세요.
                return 0;
            }
        }
    """.trimIndent() + System.lineSeparator()

    private companion object {
        val SESSION_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

internal data class SessionWorkspace(
    val sessionId: String,
    val directory: Path,
    val problemFile: Path,
    val solutionFile: Path,
)
