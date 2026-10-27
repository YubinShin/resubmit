package io.github.yubinshin.resubmit

import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodeRunnerTest {
    @Test
    fun `compiles a valid Java submission into an isolated output directory`() {
        withTempDirectory { root ->
            val sourceFile = root.resolve("Solution.java")
            val outputDirectory = root.resolve("classes")
            sourceFile.writeText(validSolution())

            val result = ProcessJavaCodeRunner().compile(listOf(sourceFile), outputDirectory)

            val success = assertIs<CompilationResult.Success>(result)
            assertEquals(outputDirectory, success.outputDirectory)
            assertTrue(outputDirectory.resolve("Solution.class").exists())
        }
    }

    @Test
    fun `returns javac diagnostics for an invalid Java submission`() {
        withTempDirectory { root ->
            val sourceFile = root.resolve("Solution.java")
            sourceFile.writeText("public class Solution { // 한글 주석\n this is not Java; }")

            val result = ProcessJavaCodeRunner().compile(listOf(sourceFile), root.resolve("classes"))

            val failure = assertIs<CompilationResult.Failed>(result)
            assertTrue(failure.exitCode != 0)
            assertContains(failure.diagnostics, "error:")
        }
    }

    @Test
    fun `terminates a compiler process that exceeds the timeout`() {
        withTempDirectory { root ->
            val sourceFile = root.resolve("Solution.java")
            sourceFile.writeText(validSolution())
            val runner = ProcessJavaCodeRunner(
                timeout = Duration.ofMillis(100),
                commandFactory = { _, _ -> listOf("/bin/sh", "-c", "sleep 2") },
            )

            val result = runner.compile(listOf(sourceFile), root.resolve("classes"))

            assertIs<CompilationResult.TimedOut>(result)
        }
    }

    @Test
    fun `returns runner error when source file does not exist`() {
        withTempDirectory { root ->
            val result = ProcessJavaCodeRunner().compile(
                listOf(root.resolve("missing.java")),
                root.resolve("classes"),
            )

            assertIs<CompilationResult.RunnerError>(result)
        }
    }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("resubmit-runner-test")
        try {
            root.createDirectories()
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun validSolution(): String = """
        public class Solution {
            public int solution(int[] numbers, int target) {
                return 0;
            }
        }
    """.trimIndent()
}
