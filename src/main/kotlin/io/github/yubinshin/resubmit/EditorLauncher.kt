package io.github.yubinshin.resubmit

import java.nio.file.Path

internal fun interface EditorLauncher {
    fun open(file: Path): EditorLaunchResult
}

internal sealed interface EditorLaunchResult {
    data object NotConfigured : EditorLaunchResult
    data class Completed(val exitCode: Int) : EditorLaunchResult
    data class Failed(val message: String) : EditorLaunchResult
}

internal class EnvironmentEditorLauncher(
    environment: Map<String, String>,
    private val processRunner: EditorProcessRunner = SystemEditorProcessRunner(),
) : EditorLauncher {
    private val configuredCommand = environment["RESUBMIT_EDITOR"]
        ?.takeIf(String::isNotBlank)
        ?: environment["EDITOR"]?.takeIf(String::isNotBlank)

    override fun open(file: Path): EditorLaunchResult {
        val command = configuredCommand ?: return EditorLaunchResult.NotConfigured
        val arguments = try {
            EditorCommandParser.parse(command)
        } catch (exception: IllegalArgumentException) {
            return EditorLaunchResult.Failed(exception.message ?: "에디터 명령을 해석하지 못했습니다.")
        }
        return processRunner.run(arguments + file.toAbsolutePath().normalize().toString())
    }
}

internal fun interface EditorProcessRunner {
    fun run(command: List<String>): EditorLaunchResult
}

internal class SystemEditorProcessRunner : EditorProcessRunner {
    override fun run(command: List<String>): EditorLaunchResult = try {
        val exitCode = ProcessBuilder(command)
            .inheritIO()
            .start()
            .waitFor()
        EditorLaunchResult.Completed(exitCode)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        EditorLaunchResult.Failed("에디터 실행 대기가 중단되었습니다.")
    } catch (exception: Exception) {
        EditorLaunchResult.Failed(exception.message ?: exception::class.simpleName.orEmpty())
    }
}

internal object EditorCommandParser {
    fun parse(command: String): List<String> {
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false
        var tokenStarted = false

        command.forEach { character ->
            when {
                escaping -> {
                    current.append(character)
                    escaping = false
                    tokenStarted = true
                }
                character == '\\' && quote != '\'' -> {
                    escaping = true
                    tokenStarted = true
                }
                quote != null && character == quote -> quote = null
                quote == null && (character == '\'' || character == '"') -> {
                    quote = character
                    tokenStarted = true
                }
                quote == null && character.isWhitespace() -> {
                    if (tokenStarted) {
                        arguments += current.toString()
                        current.clear()
                        tokenStarted = false
                    }
                }
                else -> {
                    current.append(character)
                    tokenStarted = true
                }
            }
        }

        require(!escaping) { "에디터 명령 끝의 escape 문자가 완성되지 않았습니다." }
        require(quote == null) { "에디터 명령의 따옴표가 닫히지 않았습니다." }
        if (tokenStarted) arguments += current.toString()
        require(arguments.isNotEmpty()) { "에디터 명령이 비어 있습니다." }
        return arguments
    }
}
