package io.github.yubinshin.resubmit

import java.net.URI
import java.time.Clock
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmReviewProviderTest {
    @Test
    fun `factory rejects malformed keys before HTTP without echoing credentials`() {
        for (provider in listOf("openai", "gemini", "anthropic")) {
            val prefix = provider.uppercase()
            val secret = "SENTINEL_CREDENTIAL_NOT_FOR_LOGS"
            var calls = 0
            val http = JsonHttpClient { _, _, _ -> calls++; error("must not send") }
            val configured = ReviewProviderFactory.create(mapOf(
                "RESUBMIT_REVIEW_PROVIDER" to provider,
                "${prefix}_API_KEY" to "$secret\n", "${prefix}_MODEL" to "test-model",
            ), http)
            val result = assertIs<ReviewResult.Unavailable>(configured.review(wrongAnswerRequest()))
            assertEquals(0, calls)
            assertContains(result.message, "형식")
            assertFalse(result.message.contains(secret))
        }
    }

    @Test
    fun `JDK header errors cannot expose keys through review or recorded history`() {
        val secret = "SENTINEL_HEADER_CREDENTIAL"
        val http = JdkJsonHttpClient()
        // These invalid headers are rejected before client.send; no network request occurs.
        val providers = listOf(
            OpenAiReviewProvider("$secret\n", "test-model", http),
            GeminiReviewProvider("$secret\n", "test-model", http),
            AnthropicReviewProvider("$secret\n", "test-model", http),
        )
        val root = createTempDirectory("resubmit-safe-review-error-")
        try {
            val workspace = SessionWorkspaceFactory(root, Clock.systemUTC()).create(DemoProblem.targetNumber)
            val snapshot = SubmissionSnapshotFactory().create(workspace)
            val path = root.resolve("history.jsonl")
            val recorder = JsonlAttemptRecorder(path)
            for (provider in providers) {
                val result = assertIs<ReviewResult.Unavailable>(provider.review(wrongAnswerRequest()))
                assertFalse(result.message.contains(secret), provider.provider)
                assertIs<HistoryWriteResult.Success>(recorder.record(workspace, snapshot, DemoProblem.targetNumber,
                    CompilationResult.Failed(1, 0, "compiler error", false), null,
                    ReviewHistory.Unavailable(provider.provider, result.message)))
            }
            assertFalse(path.readText().contains(secret))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Anthropic sends only supported constraints while other providers retain theirs`() {
        val http = RecordingHttpClient(JsonHttpResponse(503, ""))
        AnthropicReviewProvider("secret", "test-model", http).review(wrongAnswerRequest())
        val schema = Json.parseToJsonElement(http.body).jsonObject.getValue("output_config").jsonObject
            .getValue("format").jsonObject.getValue("schema")
        fun checkConstraints(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    assertFalse("minLength" in element)
                    assertFalse("maxItems" in element)
                    element["minItems"]?.let { assertTrue(it.jsonPrimitive.int <= 1) }
                    element.values.forEach(::checkConstraints)
                }
                is JsonArray -> element.forEach(::checkConstraints)
                else -> Unit
            }
        }
        checkConstraints(schema)
        assertEquals("array", schema.jsonObject.getValue("properties").jsonObject
            .getValue("hints").jsonObject.getValue("type").jsonPrimitive.content)

        OpenAiReviewProvider("secret", "test-model", http).review(wrongAnswerRequest())
        val originalHints = Json.parseToJsonElement(http.body).jsonObject.getValue("text").jsonObject
            .getValue("format").jsonObject.getValue("schema").jsonObject
            .getValue("properties").jsonObject.getValue("hints").jsonObject
        assertEquals(3, originalHints.getValue("minItems").jsonPrimitive.int)
        assertEquals(3, originalHints.getValue("maxItems").jsonPrimitive.int)
        assertEquals(1, originalHints.getValue("items").jsonObject.getValue("minLength").jsonPrimitive.int)
    }

    @Test
    fun `Anthropic still rejects wrong hint count and blank fields locally`() {
        for (hints in listOf(listOf("one", "two"), listOf("one", " ", "three"))) {
            val payload = buildJsonObject {
                put("relatedSkills", buildJsonArray { add(JsonPrimitive("arrays")) })
                put("evidence", buildJsonArray { add(JsonPrimitive("case-1 failed")) })
                put("hints", JsonArray(hints.map(::JsonPrimitive)))
            }
            val response = buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", payload.toString()) })
                })
            }.toString()
            assertIs<ReviewResult.Unavailable>(AnthropicReviewProvider("secret", "test-model",
                RecordingHttpClient(JsonHttpResponse(200, response))).review(wrongAnswerRequest()))
        }
    }

    @Test
    fun `openai adapter sends structured request and parses review`() {
        val http = RecordingHttpClient(
            JsonHttpResponse(
                200,
                openAiResponse("재귀", "case-1 actual=0"),
            ),
        )
        val result = OpenAiReviewProvider("secret", "gpt-test", http).review(wrongAnswerRequest())

        val review = assertIs<ReviewResult.Ready>(result).review
        assertEquals("openai", review.provider)
        assertEquals(ReviewFailureType.WRONG_ANSWER, review.failureType)
        assertEquals(listOf("case-1 actual=0"), review.evidence)
        assertEquals(3, review.hints.size)
        assertEquals("gpt-test", review.model)
        assertEquals(REVIEW_PROMPT_VERSION, review.promptVersion)
        assertEquals(URI.create("https://api.openai.com/v1/responses"), http.uri)
        assertEquals("Bearer secret", http.headers["Authorization"])
        assertContains(http.body, "gpt-test")
        assertContains(http.body, "return 0")
        assertContains(http.body, "json_schema")
    }

    @Test
    fun `openai adapter reviews compile failure without changing deterministic classification`() {
        val http = RecordingHttpClient(
            JsonHttpResponse(200, openAiResponse("Java 문법", "Solution.java:4 세미콜론 누락")),
        )
        val request = ReviewRequest(
            problem = DemoProblem.targetNumber,
            sourceCode = "public class Solution {",
            compilation = CompilationResult.Failed(
                1,
                8,
                "/Users/alice/private/session/Solution.java:4: error: ';' expected",
                false,
            ),
        )

        val review = assertIs<ReviewResult.Ready>(OpenAiReviewProvider("secret", "gpt-test", http).review(request)).review

        assertEquals(ReviewFailureType.COMPILE_ERROR, review.failureType)
        assertContains(http.body, "확정된 컴파일 결과: FAILED")
        assertContains(http.body, "Solution.java:4")
        assertEquals(false, http.body.contains("/Users/alice"))
        assertEquals(null, request.tests)
    }

    @Test
    fun `review prompt includes previous attempt result and only hints already viewed`() {
        val http = RecordingHttpClient(JsonHttpResponse(200, openAiResponse("재귀", "case-1 실패")))
        val previousRecord = AttemptRecord(
            sessionId = "session",
            attemptId = "session-attempt-001",
            problemId = DemoProblem.targetNumber.id,
            submittedAt = "2026-10-07T15:00+09:00",
            sourceSha256 = "previous-hash",
            compilation = CompilationRecord(CompilationStatus.PASSED),
            tests = TestRecord(TestStatus.FAILED, passedCount = 1, totalCount = 4),
            review = ReviewRecord(
                ReviewStatus.READY,
                evidence = listOf("case-2 실패"),
                hints = listOf("hint one", "hint two", "hint three"),
                hintLevel = 1,
                hintViewed = true,
            ),
        )
        val request = wrongAnswerRequest().copy(
            previousAttempt = PreviousAttemptContext(
                previousRecord,
                "HEAD\n" + "x".repeat(5_000) + "UNIQUE_MIDDLE_SENTINEL" + "y".repeat(5_000) + "\nTAIL",
            ),
        )

        OpenAiReviewProvider("secret", "gpt-test", http).review(request)

        assertContains(http.body, "직전 제출 결과")
        assertContains(http.body, "FAILED 1/4")
        assertContains(http.body, "hint one")
        assertEquals(false, http.body.contains("hint two"))
        assertContains(http.body, "직전 제출 코드")
        assertContains(http.body, "직전 제출 코드 일부는 길이 제한으로 생략됨")
        assertContains(http.body, "HEAD")
        assertContains(http.body, "TAIL")
        assertEquals(false, http.body.contains("UNIQUE_MIDDLE_SENTINEL"))
        assertEquals(false, http.body.contains("/Users/alice"))
    }

    @Test
    fun `gemini adapter parses interaction model output`() {
        val http = RecordingHttpClient(
            JsonHttpResponse(
                200,
                """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"{\"relatedSkills\":[\"상태 전이\"],\"evidence\":[\"case-1 실패\"],\"hints\":[\"방향\",\"문제 지점\",\"개선 방향\"]}"}]}]}""",
            ),
        )
        val result = GeminiReviewProvider("secret", "gemini-test", http).review(wrongAnswerRequest())

        val review = assertIs<ReviewResult.Ready>(result).review
        assertEquals("gemini", review.provider)
        assertEquals(listOf("상태 전이"), review.relatedSkills)
        assertEquals("secret", http.headers["x-goog-api-key"])
        assertContains(http.body, "response_format")
    }

    @Test
    fun `anthropic adapter parses message text content`() {
        val http = RecordingHttpClient(
            JsonHttpResponse(
                200,
                """{"content":[{"type":"text","text":"{\"relatedSkills\":[\"탐색\"],\"evidence\":[\"두 테스트 실패\"],\"hints\":[\"방향\",\"문제 지점\",\"개선 방향\"]}"}]}""",
            ),
        )
        val result = AnthropicReviewProvider("secret", "claude-test", http).review(wrongAnswerRequest())

        val review = assertIs<ReviewResult.Ready>(result).review
        assertEquals("anthropic", review.provider)
        assertEquals("secret", http.headers["x-api-key"])
        assertEquals(null, http.headers["Authorization"])
        assertEquals("2023-06-01", http.headers["anthropic-version"])
        assertContains(http.body, "output_config")
    }

    @Test
    fun `provider factory keeps fixture as default and reports missing configuration`() {
        assertIs<FixtureReviewProvider>(ReviewProviderFactory.create(emptyMap(), RecordingHttpClient()))

        val provider = ReviewProviderFactory.create(
            mapOf("RESUBMIT_REVIEW_PROVIDER" to "openai"),
            RecordingHttpClient(),
        )
        assertEquals("openai", provider.provider)
        val unavailable = assertIs<ReviewResult.Unavailable>(provider.review(wrongAnswerRequest()))
        assertContains(unavailable.message, "OPENAI_API_KEY")
        assertContains(unavailable.message, "OPENAI_MODEL")
    }

    @Test
    fun `adapter maps HTTP failure without exposing response body`() {
        val http = RecordingHttpClient(JsonHttpResponse(401, "secret provider response"))

        val unavailable = assertIs<ReviewResult.Unavailable>(
            OpenAiReviewProvider("secret", "gpt-test", http).review(wrongAnswerRequest()),
        )

        assertEquals("openai API가 HTTP 401로 응답했습니다.", unavailable.message)
    }

    @Test
    fun `adapter classifies user exception as runtime error`() {
        val http = RecordingHttpClient(
            JsonHttpResponse(
                200,
                openAiResponse("예외 처리", "case-1 NullPointerException"),
            ),
        )
        val request = ReviewRequest(
            problem = DemoProblem.targetNumber,
            tests = TestRunResult.Completed(
                cases = listOf(TestCaseResult.Error("case-1", "NullPointerException")),
                durationMs = 5,
            ),
            sourceCode = "public class Solution {}",
        )

        val review = assertIs<ReviewResult.Ready>(
            OpenAiReviewProvider("secret", "gpt-test", http).review(request),
        ).review

        assertEquals(ReviewFailureType.RUNTIME_ERROR, review.failureType)
    }

    @Test
    fun `adapter rejects whitespace only structured fields`() {
        val http = RecordingHttpClient(
            JsonHttpResponse(
                200,
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"relatedSkills\":[\" \"],\"evidence\":[\"\\t\"],\"hints\":[\"방향\",\"문제 지점\",\"개선 방향\"]}"}]}]}""",
            ),
        )

        val unavailable = assertIs<ReviewResult.Unavailable>(
            OpenAiReviewProvider("secret", "gpt-test", http).review(wrongAnswerRequest()),
        )

        assertContains(unavailable.message, "리뷰 요청 또는 응답을 처리하지 못했습니다.")
    }

    @Test
    fun `adapter rejects a response without exactly three hints`() {
        val http = RecordingHttpClient(
            JsonHttpResponse(
                200,
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"relatedSkills\":[\"재귀\"],\"evidence\":[\"case-1 실패\"],\"hints\":[\"방향\",\"문제 지점\"]}"}]}]}""",
            ),
        )

        val unavailable = assertIs<ReviewResult.Unavailable>(
            OpenAiReviewProvider("secret", "gpt-test", http).review(wrongAnswerRequest()),
        )

        assertContains(unavailable.message, "리뷰 요청 또는 응답을 처리하지 못했습니다.")
    }

    @Test
    fun `adapter lists evidence ids in prompt and keeps cited ids`() {
        val request = wrongAnswerRequest().copy(sourceEvidence = listOf(loopEvidence("ev-11111111-1")))
        val http = RecordingHttpClient(JsonHttpResponse(200, openAiResponseCiting("ev-11111111-1")))

        val review = assertIs<ReviewResult.Ready>(
            OpenAiReviewProvider("secret", "gpt-test", http).review(request),
        ).review

        assertEquals(listOf("ev-11111111-1"), review.citedEvidenceIds)
        assertEquals(request.sourceEvidence, review.sourceEvidence)
        assertContains(http.body, "ev-11111111-1")
        assertContains(http.body, "LOOP_FOR")
    }

    @Test
    fun `adapter rejects citation of an id that was not provided`() {
        val request = wrongAnswerRequest().copy(sourceEvidence = listOf(loopEvidence("ev-11111111-1")))
        val http = RecordingHttpClient(JsonHttpResponse(200, openAiResponseCiting("ev-11111111-9")))

        val unavailable = assertIs<ReviewResult.Unavailable>(
            OpenAiReviewProvider("secret", "gpt-test", http).review(request),
        )

        assertFalse(unavailable.message.contains("ev-11111111-9"))
    }

    @Test
    fun `adapter rejects citation of evidence from another source version`() {
        val request = wrongAnswerRequest().copy(sourceEvidence = listOf(loopEvidence("ev-22222222-1")))
        val http = RecordingHttpClient(JsonHttpResponse(200, openAiResponseCiting("ev-11111111-1")))

        val unavailable = assertIs<ReviewResult.Unavailable>(
            OpenAiReviewProvider("secret", "gpt-test", http).review(request),
        )

        assertContains(unavailable.message, "리뷰 요청 또는 응답을 처리하지 못했습니다.")
    }

    private fun loopEvidence(id: String) = SourceEvidence(
        id = id,
        file = "Solution.java",
        line = 3,
        method = "solution",
        ruleId = "LOOP_FOR",
        description = "for 반복문",
        tool = "java-ast",
        toolVersion = "v1",
        sourceSha256 = "0".repeat(64),
    )

    private fun openAiResponseCiting(id: String): String =
        """{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"relatedSkills\":[\"반복\"],\"evidence\":[\"case-1 실패\"],\"citedEvidenceIds\":[\"$id\"],\"hints\":[\"방향\",\"문제 지점\",\"개선 방향\"]}"}]}]}"""

    private fun wrongAnswerRequest(): ReviewRequest = ReviewRequest(
        problem = DemoProblem.targetNumber,
        tests = TestRunResult.Completed(
            cases = listOf(TestCaseResult.Value("case-1", false, "5", "0")),
            durationMs = 5,
        ),
        sourceCode = "public class Solution { int solution() { return 0; } }",
    )

    private fun openAiResponse(skill: String, evidence: String): String =
        """{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"relatedSkills\":[\"$skill\"],\"evidence\":[\"$evidence\"],\"hints\":[\"방향\",\"문제 지점\",\"개선 방향\"]}"}]}]}"""

    private class RecordingHttpClient(
        private val response: JsonHttpResponse = JsonHttpResponse(200, "{}"),
    ) : JsonHttpClient {
        lateinit var uri: URI
        lateinit var headers: Map<String, String>
        lateinit var body: String

        override fun post(uri: URI, headers: Map<String, String>, body: String): JsonHttpResponse {
            this.uri = uri
            this.headers = headers
            this.body = body
            return response
        }
    }
}
