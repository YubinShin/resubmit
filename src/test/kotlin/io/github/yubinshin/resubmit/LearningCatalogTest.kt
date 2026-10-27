package io.github.yubinshin.resubmit

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningCatalogTest {
    @Test
    fun `default catalog contains only the public executable fixtures`() {
        val snapshot = LocalLearningCatalog().load()
        assertEquals(6, snapshot.entries.size)
        assertEquals(snapshot.entries.size, snapshot.entries.map { it.key }.distinct().size)
        assertTrue(snapshot.entries.all { it.source == "resubmit" && it.executable != null })
        assertTrue(snapshot.entries.all { it.platform == null && it.url == null })
        assertTrue(snapshot.notices.single().contains("RESUBMIT_CODE_RPG_HOME"))
    }

    @Test
    fun `parses quoted multiline csv without losing commas or escaped quotes`() {
        assertEquals(listOf(listOf("id", "text", "last"), listOf("1", "a,\nb \"quoted\"", "")),
            parseCatalogCsv("id,text,last\r\n1,\"a,\nb \"\"quoted\"\"\",\r\n"))
        assertFailsWith<IllegalArgumentException> { parseCatalogCsv("a,\"unfinished") }
        assertFailsWith<IllegalArgumentException> { parseCatalogCsv("\"closed\"junk") }
    }

    @Test
    fun `external rows cannot override public executable fixtures`() {
        val home = createTempDirectory("catalog-test")
        try {
            Files.createDirectories(home.resolve("content"))
            Files.writeString(home.resolve("content/problems.csv"),
                "problem_id,title,category,platform,problem_text,judge_mode,difficulty\n" +
                    "demo-pair-indices,Imported 목표합 인덱스,해시,leetcode,Imported,auto,1\n" +
                    "new-problem,New problem,DP;배열,local,Description,auto,2\n")
            val snapshot = LocalLearningCatalog(home).load()
            assertTrue(snapshot.notices.isEmpty())
            assertEquals(ProblemCatalog.all.size + 2, snapshot.entries.size)
            val imported = snapshot.entries.single { it.sourceId == "new-problem" }
            assertNull(imported.executable)
            assertEquals(listOf("DP", "배열"), imported.skills)
            val merged = snapshot.entries.single { it.source == "resubmit" && it.sourceId == "demo-pair-indices" }
            val external = snapshot.entries.single { it.source == "code-rpg" && it.sourceId == "demo-pair-indices" }
            assertNull(external.executable)
            assertEquals("Imported", external.description)
            assertEquals("code-rpg 원본 1", external.difficulty)
            assertEquals(ProblemCatalog.twoSum, merged.executable)
            assertNull(merged.difficulty)
            assertEquals(ProblemCatalog.twoSum.description, merged.description)
        } finally { home.toFile().deleteRecursively() }
    }

    @Test
    fun `wrong width row is reported without discarding valid problems`() {
        val root = createTempDirectory("catalog-partial-test")
        try {
            val file = root.resolve("problems.csv")
            Files.writeString(file, "problem_id,title,category,platform,problem_text\na,A,DP,local,text\nb,B,DP,local,text,extra\n")
            val snapshot = readCodeRpgCatalog(file)
            assertEquals(listOf("a"), snapshot.entries.map { it.sourceId })
            assertEquals(1, snapshot.notices.size)
            assertTrue(snapshot.notices.single().contains("(b)"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `invalid or missing external catalog falls back with visible notice`() {
        val home = createTempDirectory("catalog-invalid-test")
        try {
            assertEquals(ProblemCatalog.all.size, LocalLearningCatalog(home).load().entries.size)
            assertTrue(LocalLearningCatalog(home).load().notices.isNotEmpty())
            Files.createDirectories(home.resolve("content"))
            Files.writeString(home.resolve("content/problems.csv"),
                "problem_id,title,category,platform,problem_text\na,A,DP,local,text\na,B,DP,local,text\n")
            assertFailsWith<IllegalArgumentException> { readCodeRpgCatalog(home.resolve("content/problems.csv")) }
            assertEquals(ProblemCatalog.all.size, LocalLearningCatalog(home).load().entries.size)
            Files.writeString(home.resolve("content/problems.csv"), "problem_id,title\na,A\n")
            assertFailsWith<IllegalArgumentException> { readCodeRpgCatalog(home.resolve("content/problems.csv")) }
        } finally { home.toFile().deleteRecursively() }
    }
}
