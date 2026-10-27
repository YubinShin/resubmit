package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DiagnosticSessionTest {
    @Test
    fun `interrupted time survives persisted resume without double counting`() {
        for (priorSeconds in listOf(0L, 5L)) {
            val root = createTempDirectory("resubmit-diagnostic-time-")
            try {
                val ids = listOf("demo-pair-indices", "demo-bracket-order", "demo-insert-slot", "demo-land-groups")
                val store = JsonDiagnosticSessionStore(root.resolve("diagnostic.json"))
                val observations = if (priorSeconds == 0L) emptyList() else listOf(
                    DiagnosticObservation(ids.first(), DiagnosticOutcome.NOT_SUBMITTED, 0, elapsedSeconds = priorSeconds),
                )
                assertIs<DiagnosticSessionWriteResult.Success>(store.write(DiagnosticSessionRecord(
                    sessionId = "diagnostic", problemIds = ids, status = DiagnosticStatus.IN_PROGRESS,
                    elapsedSeconds = priorSeconds, activeSinceEpochMillis = 1000, observations = observations,
                )))
                assertIs<DiagnosticSessionWriteResult.Success>(checkpointActiveDiagnosticSession(store, 11000))
                val checkpoint = assertIs<DiagnosticSessionReadResult.Found>(store.read()).session
                assertEquals(priorSeconds + 10, checkpoint.elapsedSeconds)
                assertEquals(10, checkpoint.currentProblemElapsedSeconds)
                assertNull(checkpointActiveDiagnosticSession(store, 12000))

                var now = 11000L
                val clock = object : Clock() {
                    override fun instant(): Instant = Instant.ofEpochMilli(now)
                    override fun getZone(): ZoneId = ZoneId.of("UTC")
                    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
                }
                val profile = JsonSelfReportStore(root.resolve("profile.json"))
                assertIs<SelfReportWriteResult.Success>(profile.write(SelfReportProfile(
                    programmingLanguage = "Java", codingExperience = CodingExperience.UNSURE,
                    learningGoal = "test", claimedSkillExperience = emptyMap(), recordedAt = clock.instant().toString(),
                )))
                // Resume and quit twice, spending 5 seconds each time. Never submit or call a provider.
                repeat(2) { resumed ->
                    var reads = 0
                    val input = object : BufferedReader(StringReader("")) {
                        override fun readLine(): String = if (reads++ == 0) "2" else {
                            now += 5000
                            "q"
                        }
                    }
                    val exit = CodingCoachCli(input, PrintWriter(StringWriter()),
                        SessionWorkspaceFactory(root, clock), CodeRunner { _, _ -> error("no submission") },
                        TestRunner { _, _ -> error("no submission") }, SubmissionSnapshotFactory(clock),
                        ReviewProvider { error("no review") }, AttemptHistoryReader { HistoryReadResult.NotFound },
                        AttemptRecorder { _, _, _, _, _, _ -> error("no attempt") },
                        selfReportStore = profile, selfReportProvider = SelfReportProvider { error("profile exists") },
                        diagnosticSessionStore = store, clock = clock).run()
                    assertEquals(0, exit)
                    val saved = assertIs<DiagnosticSessionReadResult.Found>(store.read()).session
                    val expected = priorSeconds + 10 + (resumed + 1) * 5
                    assertEquals(expected, saved.elapsedSeconds)
                    assertEquals(expected, saved.observations.single().elapsedSeconds)
                    assertEquals(0, saved.currentProblemElapsedSeconds)
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `older diagnostic records default pending problem time to zero`() {
        val record = Json.decodeFromString<DiagnosticSessionRecord>(
            """{"sessionId":"old","problemIds":["p"],"status":"PAUSED","elapsedSeconds":7}""",
        )
        assertEquals(0, record.currentProblemElapsedSeconds)
        assertEquals(7, record.elapsedSeconds)
    }

    @Test
    fun `repeated interruptions accumulate pending problem time once`() {
        val root = createTempDirectory("resubmit-diagnostic-checkpoints-")
        try {
            val store = JsonDiagnosticSessionStore(root.resolve("diagnostic.json"))
            store.write(DiagnosticSessionRecord("diagnostic", listOf("p"), status = DiagnosticStatus.IN_PROGRESS,
                activeSinceEpochMillis = 1000))
            checkpointActiveDiagnosticSession(store, 11000)
            val first = assertIs<DiagnosticSessionReadResult.Found>(store.read()).session
            store.write(first.copy(status = DiagnosticStatus.IN_PROGRESS, activeSinceEpochMillis = 20000))
            checkpointActiveDiagnosticSession(store, 23000)
            val second = assertIs<DiagnosticSessionReadResult.Found>(store.read()).session
            assertEquals(13, second.elapsedSeconds)
            assertEquals(13, second.currentProblemElapsedSeconds)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
