package io.github.yubinshin.resubmit

import java.io.PrintWriter
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.TimeSource

internal class TerminalUi private constructor(
    private val styled: Boolean,
    private val screenMode: Boolean,
    val width: Int,
) {
    private val screenActive = AtomicBoolean(false)
    val supportsInteractiveInput: Boolean = screenMode

    fun bold(text: String): String = style(BOLD, text)

    fun success(text: String): String = style(GREEN, text)

    fun warning(text: String): String = style(YELLOW, text)

    fun failure(text: String): String = style(RED, text)

    fun muted(text: String): String = style(DIM, text)

    private val contentWidth: Int
        get() = (width - RIGHT_MARGIN_WIDTH).coerceAtLeast(MIN_CONTENT_WIDTH)

    fun rule(): String = "─".repeat(contentWidth)

    fun majorRule(): String = "━".repeat(contentWidth)

    fun statusRow(
        label: String,
        value: String,
        detail: String = "",
        tone: TerminalTone = TerminalTone.DEFAULT,
    ): String {
        val labelColumn = label + " ".repeat((STATUS_LABEL_WIDTH - terminalCellWidth(label)).coerceAtLeast(1))
        val valueColumn = value + " ".repeat((STATUS_VALUE_WIDTH - terminalCellWidth(value)).coerceAtLeast(1))
        val styledValue = when (tone) {
            TerminalTone.DEFAULT -> valueColumn
            TerminalTone.SUCCESS -> success(valueColumn)
            TerminalTone.WARNING -> warning(valueColumn)
            TerminalTone.FAILURE -> failure(valueColumn)
        }
        if (detail.isBlank()) return labelColumn + styledValue.trimEnd()
        val sameLineWidth = STATUS_LABEL_WIDTH + STATUS_VALUE_WIDTH + terminalCellWidth(detail)
        return if (sameLineWidth <= contentWidth) {
            labelColumn + styledValue + detail
        } else {
            labelColumn + styledValue.trimEnd() + System.lineSeparator() + "  " + detail
        }
    }

    fun enterScreen(output: PrintWriter) {
        if (screenMode && screenActive.compareAndSet(false, true)) {
            clearScreen(output)
            output.flush()
        }
    }

    fun leaveScreen(output: PrintWriter) {
        if (screenMode && screenActive.compareAndSet(true, false)) {
            output.flush()
        }
    }

    fun clearScreen(output: PrintWriter): Boolean {
        return if (screenMode && screenActive.get()) {
            output.print(CLEAR_SCREEN)
            true
        } else {
            false
        }
    }

    fun <T> withProgress(output: PrintWriter, message: String, operation: () -> T): T {
        if (!screenMode || !screenActive.get()) {
            output.println("$message…")
            output.flush()
            return operation()
        }

        val finished = AtomicBoolean(false)
        val startedAt = TimeSource.Monotonic.markNow()
        val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        fun render(frame: String) {
            val elapsedSeconds = startedAt.elapsedNow().inWholeSeconds
            synchronized(output) {
                output.print("\r$CLEAR_LINE$frame $message · ${elapsedSeconds}초")
                output.flush()
            }
        }

        render(frames.first())
        val spinner = thread(name = "review-progress", isDaemon = true) {
            var index = 1
            while (!finished.get()) {
                try {
                    Thread.sleep(PROGRESS_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                if (!finished.get()) {
                    render(frames[index % frames.size])
                    index += 1
                }
            }
        }
        return try {
            operation()
        } finally {
            finished.set(true)
            spinner.interrupt()
            try {
                spinner.join(PROGRESS_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            synchronized(output) {
                output.print("\r$CLEAR_LINE")
                output.flush()
            }
        }
    }

    fun pathLine(label: String, path: Path, indent: String = ""): String {
        val prefix = "$indent$label  "
        val available = (contentWidth - terminalCellWidth(prefix)).coerceAtLeast(MIN_PATH_WIDTH)
        return prefix + ellipsizeMiddle(path.toAbsolutePath().normalize().toString(), available)
    }

    fun path(path: Path, indent: String = ""): String {
        val available = (contentWidth - terminalCellWidth(indent)).coerceAtLeast(MIN_PATH_WIDTH)
        return indent + ellipsizeMiddle(path.toAbsolutePath().normalize().toString(), available)
    }

    fun wrap(text: String, indent: String = ""): List<String> {
        val availableWidth = (contentWidth - terminalCellWidth(indent)).coerceAtLeast(MIN_PATH_WIDTH)
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        text.split(Regex("\\s+")).filter(String::isNotEmpty).forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (terminalCellWidth(candidate) <= availableWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines += indent + current.toString()
                val chunks = word.splitByDisplayWidth(availableWidth)
                lines += chunks.dropLast(1).map { indent + it }
                current = StringBuilder(chunks.last())
            }
        }
        if (current.isNotEmpty()) lines += indent + current.toString()
        return lines.ifEmpty { listOf(indent) }
    }

    private fun style(code: String, text: String): String = if (styled) "$code$text$RESET" else text

    companion object {
        private const val DEFAULT_WIDTH = 80
        private const val MIN_WIDTH = 40
        private const val MIN_CONTENT_WIDTH = MIN_WIDTH - 1
        private const val MAX_WIDTH = 200
        private const val MIN_PATH_WIDTH = 16
        private const val RESET = "\u001B[0m"
        private const val BOLD = "\u001B[1m"
        private const val DIM = "\u001B[2m"
        private const val RED = "\u001B[31m"
        private const val GREEN = "\u001B[32m"
        private const val YELLOW = "\u001B[33m"
        private const val CLEAR_SCREEN = "\u001B[2J\u001B[H"
        private const val CLEAR_LINE = "\u001B[2K"
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val PROGRESS_JOIN_TIMEOUT_MS = 250L
        private const val STATUS_LABEL_WIDTH = 12
        private const val STATUS_VALUE_WIDTH = 20
        private const val RIGHT_MARGIN_WIDTH = 1

        fun fromEnvironment(
            environment: Map<String, String>,
            consolePresent: Boolean,
            detectedWidth: Int? = null,
        ): TerminalUi {
            val width = detectedWidth?.coerceIn(MIN_WIDTH, MAX_WIDTH)
                ?: environment["COLUMNS"]?.toIntOrNull()?.coerceIn(MIN_WIDTH, MAX_WIDTH)
                ?: DEFAULT_WIDTH
            val styled = "NO_COLOR" !in environment && environment["TERM"] != "dumb" &&
                (consolePresent || environment["TERM"] != null || environment["COLORTERM"] != null)
            val screenMode = "RESUBMIT_NO_TUI" !in environment && environment["TERM"] != "dumb" &&
                (consolePresent || environment["TERM"] != null) && environment["CI"] == null
            return TerminalUi(styled, screenMode, width)
        }

        fun plain(width: Int = DEFAULT_WIDTH): TerminalUi =
            TerminalUi(false, false, width.coerceIn(MIN_WIDTH, MAX_WIDTH))
    }
}

internal object TerminalWidthDetector {
    fun detect(): Int? {
        val result = BoundedProcessExecutor(Duration.ofMillis(DETECTION_TIMEOUT_MS)).execute(
            listOf("sh", "-c", "stty size < /dev/tty"),
        )
        if (result !is ProcessResult.Completed || result.exitCode != 0) return null
        return parse(result.output)
    }

    fun parse(output: String): Int? = output.trim()
        .split(Regex("\\s+"))
        .takeIf { it.size >= 2 }
        ?.last()
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    private const val DETECTION_TIMEOUT_MS = 500L
}

internal enum class TerminalTone {
    DEFAULT,
    SUCCESS,
    WARNING,
    FAILURE,
}

internal fun ellipsizeMiddle(value: String, maxLength: Int): String {
    require(maxLength >= 3) { "말줄임 너비는 3 이상이어야 합니다." }
    if (terminalCellWidth(value) <= maxLength) return value
    val remaining = maxLength - 1
    val prefixWidth = remaining / 3
    val suffixWidth = remaining - prefixWidth
    return value.takeDisplayCells(prefixWidth) + "…" + value.takeLastDisplayCells(suffixWidth)
}

internal fun terminalCellWidth(value: String): Int = value.codePoints().map { codePoint ->
    if (codePoint.isWideTerminalCharacter()) 2 else 1
}.sum()

private fun String.takeDisplayCells(maxWidth: Int): String {
    val result = StringBuilder()
    var used = 0
    codePoints().forEachOrdered { codePoint ->
        val characterWidth = if (codePoint.isWideTerminalCharacter()) 2 else 1
        if (used + characterWidth <= maxWidth) {
            result.appendCodePoint(codePoint)
            used += characterWidth
        }
    }
    return result.toString()
}

private fun String.takeLastDisplayCells(maxWidth: Int): String {
    val selected = mutableListOf<Int>()
    var used = 0
    val codePoints = codePoints().toArray()
    for (index in codePoints.lastIndex downTo 0) {
        val codePoint = codePoints[index]
        val characterWidth = if (codePoint.isWideTerminalCharacter()) 2 else 1
        if (used + characterWidth > maxWidth) break
        selected += codePoint
        used += characterWidth
    }
    return buildString { selected.asReversed().forEach(::appendCodePoint) }
}

private fun String.splitByDisplayWidth(maxWidth: Int): List<String> {
    val chunks = mutableListOf<String>()
    var current = StringBuilder()
    var used = 0
    codePoints().forEachOrdered { codePoint ->
        val characterWidth = if (codePoint.isWideTerminalCharacter()) 2 else 1
        if (used + characterWidth > maxWidth && current.isNotEmpty()) {
            chunks += current.toString()
            current = StringBuilder()
            used = 0
        }
        current.appendCodePoint(codePoint)
        used += characterWidth
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks.ifEmpty { listOf("") }
}

private fun Int.isWideTerminalCharacter(): Boolean =
    this in 0x1100..0x115F || this == 0x2329 || this == 0x232A ||
        this in 0x2E80..0xA4CF || this in 0xAC00..0xD7A3 || this in 0xF900..0xFAFF ||
        this in 0xFE10..0xFE19 || this in 0xFE30..0xFE6F || this in 0xFF00..0xFF60 ||
        this in 0xFFE0..0xFFE6 || this in 0x1F300..0x1FAFF || this in 0x20000..0x3FFFD
