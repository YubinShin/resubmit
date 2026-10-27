package io.github.yubinshin.resubmit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun interface AttemptRecorder {
    fun record(
        workspace: SessionWorkspace,
        snapshot: SubmissionSnapshot,
        problem: Problem,
        compilation: CompilationResult,
        tests: TestRunResult?,
        review: ReviewHistory,
    ): HistoryWriteResult
}

internal fun interface AttemptHistoryReader {
    fun latest(sessionId: String): HistoryReadResult

    fun allRecords(): HistoryRecordsReadResult = HistoryRecordsReadResult.NotFound

    fun previous(sessionId: String, excludingAttemptId: String): HistoryReadResult = latest(sessionId)

    fun all(sessionId: String): HistoryRecordsReadResult = when (val result = latest(sessionId)) {
        is HistoryReadResult.Found -> HistoryRecordsReadResult.Found(listOf(result.attempt))
        HistoryReadResult.NotFound -> HistoryRecordsReadResult.NotFound
        is HistoryReadResult.Failed -> HistoryRecordsReadResult.Failed(result.message)
    }
}

internal class JsonlAttemptHistoryReader(
    private val historyFile: Path,
) : AttemptHistoryReader {
    private val json = Json { ignoreUnknownKeys = true }

    override fun allRecords(): HistoryRecordsReadResult = try {
        if (Files.notExists(historyFile)) {
            HistoryRecordsReadResult.NotFound
        } else {
            val records = Files.readAllLines(historyFile, StandardCharsets.UTF_8)
                .filter(String::isNotBlank).mapIndexed { index, line ->
                    val record = runCatching { json.decodeFromString<AttemptRecord>(line) }.getOrElse {
                        error("제출 이력 ${index + 1}행을 읽지 못했습니다.")
                    }
                    require(record.schemaVersion in SUPPORTED_SCHEMA_VERSIONS) {
                        "제출 이력 ${index + 1}행의 버전을 지원하지 않습니다."
                    }
                    record
                }
            if (records.isEmpty()) HistoryRecordsReadResult.NotFound else HistoryRecordsReadResult.Found(records)
        }
    } catch (exception: Exception) {
        HistoryRecordsReadResult.Failed(exception.message ?: "제출 이력을 읽지 못했습니다.")
    }

    override fun latest(sessionId: String): HistoryReadResult = readRecords(sessionId).let { result ->
        when (result) {
            is HistoryReadResult.Found -> result
            HistoryReadResult.NotFound -> result
            is HistoryReadResult.Failed -> result
        }
    }

    override fun previous(sessionId: String, excludingAttemptId: String): HistoryReadResult = try {
        val previous = validRecords(sessionId).firstOrNull { it.attemptId != excludingAttemptId }
        if (previous == null) HistoryReadResult.NotFound else HistoryReadResult.Found(previous)
    } catch (exception: Exception) {
        HistoryReadResult.Failed(exception.message ?: exception::class.simpleName.orEmpty())
    }

    override fun all(sessionId: String): HistoryRecordsReadResult = try {
        val records = validRecords(sessionId).asReversed()
        if (records.isEmpty()) HistoryRecordsReadResult.NotFound else HistoryRecordsReadResult.Found(records)
    } catch (exception: Exception) {
        HistoryRecordsReadResult.Failed(exception.message ?: exception::class.simpleName.orEmpty())
    }

    private fun readRecords(sessionId: String): HistoryReadResult = try {
        val record = validRecords(sessionId).firstOrNull()
        if (record == null) HistoryReadResult.NotFound else HistoryReadResult.Found(record)
    } catch (exception: Exception) {
        HistoryReadResult.Failed(exception.message ?: exception::class.simpleName.orEmpty())
    }

    private fun validRecords(sessionId: String): List<AttemptRecord> {
        if (!Files.exists(historyFile)) {
            return emptyList()
        }
        return Files.readAllLines(historyFile, StandardCharsets.UTF_8)
            .asReversed()
            .asSequence()
            .filter(String::isNotBlank)
            .mapNotNull { line -> runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() }
            .filter { element -> element["sessionId"]?.jsonPrimitive?.contentOrNull == sessionId }
            .filter { element -> element["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() in SUPPORTED_SCHEMA_VERSIONS }
            .mapNotNull { element -> runCatching { json.decodeFromJsonElement<AttemptRecord>(element) }.getOrNull() }
            .toList()
    }
}

internal class JsonlAttemptRecorder(
    private val historyFile: Path,
) : AttemptRecorder {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Synchronized
    override fun record(
        workspace: SessionWorkspace,
        snapshot: SubmissionSnapshot,
        problem: Problem,
        compilation: CompilationResult,
        tests: TestRunResult?,
        review: ReviewHistory,
    ): HistoryWriteResult {
        return try {
            val attempt = attemptRecord(workspace, snapshot, problem, compilation, tests, review)
            historyFile.parent?.let(Files::createDirectories)
            Files.writeString(
                historyFile,
                json.encodeToString(attempt) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            HistoryWriteResult.Success(historyFile)
        } catch (exception: Exception) {
            HistoryWriteResult.Failed(exception.message ?: exception::class.simpleName.orEmpty())
        }
    }

    private fun attemptRecord(
        workspace: SessionWorkspace,
        snapshot: SubmissionSnapshot,
        problem: Problem,
        compilation: CompilationResult,
        tests: TestRunResult?,
        review: ReviewHistory,
    ): AttemptRecord = AttemptRecord(
        sessionId = workspace.sessionId,
        attemptId = snapshot.attemptId,
        problemId = problem.id,
        submittedAt = snapshot.submittedAt,
        sourceSha256 = snapshot.sourceSha256,
        compilation = compilation.toRecord(),
        tests = tests.toRecord(),
        review = review.toRecord(),
    )

    private fun ReviewHistory.toRecord(): ReviewRecord = when (this) {
        ReviewHistory.NotRun -> ReviewRecord(status = ReviewStatus.NOT_RUN)
        is ReviewHistory.Unavailable -> ReviewRecord(
            status = ReviewStatus.UNAVAILABLE,
            provider = provider,
            errorMessage = message,
        )
        is ReviewHistory.Ready -> ReviewRecord(
            status = ReviewStatus.READY,
            provider = review.provider,
            failureType = review.failureType,
            evidence = review.evidence,
            relatedSkills = review.relatedSkills,
            hints = review.hints,
            model = review.model,
            promptVersion = review.promptVersion,
            hintLevel = hintLevelViewed,
            hintViewed = hintViewed,
            citedEvidenceIds = review.citedEvidenceIds,
            sourceEvidence = review.sourceEvidence,
        )
    }

    private fun CompilationResult.toRecord(): CompilationRecord = when (this) {
        is CompilationResult.Success -> CompilationRecord(
            status = CompilationStatus.PASSED,
            durationMs = durationMs,
            outputTruncated = outputTruncated,
        )
        is CompilationResult.Failed -> CompilationRecord(
            status = CompilationStatus.FAILED,
            exitCode = exitCode,
            durationMs = durationMs,
            outputTruncated = outputTruncated || diagnostics.length > MAX_STORED_DIAGNOSTIC_CHARS,
            diagnostics = diagnostics.take(MAX_STORED_DIAGNOSTIC_CHARS),
        )
        is CompilationResult.TimedOut -> CompilationRecord(
            status = CompilationStatus.TIMED_OUT,
            durationMs = durationMs,
            outputTruncated = outputTruncated || diagnostics.length > MAX_STORED_DIAGNOSTIC_CHARS,
            diagnostics = diagnostics.take(MAX_STORED_DIAGNOSTIC_CHARS),
        )
        is CompilationResult.RunnerError -> CompilationRecord(
            status = CompilationStatus.RUNNER_ERROR,
            errorMessage = message,
        )
    }

    private fun TestRunResult?.toRecord(): TestRecord = when (this) {
        null -> TestRecord(status = TestStatus.NOT_RUN)
        is TestRunResult.Completed -> TestRecord(
            status = if (passedCount == cases.size) TestStatus.PASSED else TestStatus.FAILED,
            passedCount = passedCount,
            totalCount = cases.size,
            durationMs = durationMs,
            cases = cases.map { testCase ->
                when (testCase) {
                    is TestCaseResult.Value -> TestCaseRecord(
                        id = testCase.id,
                        status = if (testCase.passed) TestCaseStatus.PASSED else TestCaseStatus.FAILED,
                        expected = testCase.expected,
                        actual = testCase.actual,
                    )
                    is TestCaseResult.Error -> TestCaseRecord(
                        id = testCase.id,
                        status = TestCaseStatus.ERROR,
                        errorType = testCase.errorType,
                    )
                }
            },
        )
        is TestRunResult.TimedOut -> TestRecord(
            status = TestStatus.TIMED_OUT,
            durationMs = durationMs,
        )
        is TestRunResult.ExecutionError -> TestRecord(
            status = TestStatus.EXECUTION_ERROR,
            durationMs = durationMs,
            exitCode = exitCode,
            errorMessage = message,
        )
        is TestRunResult.RunnerError -> TestRecord(
            status = TestStatus.RUNNER_ERROR,
            errorMessage = message,
        )
    }
}

internal sealed interface HistoryWriteResult {
    data class Success(val path: Path) : HistoryWriteResult
    data class Failed(val message: String) : HistoryWriteResult
}

internal sealed interface HistoryReadResult {
    data class Found(val attempt: AttemptRecord) : HistoryReadResult
    data object NotFound : HistoryReadResult
    data class Failed(val message: String) : HistoryReadResult
}

internal sealed interface HistoryRecordsReadResult {
    data class Found(val attempts: List<AttemptRecord>) : HistoryRecordsReadResult
    data object NotFound : HistoryRecordsReadResult
    data class Failed(val message: String) : HistoryRecordsReadResult
}

@Serializable
internal data class AttemptRecord(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val sessionId: String,
    val attemptId: String,
    val problemId: String,
    val submittedAt: String,
    val sourceSha256: String,
    val compilation: CompilationRecord,
    val tests: TestRecord,
    val review: ReviewRecord,
)

@Serializable
internal data class ReviewRecord(
    val status: ReviewStatus,
    val provider: String? = null,
    val failureType: ReviewFailureType? = null,
    val evidence: List<String> = emptyList(),
    val relatedSkills: List<String> = emptyList(),
    val hints: List<String> = emptyList(),
    val model: String? = null,
    val promptVersion: String? = null,
    val hintLevel: Int? = null,
    val hintViewed: Boolean = false,
    val errorMessage: String? = null,
    val citedEvidenceIds: List<String> = emptyList(),
    val sourceEvidence: List<SourceEvidence> = emptyList(),
)

@Serializable
internal enum class ReviewStatus { READY, UNAVAILABLE, NOT_RUN }

@Serializable
internal data class CompilationRecord(
    val status: CompilationStatus,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val outputTruncated: Boolean = false,
    val diagnostics: String = "",
    val errorMessage: String? = null,
)

@Serializable
internal enum class CompilationStatus { PASSED, FAILED, TIMED_OUT, RUNNER_ERROR }

@Serializable
internal data class TestRecord(
    val status: TestStatus,
    val passedCount: Int? = null,
    val totalCount: Int? = null,
    val durationMs: Long? = null,
    val exitCode: Int? = null,
    val errorMessage: String? = null,
    val cases: List<TestCaseRecord> = emptyList(),
)

@Serializable
internal enum class TestStatus { PASSED, FAILED, TIMED_OUT, EXECUTION_ERROR, RUNNER_ERROR, NOT_RUN }

@Serializable
internal data class TestCaseRecord(
    val id: String,
    val status: TestCaseStatus,
    val expected: String? = null,
    val actual: String? = null,
    val errorType: String? = null,
)

@Serializable
internal enum class TestCaseStatus { PASSED, FAILED, ERROR }

private const val CURRENT_SCHEMA_VERSION = 3
private val SUPPORTED_SCHEMA_VERSIONS = setOf(2, CURRENT_SCHEMA_VERSION)
private const val MAX_STORED_DIAGNOSTIC_CHARS = 8_192
