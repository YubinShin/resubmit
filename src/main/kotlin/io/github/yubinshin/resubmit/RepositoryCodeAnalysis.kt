package io.github.yubinshin.resubmit

import com.sun.source.tree.*
import com.sun.source.util.JavacTask
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class SourcePattern(val label: String, val line: Long, val method: String, val problemIds: List<String>)

@Serializable
internal data class AnalyzedSource(
    val path: String,
    val sha256: String,
    val patterns: List<SourcePattern>,
    val duplicateOf: String? = null,
    val error: String? = null,
)

@Serializable
internal data class RepositoryAnalysis(
    val root: String,
    val analyzedAt: String,
    val files: List<AnalyzedSource>,
    val notices: List<String>,
    val analyzerVersion: String = "java-ast-v1",
    val javaVersion: String = System.getProperty("java.version"),
    val metadataSha256: String? = null,
    val stale: Boolean = false,
)

/** Parses Java syntax only. Never compiles, loads classes, runs processors, or executes learner code. */
internal class RepositoryCodeAnalyzer(
    private val root: Path,
    private val reportFile: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val excluded = setOf(".git", ".resubmit", "build", "bin", "target", "archive", "node_modules", "test", "tests")
    private val maxBytes = 1_048_576L

    fun read(): RepositoryAnalysis? {
        if (Files.notExists(reportFile)) return null
        return try {
            val report = json.decodeFromString<RepositoryAnalysis>(Files.readString(reportFile))
            val paths = sources()
            val changed = !Files.isDirectory(root, NOFOLLOW_LINKS) || report.root != root.toAbsolutePath().normalize().toString() || report.analyzerVersion != "java-ast-v1" ||
                report.javaVersion != System.getProperty("java.version") || report.metadataSha256 != metadataHash() ||
                paths.map { relative(it) }.toSet() != report.files.map { it.path }.toSet() ||
                paths.any { path -> report.files.firstOrNull { it.path == relative(path) }?.sha256 != sourceHash(path) }
            report.copy(stale = changed)
        } catch (exception: Exception) {
            RepositoryAnalysis(root.toString(), "", emptyList(), listOf("저장된 분석을 확인하지 못했습니다: ${exception.message}"), stale = true)
        }
    }

    fun analyze(): RepositoryAnalysis {
        val notices = mutableListOf<String>()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) {
            return RepositoryAnalysis(root.toString(), clock.instant().toString(), emptyList(), listOf("분석 디렉터리가 없습니다: $root"))
        }
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: return RepositoryAnalysis(root.toString(), clock.instant().toString(), emptyList(), listOf("Java 구문 분석에는 JDK가 필요합니다."))
        val associations = readAssociations(notices)
        val hashes = mutableMapOf<String, String>()
        val files = sources().map { path ->
            val relative = relative(path)
            try {
                if (Files.size(path) > maxBytes) {
                    AnalyzedSource(relative, "size-limit", emptyList(), error = "분석 크기 제한 초과: $maxBytes bytes")
                } else {
                    val content = Files.readString(path)
                    val hash = sha256(content.toByteArray(Charsets.UTF_8))
                    val duplicate = hashes[hash]
                    if (duplicate != null) {
                        AnalyzedSource(relative, hash, emptyList(), duplicateOf = duplicate)
                    } else {
                        hashes[hash] = relative
                        val diagnostics = DiagnosticCollector<JavaFileObject>()
                        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { manager ->
                            val source = object : SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.SOURCE) {
                                override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = content
                            }
                            val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none"), null, listOf(source)) as JavacTask
                            val units = task.parse().toList()
                            val errors = diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }
                            if (errors.isNotEmpty()) {
                                AnalyzedSource(relative, hash, emptyList(), error = errors.joinToString("; ") { "${it.lineNumber}행: ${it.getMessage(null)}" })
                            } else {
                                val findings = mutableListOf<SourcePattern>()
                                val trees = Trees.instance(task)
                                units.forEach { unit ->
                                    object : TreeScanner<Unit, String?>() {
                                        override fun visitMethod(node: MethodTree, context: String?) {
                                            super.visitMethod(node, node.name.toString())
                                        }
                                        private fun record(node: Tree, label: String, method: String?) {
                                            if (method == null) return
                                            val offset = trees.sourcePositions.getStartPosition(unit, node)
                                            val links = associations.filter { it.path == relative && (it.method == null || it.method == method) }
                                            findings.add(SourcePattern(label, unit.lineMap.getLineNumber(offset), method, links.map { it.id }.distinct()))
                                        }
                                        override fun visitForLoop(node: ForLoopTree, method: String?) {
                                            record(node, "for 반복문", method); super.visitForLoop(node, method)
                                        }
                                        override fun visitEnhancedForLoop(node: EnhancedForLoopTree, method: String?) {
                                            record(node, "향상된 for 반복문", method); super.visitEnhancedForLoop(node, method)
                                        }
                                        override fun visitWhileLoop(node: WhileLoopTree, method: String?) {
                                            record(node, "while 반복문", method); super.visitWhileLoop(node, method)
                                        }
                                        override fun visitDoWhileLoop(node: DoWhileLoopTree, method: String?) {
                                            record(node, "do-while 반복문", method); super.visitDoWhileLoop(node, method)
                                        }
                                        override fun visitNewClass(node: NewClassTree, method: String?) {
                                            val name = node.identifier.toString().substringBefore('<').substringAfterLast('.')
                                            if (name in setOf("HashMap", "HashSet", "TreeMap", "TreeSet", "PriorityQueue", "ArrayDeque")) {
                                                record(node, "$name 생성 구문 (타입 해석 미수행)", method)
                                            }
                                            super.visitNewClass(node, method)
                                        }
                                        override fun visitMethodInvocation(node: MethodInvocationTree, method: String?) {
                                            val call = node.methodSelect.toString()
                                            if (method != null && (call == method || call == "this.$method")) {
                                                record(node, "동일 이름 메서드 호출 (재귀 여부 미확인)", method)
                                            }
                                            super.visitMethodInvocation(node, method)
                                        }
                                    }.scan(unit, null)
                                }
                                AnalyzedSource(relative, hash, findings.distinct())
                            }
                        }
                    }
                }
            } catch (exception: Exception) {
                AnalyzedSource(relative, "unreadable", emptyList(), error = exception.message ?: "파일 분석 실패")
            }
        }
        val report = RepositoryAnalysis(root.toAbsolutePath().normalize().toString(), clock.instant().toString(), files, notices,
            metadataSha256 = metadataHash())
        return try {
            Files.createDirectories(requireNotNull(reportFile.toAbsolutePath().parent))
            val temporary = Files.createTempFile(reportFile.toAbsolutePath().parent, "source-analysis-", ".tmp")
            try {
                Files.writeString(temporary, json.encodeToString(report))
                try { Files.move(temporary, reportFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, reportFile, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
            report
        } catch (exception: Exception) { report.copy(notices = notices + "분석 결과 저장 실패: ${exception.message}") }
    }

    private fun sources(): List<Path> {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return emptyList()
        val paths = mutableListOf<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.fileName.toString() in excluded) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && file.fileName.toString().endsWith(".java")) paths.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return paths.sorted()
    }

    private data class Association(val path: String, val method: String?, val id: String)
    private fun metadata(): Path? = listOf(root.resolve("data/problems.csv"), root.resolve("content/problems.csv"))
        .firstOrNull { Files.isRegularFile(it, NOFOLLOW_LINKS) }
    private fun metadataHash(): String? = metadata()?.let { sha256(Files.readAllBytes(it)) }
    private fun relative(path: Path): String = root.relativize(path).toString().replace('\\', '/')
    private fun sourceHash(path: Path): String = if (Files.size(path) > maxBytes) "size-limit" else sha256(Files.readAllBytes(path))
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readAssociations(notices: MutableList<String>): List<Association> {
        val path = metadata() ?: run { notices.add("문제 연결용 CSV가 없어 소스 위치만 표시합니다."); return emptyList() }
        return try {
            val rows = parseCatalogCsv(Files.readString(path).removePrefix("\uFEFF"))
            val header = rows.first()
            require(header.containsAll(listOf("problem_id", "judge_file", "judge_method"))) { "문제 연결용 열이 없습니다." }
            val associations = rows.drop(1).flatMapIndexed { index, values ->
                if (values.size != header.size) {
                    notices.add("문제 연결 CSV 데이터 ${index + 1}행의 열 수가 달라 제외했습니다.")
                    emptyList()
                } else {
                    val row = header.zip(values).toMap()
                    val judge = row.getValue("judge_file")
                    (row["source_paths"].orEmpty().split(';') + judge).filter(String::isNotBlank).distinct().map { source ->
                        Association(source, if (source == judge) row["judge_method"]?.takeIf(String::isNotBlank) else null, row.getValue("problem_id"))
                    }
                }
            }
            val sharedPaths = associations.groupBy { it.path }.filterValues { links -> links.map { it.id }.distinct().size > 1 }.keys
            val ambiguousMethods = associations.filter { it.method != null }.groupBy { it.path to it.method }
                .filterValues { links -> links.map { it.id }.distinct().size > 1 }.keys
            val reliable = associations.filterNot { (it.method == null && it.path in sharedPaths) || (it.path to it.method) in ambiguousMethods }
            if (reliable.size != associations.size) notices.add("여러 문제를 구분할 수 없는 파일·메서드 연결은 제외했습니다.")
            reliable
        } catch (exception: Exception) { notices.add("문제 연결 실패: ${exception.message}"); emptyList() }
    }
}
