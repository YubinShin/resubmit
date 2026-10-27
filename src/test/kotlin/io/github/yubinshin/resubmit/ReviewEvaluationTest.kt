package io.github.yubinshin.resubmit

import java.nio.file.Files
import java.time.Clock
import java.security.MessageDigest
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse

/** Checks the execution labels in the golden set, not LLM explanation quality. No API calls. */
class ReviewEvaluationTest {
    @Test
    fun `recorded Gemini responses pass production parser and evidence validation offline`() {
        val dataset = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/evaluation/cases.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        val recordedRuns = listOf("2026-10-27-gemini-3.8-flash", "2026-10-27-gemini-3.8-flash-v4") +
            (1..3).flatMap { trial -> listOf("v3", "v4").map { "2026-10-27-repeated/$it-trial-$trial" } }
        for ((run, value) in recordedRuns.flatMap { run ->
            val manifest = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/evaluation/runs/$run/manifest.json"))
                .bufferedReader().use { it.readText() }).jsonObject
            val ids = manifest.getValue("calls").jsonArray.map { it.jsonObject.string("caseId") }.toSet()
            dataset.getValue("cases").jsonArray.filter { it.jsonObject.string("id") in ids }.map { run to it }
        }) {
            val case = value.jsonObject
            val id = case.string("id")
            val source = case.string("source")
            val prefix = "/evaluation/runs/$run/$id"
            val response = checkNotNull(javaClass.getResourceAsStream("$prefix.response.json"))
                .bufferedReader().use { it.readText() }
            val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val compilation = if (case.string("expectedCompilation") == "FAILED") {
                CompilationResult.Failed(1, 0, "recorded compiler failure", false)
            } else CompilationResult.Success(Path.of("unused"), 0, "", false)
            val tests = if (compilation is CompilationResult.Success) {
                val actuals = case.getValue("expectedActuals").jsonArray.map { it.jsonPrimitive.content }
                TestRunResult.Completed(listOf("empty", "singleton", "mixed").mapIndexed { index, testId ->
                    val expected = listOf("0", "5", "5")[index]
                    TestCaseResult.Value(testId, actuals[index] == expected, expected, actuals[index])
                }, 0)
            } else null
            // Execution labels were checked by actual Java execution in the other test.
            // This replay checks response parsing, schema and IDs; it does not grade explanation quality.
            val spec = dataset.getValue("problem").jsonObject
            val problem = Problem(spec.string("id"), spec.string("title"), spec.string("description"),
                spec.string("contract"), source, emptyList(), listOf("배열"),
                listOf("방향", "위치", "개선"), listOf("방향", "위치", "개선"))
            val request = ReviewRequest(problem, tests, source, compilation,
                sourceEvidence = SourceEvidenceAnalyzer().analyze(source, hash))
            val http = JsonHttpClient { _, _, _ -> JsonHttpResponse(200, response) }
            val review = assertIs<ReviewResult.Ready>(
                GeminiReviewProvider("offline-only", "gemini-3.8-flash", http).review(request), id).review
            val expected = when {
                case.string("expectedCompilation") == "FAILED" -> ReviewFailureType.COMPILE_ERROR
                case.getValue("expectedPassedCount").jsonPrimitive.int < case.getValue("expectedTestCount").jsonPrimitive.int ->
                    ReviewFailureType.WRONG_ANSWER
                else -> ReviewFailureType.PASSED
            }
            assertEquals(expected, review.failureType, id)
        }
    }

    @Test
    fun `prepare review requests without contacting an API`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/evaluation/cases.json"))
            .bufferedReader().use { it.readText() }
        val dataset = Json.parseToJsonElement(text).jsonObject
        val spec = dataset.getValue("problem").jsonObject
        val tests = spec.getValue("tests").jsonArray.map { value ->
            val item = value.jsonObject
            ProblemTestCase(item.string("id"), item.string("invocation"), item.string("expected"))
        }
        val output = Path.of("build/evaluation-preview")
        Files.createDirectories(output)
        for (value in dataset.getValue("cases").jsonArray) {
            val case = value.jsonObject
            val id = case.string("id")
            val source = case.string("source")
            val problem = Problem(spec.string("id"), spec.string("title"), spec.string("description"),
                spec.string("contract"), source, tests, listOf("배열"),
                listOf("방향", "위치", "개선"), listOf("방향", "위치", "개선"))
            val root = Files.createTempDirectory("resubmit-preview-")
            try {
                val workspace = SessionWorkspaceFactory(root, Clock.systemUTC()).create(problem)
                val compilation = ProcessJavaCodeRunner().compile(
                    listOf(workspace.solutionFile, workspace.harnessFile), root.resolve("classes"))
                val result = if (compilation is CompilationResult.Success) {
                    ProcessJavaTestRunner().run(compilation.outputDirectory, tests)
                } else {
                    assertIs<CompilationResult.Failed>(compilation, id)
                    null
                }
                if (result != null) assertIs<TestRunResult.Completed>(result, id)
                val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val request = ReviewRequest(problem, result, source, compilation,
                    sourceEvidence = SourceEvidenceAnalyzer().analyze(source, hash))
                var captured = ""
                // This injected client cannot make network requests and receives no real key.
                val http = JsonHttpClient { _, _, body ->
                    captured = body
                    JsonHttpResponse(503, "")
                }
                assertIs<ReviewResult.Unavailable>(GeminiReviewProvider("preview-only", "gemini-3.8-flash", http)
                    .review(request))
                val body = Json.parseToJsonElement(captured).jsonObject
                assertEquals("gemini-3.8-flash", body.string("model"))
                assertEquals(false, body.getValue("store").jsonPrimitive.boolean)
                assertFalse(captured.contains("preview-only"))
                assertFalse(captured.contains(root.toString()))
                for (criterion in case.getValue("required").jsonArray + case.getValue("forbidden").jsonArray) {
                    assertFalse(body.string("input").contains(criterion.jsonPrimitive.content), id)
                }
                Files.writeString(output.resolve("$id.request.json"), captured + "\n")
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `golden execution labels agree with actual Java execution`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/evaluation/cases.json"))
            .bufferedReader().use { it.readText() }
        val dataset = Json.parseToJsonElement(text).jsonObject
        val spec = dataset.getValue("problem").jsonObject
        val tests = spec.getValue("tests").jsonArray.map { value ->
            val item = value.jsonObject
            ProblemTestCase(item.string("id"), item.string("invocation"), item.string("expected"))
        }
        for (value in dataset.getValue("cases").jsonArray) {
            val case = value.jsonObject
            val id = case.string("id")
            val source = case.string("source")
            val problem = Problem(spec.string("id"), spec.string("title"), spec.string("description"),
                spec.string("contract"), source, tests, listOf("배열"),
                listOf("방향", "위치", "개선"), listOf("방향", "위치", "개선"))
            val root = Files.createTempDirectory("resubmit-golden-")
            try {
                val workspace = SessionWorkspaceFactory(root, Clock.systemUTC()).create(problem)
                val compilation = ProcessJavaCodeRunner().compile(
                    listOf(workspace.solutionFile, workspace.harnessFile), root.resolve("classes"))
                if (case.string("expectedCompilation") == "FAILED") {
                    assertIs<CompilationResult.Failed>(compilation, id)
                } else {
                    val success = assertIs<CompilationResult.Success>(compilation, id)
                    val result = assertIs<TestRunResult.Completed>(
                        ProcessJavaTestRunner().run(success.outputDirectory, tests), id)
                    assertEquals(case.getValue("expectedTestCount").jsonPrimitive.int, result.cases.size, id)
                    assertEquals(case.getValue("expectedPassedCount").jsonPrimitive.int, result.passedCount, id)
                    case["expectedActuals"]?.jsonArray?.let { actuals ->
                        assertEquals(actuals.map { it.jsonPrimitive.content },
                            result.cases.map { assertIs<TestCaseResult.Value>(it, id).actual }, id)
                    }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
