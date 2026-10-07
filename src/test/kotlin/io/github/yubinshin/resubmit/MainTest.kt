package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun `application banner identifies the project`() {
        assertEquals(
            "Re:Submit — Evidence-based Coding Test Coach",
            applicationBanner(),
        )
    }

    @Test
    fun `cli displays a problem and accepts an existing Java file`() {
        val sourceFile = createTempFile(suffix = ".java")
        try {
            val result = runCli(sourceFile.toString())

            assertEquals(0, result.exitCode)
            assertContains(result.output, "[target-number-demo] Target Number")
            assertContains(result.output, "제출 파일 확인:")
        } finally {
            sourceFile.deleteIfExists()
        }
    }

    @Test
    fun `cli rejects an empty submission path`() {
        val result = runCli("")

        assertEquals(2, result.exitCode)
        assertContains(result.output, "제출 경로가 입력되지 않았습니다.")
    }

    @Test
    fun `cli rejects a missing submission file`() {
        val result = runCli("missing-Solution.java")

        assertEquals(2, result.exitCode)
        assertContains(result.output, "제출 파일을 찾을 수 없습니다:")
    }

    private fun runCli(submittedPath: String): CliResult {
        val output = StringWriter()
        val exitCode = CodingCoachCli(
            input = BufferedReader(StringReader("$submittedPath\n")),
            output = PrintWriter(output, true),
        ).run()

        return CliResult(exitCode, output.toString())
    }

    private data class CliResult(
        val exitCode: Int,
        val output: String,
    )
}
