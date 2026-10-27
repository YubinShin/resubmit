package io.github.yubinshin.resubmit

import java.time.Duration
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessExecutorTest {
    @Test
    fun `large output without newline remains bounded in a small parent heap`() {
        val classpath = listOf(
            ProcessExecutorOutputProbe::class.java,
            BoundedProcessExecutor::class.java,
            kotlin.Unit::class.java,
        ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(System.getProperty("path.separator"))
        val result = assertIs<ProcessResult.Completed>(BoundedProcessExecutor(Duration.ofSeconds(20)).execute(
            listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx16m",
                "-cp", classpath, ProcessExecutorOutputProbe::class.java.name),
        ))
        assertEquals(0, result.exitCode, result.output)
        assertTrue(result.output.contains("BOUNDED_OUTPUT_OK"), result.output)
    }

    @Test
    fun `completed root cleans up background child that inherits output`() {
        val root = createTempDirectory("resubmit-background-child-test")
        val pidFile = root.resolve("child.pid")
        try {
            val result = BoundedProcessExecutor(Duration.ofSeconds(3)).execute(
                listOf(
                    "/bin/sh",
                    "-c",
                    "sleep 30 & echo \$! > \"\$1\"; echo root-finished; sleep 0.1",
                    "sh",
                    pidFile.toString(),
                ),
            )
            val childPid = pidFile.readText().trim().toLong()

            assertIs<ProcessResult.Completed>(result)
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("root-finished"))
            waitUntil { ProcessHandle.of(childPid).map { handle -> !handle.isAlive }.orElse(true) }
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `timeout cleans up child started while root is terminating`() {
        val root = createTempDirectory("resubmit-timeout-race-test")
        val pidFile = root.resolve("late-child.pid")
        try {
            val result = BoundedProcessExecutor(Duration.ofMillis(200)).execute(
                listOf(
                    "/bin/sh",
                    "-c",
                    "trap 'sleep 30 & echo \$! > \"\$1\"; wait' TERM; while :; do :; done",
                    "sh",
                    pidFile.toString(),
                ),
            )

            assertIs<ProcessResult.TimedOut>(result)
            waitUntil { pidFile.exists() }
            val childPid = pidFile.readText().trim().toLong()
            waitUntil { ProcessHandle.of(childPid).map { handle -> !handle.isAlive }.orElse(true) }
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `interrupt terminates child process and restores interrupt flag`() {
        val root = createTempDirectory("resubmit-interrupt-test")
        val pidFile = root.resolve("child.pid")
        val result = AtomicReference<ProcessResult>()
        val interrupted = AtomicBoolean(false)
        try {
            val worker = thread(name = "process-interrupt-test") {
                result.set(
                    BoundedProcessExecutor(Duration.ofSeconds(30)).execute(
                        listOf("/bin/sh", "-c", "echo \$\$ > \"\$1\"; sleep 30", "sh", pidFile.toString()),
                    ),
                )
                interrupted.set(Thread.currentThread().isInterrupted)
            }
            waitUntil { pidFile.exists() }
            val childPid = pidFile.readText().trim().toLong()

            worker.interrupt()
            worker.join(3_000)
            waitUntil { ProcessHandle.of(childPid).map { handle -> !handle.isAlive }.orElse(true) }

            assertFalse(worker.isAlive)
            assertIs<ProcessResult.RunnerError>(result.get())
            assertTrue(interrupted.get())
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(condition())
    }
}

/** Separate parent JVM makes this regression fail even when the Gradle worker has a large heap. */
object ProcessExecutorOutputProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "produce") {
            val chunk = ByteArray(8192) { 'x'.code.toByte() }
            repeat(4096) { System.out.write(chunk) } // 32 MiB, no newline.
            System.out.flush()
            return
        }
        val result = BoundedProcessExecutor(Duration.ofSeconds(10), 1024).execute(listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), ProcessExecutorOutputProbe::class.java.name, "produce",
        ))
        check(result is ProcessResult.Completed) { result.toString() }
        check(result.exitCode == 0) { result.toString() }
        check(result.output == "x".repeat(1024))
        check(result.outputTruncated)
        println("BOUNDED_OUTPUT_OK")
    }
}
