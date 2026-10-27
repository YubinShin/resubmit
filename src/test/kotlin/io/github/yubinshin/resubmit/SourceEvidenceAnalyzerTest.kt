package io.github.yubinshin.resubmit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceEvidenceAnalyzerTest {
    private val source = """
        import java.util.*;
        public class Solution {
            public int solution(int[] numbers, int target) {
                Map<Integer, Integer> seen = new HashMap<>();
                for (int number : numbers) {
                    seen.merge(number, 1, Integer::sum);
                }
                return solution(numbers, target);
            }
        }
    """.trimIndent()

    @Test
    fun `records rule, location, tool and source hash for each finding`() {
        val evidence = SourceEvidenceAnalyzer().analyze(source, "a".repeat(64))

        assertEquals(
            listOf("NEW_COLLECTION" to 4L, "LOOP_FOR_EACH" to 5L, "SAME_NAME_CALL" to 8L),
            evidence.map { it.ruleId to it.line },
        )
        assertTrue(evidence.all { it.method == "solution" && it.file == "Solution.java" })
        assertTrue(evidence.all { it.sourceSha256 == "a".repeat(64) && it.tool == "java-ast" })
        assertEquals(listOf("ev-aaaaaaaa-1", "ev-aaaaaaaa-2", "ev-aaaaaaaa-3"), evidence.map { it.id })
    }

    @Test
    fun `ids differ between source versions`() {
        val first = SourceEvidenceAnalyzer().analyze(source, "a".repeat(64)).map { it.id }
        val second = SourceEvidenceAnalyzer().analyze(source, "b".repeat(64)).map { it.id }

        assertTrue(first.intersect(second.toSet()).isEmpty())
    }

    @Test
    fun `source with syntax errors yields no evidence`() {
        assertEquals(emptyList(), SourceEvidenceAnalyzer().analyze("public class Solution {", "c".repeat(64)))
    }
}
