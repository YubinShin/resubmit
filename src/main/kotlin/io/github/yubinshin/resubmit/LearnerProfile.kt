package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class CodingExperience {
    NOT_TRIED,
    FOLLOWED_EXAMPLES,
    SOLVED_EASY_INDEPENDENTLY,
    SOLVED_MEDIUM_REGULARLY,
    CAN_APPROACH_UNFAMILIAR,
    UNSURE,
}

internal fun CodingExperience.label(): String = when (this) {
    CodingExperience.NOT_TRIED -> "아직 시작 전"
    CodingExperience.FOLLOWED_EXAMPLES -> "예제·풀이를 따라 해봄"
    CodingExperience.SOLVED_EASY_INDEPENDENTLY -> "쉬운 문제를 혼자 풀어봄"
    CodingExperience.SOLVED_MEDIUM_REGULARLY -> "중간 난도도 종종 혼자 풀이"
    CodingExperience.CAN_APPROACH_UNFAMILIAR -> "낯선 문제도 접근을 설명 가능"
    CodingExperience.UNSURE -> "진단으로 확인 필요"
}

@Serializable
internal enum class SkillExperience {
    NOT_TRIED,
    SEEN_CONCEPT,
    FOLLOWED_SOLUTION,
    SOLVED_BASIC_INDEPENDENTLY,
    SOLVED_VARIATION_INDEPENDENTLY,
    UNSURE,
}

internal fun SkillExperience.label(): String = when (this) {
    SkillExperience.NOT_TRIED -> "아직 시도하지 않음"
    SkillExperience.SEEN_CONCEPT -> "개념·예제를 봄"
    SkillExperience.FOLLOWED_SOLUTION -> "풀이를 따라 해봄"
    SkillExperience.SOLVED_BASIC_INDEPENDENTLY -> "기본 문제를 혼자 풀어봄"
    SkillExperience.SOLVED_VARIATION_INDEPENDENTLY -> "응용 문제도 혼자 풀어봄"
    SkillExperience.UNSURE -> "잘 모르겠음"
}

@Serializable
internal data class SelfReportProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val programmingLanguage: String,
    val codingExperience: CodingExperience,
    val learningGoal: String,
    val claimedSkillExperience: Map<String, SkillExperience>,
    val recordedAt: String,
)

private const val CURRENT_SCHEMA_VERSION = 2

@Serializable
private data class LegacySelfReportProfile(
    val programmingLanguage: String,
    val experienceLevel: Int,
    val learningGoal: String,
    val claimedSkillConfidence: Map<String, Int?>,
    val recordedAt: String,
)

internal sealed interface SelfReportReadResult {
    data class Found(val profile: SelfReportProfile) : SelfReportReadResult
    data object NotFound : SelfReportReadResult
    data class Failed(val message: String) : SelfReportReadResult
}

internal sealed interface SelfReportWriteResult {
    data object Success : SelfReportWriteResult
    data class Failed(val message: String) : SelfReportWriteResult
}

internal interface SelfReportStore {
    fun read(): SelfReportReadResult
    fun write(profile: SelfReportProfile): SelfReportWriteResult
}

internal class JsonSelfReportStore(private val path: Path) : SelfReportStore {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    override fun read(): SelfReportReadResult {
        if (Files.notExists(path)) return SelfReportReadResult.NotFound
        return try {
            val content = Files.readString(path)
            val profile = runCatching { json.decodeFromString<SelfReportProfile>(content) }.getOrNull()
                ?: migrateLegacyProfile(json.decodeFromString<LegacySelfReportProfile>(content)).also(::write)
            SelfReportReadResult.Found(profile)
        } catch (exception: Exception) {
            SelfReportReadResult.Failed(exception.message ?: exception::class.simpleName.orEmpty())
        }
    }

    override fun write(profile: SelfReportProfile): SelfReportWriteResult = try {
        val parent = requireNotNull(path.parent) { "프로필 경로에 부모 디렉터리가 필요합니다." }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "${path.fileName}-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(profile))
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        SelfReportWriteResult.Success
    } catch (exception: Exception) {
        SelfReportWriteResult.Failed(exception.message ?: exception::class.simpleName.orEmpty())
    }

    private fun migrateLegacyProfile(profile: LegacySelfReportProfile): SelfReportProfile = SelfReportProfile(
        programmingLanguage = profile.programmingLanguage,
        codingExperience = profile.experienceLevel.toCodingExperience(),
        learningGoal = profile.learningGoal,
        claimedSkillExperience = profile.claimedSkillConfidence.mapValues { (_, score) ->
            score.toSkillExperience()
        },
        recordedAt = profile.recordedAt,
    )

    private fun Int.toCodingExperience(): CodingExperience = when (this) {
        1 -> CodingExperience.NOT_TRIED
        2 -> CodingExperience.FOLLOWED_EXAMPLES
        3 -> CodingExperience.SOLVED_EASY_INDEPENDENTLY
        4 -> CodingExperience.SOLVED_MEDIUM_REGULARLY
        5 -> CodingExperience.CAN_APPROACH_UNFAMILIAR
        else -> error("지원하지 않는 이전 경험 수준입니다: $this")
    }

    private fun Int?.toSkillExperience(): SkillExperience = when (this) {
        1 -> SkillExperience.NOT_TRIED
        2 -> SkillExperience.SEEN_CONCEPT
        3 -> SkillExperience.FOLLOWED_SOLUTION
        4 -> SkillExperience.SOLVED_BASIC_INDEPENDENTLY
        5 -> SkillExperience.SOLVED_VARIATION_INDEPENDENTLY
        null -> SkillExperience.UNSURE
        else -> error("지원하지 않는 이전 유형별 자신감 값입니다: $this")
    }
}

internal fun interface SelfReportProvider {
    fun collect(skills: List<String>): SelfReportProfile?
}

internal class InteractiveSelfReportProvider(
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val terminalUi: TerminalUi = TerminalUi.plain(),
) : SelfReportProvider {
    override fun collect(skills: List<String>): SelfReportProfile? {
        val totalSteps = 3 + skills.size
        val language = askLanguage(1, totalSteps) ?: return null
        val experience = askCodingExperience(2, totalSteps) ?: return null
        val goal = askText(3, totalSteps, "학습 목표", "준비 중인 시험이나 이루고 싶은 목표를 적어주세요.") ?: return null
        val claimedSkills = linkedMapOf<String, SkillExperience>()
        skills.forEachIndexed { index, skill ->
            claimedSkills[skill] = askSkillExperience(index + 4, totalSteps, skill) ?: return null
        }

        return SelfReportProfile(
            programmingLanguage = language,
            codingExperience = experience,
            learningGoal = goal,
            claimedSkillExperience = claimedSkills,
            recordedAt = Instant.now(clock).toString(),
        )
    }

    private fun askLanguage(step: Int, total: Int): String? {
        val options = listOf("Java", "Python", "C++", "JavaScript", "Kotlin", "직접 입력")
        val selected = askChoice(
            step = step,
            total = total,
            title = "주로 사용할 언어",
            description = "진단 전 자기보고를 입력합니다. 유형별 경험은 진단 결과와 별도로 저장해 함께 비교합니다. " +
                "선호 언어로 저장되며 자동 채점은 Java만 지원해요. q를 누르면 종료 후 다음 실행에서 다시 시작합니다.",
            options = options,
        ) ?: return null
        if (selected < options.lastIndex) return options[selected]
        return askText(step, total, "언어 직접 입력", "사용할 언어 이름을 입력해주세요.")
            ?.let(::normalizeLanguage)
    }

    private fun askCodingExperience(step: Int, total: Int): CodingExperience? {
        val options = listOf(
            "아직 코테 문제를 거의 풀어본 적 없어요" to CodingExperience.NOT_TRIED,
            "예제나 풀이를 보며 따라 해봤어요" to CodingExperience.FOLLOWED_EXAMPLES,
            "쉬운 문제는 혼자 풀어본 적 있어요" to CodingExperience.SOLVED_EASY_INDEPENDENTLY,
            "중간 난도 문제도 종종 혼자 풀어요" to CodingExperience.SOLVED_MEDIUM_REGULARLY,
            "낯선 문제도 접근을 세우고 풀이를 설명할 수 있어요" to CodingExperience.CAN_APPROACH_UNFAMILIAR,
            "잘 모르겠어요 — 진단 결과로 확인할게요" to CodingExperience.UNSURE,
        )
        val selected = askChoice(
            step,
            total,
            "코테 문제 풀이 경험",
            "가장 가까운 항목을 골라주세요. 정확한 등급을 매기는 질문은 아니에요.",
            options.map { it.first },
        ) ?: return null
        return options[selected].second
    }

    private fun askSkillExperience(step: Int, total: Int, skill: String): SkillExperience? {
        val options = listOf(
            "아직 이 유형을 풀어본 적 없어요" to SkillExperience.NOT_TRIED,
            "개념이나 예제를 본 적 있어요" to SkillExperience.SEEN_CONCEPT,
            "풀이를 보거나 힌트를 받아 해결해봤어요" to SkillExperience.FOLLOWED_SOLUTION,
            "기본 문제는 힌트 없이 풀어본 적 있어요" to SkillExperience.SOLVED_BASIC_INDEPENDENTLY,
            "응용 문제도 스스로 접근해 풀어봤어요" to SkillExperience.SOLVED_VARIATION_INDEPENDENTLY,
            "잘 모르겠어요" to SkillExperience.UNSURE,
        )
        val selected = askChoice(
            step,
            total,
            "$skill 경험",
            "자신감 점수 대신 실제로 해본 경험을 골라주세요.",
            options.map { it.first },
        ) ?: return null
        return options[selected].second
    }

    private fun askText(step: Int, total: Int, title: String, description: String): String? {
        var errorMessage: String? = null
        while (true) {
            showStep(step, total, title, description)
            errorMessage?.let { output.println(terminalUi.warning(it)) }
            output.print("입력 (q: 종료): ")
            output.flush()
            val answer = input.readLine()?.trim() ?: return null
            if (answer.equals("q", ignoreCase = true) || answer == "ㅂ") return null
            if (answer.isNotBlank()) return if (title == "언어 직접 입력") normalizeLanguage(answer) else answer
            errorMessage = "비어 있지 않은 내용을 입력해주세요."
        }
    }

    private fun askChoice(
        step: Int,
        total: Int,
        title: String,
        description: String,
        options: List<String>,
    ): Int? {
        return ChoiceMenu(input, output, terminalUi).select(
            title = "진단 전 자기보고 $step / $total · $title",
            description = description,
            options = options,
        )
    }

    private fun showStep(step: Int, total: Int, title: String, description: String) {
        terminalUi.clearScreen(output)
        output.println(terminalUi.muted("진단 전 자기보고  $step / $total"))
        output.println()
        output.println(terminalUi.bold(title))
        output.println(description)
        output.println()
    }

    private fun normalizeLanguage(language: String): String = when (language.trim().lowercase()) {
        "java" -> "Java"
        "python", "python3" -> "Python"
        "c++", "cpp" -> "C++"
        "javascript", "js" -> "JavaScript"
        "kotlin" -> "Kotlin"
        else -> language.trim()
    }
}
