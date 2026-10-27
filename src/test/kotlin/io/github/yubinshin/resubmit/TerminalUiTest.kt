package io.github.yubinshin.resubmit

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalUiTest {
    @Test
    fun `enables ANSI emphasis in a supported terminal`() {
        val ui = TerminalUi.fromEnvironment(mapOf("TERM" to "xterm-256color"), consolePresent = false)

        assertEquals("\u001B[1mRe:Submit\u001B[0m", ui.bold("Re:Submit"))
        assertEquals("\u001B[32mPASS\u001B[0m", ui.success("PASS"))
        assertEquals("\u001B[2mATTEMPT 2\u001B[0m", ui.muted("ATTEMPT 2"))
    }

    @Test
    fun `NO_COLOR disables ANSI styling`() {
        val ui = TerminalUi.fromEnvironment(
            mapOf("TERM" to "xterm-256color", "NO_COLOR" to ""),
            consolePresent = true,
        )

        assertEquals("Re:Submit", ui.bold("Re:Submit"))
        assertEquals("FAIL", ui.failure("FAIL"))
    }

    @Test
    fun `long path uses a middle ellipsis and keeps the filename`() {
        val ui = TerminalUi.plain(width = 40)

        val rendered = ui.pathLine(
            "제출",
            Path.of("/very/long/workspace/location/session/target-number/Solution.java"),
        )

        assertTrue(terminalCellWidth(rendered) <= 40)
        assertContains(rendered, "…")
        assertTrue(rendered.endsWith("Solution.java"))
        assertFalse(rendered.contains("target-number"))
    }

    @Test
    fun `short path is not abbreviated`() {
        assertEquals("/tmp/Solution.java", ellipsizeMiddle("/tmp/Solution.java", 30))
    }

    @Test
    fun `ellipsis respects the display width of Korean paths`() {
        val rendered = ellipsizeMiddle("/사용자/프로젝트/아주긴세션이름/Solution.java", 30)

        assertTrue(terminalCellWidth(rendered) <= 30)
        assertContains(rendered, "…")
        assertTrue(rendered.endsWith("Solution.java"))
    }

    @Test
    fun `wrap keeps Korean description within the terminal width`() {
        val lines = TerminalUi.plain(width = 40).wrap(
            "주어진 정수에 더하기 또는 빼기를 적용해 target을 만드는 경우의 수를 반환하세요.",
            indent = "  ",
        )

        assertTrue(lines.size > 1)
        assertTrue(lines.all { terminalCellWidth(it) <= 40 })
        assertTrue(lines.all { it.startsWith("  ") })
    }

    @Test
    fun `interactive terminal clears without entering alternate screen`() {
        val output = StringWriter()
        val writer = PrintWriter(output, true)
        val ui = TerminalUi.fromEnvironment(mapOf("TERM" to "xterm-256color"), consolePresent = false)

        ui.enterScreen(writer)
        ui.clearScreen(writer)
        ui.leaveScreen(writer)
        ui.leaveScreen(writer)

        assertEquals(
            "\u001B[2J\u001B[H\u001B[2J\u001B[H",
            output.toString(),
        )
        assertFalse(output.toString().contains("\u001B[?1049"))
    }

    @Test
    fun `RESUBMIT_NO_TUI disables screen redraw`() {
        val output = StringWriter()
        val writer = PrintWriter(output, true)
        val ui = TerminalUi.fromEnvironment(
            mapOf("TERM" to "xterm-256color", "RESUBMIT_NO_TUI" to "1"),
            consolePresent = true,
        )

        ui.enterScreen(writer)
        ui.clearScreen(writer)
        ui.leaveScreen(writer)

        assertEquals("", output.toString())
    }

    @Test
    fun `interactive progress renders and clears a spinner line`() {
        val output = StringWriter()
        val writer = PrintWriter(output, true)
        val ui = TerminalUi.fromEnvironment(mapOf("TERM" to "xterm-256color"), consolePresent = true)
        ui.enterScreen(writer)
        output.buffer.setLength(0)

        val result = ui.withProgress(writer, "openai 리뷰 요청 중") { "ready" }

        assertEquals("ready", result)
        assertContains(output.toString(), "⠋ openai 리뷰 요청 중 · 0초")
        assertTrue(output.toString().endsWith("\r\u001B[2K"))
    }

    @Test
    fun `sequential progress prints one stable status line`() {
        val output = StringWriter()
        val writer = PrintWriter(output, true)

        val result = TerminalUi.plain().withProgress(writer, "gemini 리뷰 요청 중") { "ready" }

        assertEquals("ready", result)
        assertEquals("gemini 리뷰 요청 중…${System.lineSeparator()}", output.toString())
    }

    @Test
    fun `status row aligns Korean labels values and details`() {
        val row = TerminalUi.plain().statusRow("테스트", "3 / 4 PASS", "184 ms", TerminalTone.WARNING)

        assertEquals("테스트      3 / 4 PASS          184 ms", row)
    }

    @Test
    fun `status row moves long detail below on a narrow terminal`() {
        val row = TerminalUi.plain(width = 40).statusRow(
            "테스트",
            "EXECUTION_ERROR",
            "exit 1 · 1234 ms",
            TerminalTone.FAILURE,
        )

        assertEquals(
            "테스트      EXECUTION_ERROR${System.lineSeparator()}  exit 1 · 1234 ms",
            row,
        )
    }

    @Test
    fun `major and section rules match terminal width`() {
        val ui = TerminalUi.plain(width = 40)

        assertEquals("━".repeat(39), ui.majorRule())
        assertEquals("─".repeat(39), ui.rule())
    }

    @Test
    fun `detected tty width takes priority over COLUMNS`() {
        val ui = TerminalUi.fromEnvironment(
            mapOf("TERM" to "xterm-256color", "COLUMNS" to "80"),
            consolePresent = true,
            detectedWidth = 67,
        )

        assertEquals(67, ui.width)
        assertEquals(66, terminalCellWidth(ui.rule()))
    }

    @Test
    fun `terminal width detector parses stty rows and columns`() {
        assertEquals(67, TerminalWidthDetector.parse("24 67\n"))
        assertEquals(null, TerminalWidthDetector.parse("not a tty"))
    }
}
