package io.github.yubinshin.resubmit

import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

internal fun interface TestRunner {
    fun run(classDirectory: Path, testCases: List<ProblemTestCase>): TestRunResult
}

internal class ProcessJavaTestRunner(
    private val timeout: Duration = Duration.ofSeconds(2),
    private val outputLimitChars: Int = 64 * 1024,
    private val commandFactory: (Path, Path, String) -> List<String> = ::javaCommand,
) : TestRunner {
    override fun run(classDirectory: Path, testCases: List<ProblemTestCase>): TestRunResult {
        return try {
            val protocolDirectory = Files.createTempDirectory("resubmit-protocol-")
            try {
                val resultFile = protocolDirectory.resolve("results.tsv")
                val protocolToken = UUID.randomUUID().toString()
                when (val process = BoundedProcessExecutor(timeout, outputLimitChars)
                    .execute(commandFactory(classDirectory, resultFile, protocolToken))) {
                    is ProcessResult.TimedOut -> TestRunResult.TimedOut(
                        durationMs = process.durationMs,
                        output = process.output,
                        outputTruncated = process.outputTruncated,
                    )
                    is ProcessResult.RunnerError -> TestRunResult.RunnerError(process.message)
                    is ProcessResult.Completed -> parse(
                        process = process,
                        testCases = testCases,
                        protocolOutput = if (Files.isRegularFile(resultFile)) Files.readString(resultFile) else "",
                        protocolToken = protocolToken,
                    )
                }
            } finally {
                protocolDirectory.toFile().deleteRecursively()
            }
        } catch (exception: Exception) {
            TestRunResult.RunnerError(exception.message ?: exception::class.simpleName.orEmpty())
        }
    }

    private fun parse(
        process: ProcessResult.Completed,
        testCases: List<ProblemTestCase>,
        protocolOutput: String,
        protocolToken: String,
    ): TestRunResult {
        val cases = protocolOutput.lineSequence()
            .filter { line -> line.isNotBlank() }
            .mapNotNull { line -> parseCase(line, protocolToken) }
            .toList()

        if (cases.size != testCases.size) {
            return TestRunResult.ExecutionError(
                exitCode = process.exitCode,
                durationMs = process.durationMs,
                message = "테스트 결과를 모두 읽지 못했습니다 (${cases.size}/${testCases.size}).",
                output = process.output,
                outputTruncated = process.outputTruncated,
            )
        }

        cases.zip(testCases).forEach { (actual, expected) ->
            if (actual.id != expected.id) {
                return protocolError(process, "예상하지 않은 테스트 ID입니다: ${actual.id}")
            }
            if (actual is TestCaseResult.Value && actual.expected != expected.expected) {
                return protocolError(process, "${actual.id}의 expected 값이 일치하지 않습니다.")
            }
        }

        val expectedExitCode = if (cases.all(TestCaseResult::passed)) 0 else 1
        if (process.exitCode != expectedExitCode) {
            return protocolError(process, "테스트 결과와 프로세스 종료 코드가 일치하지 않습니다.")
        }

        return TestRunResult.Completed(
            cases = cases,
            durationMs = process.durationMs,
            output = process.output,
            outputTruncated = process.outputTruncated,
        )
    }

    private fun protocolError(process: ProcessResult.Completed, message: String) = TestRunResult.ExecutionError(
        exitCode = process.exitCode,
        durationMs = process.durationMs,
        message = message,
        output = process.output,
        outputTruncated = process.outputTruncated,
    )

    private fun decodeValue(value: String): String = if (value.startsWith("b64:")) {
        runCatching {
            String(java.util.Base64.getUrlDecoder().decode(value.removePrefix("b64:")), Charsets.UTF_8)
        }.getOrDefault(value)
    } else value

    private fun parseCase(line: String, protocolToken: String): TestCaseResult? {
        val fields = line.split('\t')
        if (fields.size < 5 || fields[0] != protocolToken || fields[1] != "RESULT") {
            return null
        }
        return when (fields[3]) {
            "PASS", "FAIL" -> if (fields.size == 6) {
                TestCaseResult.Value(
                    id = fields[2],
                    passed = fields[3] == "PASS",
                    expected = decodeValue(fields[4]),
                    actual = decodeValue(fields[5]),
                )
            } else {
                null
            }
            "ERROR" -> if (fields.size == 5) TestCaseResult.Error(fields[2], fields[4]) else null
            else -> null
        }
    }

    private companion object {
        fun javaCommand(classDirectory: Path, resultFile: Path, protocolToken: String): List<String> = listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=UTF-8",
            "-cp",
            classDirectory.toString(),
            "TestHarness",
            resultFile.toString(),
            protocolToken,
        )
    }
}

internal sealed interface TestRunResult {
    data class Completed(
        val cases: List<TestCaseResult>,
        val durationMs: Long,
        val output: String = "",
        val outputTruncated: Boolean = false,
    ) : TestRunResult {
        val passedCount: Int = cases.count(TestCaseResult::passed)
    }

    data class TimedOut(
        val durationMs: Long,
        val output: String = "",
        val outputTruncated: Boolean = false,
    ) : TestRunResult

    data class ExecutionError(
        val exitCode: Int,
        val durationMs: Long,
        val message: String,
        val output: String = "",
        val outputTruncated: Boolean = false,
    ) : TestRunResult

    data class RunnerError(val message: String) : TestRunResult
}

internal sealed interface TestCaseResult {
    val id: String
    val passed: Boolean

    data class Value(
        override val id: String,
        override val passed: Boolean,
        val expected: String,
        val actual: String,
    ) : TestCaseResult

    data class Error(
        override val id: String,
        val errorType: String,
    ) : TestCaseResult {
        override val passed: Boolean = false
    }
}
