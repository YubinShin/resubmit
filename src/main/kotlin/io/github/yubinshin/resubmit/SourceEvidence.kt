package io.github.yubinshin.resubmit

import com.sun.source.tree.*
import com.sun.source.util.JavacTask
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import kotlinx.serialization.Serializable

/** 제출 소스의 한 위치에서 구문 분석으로 확인한 사실. 오답이나 시간 복잡도를 확정하지 않는다. */
@Serializable
internal data class SourceEvidence(
    val id: String,
    val file: String,
    val line: Long,
    val method: String,
    val ruleId: String,
    val description: String,
    val tool: String,
    val toolVersion: String,
    val sourceSha256: String,
)

/** Parses Java syntax only. Never compiles, loads classes, runs processors, or executes learner code. */
internal class SourceEvidenceAnalyzer {
    fun analyze(sourceCode: String, sourceSha256: String, file: String = "Solution.java"): List<SourceEvidence> {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: return emptyList()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val findings = mutableListOf<Finding>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { manager ->
            val source = object : SimpleJavaFileObject(
                java.net.URI.create("string:///$file"),
                JavaFileObject.Kind.SOURCE,
            ) {
                override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = sourceCode
            }
            val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none"), null, listOf(source)) as JavacTask
            val units = task.parse().toList()
            if (diagnostics.diagnostics.any { it.kind == Diagnostic.Kind.ERROR }) return emptyList()
            val trees = Trees.instance(task)
            units.forEach { unit ->
                object : TreeScanner<Unit, String?>() {
                    override fun visitMethod(node: MethodTree, context: String?) {
                        super.visitMethod(node, node.name.toString())
                    }

                    private fun record(node: Tree, ruleId: String, description: String, method: String?) {
                        if (method == null) return
                        val offset = trees.sourcePositions.getStartPosition(unit, node)
                        findings.add(Finding(unit.lineMap.getLineNumber(offset), method, ruleId, description))
                    }

                    override fun visitForLoop(node: ForLoopTree, method: String?) {
                        record(node, "LOOP_FOR", "for 반복문", method); super.visitForLoop(node, method)
                    }

                    override fun visitEnhancedForLoop(node: EnhancedForLoopTree, method: String?) {
                        record(node, "LOOP_FOR_EACH", "향상된 for 반복문", method); super.visitEnhancedForLoop(node, method)
                    }

                    override fun visitWhileLoop(node: WhileLoopTree, method: String?) {
                        record(node, "LOOP_WHILE", "while 반복문", method); super.visitWhileLoop(node, method)
                    }

                    override fun visitDoWhileLoop(node: DoWhileLoopTree, method: String?) {
                        record(node, "LOOP_DO_WHILE", "do-while 반복문", method); super.visitDoWhileLoop(node, method)
                    }

                    override fun visitNewClass(node: NewClassTree, method: String?) {
                        val name = node.identifier.toString().substringBefore('<').substringAfterLast('.')
                        if (name in COLLECTION_TYPES) {
                            record(node, "NEW_COLLECTION", "$name 생성 구문 (타입 해석 미수행)", method)
                        }
                        super.visitNewClass(node, method)
                    }

                    override fun visitMethodInvocation(node: MethodInvocationTree, method: String?) {
                        val call = node.methodSelect.toString()
                        if (method != null && (call == method || call == "this.$method")) {
                            record(node, "SAME_NAME_CALL", "동일 이름 메서드 호출 (재귀 여부 미확인)", method)
                        }
                        super.visitMethodInvocation(node, method)
                    }
                }.scan(unit, null)
            }
        }
        val prefix = "ev-${sourceSha256.take(ID_HASH_LENGTH)}"
        return findings.distinct().mapIndexed { index, finding ->
            SourceEvidence(
                id = "$prefix-${index + 1}",
                file = file,
                line = finding.line,
                method = finding.method,
                ruleId = finding.ruleId,
                description = finding.description,
                tool = TOOL,
                toolVersion = "$TOOL_VERSION (jdk ${System.getProperty("java.version")})",
                sourceSha256 = sourceSha256,
            )
        }
    }

    private data class Finding(val line: Long, val method: String, val ruleId: String, val description: String)

    private companion object {
        const val TOOL = "java-ast"
        const val TOOL_VERSION = "v1"
        const val ID_HASH_LENGTH = 8
        val COLLECTION_TYPES = setOf("HashMap", "HashSet", "TreeMap", "TreeSet", "PriorityQueue", "ArrayDeque")
    }
}
