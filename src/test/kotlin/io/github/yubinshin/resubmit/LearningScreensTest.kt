package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LearningScreensTest {
    @Test
    fun `search opens a compatible problem and returns it for execution`() {
        val catalog = LocalLearningCatalog().load()
        val output = StringWriter()
        val screen = screens("5\n목표합 인덱스\n1\n1\n", output)
        assertEquals(ProblemCatalog.twoSum, screen.selectProblem(catalog,
            buildLearningInsights(catalog.entries, HistoryRecordsReadResult.NotFound), emptySet()))
        assertContains(output.toString(), "추천 이유")
    }

    @Test
    fun `external problem detail cannot start local execution`() {
        val entry = CatalogEntry("external:1", "External", listOf("DP"), "external", "1", null, "Description")
        val catalog = CatalogSnapshot(listOf(entry))
        val output = StringWriter()
        val result = screens("6\n1\n1\n7\n", output).selectProblem(catalog,
            buildLearningInsights(catalog.entries, HistoryRecordsReadResult.NotFound), emptySet())
        assertNull(result)
        assertContains(output.toString(), "이 CLI의 채점 자료가 없습니다")
    }

    @Test
    fun `catalog pages can advance return and search without exiting application`() {
        val entries = (1..10).map {
            CatalogEntry("external:$it", "External $it", listOf("DP"), "external", "$it", null, "Description")
        }
        val catalog = CatalogSnapshot(entries)
        val output = StringWriter()
        // first page: next=9, second page: previous=3, first page: back=10, home=7
        assertNull(screens("6\n9\n3\n10\n7\n", output).selectProblem(catalog,
            buildLearningInsights(entries, HistoryRecordsReadResult.NotFound), emptySet()))
        assertContains(output.toString(), "2/2 페이지")
    }

    @Test
    fun `empty search returns to learning menu`() {
        val catalog = LocalLearningCatalog().load()
        val output = StringWriter()
        assertNull(screens("5\nnot-a-problem\n\n7\n", output).selectProblem(catalog,
            buildLearningInsights(catalog.entries, HistoryRecordsReadResult.NotFound), emptySet()))
        assertContains(output.toString(), "조건에 맞는 항목이 없습니다")
    }

    @Test
    fun `profile reports missing observation and provides edit action`() {
        val catalog = LocalLearningCatalog().load()
        val output = StringWriter()
        assertEquals(ProfileAction.EDIT, screens("3\n", output).profile(catalog,
            buildLearningInsights(catalog.entries, HistoryRecordsReadResult.NotFound),
            SelfReportReadResult.NotFound, DiagnosticSessionReadResult.NotFound))
        assertContains(output.toString(), "관찰 부족")
        assertContains(output.toString(), "학습 목표·자기보고 수정")
    }

    @Test
    fun `profile offers on demand repository analysis and marks stale evidence`() {
        val catalog = LocalLearningCatalog().load()
        val insights = buildLearningInsights(catalog.entries, HistoryRecordsReadResult.NotFound)
        val output = StringWriter()
        assertEquals(ProfileAction.ANALYZE, screens("5\n", output).profile(catalog, insights, null, null))
        val stale = RepositoryAnalysis("/source", "yesterday", emptyList(), emptyList(), stale = true)
        assertEquals(ProfileAction.BACK, screens("6\n", output).profile(catalog, insights, null, null, stale))
        assertContains(output.toString(), "다시 분석하세요")
    }

    private fun screens(input: String, output: StringWriter) = LearningScreens(
        BufferedReader(StringReader(input)), PrintWriter(output, true), TerminalUi.plain(), Clock.systemUTC(),
    )
}
