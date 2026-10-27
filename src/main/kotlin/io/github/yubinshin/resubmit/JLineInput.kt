package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.StringReader
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.Attributes.LocalFlag

internal class JLineInput private constructor(
    private val terminal: Terminal,
) : BufferedReader(StringReader("")), ArrowChoiceInput {
    private val lineReader: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .build()

    override fun readLine(): String? = try {
        lineReader.readLine()
    } catch (_: EndOfFileException) {
        null
    } catch (_: UserInterruptException) {
        null
    }

    override fun choose(optionCount: Int, render: (selectedIndex: Int) -> Unit): Int? {
        require(optionCount > 0)
        val originalAttributes = terminal.enterRawMode()
        return try {
            // Some terminals do not clear ECHO when enterRawMode() is called.
            // Explicitly disable line buffering and echo so escape sequences are
            // consumed by this menu instead of being printed as `^[[A`/`^[[B`.
            val rawAttributes = terminal.attributes
            rawAttributes.setLocalFlag(LocalFlag.ECHO, false)
            rawAttributes.setLocalFlag(LocalFlag.ECHONL, false)
            rawAttributes.setLocalFlag(LocalFlag.ICANON, false)
            terminal.attributes = rawAttributes

            var selectedIndex = 0
            var finished = false
            var selection: Int? = null
            while (!finished) {
                render(selectedIndex)
                when (readMenuKey()) {
                    MenuKey.UP -> selectedIndex = (selectedIndex - 1 + optionCount) % optionCount
                    MenuKey.DOWN -> selectedIndex = (selectedIndex + 1) % optionCount
                    MenuKey.ENTER -> {
                        selection = selectedIndex
                        finished = true
                    }
                    MenuKey.QUIT -> finished = true
                    is MenuKey.OTHER -> Unit
                }
            }
            selection
        } finally {
            terminal.setAttributes(originalAttributes)
        }
    }

    private fun readMenuKey(): MenuKey {
        return when (val code = terminal.reader().read()) {
            -1, CTRL_C -> MenuKey.QUIT
            ENTER_LF, ENTER_CR -> MenuKey.ENTER
            ESCAPE -> readArrowKey()
            'q'.code, 'Q'.code -> MenuKey.QUIT
            else -> MenuKey.OTHER(code)
        }
    }

    private fun readArrowKey(): MenuKey {
        val prefix = terminal.reader().read()
        if (prefix != '['.code && prefix != 'O'.code) return MenuKey.OTHER(prefix)
        return when (terminal.reader().read()) {
            'A'.code -> MenuKey.UP
            'B'.code -> MenuKey.DOWN
            else -> MenuKey.OTHER(prefix)
        }
    }

    private sealed interface MenuKey {
        data object UP : MenuKey
        data object DOWN : MenuKey
        data object ENTER : MenuKey
        data object QUIT : MenuKey
        data class OTHER(val code: Int) : MenuKey
    }

    override fun close() {
        runCatching { terminal.close() }
        super.close()
    }

    internal companion object {
        const val CTRL_C = 3
        const val ENTER_LF = 10
        const val ENTER_CR = 13
        const val ESCAPE = 27

        fun createOrNull(): JLineInput? = runCatching {
            JLineInput(TerminalBuilder.builder().system(true).build())
        }.getOrNull()
    }
}
