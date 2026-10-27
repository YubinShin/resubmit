package io.github.yubinshin.resubmit

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RepositoryCodeAnalysisTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `uses syntax trees ignores comments and strings and links exact metadata method`() = withRoot { root ->
        write(root, "java/Solution.java", """
            class Solution {
                // new HashMap<>(); while (true) {}
                String note = "new PriorityQueue<>();";
                void solve() { for (int i = 0; i < 3; i++) { } }
                void other() { while (false) { } }
            }
        """.trimIndent())
        write(root, "data/problems.csv", "problem_id,judge_file,judge_method,source_paths\ndemo-pair-indices,java/Solution.java,solve,java/Solution.java\n")
        val report = analyzer(root).analyze()
        val file = report.files.single()
        assertEquals(listOf("for 반복문", "while 반복문"), file.patterns.map { it.label })
        assertEquals(listOf("demo-pair-indices"), file.patterns.first().problemIds)
        assertTrue(file.patterns.last().problemIds.isEmpty())
        assertEquals(4, file.patterns.first().line)
        assertEquals(64, file.sha256.length)
        assertFalse(assertNotNull(analyzer(root).read()).stale)
    }

    @Test
    fun `deduplicates content skips generated directories and does not follow symlinks`() = withRoot { root ->
        val code = "class A { void solve() { for (;;) {} } }"
        write(root, "java/A.java", code)
        write(root, "java/Copy.java", code)
        write(root, "build/Generated.java", code)
        Files.createSymbolicLink(root.resolve("java/Linked.java"), root.resolve("java/A.java"))
        val report = analyzer(root).analyze()
        assertEquals(2, report.files.size)
        assertEquals(1, report.files.count { it.duplicateOf != null })
        assertEquals(1, report.files.sumOf { it.patterns.size })
    }

    @Test
    fun `syntax errors are reported and learner code is never executed`() = withRoot { root ->
        val marker = root.resolve("executed")
        write(root, "java/Broken.java", "class Broken { void broken( }")
        write(root, "java/Danger.java", "class Danger { static { try { new java.io.File(\"$marker\").createNewFile(); } catch (Exception e) {} } }")
        val report = analyzer(root).analyze()
        assertNotNull(report.files.first { it.path.endsWith("Broken.java") }.error)
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `source additions edits and metadata changes invalidate stored analysis`() = withRoot { root ->
        write(root, "java/A.java", "class A { void solve() { while (true) {} } }")
        val service = analyzer(root)
        service.analyze()
        assertFalse(assertNotNull(service.read()).stale)
        write(root, "java/A.java", "class A { void solve() {} }")
        assertTrue(assertNotNull(service.read()).stale)
        service.analyze()
        write(root, "java/B.java", "class B {}")
        assertTrue(assertNotNull(service.read()).stale)
        service.analyze()
        write(root, "data/problems.csv", "problem_id,judge_file,judge_method\nx,java/A.java,solve\n")
        assertTrue(assertNotNull(service.read()).stale)
    }

    @Test
    fun `source evidence suggests verification without inventing performance observations`() = withRoot { root ->
        write(root, "java/A.java", "class A { void solve() { while (true) {} } }")
        write(root, "data/problems.csv", "problem_id,judge_file,judge_method\ndemo-insert-slot,java/A.java,solve\n")
        val report = analyzer(root).analyze()
        val entries = LocalLearningCatalog().load().entries
        val insights = buildLearningInsights(entries, HistoryRecordsReadResult.NotFound)
        val recommended = recommendProblems(entries, insights, clock, repository = report)
        assertEquals("demo-insert-slot", recommended.first().entry.sourceId)
        assertTrue(recommended.first().reason.contains("수행 기록은 없어"))
        assertTrue(insights.problems.isEmpty())
        assertTrue(insights.skills.all { it.summary == "관찰 부족" })
        assertTrue(recommendProblems(entries, insights, clock, repository = report.copy(stale = true)).all { it.evidence.isEmpty() })
    }

    @Test
    fun `missing directory and damaged report have explicit status`() = withRoot { root ->
        val service = RepositoryCodeAnalyzer(root.resolve("absent"), root.resolve("report.json"), clock)
        assertTrue(service.analyze().notices.single().contains("디렉터리가 없습니다"))
        write(root, "report.json", "not json")
        val report = assertNotNull(service.read())
        assertTrue(report.stale)
        assertTrue(report.notices.isNotEmpty())
    }

    @Test
    fun `shared source files without method mapping are not attributed to every problem`() = withRoot { root ->
        write(root, "java/Shared.java", "class Shared { void solve() { while (true) {} } }")
        write(root, "data/problems.csv", "problem_id,judge_file,judge_method,source_paths\na,java/A.java,solve,java/Shared.java\nb,java/B.java,solve,java/Shared.java\n")
        val report = analyzer(root).analyze()
        assertTrue(report.files.single().patterns.single().problemIds.isEmpty())
        assertTrue(report.notices.any { it.contains("구분할 수 없는") })
    }

    private fun analyzer(root: Path) = RepositoryCodeAnalyzer(root, root.resolve(".resubmit/source-analysis.json"), clock)
    private fun write(root: Path, relative: String, content: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
    private fun withRoot(block: (Path) -> Unit) {
        val root = createTempDirectory("repository-analysis")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
