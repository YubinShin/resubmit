package io.github.yubinshin.resubmit

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorLauncherTest {
    @Test
    fun `RESUBMIT_EDITOR takes priority over EDITOR`() {
        val commands = mutableListOf<List<String>>()
        val launcher = EnvironmentEditorLauncher(
            mapOf("RESUBMIT_EDITOR" to "preferred --wait", "EDITOR" to "fallback"),
            recordingRunner(commands),
        )

        assertEquals(EditorLaunchResult.Completed(0), launcher.open(Path.of("Solution.java")))
        assertEquals("preferred", commands.single().first())
        assertEquals("--wait", commands.single()[1])
    }

    @Test
    fun `EDITOR is used when RESUBMIT_EDITOR is absent`() {
        val commands = mutableListOf<List<String>>()
        val launcher = EnvironmentEditorLauncher(
            mapOf("EDITOR" to "fallback --wait"),
            recordingRunner(commands),
        )

        launcher.open(Path.of("Solution.java"))

        assertEquals("fallback", commands.single().first())
    }

    @Test
    fun `returns not configured when editor variables are absent`() {
        assertEquals(
            EditorLaunchResult.NotConfigured,
            EnvironmentEditorLauncher(emptyMap()).open(Path.of("Solution.java")),
        )
    }

    @Test
    fun `preserves quoted executable paths and arguments without a shell`() {
        val commands = mutableListOf<List<String>>()
        val launcher = EnvironmentEditorLauncher(
            mapOf("RESUBMIT_EDITOR" to "\"/Applications/Editor With Spaces/bin/editor\" --wait \"profile name\""),
            recordingRunner(commands),
        )
        val file = Path.of("directory with spaces", "Solution.java").toAbsolutePath().normalize()

        launcher.open(file)

        assertEquals(
            listOf(
                "/Applications/Editor With Spaces/bin/editor",
                "--wait",
                "profile name",
                file.toString(),
            ),
            commands.single(),
        )
    }

    private fun recordingRunner(commands: MutableList<List<String>>) = EditorProcessRunner { command ->
        commands += command
        EditorLaunchResult.Completed(0)
    }
}
