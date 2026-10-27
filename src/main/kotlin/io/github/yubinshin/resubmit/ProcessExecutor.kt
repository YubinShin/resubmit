package io.github.yubinshin.resubmit

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.TimeSource

internal class BoundedProcessExecutor(
    private val timeout: Duration,
    private val outputLimitChars: Int = 64 * 1024,
) {
    fun execute(command: List<String>): ProcessResult {
        var process: Process? = null
        return try {
            val startedAt = TimeSource.Monotonic.markNow()
            val startedProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process = startedProcess
            val descendants = linkedMapOf<Long, ProcessHandle>()
            val output = BoundedProcessOutput(outputLimitChars)
            val outputReadFailure = AtomicReference<Throwable?>()
            val closingOutput = AtomicBoolean(false)
            val reader = thread(name = "process-output-reader", isDaemon = true) {
                try {
                    startedProcess.inputStream.reader(StandardCharsets.UTF_8).use { input ->
                        val buffer = CharArray(8 * 1024)
                        var count = input.read(buffer)
                        while (count != -1) {
                            output.append(buffer, count)
                            count = input.read(buffer)
                        }
                    }
                } catch (throwable: Throwable) {
                    if (!closingOutput.get()) {
                        outputReadFailure.set(throwable)
                    }
                }
            }

            if (!waitFor(startedProcess, descendants)) {
                terminate(startedProcess, descendants.values)
                reader.join(READER_JOIN_TIMEOUT_MS)
                ProcessResult.TimedOut(
                    durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                    output = output.value(),
                    outputTruncated = output.truncated,
                )
            } else {
                terminateDescendants(descendants.values)
                reader.join(READER_JOIN_TIMEOUT_MS)
                if (reader.isAlive) {
                    closingOutput.set(true)
                    startedProcess.inputStream.close()
                    reader.join(READER_CLOSE_JOIN_TIMEOUT_MS)
                }
                outputReadFailure.get()?.let { failure ->
                    return ProcessResult.RunnerError(
                        "프로세스 출력을 읽지 못했습니다: ${failure.message ?: failure::class.simpleName}",
                    )
                }
                ProcessResult.Completed(
                    exitCode = startedProcess.exitValue(),
                    durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                    output = output.value(),
                    outputTruncated = output.truncated,
                )
            }
        } catch (exception: InterruptedException) {
            process?.let { startedProcess -> runCatching { terminate(startedProcess) } }
            Thread.currentThread().interrupt()
            ProcessResult.RunnerError("프로세스 실행이 중단되었습니다.")
        } catch (exception: Exception) {
            ProcessResult.RunnerError(exception.message ?: exception::class.simpleName.orEmpty())
        }
    }

    private fun waitFor(process: Process, descendants: MutableMap<Long, ProcessHandle>): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (process.isAlive) {
            rememberDescendants(process, descendants)
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) {
                rememberDescendants(process, descendants)
                return false
            }
            process.waitFor(
                minOf(WAIT_POLL_MS, TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1)),
                TimeUnit.MILLISECONDS,
            )
        }
        rememberDescendants(process, descendants)
        return true
    }

    private fun terminate(process: Process, knownDescendants: Collection<ProcessHandle> = emptyList()) {
        val descendants = knownDescendants.associateByTo(linkedMapOf()) { handle -> handle.pid() }
        rememberDescendants(process, descendants)
        descendants.values.toList().asReversed().forEach { handle -> handle.destroy() }
        process.destroy()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TERMINATION_GRACE_MS)
        while (process.isAlive && System.nanoTime() < deadline) {
            rememberDescendants(process, descendants)
            descendants.values.filter { handle -> handle.isAlive }.forEach { handle -> handle.destroy() }
            process.waitFor(WAIT_POLL_MS, TimeUnit.MILLISECONDS)
        }
        rememberDescendants(process, descendants)
        descendants.values.filter { handle -> handle.isAlive }.forEach { handle -> handle.destroyForcibly() }
        waitForHandles(descendants.values)
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }

    private fun terminateDescendants(descendants: Collection<ProcessHandle>) {
        descendants.toList().asReversed().filter { handle -> handle.isAlive }.forEach { handle -> handle.destroy() }
        descendants.filter { handle -> handle.isAlive }.forEach { handle -> handle.destroyForcibly() }
        waitForHandles(descendants)
    }

    private fun waitForHandles(handles: Collection<ProcessHandle>) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TERMINATION_GRACE_MS)
        while (handles.any { handle -> handle.isAlive } && System.nanoTime() < deadline) {
            Thread.sleep(WAIT_POLL_MS)
        }
    }

    private fun rememberDescendants(process: Process, descendants: MutableMap<Long, ProcessHandle>) {
        process.toHandle().descendants().forEach { handle -> descendants.putIfAbsent(handle.pid(), handle) }
    }

    private class BoundedProcessOutput(
        private val limit: Int,
    ) {
        private val content = StringBuilder()
        var truncated: Boolean = false
            private set

        @Synchronized
        fun append(buffer: CharArray, count: Int) {
            val retained = minOf(count, (limit - content.length).coerceAtLeast(0))
            content.append(buffer, 0, retained)
            if (retained < count) truncated = true
        }

        @Synchronized
        fun value(): String = content.toString()
    }

    private companion object {
        const val READER_JOIN_TIMEOUT_MS = 1_000L
        const val READER_CLOSE_JOIN_TIMEOUT_MS = 200L
        const val TERMINATION_GRACE_MS = 200L
        const val WAIT_POLL_MS = 10L
    }
}

internal sealed interface ProcessResult {
    data class Completed(
        val exitCode: Int,
        val durationMs: Long,
        val output: String,
        val outputTruncated: Boolean,
    ) : ProcessResult

    data class TimedOut(
        val durationMs: Long,
        val output: String,
        val outputTruncated: Boolean,
    ) : ProcessResult

    data class RunnerError(val message: String) : ProcessResult
}
