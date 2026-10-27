package io.github.yubinshin.resubmit

import java.nio.file.Path
import kotlin.io.path.readLines

internal class CompilerDiagnosticRenderer {
    fun render(sourceFile: Path, rawDiagnostics: String): String {
        val diagnostic = parse(rawDiagnostics) ?: return fallback(rawDiagnostics)
        val sourceLine = sourceFile.readLines().getOrNull(diagnostic.line - 1).orEmpty()
        val lineNumberWidth = diagnostic.line.toString().length
        val caretPadding = " ".repeat((diagnostic.column - 1).coerceAtLeast(0))

        return buildString {
            appendLine("error[COMPILE]: ${title(diagnostic.message)}")
            appendLine("  --> ${sourceFile.fileName}:${diagnostic.line}:${diagnostic.column}")
            appendLine("${" ".repeat(lineNumberWidth)} |")
            appendLine("${diagnostic.line} | $sourceLine")
            appendLine("${" ".repeat(lineNumberWidth)} | $caretPadding^")
            appendLine("${" ".repeat(lineNumberWidth)} |")
            appendLine("help: ${help(diagnostic.message)}")
            appendLine()
            appendLine("javac: ${diagnostic.message}")
        }
    }

    private fun parse(rawDiagnostics: String): CompilerDiagnostic? {
        val lines = rawDiagnostics.lines()
        lines.forEachIndexed { index, line ->
            val match = DIAGNOSTIC_HEADER.matchEntire(line) ?: return@forEachIndexed
            val sourceLine = lines.getOrNull(index + 1).orEmpty()
            val caretLine = lines.getOrNull(index + 2).orEmpty()
            val column = caretLine.indexOf('^').takeIf { it >= 0 }?.plus(1)
                ?: sourceLine.length.coerceAtLeast(1)
            return CompilerDiagnostic(
                line = match.groupValues[2].toInt(),
                column = column,
                message = match.groupValues[4],
            )
        }
        return null
    }

    private fun title(message: String): String = when {
        message == "';' expected" -> "문장이 완성되지 않았습니다"
        message.startsWith("cannot find symbol") -> "이름을 찾을 수 없습니다"
        message.startsWith("incompatible types") -> "타입이 일치하지 않습니다"
        message.startsWith("missing return statement") -> "반환문이 필요합니다"
        message.contains("should be declared in a file named") -> "클래스명과 파일명이 일치하지 않습니다"
        else -> "Java 컴파일에 실패했습니다"
    }

    private fun help(message: String): String = when {
        message == "';' expected" -> "문장 끝에 세미콜론(`;`)이 빠졌는지 확인하세요."
        message.startsWith("cannot find symbol") -> "변수·메서드 이름의 철자와 선언 범위를 확인하세요."
        message.startsWith("incompatible types") -> "대입하거나 반환하는 값의 타입을 확인하세요."
        message.startsWith("missing return statement") -> "모든 실행 경로에서 반환값을 제공하는지 확인하세요."
        message.contains("should be declared in a file named") -> "public 클래스 이름을 `Solution`으로 유지하세요."
        else -> "표시된 위치와 바로 앞 문장이 완성됐는지 확인하세요."
    }

    private fun fallback(rawDiagnostics: String): String = buildString {
        appendLine("error[COMPILE]: Java 컴파일에 실패했습니다")
        appendLine("help: javac 원문에서 오류 위치와 바로 앞 문장을 확인하세요.")
        if (rawDiagnostics.isNotBlank()) {
            appendLine()
            appendLine("javac:")
            rawDiagnostics.trimEnd().lineSequence().forEach { line -> appendLine("  $line") }
        }
    }

    private companion object {
        val DIAGNOSTIC_HEADER = Regex("^(.+\\.java):(\\d+): (error|warning): (.+)$")
    }
}

internal data class CompilerDiagnostic(
    val line: Int,
    val column: Int,
    val message: String,
)
