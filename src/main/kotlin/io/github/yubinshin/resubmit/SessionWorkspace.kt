package io.github.yubinshin.resubmit

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class SessionWorkspaceFactory(
    private val projectRoot: Path,
    private val clock: Clock,
) {
    fun existing(problem: Problem): List<SessionWorkspace> {
        val sessionsRoot = projectRoot.resolve(".resubmit/sessions")
        if (!Files.isDirectory(sessionsRoot)) {
            return emptyList()
        }
        val sessionName = Regex("^(\\d{8}-\\d{6})-${Regex.escape(problem.id)}(?:-(\\d+))?$")
        return Files.list(sessionsRoot).use { paths ->
            paths
                .filter(Files::isDirectory)
                .filter { path -> sessionName.matches(path.fileName.toString()) }
                .map(::workspaceFrom)
                .filter(SessionWorkspace::isComplete)
                .filter { workspace -> compatible(workspace, problem) }
                .sorted(
                    compareByDescending<SessionWorkspace> { workspace ->
                        sessionName.matchEntire(workspace.sessionId)?.groupValues?.get(1).orEmpty()
                    }.thenByDescending { workspace ->
                        sessionName.matchEntire(workspace.sessionId)?.groupValues?.get(2)?.toIntOrNull() ?: 1
                    },
                )
                .toList()
        }
    }

    fun create(problem: Problem): SessionWorkspace {
        val sessionsRoot = projectRoot.resolve(".resubmit/sessions")
        Files.createDirectories(sessionsRoot)

        val baseName = "${SESSION_TIME_FORMAT.format(ZonedDateTime.now(clock))}-${problem.id}"
        val sessionDirectory = nextAvailableDirectory(sessionsRoot, baseName)
        Files.createDirectory(sessionDirectory)

        val problemFile = sessionDirectory.resolve("Problem.md")
        val solutionFile = sessionDirectory.resolve("Solution.java")
        val internalDirectory = sessionDirectory.resolve(".internal")
        Files.createDirectory(internalDirectory)
        val harnessFile = internalDirectory.resolve("TestHarness.java")
        Files.writeString(problemFile, problem.toMarkdown())
        Files.writeString(solutionFile, problem.solutionTemplate)
        Files.writeString(harnessFile, problem.testHarnessSource())
        Files.writeString(internalDirectory.resolve(FIXTURE_HASH_FILE), problem.fixtureHash())

        return workspaceFrom(sessionDirectory)
    }

    private fun workspaceFrom(sessionDirectory: Path): SessionWorkspace = SessionWorkspace(
        sessionId = sessionDirectory.fileName.toString(),
        directory = sessionDirectory,
        problemFile = sessionDirectory.resolve("Problem.md"),
        solutionFile = sessionDirectory.resolve("Solution.java"),
        harnessFile = sessionDirectory.resolve(".internal/TestHarness.java"),
    )

    private fun compatible(workspace: SessionWorkspace, problem: Problem): Boolean {
        return runCatching {
            val hashFile = workspace.directory.resolve(".internal/$FIXTURE_HASH_FILE")
            val expectedHash = problem.fixtureHash()
            val persistedHash = fixtureHash(
                Files.readString(workspace.problemFile),
                Files.readString(workspace.harnessFile),
            )
            val legacyHash = if (problem.id == ProblemCatalog.targetNumber.id) {
                fixtureHash(problem.toMarkdown(), problem.legacyTestHarnessSource())
            } else null
            val previousFormatHash = fixtureHash(problem.toPreviousMarkdown(), problem.testHarnessSource(redirectOutput = true))
            val legacyPreviousFormatHash = if (problem.id == ProblemCatalog.targetNumber.id) {
                fixtureHash(problem.toPreviousMarkdown(), problem.legacyTestHarnessSource())
            } else null
            val supportedHashes = setOfNotNull(expectedHash, legacyHash, previousFormatHash, legacyPreviousFormatHash)
            if (Files.isRegularFile(hashFile)) {
                val recordedHash = Files.readString(hashFile).trim()
                recordedHash in supportedHashes && persistedHash == recordedHash
            } else {
                persistedHash in supportedHashes
            }
        }.getOrDefault(false)
    }

    private fun Problem.fixtureHash(): String = fixtureHash(toMarkdown(), testHarnessSource())

    private fun fixtureHash(problemMarkdown: String, harnessSource: String): String {
        val fixture = problemMarkdown + "\u0000" + harnessSource
        val digest = MessageDigest.getInstance("SHA-256").digest(fixture.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun nextAvailableDirectory(sessionsRoot: Path, baseName: String): Path {
        val initial = sessionsRoot.resolve(baseName)
        if (Files.notExists(initial)) {
            return initial
        }

        var suffix = 2
        while (Files.exists(sessionsRoot.resolve("$baseName-$suffix"))) {
            suffix += 1
        }
        return sessionsRoot.resolve("$baseName-$suffix")
    }

    private fun Problem.toMarkdown(): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine(description)
        appendLine()
        appendLine("## 제출 규약")
        appendLine()
        append(submissionContract)
        appendLine()
        appendLine()
        appendLine("## 공개 테스트 예시")
        appendLine()
        testCases.forEachIndexed { index, testCase ->
            appendLine("${index + 1}. 입력: `${testCase.invocation}`")
            appendLine("   기대 결과: `${testCase.expected}`")
        }
        if (source != null) {
            appendLine()
            appendLine()
            append("출처: ${source.repository}/${source.path} · ${source.recordId} (${source.createdAt})")
        }
        appendLine()
    }

    private fun Problem.toPreviousMarkdown(): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine(description)
        appendLine()
        appendLine("## 제출 규약")
        appendLine()
        append(submissionContract)
        if (source != null) {
            appendLine()
            appendLine()
            append("출처: ${source.repository}/${source.path} · ${source.recordId} (${source.createdAt})")
        }
        appendLine()
    }

    private fun Problem.testHarnessSource(redirectOutput: Boolean = false): String {
        val checks = testCases.joinToString(System.lineSeparator()) { testCase ->
            val valueExpression = if (testCase.arrayResult) {
                "formatValue(${testCase.invocation})"
            } else {
                "String.valueOf(${testCase.invocation})"
            }
            "failures += check(\"${escapeJava(testCase.id)}\", \"${base64(testCase.expected)}\", () -> $valueExpression);"
        }
        val arrayFormatter = if (testCases.any(ProblemTestCase::arrayResult)) {
            """
            private static String formatValue(Object value) {
                if (value == null || !value.getClass().isArray()) return String.valueOf(value);
                int length = java.lang.reflect.Array.getLength(value);
                StringBuilder formatted = new StringBuilder("[");
                for (int index = 0; index < length; index++) {
                    if (index > 0) formatted.append(", ");
                    formatted.append(formatValue(java.lang.reflect.Array.get(value, index)));
                }
                return formatted.append(']').toString();
            }
            """.trimIndent()
        } else ""
        val redirectImports = if (redirectOutput) {
            "import java.io.OutputStream;\n            import java.io.PrintStream;"
        } else ""
        val redirectCode = if (redirectOutput) {
            "                        PrintStream discarded = new PrintStream(OutputStream.nullOutputStream());\n" +
                "                        System.setOut(discarded);\n" +
                "                        System.setErr(discarded);\n" +
                "                        int failures = 0;"
        } else "                        int failures = 0;"
        return """
            $redirectImports
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
$redirectCode
                        $checks
                        writer.flush();
                        System.exit(failures == 0 ? 0 : 1);
                    }
                }

                private interface ValueSupplier { String get(); }
                $arrayFormatter
                private static int check(String id, String expected, ValueSupplier supplier) {
                    try {
                        String actual = java.util.Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(supplier.get().getBytes(StandardCharsets.UTF_8));
                        String status = actual.equals(expected) ? "PASS" : "FAIL";
                        resultWriter.println(protocolToken + "\tRESULT\t" + id + "\t" + status + "\tb64:" + expected + "\tb64:" + actual);
                        return actual.equals(expected) ? 0 : 1;
                    } catch (Throwable throwable) {
                        resultWriter.println(protocolToken + "\tRESULT\t" + id + "\tERROR\t" + throwable.getClass().getSimpleName());
                        return 1;
                    }
                }
            }
        """.trimIndent() + System.lineSeparator()
    }

    private fun Problem.legacyTestHarnessSource(): String {
        val checks = testCases.joinToString(System.lineSeparator()) { testCase ->
            val arguments = requireNotNull(testCase.legacyArguments)
            "failures += check(\"${testCase.id}\", $arguments);"
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

    private fun base64(value: String): String = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun escapeJava(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private companion object {
        val SESSION_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        const val FIXTURE_HASH_FILE = "fixture.sha256"
    }
}

internal data class SessionWorkspace(
    val sessionId: String,
    val directory: Path,
    val problemFile: Path,
    val solutionFile: Path,
    val harnessFile: Path,
) {
    fun isComplete(): Boolean = Files.isRegularFile(problemFile) &&
        Files.isRegularFile(solutionFile) &&
        Files.isRegularFile(harnessFile)
}
