package io.github.yubinshin.resubmit

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal fun interface JsonHttpClient {
    fun post(uri: URI, headers: Map<String, String>, body: String): JsonHttpResponse
}

internal data class JsonHttpResponse(val statusCode: Int, val body: String)

internal class JdkJsonHttpClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : JsonHttpClient {
    override fun post(uri: URI, headers: Map<String, String>, body: String): JsonHttpResponse {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach(builder::header)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return JsonHttpResponse(response.statusCode(), response.body())
    }
}

internal object ReviewProviderFactory {
    fun create(
        environment: Map<String, String>,
        httpClient: JsonHttpClient = JdkJsonHttpClient(),
    ): ReviewProvider = when (val provider = environment["RESUBMIT_REVIEW_PROVIDER"]?.trim()?.lowercase()) {
        null, "", "fixture" -> FixtureReviewProvider()
        "openai" -> configuredProvider(provider, environment, "OPENAI_API_KEY", "OPENAI_MODEL") { key, model ->
            OpenAiReviewProvider(key, model, httpClient)
        }
        "gemini" -> configuredProvider(provider, environment, "GEMINI_API_KEY", "GEMINI_MODEL") { key, model ->
            GeminiReviewProvider(key, model, httpClient)
        }
        "anthropic", "claude" -> configuredProvider(
            "anthropic",
            environment,
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_MODEL",
        ) { key, model -> AnthropicReviewProvider(key, model, httpClient) }
        else -> UnavailableReviewProvider(provider, "지원하지 않는 리뷰 provider입니다: $provider")
    }

    private fun configuredProvider(
        provider: String,
        environment: Map<String, String>,
        keyName: String,
        modelName: String,
        create: (String, String) -> ReviewProvider,
    ): ReviewProvider {
        val key = environment[keyName]?.takeIf(String::isNotBlank)
        val model = environment[modelName]?.takeIf(String::isNotBlank)
        val missing = listOfNotNull(if (key == null) keyName else null, if (model == null) modelName else null)
        if (key != null && key.any { it.code !in 33..126 }) {
            return UnavailableReviewProvider(provider, "$keyName 형식을 확인하세요. 공백이나 제어 문자는 허용하지 않습니다.")
        }
        return if (missing.isEmpty()) {
            create(checkNotNull(key), checkNotNull(model))
        } else {
            UnavailableReviewProvider(provider, "필수 환경변수가 없습니다: ${missing.joinToString()}")
        }
    }
}

internal class UnavailableReviewProvider(
    override val provider: String,
    private val message: String,
) : ReviewProvider {
    override fun review(request: ReviewRequest): ReviewResult = ReviewResult.Unavailable(message)
}

internal abstract class StructuredLlmReviewProvider(
    final override val provider: String,
    private val model: String,
    private val httpClient: JsonHttpClient,
) : ReviewProvider {
    private val json = Json { ignoreUnknownKeys = false }

    final override fun review(request: ReviewRequest): ReviewResult {
        if (request.tests is TestRunResult.ExecutionError || request.tests is TestRunResult.RunnerError ||
            request.compilation is CompilationResult.RunnerError || request.compilation is CompilationResult.TimedOut
        ) {
            return ReviewResult.Unavailable("실행 기반이 정상적으로 완료되지 않아 리뷰를 만들 수 없습니다.")
        }
        if (request.tests == null && request.compilation !is CompilationResult.Failed) {
            return ReviewResult.Unavailable("리뷰에 사용할 컴파일 또는 테스트 결과가 없습니다.")
        }
        return try {
            val response = httpClient.post(endpoint(), headers(), requestBody(model, reviewPrompt(request)))
            if (response.statusCode !in 200..299) {
                ReviewResult.Unavailable("$provider API가 HTTP ${response.statusCode}로 응답했습니다.")
            } else {
                val payload = json.decodeFromString<LlmReviewPayload>(extractOutput(response.body))
                validate(payload, request.sourceEvidence)
                ReviewResult.Ready(
                    SubmissionReview(
                        provider = provider,
                        failureType = request.failureType(),
                        relatedSkills = payload.relatedSkills,
                        evidence = payload.evidence,
                        hints = payload.hints,
                        model = model,
                        promptVersion = REVIEW_PROMPT_VERSION,
                        citedEvidenceIds = payload.citedEvidenceIds.distinct(),
                        sourceEvidence = request.sourceEvidence,
                    ),
                )
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            ReviewResult.Unavailable("$provider 리뷰 요청이 중단되었습니다.")
        } catch (_: Exception) {
            ReviewResult.Unavailable("$provider 리뷰 요청 또는 응답을 처리하지 못했습니다.")
        }
    }

    protected abstract fun endpoint(): URI
    protected abstract fun headers(): Map<String, String>
    protected abstract fun requestBody(model: String, prompt: String): String
    protected abstract fun extractOutput(responseBody: String): String

    protected fun parseObject(responseBody: String): JsonObject = json.parseToJsonElement(responseBody).jsonObject

    private fun validate(payload: LlmReviewPayload, sourceEvidence: List<SourceEvidence>) {
        require(payload.relatedSkills.isNotEmpty() && payload.relatedSkills.all(String::isNotBlank)) {
            "relatedSkills가 비어 있습니다."
        }
        require(payload.evidence.isNotEmpty() && payload.evidence.all(String::isNotBlank)) {
            "evidence가 비어 있습니다."
        }
        require(payload.hints.size == 3 && payload.hints.all(String::isNotBlank)) {
            "hints에는 비어 있지 않은 힌트 3개가 필요합니다."
        }
        val knownIds = sourceEvidence.map(SourceEvidence::id).toSet()
        val unknown = payload.citedEvidenceIds.filterNot(knownIds::contains)
        require(unknown.isEmpty()) {
            "제공되지 않은 근거 ID를 인용했습니다: ${unknown.joinToString()}"
        }
    }
}

internal class OpenAiReviewProvider(
    private val apiKey: String,
    model: String,
    httpClient: JsonHttpClient,
) : StructuredLlmReviewProvider("openai", model, httpClient) {
    override fun endpoint(): URI = URI.create("https://api.openai.com/v1/responses")

    override fun headers(): Map<String, String> = mapOf("Authorization" to "Bearer $apiKey")

    override fun requestBody(model: String, prompt: String): String = buildJsonObject {
        put("model", model)
        put("store", false)
        put("instructions", REVIEW_INSTRUCTIONS)
        put("input", prompt)
        put("text", buildJsonObject {
            put("format", buildJsonObject {
                put("type", "json_schema")
                put("name", "submission_review")
                put("strict", true)
                put("schema", REVIEW_SCHEMA)
            })
        })
    }.toString()

    override fun extractOutput(responseBody: String): String = parseObject(responseBody)["output"]
        ?.jsonArray
        ?.asSequence()
        ?.map(JsonElement::jsonObject)
        ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
        ?.flatMap { it["content"]?.jsonArray?.asSequence().orEmpty() }
        ?.map(JsonElement::jsonObject)
        ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
        ?.get("text")
        ?.jsonPrimitive
        ?.contentOrNull
        ?: error("구조화된 output_text가 없습니다.")
}

internal class GeminiReviewProvider(
    private val apiKey: String,
    model: String,
    httpClient: JsonHttpClient,
) : StructuredLlmReviewProvider("gemini", model, httpClient) {
    override fun endpoint(): URI = URI.create("https://generativelanguage.googleapis.com/v1beta/interactions")

    override fun headers(): Map<String, String> = mapOf("x-goog-api-key" to apiKey)

    override fun requestBody(model: String, prompt: String): String = buildJsonObject {
        put("model", model)
        put("store", false)
        put("system_instruction", REVIEW_INSTRUCTIONS)
        put("input", prompt)
        put("response_format", buildJsonObject {
            put("type", "text")
            put("mime_type", "application/json")
            put("schema", REVIEW_SCHEMA)
        })
    }.toString()

    override fun extractOutput(responseBody: String): String = parseObject(responseBody)["steps"]
        ?.jsonArray
        ?.asSequence()
        ?.map(JsonElement::jsonObject)
        ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "model_output" }
        ?.flatMap { it["content"]?.jsonArray?.asSequence().orEmpty() }
        ?.map(JsonElement::jsonObject)
        ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
        ?.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
        ?.joinToString("")
        ?.takeIf(String::isNotBlank)
        ?: error("구조화된 model_output이 없습니다.")
}

internal class AnthropicReviewProvider(
    private val apiKey: String,
    model: String,
    httpClient: JsonHttpClient,
) : StructuredLlmReviewProvider("anthropic", model, httpClient) {
    override fun endpoint(): URI = URI.create("https://api.anthropic.com/v1/messages")

    override fun headers(): Map<String, String> = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to "2023-06-01",
    )

    override fun requestBody(model: String, prompt: String): String = buildJsonObject {
        put("model", model)
        put("max_tokens", 1_024)
        put("system", REVIEW_INSTRUCTIONS)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        })
        put("output_config", buildJsonObject {
            put("format", buildJsonObject {
                put("type", "json_schema")
                put("schema", anthropicReviewSchema(REVIEW_SCHEMA))
            })
        })
    }.toString()

    override fun extractOutput(responseBody: String): String = parseObject(responseBody)["content"]
        ?.jsonArray
        ?.asSequence()
        ?.map(JsonElement::jsonObject)
        ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
        ?.get("text")
        ?.jsonPrimitive
        ?.contentOrNull
        ?: error("구조화된 text content가 없습니다.")
}

// Raw HTTP requests do not receive the Anthropic SDK's schema transformation.
// Keep unsupported constraints in local validate(), not in the transmitted schema.
private fun anthropicReviewSchema(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.filterNot { (key, item) ->
        key == "minLength" || key == "maxItems" ||
            (key == "minItems" && (item.jsonPrimitive.intOrNull ?: 0) > 1)
    }.mapValues { (_, item) -> anthropicReviewSchema(item) })
    is JsonArray -> JsonArray(value.map(::anthropicReviewSchema))
    else -> value
}

@Serializable
private data class LlmReviewPayload(
    val relatedSkills: List<String>,
    val evidence: List<String>,
    val hints: List<String>,
    val citedEvidenceIds: List<String> = emptyList(),
)

private fun ReviewRequest.failureType(): ReviewFailureType {
    if (compilation is CompilationResult.Failed) return ReviewFailureType.COMPILE_ERROR
    return when (val result = tests) {
    is TestRunResult.Completed -> if (result.cases.any { it is TestCaseResult.Error }) {
        ReviewFailureType.RUNTIME_ERROR
    } else if (result.passedCount == result.cases.size) {
        ReviewFailureType.PASSED
    } else {
        ReviewFailureType.WRONG_ANSWER
    }
    is TestRunResult.TimedOut -> ReviewFailureType.TIME_LIMIT_EXCEEDED
    is TestRunResult.ExecutionError,
    is TestRunResult.RunnerError -> error("실행 기반 오류에는 리뷰 failure type이 없습니다.")
    null -> error("리뷰 결과가 없습니다.")
    }
}

private fun reviewPrompt(request: ReviewRequest): String = buildString {
    appendLine("문제 ID: ${request.problem.id}")
    appendLine("문제: ${request.problem.title}")
    appendLine("설명: ${request.problem.description}")
    appendLine("제출 규약: ${request.problem.submissionContract}")
    when (val compilation = request.compilation) {
        is CompilationResult.Failed -> {
            appendLine("확정된 컴파일 결과: FAILED (exit=${compilation.exitCode})")
            appendLine("javac 진단:")
            appendLine(compilation.diagnostics.withoutLocalSolutionPath())
        }
        is CompilationResult.TimedOut -> appendLine("확정된 컴파일 결과: TIMEOUT (${compilation.durationMs} ms)")
        is CompilationResult.Success -> appendLine("확정된 컴파일 결과: PASSED")
        is CompilationResult.RunnerError, null -> Unit
    }
    request.tests?.let { tests ->
        appendLine("확정된 테스트 결과:")
        appendLine(tests.toPromptText())
    }
    request.previousAttempt?.let { previous ->
        appendLine("직전 제출 결과 (현재 판정을 대체하지 않는 참고 정보):")
        appendLine("attemptId: ${previous.record.attemptId}")
        appendLine("compile: ${previous.record.compilation.status}")
        appendLine("tests: ${previous.record.tests.status} ${previous.record.tests.passedCount ?: "?"}/${previous.record.tests.totalCount ?: "?"}")
        appendLine("hintViewed: ${previous.record.review.hintViewed}, hintLevel: ${previous.record.review.hintLevel ?: "none"}")
        previous.record.compilation.diagnostics.takeIf(String::isNotBlank)?.let { diagnostics ->
            appendLine("prior compiler diagnostics:")
            appendLine(diagnostics.withoutLocalSolutionPath())
        }
        previous.record.review.evidence.takeIf { it.isNotEmpty() }?.let { evidence ->
            appendLine("prior review evidence: ${evidence.joinToString("; ")}")
        }
        previous.record.review.hints.takeIf { it.isNotEmpty() }?.let { hints ->
            appendLine("prior hints shown to learner: ${hints.take(previous.record.review.hintLevel ?: 0).joinToString("; ")}")
        }
        previous.sourceCode?.let { code ->
            appendLine("직전 제출 코드:")
            appendLine("```java")
            appendLine(code.previousSourceExcerpt())
            appendLine("```")
        }
    }
    if (request.sourceEvidence.isNotEmpty()) {
        appendLine("정적 분석 근거 (구문 분석 결과이며 오답이나 시간 복잡도를 확정하지 않음):")
        request.sourceEvidence.forEach { item ->
            appendLine("- ${item.id}: ${item.file}:${item.line} ${item.method}() ${item.ruleId} ${item.description}")
        }
    }
    appendLine("제출 코드:")
    appendLine("```java")
    appendLine(request.sourceCode)
    appendLine("```")
}

private fun String.withoutLocalSolutionPath(): String =
    replace(Regex("(?m)^.*[/\\\\]Solution\\.java:"), "Solution.java:")

private fun String.previousSourceExcerpt(): String {
    if (length <= MAX_PREVIOUS_SOURCE_CONTEXT_CHARS) return this
    val marker = "\n// 직전 제출 코드 일부는 길이 제한으로 생략됨\n"
    val retainedChars = MAX_PREVIOUS_SOURCE_CONTEXT_CHARS - marker.length
    val headChars = retainedChars / 2
    val tailChars = retainedChars - headChars
    return take(headChars) + marker + takeLast(tailChars)
}

private const val MAX_PREVIOUS_SOURCE_CONTEXT_CHARS = 8_192

private fun TestRunResult.toPromptText(): String = when (this) {
    is TestRunResult.Completed -> cases.joinToString(System.lineSeparator()) { testCase ->
        when (testCase) {
            is TestCaseResult.Value ->
                "- ${testCase.id}: ${if (testCase.passed) "PASS" else "FAIL"}, expected=${testCase.expected}, actual=${testCase.actual}"
            is TestCaseResult.Error -> "- ${testCase.id}: ERROR, type=${testCase.errorType}"
        }
    }
    is TestRunResult.TimedOut -> "- TIME_LIMIT_EXCEEDED, durationMs=$durationMs"
    is TestRunResult.ExecutionError -> "- EXECUTION_ERROR"
    is TestRunResult.RunnerError -> "- RUNNER_ERROR"
}

private const val REVIEW_INSTRUCTIONS =
    "실행 결과를 변경하지 말고 제출 코드와 제공된 테스트 결과만 근거로 분석하세요. " +
        "정답 코드나 완성 풀이를 제공하지 말고 점진적인 힌트 3개를 작성하세요. " +
        "첫 번째는 개념과 확인 방향, 두 번째는 제출 코드의 구체적인 문제, 세 번째는 의사코드 수준의 개선 방향이어야 합니다. " +
        "첫 번째 힌트에서는 추가·교체할 구체적인 토큰, 수정된 조건식 또는 수정 코드를 공개하지 마세요. " +
        "컴파일 오류의 첫 번째 힌트는 진단이 가리키는 위치에서 확인할 문법 범주와 점검 방향만 안내하고, " +
        "진단에 이미 나온 수정 토큰을 바로 넣으라고 반복하지 마세요. 구체적인 누락 위치와 원인은 두 번째, 수정 방향은 세 번째에서 설명하세요. " +
        "evidence에는 확인 가능한 코드, 컴파일러 또는 테스트 근거만 포함하세요. " +
        "정적 분석 근거를 인용할 때는 제공된 근거 ID만 citedEvidenceIds에 넣고, 목록에 없는 ID를 만들지 마세요. " +
        "정적 분석 근거만으로 오답이나 시간 복잡도를 단정하지 마세요. " +
        "직전 제출 정보는 변화 설명에만 사용하고 현재 제출의 판정 근거와 혼동하지 마세요. " +
        "직전 제출 정보가 있으면 evidence에 '재제출 비교:'로 시작하는 항목을 포함하여 이전과 현재 실행 결과 및 코드에서 확인한 변화를 설명하세요. " +
        "이전 코드와 현재 코드로 확인되는 수정은 구체적으로 설명하되, 테스트 통과만으로 이전의 모든 문제가 해결됐다고 단정하지 마세요. " +
        "비교에 필요한 코드나 근거가 없으면 어떤 변화를 확인하지 못했는지 명시하세요. 직전 제출이 없으면 비교를 만들지 마세요. 한국어로 작성하세요."

private val REVIEW_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("relatedSkills", stringArraySchema())
        put("evidence", stringArraySchema())
        put("citedEvidenceIds", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
        })
        put("hints", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("pattern", "\\S")
            })
            put("minItems", 3)
            put("maxItems", 3)
        })
    })
    put("required", JsonArray(listOf("relatedSkills", "evidence", "citedEvidenceIds", "hints").map(::JsonPrimitive)))
    put("additionalProperties", false)
}

private fun stringArraySchema(): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "string")
        put("minLength", 1)
        put("pattern", "\\S")
    })
    put("minItems", 1)
}
