package io.github.yubinshin.resubmit

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.isRegularFile

internal fun interface CodeRunner {
    fun compile(sourceFiles: List<Path>, outputDirectory: Path): CompilationResult
}

internal class ProcessJavaCodeRunner(
    private val timeout: Duration = Duration.ofSeconds(10),
    private val outputLimitChars: Int = 64 * 1024,
    private val commandFactory: (List<Path>, Path) -> List<String> = ::javacCommand,
) : CodeRunner {
    override fun compile(sourceFiles: List<Path>, outputDirectory: Path): CompilationResult {
        val missingSource = sourceFiles.firstOrNull { sourceFile -> !sourceFile.isRegularFile() }
        if (missingSource != null) {
            return CompilationResult.RunnerError("컴파일할 파일을 찾을 수 없습니다: $missingSource")
        }

        return try {
            Files.createDirectories(outputDirectory)
            when (val process = BoundedProcessExecutor(timeout, outputLimitChars)
                .execute(commandFactory(sourceFiles, outputDirectory))) {
                is ProcessResult.Completed -> if (process.exitCode == 0) {
                    CompilationResult.Success(
                        outputDirectory = outputDirectory,
                        durationMs = process.durationMs,
                        diagnostics = process.output,
                        outputTruncated = process.outputTruncated,
                    )
                } else {
                    CompilationResult.Failed(
                        exitCode = process.exitCode,
                        durationMs = process.durationMs,
                        diagnostics = process.output,
                        outputTruncated = process.outputTruncated,
                    )
                }
                is ProcessResult.TimedOut ->
                CompilationResult.TimedOut(
                    durationMs = process.durationMs,
                    diagnostics = process.output,
                    outputTruncated = process.outputTruncated,
                )
                is ProcessResult.RunnerError -> CompilationResult.RunnerError(process.message)
            }
        } catch (exception: Exception) {
            CompilationResult.RunnerError(
                message = exception.message ?: exception::class.simpleName.orEmpty(),
            )
        }
    }

    private companion object {
        fun javacCommand(sourceFiles: List<Path>, outputDirectory: Path): List<String> = listOf(
            Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
            "-J-Duser.language=en",
            "-J-Duser.country=US",
            "-J-Dfile.encoding=UTF-8",
            "-encoding",
            "UTF-8",
            "-d",
            outputDirectory.toString(),
        ) + sourceFiles.map(Path::toString)
    }
}

internal sealed interface CompilationResult {
    data class Success(
        val outputDirectory: Path,
        val durationMs: Long,
        val diagnostics: String,
        val outputTruncated: Boolean,
    ) : CompilationResult

    data class Failed(
        val exitCode: Int,
        val durationMs: Long,
        val diagnostics: String,
        val outputTruncated: Boolean,
    ) : CompilationResult

    data class TimedOut(
        val durationMs: Long,
        val diagnostics: String,
        val outputTruncated: Boolean,
    ) : CompilationResult

    data class RunnerError(
        val message: String,
    ) : CompilationResult
}
