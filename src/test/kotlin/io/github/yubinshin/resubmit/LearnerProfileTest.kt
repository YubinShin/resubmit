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
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LearnerProfileTest {
    @Test
    fun `persists claimed skill confidence separately as local profile data`() {
        val root = createTempDirectory("resubmit-profile-test")
        try {
            val store = JsonSelfReportStore(root.resolve(".resubmit/profile.json"))
            val profile = SelfReportProfile(
                programmingLanguage = "Kotlin",
                codingExperience = CodingExperience.SOLVED_EASY_INDEPENDENTLY,
                learningGoal = "인터뷰 준비",
                claimedSkillExperience = mapOf(
                    "BFS" to SkillExperience.SOLVED_BASIC_INDEPENDENTLY,
                    "스택" to SkillExperience.UNSURE,
                ),
                recordedAt = "2026-10-08T00:00:00Z",
            )

            assertEquals(SelfReportWriteResult.Success, store.write(profile))
            assertEquals(SelfReportReadResult.Found(profile), store.read())
            assertTrue(root.resolve(".resubmit/profile.json").readText().contains("인터뷰 준비"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `migrates the previous numeric profile format without losing data`() {
        val root = createTempDirectory("resubmit-profile-migration-test")
        try {
            val path = root.resolve(".resubmit/profile.json")
            path.parent.toFile().mkdirs()
            path.writeText(
                """
                {
                  "programmingLanguage": "Java",
                  "experienceLevel": 3,
                  "learningGoal": "코테 준비",
                  "claimedSkillConfidence": {"BFS": 4, "Stack": null},
                  "recordedAt": "2026-10-08T00:00:00Z"
                }
                """.trimIndent(),
            )
            val store = JsonSelfReportStore(path)

            val migrated = assertIs<SelfReportReadResult.Found>(store.read()).profile

            assertEquals(CodingExperience.SOLVED_EASY_INDEPENDENTLY, migrated.codingExperience)
            assertEquals(
                mapOf(
                    "BFS" to SkillExperience.SOLVED_BASIC_INDEPENDENTLY,
                    "Stack" to SkillExperience.UNSURE,
                ),
                migrated.claimedSkillExperience,
            )
            assertEquals(SelfReportReadResult.Found(migrated), store.read())
            assertTrue(path.readText().contains("\"schemaVersion\":2"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `collects validated self report and allows unknown skill confidence`() {
        val output = StringWriter()
        val provider = InteractiveSelfReportProvider(
            input = BufferedReader(StringReader("1\n0\n4\n취업 준비\n6\n5\n")),
            output = PrintWriter(output, true),
            clock = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneId.of("UTC")),
        )

        val profile = assertIs<SelfReportProfile>(provider.collect(listOf("BFS", "Stack")))

        assertEquals("Java", profile.programmingLanguage)
        assertEquals(CodingExperience.SOLVED_MEDIUM_REGULARLY, profile.codingExperience)
        assertEquals("취업 준비", profile.learningGoal)
        assertEquals(
            mapOf("BFS" to SkillExperience.UNSURE, "Stack" to SkillExperience.SOLVED_VARIATION_INDEPENDENTLY),
            profile.claimedSkillExperience,
        )
        assertEquals("2026-10-08T00:00:00Z", profile.recordedAt)
        assertTrue(output.toString().contains("목록에 있는 번호"))
        assertTrue(output.toString().contains("자동 채점은 Java만 지원"))
    }

    @Test
    fun `redraws each onboarding step as a replacement screen`() {
        val output = StringWriter()
        val writer = PrintWriter(output, true)
        val terminalUi = TerminalUi.fromEnvironment(mapOf("TERM" to "xterm"), consolePresent = false)
        terminalUi.enterScreen(writer)
        val provider = InteractiveSelfReportProvider(
            input = BufferedReader(StringReader("1\n6\n목표\n1\n")),
            output = writer,
            clock = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneId.of("UTC")),
            terminalUi = terminalUi,
        )

        provider.collect(listOf("BFS"))

        assertTrue(output.toString().split("\u001B[2J").size - 1 >= 4)
    }

    @Test
    fun `cli opens profile settings from the home menu`() {
        val root = createTempDirectory("resubmit-profile-cli-test")
        try {
            val profilePath: Path = root.resolve(".resubmit/profile.json")
            var collectCalls = 0
            val output = StringWriter()
            val cli = CodingCoachCli(
                input = BufferedReader(StringReader("2\n3\nq\n")),
                output = PrintWriter(output, true),
                workspaceFactory = SessionWorkspaceFactory(root, fixedClock()),
                codeRunner = CodeRunner { _, _ -> error("제출하지 않아야 합니다.") },
                testRunner = TestRunner { _, _ -> error("테스트를 실행하지 않아야 합니다.") },
                snapshotFactory = SubmissionSnapshotFactory(fixedClock()),
                reviewProvider = FixtureReviewProvider(),
                attemptHistoryReader = AttemptHistoryReader { HistoryReadResult.NotFound },
                attemptRecorder = AttemptRecorder { _, _, _, _, _, _ -> error("이력을 기록하지 않아야 합니다.") },
                selfReportStore = JsonSelfReportStore(profilePath),
                selfReportProvider = SelfReportProvider { skills ->
                    collectCalls += 1
                    SelfReportProfile(
                        programmingLanguage = "Java",
                        codingExperience = CodingExperience.FOLLOWED_EXAMPLES,
                        learningGoal = "코테 준비",
                        claimedSkillExperience = skills.associateWith { SkillExperience.UNSURE },
                        recordedAt = "now",
                    )
                },
            )

            assertEquals(0, cli.run())
            assertEquals(2, collectCalls)
            assertIs<SelfReportReadResult.Found>(JsonSelfReportStore(profilePath).read())
            assertTrue(output.toString().contains("프로필 수정"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun fixedClock(): Clock = Clock.fixed(
        Instant.parse("2026-10-07T06:00:00Z"),
        ZoneId.of("Asia/Seoul"),
    )
}
