package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MainTest {
    @Test
    fun `application banner identifies the project`() {
        assertEquals(
            "Re:Submit — Evidence-based Coding Test Coach",
            applicationBanner(),
        )
    }

    @Test
    fun `cli creates a workspace and accepts enter as submission`() {
        val projectRoot = createTempDirectory("resubmit-cli-test")
        try {
            val result = runCli(projectRoot, "")

            assertEquals(0, result.exitCode)
            assertContains(result.output, "[target-number-demo] Target Number")
            assertContains(result.output, "작업 파일을 만들었습니다.")
            assertContains(result.output, "제출 파일 확인:")
            assertEquals(1, projectRoot.resolve(".resubmit/sessions").toFile().listFiles()?.size)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workspace contains problem and solution templates`() {
        val projectRoot = createTempDirectory("resubmit-workspace-test")
        try {
            val workspace = workspaceFactory(projectRoot).create(DemoProblem.targetNumber)

            assertEquals("20261007-150000-target-number-demo", workspace.sessionId)
            assertEquals(true, workspace.problemFile.exists())
            assertEquals(true, workspace.solutionFile.exists())
            assertContains(workspace.problemFile.readText(), "# Target Number")
            assertContains(workspace.solutionFile.readText(), "public class Solution")
            assertContains(workspace.solutionFile.readText(), "// TODO: 구현하세요.")
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workspace creation never overwrites an existing session`() {
        val projectRoot = createTempDirectory("resubmit-collision-test")
        try {
            val factory = workspaceFactory(projectRoot)
            val first = factory.create(DemoProblem.targetNumber)
            first.solutionFile.toFile().writeText("user code")
            val second = factory.create(DemoProblem.targetNumber)

            assertNotEquals(first.directory, second.directory)
            assertEquals("user code", first.solutionFile.readText())
            assertEquals("20261007-150000-target-number-demo-2", second.sessionId)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    private fun runCli(projectRoot: Path, command: String): CliResult {
        val output = StringWriter()
        val exitCode = CodingCoachCli(
            input = BufferedReader(StringReader("$command\n")),
            output = PrintWriter(output, true),
            workspaceFactory = workspaceFactory(projectRoot),
        ).run()

        return CliResult(exitCode, output.toString())
    }

    private fun workspaceFactory(projectRoot: Path): SessionWorkspaceFactory = SessionWorkspaceFactory(
        projectRoot = projectRoot,
        clock = Clock.fixed(
            Instant.parse("2026-10-07T06:00:00Z"),
            ZoneId.of("Asia/Seoul"),
        ),
    )

    private data class CliResult(
        val exitCode: Int,
        val output: String,
    )
}
