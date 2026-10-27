package io.github.yubinshin.resubmit

import java.nio.file.Files
import java.nio.file.Path

internal data class CatalogEntry(
    val key: String,
    val title: String,
    val skills: List<String>,
    val source: String,
    val sourceId: String,
    val difficulty: String?,
    val description: String,
    val platform: String? = null,
    val url: String? = null,
    val executable: Problem? = null,
)

internal data class CatalogSnapshot(val entries: List<CatalogEntry>, val notices: List<String> = emptyList())

internal fun interface LearningCatalog {
    fun load(): CatalogSnapshot
}

internal class LocalLearningCatalog(private val codeRpgHome: Path? = null) : LearningCatalog {
    override fun load(): CatalogSnapshot {
        val builtin = ProblemCatalog.all.map { problem ->
            val origin = problem.source?.repository ?: "resubmit"
            val id = problem.source?.recordId ?: problem.id
            CatalogEntry("$origin:$id", problem.title, problem.relatedSkills, origin, id, null,
                problem.description, executable = problem)
        }
        if (codeRpgHome == null) return CatalogSnapshot(builtin, listOf(
            "외부 카탈로그: RESUBMIT_CODE_RPG_HOME에 code-rpg 경로를 설정하면 연결됩니다.",
        ))
        return try {
            mergeCatalog(builtin, readCodeRpgCatalog(codeRpgHome.resolve("content/problems.csv")))
        } catch (exception: Exception) {
            CatalogSnapshot(builtin, listOf("외부 카탈로그를 읽지 못해 내장 문제를 사용합니다: ${exception.message}"))
        }
    }
}

private fun mergeCatalog(builtin: List<CatalogEntry>, external: CatalogSnapshot): CatalogSnapshot {
    val merged = linkedMapOf<String, CatalogEntry>()
    external.entries.forEach { merged[it.key] = it }
    builtin.forEach { entry ->
        // Only locally validated fixtures are executable, even when CSV says judge_mode=auto.
        val imported = merged[entry.key]
        merged[entry.key] = entry.copy(
            difficulty = imported?.difficulty,
            platform = imported?.platform,
            url = imported?.url,
        )
    }
    return CatalogSnapshot(merged.values.toList(), external.notices)
}

internal fun readCodeRpgCatalog(path: Path): CatalogSnapshot {
    return parseCodeRpgCatalog(Files.readString(path).removePrefix("\uFEFF"))
}

internal fun parseCodeRpgCatalog(text: String): CatalogSnapshot {
    val rows = parseCatalogCsv(text.removePrefix("\uFEFF"))
    require(rows.isNotEmpty()) { "카탈로그가 비어 있습니다." }
    val header = rows.first()
    require(header.distinct().size == header.size) { "CSV 열 이름이 중복됩니다." }
    require(header.containsAll(listOf("problem_id", "title", "category", "platform", "problem_text"))) {
        "카탈로그 필수 열이 없습니다."
    }
    val ids = mutableSetOf<String>()
    val notices = mutableListOf<String>()
    val entries = rows.drop(1).filterNot { it.all(String::isBlank) }.mapIndexedNotNull { index, values ->
        if (values.size != header.size) {
            notices.add("외부 카탈로그 데이터 ${index + 1}행 (${values.firstOrNull().orEmpty()}): 열 수가 달라 제외했습니다.")
            return@mapIndexedNotNull null
        }
        val row = header.zip(values).toMap()
        val id = row.getValue("problem_id").trim()
        val title = row.getValue("title").trim()
        require(id.isNotEmpty() && title.isNotEmpty()) { "문제 ID나 제목이 비어 있습니다." }
        require(ids.add(id)) { "중복 문제 ID: $id" }
        val platform = row.getValue("platform").trim()
        val externalId = row["external_id"].orEmpty().trim()
        val url = if (platform == "programmers" && externalId.matches(Regex("[0-9]+"))) {
            "https://school.programmers.co.kr/learn/courses/30/lessons/$externalId"
        } else null
        CatalogEntry(
            key = "code-rpg:$id", title = title,
            skills = row.getValue("category").split(';').map(String::trim).filter(String::isNotEmpty).distinct(),
            source = "code-rpg", sourceId = id,
            difficulty = row["difficulty"]?.trim()?.takeIf(String::isNotEmpty)?.let { "code-rpg 원본 $it" },
            description = row.getValue("problem_text"), platform = platform, url = url,
        )
    }
    return CatalogSnapshot(entries, notices)
}

// RFC-style quoted fields, embedded newlines and escaped quotes; malformed input fails visibly.
internal fun parseCatalogCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var closedQuote = false
    var index = 0
    fun finishField() { row.add(field.toString()); field.setLength(0); closedQuote = false }
    fun finishRow() { finishField(); rows.add(row.toList()); row.clear() }
    while (index < text.length) {
        val char = text[index]
        if (quoted) {
            if (char == '"') {
                if (text.getOrNull(index + 1) == '"') { field.append('"'); index++ }
                else { quoted = false; closedQuote = true }
            } else field.append(char)
        } else {
            require(!closedQuote || char in listOf(',', '\r', '\n')) { "닫는 따옴표 뒤에 잘못된 문자가 있습니다." }
            when (char) {
                '"' -> { require(field.isEmpty()) { "필드 중간의 따옴표입니다." }; quoted = true }
                ',' -> finishField()
                '\n', '\r' -> { finishRow(); if (char == '\r' && text.getOrNull(index + 1) == '\n') index++ }
                else -> field.append(char)
            }
        }
        index++
    }
    require(!quoted) { "닫히지 않은 CSV 따옴표입니다." }
    if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) finishRow()
    return rows
}
