package io.github.yubinshin.resubmit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class DiagnosticStatus { IN_PROGRESS, PAUSED, COMPLETED }

@Serializable
internal enum class DiagnosticOutcome { PASSED, NOT_PASSED, COMPILE_FAILED, TIMED_OUT, NOT_SUBMITTED }

@Serializable
internal data class DiagnosticObservation(
    val problemId: String,
    val outcome: DiagnosticOutcome,
    val attemptCount: Int,
    val hintLevelViewed: Int? = null,
    val elapsedSeconds: Long,
)

@Serializable
internal data class DiagnosticSessionRecord(
    val sessionId: String,
    val problemIds: List<String>,
    val currentProblemIndex: Int = 0,
    val status: DiagnosticStatus,
    val elapsedSeconds: Long = 0,
    val activeSinceEpochMillis: Long? = null,
    val problemSessionIds: Map<String, String> = emptyMap(),
    val observations: List<DiagnosticObservation> = emptyList(),
    // Interrupted time is already in elapsedSeconds but not yet in the current observation.
    val currentProblemElapsedSeconds: Long = 0,
)

internal sealed interface DiagnosticSessionReadResult {
    data class Found(val session: DiagnosticSessionRecord) : DiagnosticSessionReadResult
    data object NotFound : DiagnosticSessionReadResult
    data class Failed(val message: String) : DiagnosticSessionReadResult
}

internal sealed interface DiagnosticSessionWriteResult {
    data object Success : DiagnosticSessionWriteResult
    data class Failed(val message: String) : DiagnosticSessionWriteResult
}

internal interface DiagnosticSessionStore {
    fun read(): DiagnosticSessionReadResult
    fun write(session: DiagnosticSessionRecord): DiagnosticSessionWriteResult
}

internal fun checkpointActiveDiagnosticSession(
    store: DiagnosticSessionStore,
    nowEpochMillis: Long,
): DiagnosticSessionWriteResult? {
    val session = (store.read() as? DiagnosticSessionReadResult.Found)?.session ?: return null
    if (session.status != DiagnosticStatus.IN_PROGRESS) return null
    val elapsed = session.activeSinceEpochMillis
        ?.let { ((nowEpochMillis - it).coerceAtLeast(0) / 1_000) }
        ?: 0
    return store.write(
        session.copy(
            status = DiagnosticStatus.PAUSED,
            elapsedSeconds = session.elapsedSeconds + elapsed,
            currentProblemElapsedSeconds = session.currentProblemElapsedSeconds + elapsed,
            activeSinceEpochMillis = null,
        ),
    )
}

internal class JsonDiagnosticSessionStore(private val path: Path) : DiagnosticSessionStore {
    private val json = Json { encodeDefaults = true }

    override fun read(): DiagnosticSessionReadResult {
        if (Files.notExists(path)) return DiagnosticSessionReadResult.NotFound
        return try {
            DiagnosticSessionReadResult.Found(json.decodeFromString(Files.readString(path)))
        } catch (exception: Exception) {
            DiagnosticSessionReadResult.Failed(exception.message ?: exception::class.simpleName.orEmpty())
        }
    }

    override fun write(session: DiagnosticSessionRecord): DiagnosticSessionWriteResult = try {
        val parent = requireNotNull(path.parent) { "진단 세션 경로에 부모 디렉터리가 필요합니다." }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "${path.fileName}-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(session))
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        DiagnosticSessionWriteResult.Success
    } catch (exception: Exception) {
        DiagnosticSessionWriteResult.Failed(exception.message ?: exception::class.simpleName.orEmpty())
    }
}
