package io.github.yubinshin.resubmit

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.security.MessageDigest
import kotlin.io.path.appendText
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionWorkspaceTest {
    @Test
    fun `sorts same-second collision suffixes numerically`() {
        withTempDirectory { root ->
            val factory = SessionWorkspaceFactory(root, fixedClock())
            repeat(10) { factory.create(DemoProblem.targetNumber) }

            val existing = factory.existing(DemoProblem.targetNumber)

            assertTrue(existing.first().sessionId.endsWith("-10"))
            assertEquals(10, existing.size)
        }
    }

    @Test
    fun `excludes sessions created from an incompatible fixture`() {
        withTempDirectory { root ->
            val factory = SessionWorkspaceFactory(root, fixedClock())
            factory.create(DemoProblem.targetNumber)
            val changedProblem = DemoProblem.targetNumber.copy(
                testCases = DemoProblem.targetNumber.testCases.map { testCase ->
                    if (testCase.id == "case-1") {
                        testCase.copy(invocation = "new Solution().solution(new int[] {2, 2, 2}, 3)")
                    } else testCase
                },
            )

            assertEquals(emptyList(), factory.existing(changedProblem))
        }
    }

    @Test
    fun `accepts a compatible session created before fixture hashes`() {
        withTempDirectory { root ->
            val factory = SessionWorkspaceFactory(root, fixedClock())
            val workspace = factory.create(DemoProblem.targetNumber)
            workspace.directory.resolve(".internal/fixture.sha256").deleteIfExists()

            assertEquals(listOf(workspace), factory.existing(DemoProblem.targetNumber))
        }
    }

    @Test
    fun `resumes and runs a target number session with the legacy harness fixture`() {
        withTempDirectory { root ->
            val factory = SessionWorkspaceFactory(root, fixedClock())
            val workspace = factory.create(DemoProblem.targetNumber)
            val legacyHarness = legacyTargetNumberHarness()
            workspace.harnessFile.writeText(legacyHarness)
            workspace.directory.resolve(".internal/fixture.sha256").writeText(
                fixtureHash(workspace.problemFile.toFile().readText(), legacyHarness),
            )

            assertEquals(listOf(workspace), factory.existing(DemoProblem.targetNumber))

            workspace.solutionFile.writeText(
                """
                public class Solution {
                    public int solution(int[] numbers, int target) {
                        return search(numbers, target, 0, 0);
                    }
                    private int search(int[] numbers, int target, int index, int sum) {
                        if (index == numbers.length) return sum == target ? 1 : 0;
                        return search(numbers, target, index + 1, sum + numbers[index])
                            + search(numbers, target, index + 1, sum - numbers[index]);
                    }
                }
                """.trimIndent(),
            )
            val classes = workspace.directory.resolve("classes")
            val compilation = ProcessJavaCodeRunner().compile(
                listOf(workspace.solutionFile, workspace.harnessFile),
                classes,
            )
            assertTrue(compilation is CompilationResult.Success)
            val result = ProcessJavaTestRunner().run(classes, DemoProblem.targetNumber.testCases)
            assertTrue(result is TestRunResult.Completed && result.passedCount == 4)
        }
    }

    @Test
    fun `runs public diagnostic problem fixtures including array return values`() {
        val fixtures = listOf(
            ProblemCatalog.twoSum to """
                import java.util.HashMap;
                public class Solution {
                    public int[] solution(int[] nums, int target) {
                        HashMap<Integer, Integer> seen = new HashMap<>();
                        for (int i = 0; i < nums.length; i++) {
                            Integer match = seen.get(target - nums[i]);
                            if (match != null) return new int[] {match, i};
                            seen.putIfAbsent(nums[i], i);
                        }
                        return new int[0];
                    }
                }
            """.trimIndent(),
            ProblemCatalog.validParentheses to """
                public class Solution {
                    public boolean solution(String s) {
                        char[] stack = new char[s.length()];
                        int size = 0;
                        for (char c : s.toCharArray()) {
                            if (c == '(' || c == '[' || c == '{') stack[size++] = c;
                            else {
                                if (size == 0) return false;
                                char open = stack[--size];
                                if ((c == ')' && open != '(') || (c == ']' && open != '[') || (c == '}' && open != '{')) return false;
                            }
                        }
                        return size == 0;
                    }
                }
            """.trimIndent(),
            ProblemCatalog.searchInsertPosition to """
                public class Solution {
                    public int solution(int[] nums, int target) {
                        int low = 0, high = nums.length;
                        while (low < high) {
                            int mid = low + (high - low) / 2;
                            if (nums[mid] < target) low = mid + 1;
                            else high = mid;
                        }
                        return low;
                    }
                }
            """.trimIndent(),
            ProblemCatalog.numberOfIslands to """
                import java.util.ArrayDeque;
                public class Solution {
                    public int solution(char[][] grid) {
                        int count = 0;
                        int[] dr = {-1, 1, 0, 0}, dc = {0, 0, -1, 1};
                        for (int r = 0; r < grid.length; r++) {
                            for (int c = 0; c < grid[r].length; c++) {
                                if (grid[r][c] != '1') continue;
                                count++;
                                ArrayDeque<int[]> queue = new ArrayDeque<>();
                                queue.add(new int[] {r, c});
                                grid[r][c] = '0';
                                while (!queue.isEmpty()) {
                                    int[] cell = queue.remove();
                                    for (int d = 0; d < 4; d++) {
                                        int nr = cell[0] + dr[d], nc = cell[1] + dc[d];
                                        if (nr >= 0 && nr < grid.length && nc >= 0 && nc < grid[nr].length && grid[nr][nc] == '1') {
                                            grid[nr][nc] = '0';
                                            queue.add(new int[] {nr, nc});
                                        }
                                    }
                                }
                            }
                        }
                        return count;
                    }
                }
            """.trimIndent(),
        )

        fixtures.forEach { (problem, solution) ->
            withTempDirectory { root ->
                val workspace = SessionWorkspaceFactory(root, fixedClock()).create(problem)
                workspace.solutionFile.writeText(solution)
                val source = requireNotNull(problem.source)
                assertEquals("resubmit", source.repository)
                assertTrue(workspace.problemFile.toFile().readText().contains("${source.repository}/${source.path} · ${source.recordId}"))

                val compilation = ProcessJavaCodeRunner().compile(
                    listOf(workspace.solutionFile, workspace.harnessFile),
                    workspace.directory.resolve("classes"),
                )

                assertTrue(compilation is CompilationResult.Success, "${problem.id} did not compile: $compilation")
                val result = ProcessJavaTestRunner().run(workspace.directory.resolve("classes"), problem.testCases)
                assertTrue(
                    result is TestRunResult.Completed && result.passedCount == problem.testCases.size,
                    "${problem.id} did not pass its fixture: $result",
                )
            }
        }
    }

    @Test
    fun `excludes a session when persisted problem text is changed`() {
        withTempDirectory { root ->
            val factory = SessionWorkspaceFactory(root, fixedClock())
            val workspace = factory.create(DemoProblem.targetNumber)
            workspace.problemFile.appendText("\nchanged")

            assertEquals(emptyList(), factory.existing(DemoProblem.targetNumber))
        }
    }

    @Test
    fun `excludes a session when persisted harness is changed`() {
        withTempDirectory { root ->
            val factory = SessionWorkspaceFactory(root, fixedClock())
            val workspace = factory.create(DemoProblem.targetNumber)
            workspace.harnessFile.appendText("\n// changed")

            assertEquals(emptyList(), factory.existing(DemoProblem.targetNumber))
        }
    }

    private fun fixedClock(): Clock = Clock.fixed(
        Instant.parse("2026-10-07T06:00:00Z"),
        ZoneId.of("Asia/Seoul"),
    )

    private fun fixtureHash(problemMarkdown: String, harnessSource: String): String {
        val fixture = problemMarkdown + "\u0000" + harnessSource
        return MessageDigest.getInstance("SHA-256")
            .digest(fixture.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun legacyTargetNumberHarness(): String {
        val checks = DemoProblem.targetNumber.testCases.joinToString(System.lineSeparator()) { testCase ->
            "failures += check(\"${testCase.id}\", ${requireNotNull(testCase.legacyArguments)});"
        }
        return """
            import java.io.OutputStream;
            import java.io.PrintStream;
            import java.io.PrintWriter;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;

            public class TestHarness {
                private static PrintWriter resultWriter;
                private static String protocolToken;

                public static void main(String[] args) throws Exception {
                    if (args.length != 2) System.exit(2);
                    protocolToken = args[1];
                    try (PrintWriter writer = new PrintWriter(
                            Files.newBufferedWriter(
                                Path.of(args[0]),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE))) {
                        resultWriter = writer;
                        PrintStream discarded = new PrintStream(OutputStream.nullOutputStream());
                        System.setOut(discarded);
                        System.setErr(discarded);
                        int failures = 0;
                        $checks
                        writer.flush();
                        System.exit(failures == 0 ? 0 : 1);
                    }
                }

                private static int check(String id, int[] numbers, int target, int expected) {
                    try {
                        int actual = new Solution().solution(numbers, target);
                        String status = actual == expected ? "PASS" : "FAIL";
                        resultWriter.println(protocolToken + "\tRESULT\t" + id + "\t" + status + "\t" + expected + "\t" + actual);
                        return actual == expected ? 0 : 1;
                    } catch (Throwable throwable) {
                        resultWriter.println(protocolToken + "\tRESULT\t" + id + "\tERROR\t" + throwable.getClass().getSimpleName());
                        return 1;
                    }
                }
            }
        """.trimIndent() + System.lineSeparator()
    }

    private fun withTempDirectory(block: (java.nio.file.Path) -> Unit) {
        val root = createTempDirectory("resubmit-workspace-test")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
