package io.github.yubinshin.resubmit

import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains

class CompilerDiagnosticTest {
    @Test
    fun `renders source location caret help and raw javac message`() {
        withSource(
            """
            public class Solution {
                int solution() {
                    return 0
                }
            }
            """.trimIndent(),
        ) { sourceFile ->
            val raw = """
                $sourceFile:3: error: ';' expected
                        return 0
                                ^
                1 error
            """.trimIndent()

            val rendered = CompilerDiagnosticRenderer().render(sourceFile, raw)

            assertContains(rendered, "error[COMPILE]: 문장이 완성되지 않았습니다")
            assertContains(rendered, "--> ${sourceFile.fileName}:3:17")
            assertContains(rendered, "3 |         return 0")
            assertContains(rendered, "help: 문장 끝에 세미콜론(`;`)이 빠졌는지 확인하세요.")
            assertContains(rendered, "javac: ';' expected")
        }
    }

    @Test
    fun `uses conservative guidance for an unknown compiler message`() {
        withSource("public class Solution {}") { sourceFile ->
            val raw = "$sourceFile:1: error: unfamiliar compiler message\npublic class Solution {}\n^"

            val rendered = CompilerDiagnosticRenderer().render(sourceFile, raw)

            assertContains(rendered, "error[COMPILE]: Java 컴파일에 실패했습니다")
            assertContains(rendered, "표시된 위치와 바로 앞 문장이 완성됐는지 확인하세요.")
            assertContains(rendered, "javac: unfamiliar compiler message")
        }
    }

    @Test
    fun `preserves unparsed javac output in fallback rendering`() {
        withSource("public class Solution {}") { sourceFile ->
            val rendered = CompilerDiagnosticRenderer().render(sourceFile, "unexpected javac output")

            assertContains(rendered, "error[COMPILE]: Java 컴파일에 실패했습니다")
            assertContains(rendered, "javac:\n  unexpected javac output")
        }
    }

    private fun withSource(content: String, block: (java.nio.file.Path) -> Unit) {
        val sourceFile = createTempFile(prefix = "Solution", suffix = ".java")
        try {
            sourceFile.writeText(content)
            block(sourceFile)
        } finally {
            sourceFile.toFile().delete()
        }
    }
}
