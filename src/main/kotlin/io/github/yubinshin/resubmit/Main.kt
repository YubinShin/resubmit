package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.system.exitProcess

fun main() {
    val exitCode = CodingCoachCli(
        input = System.`in`.bufferedReader(),
        output = PrintWriter(System.out, true),
    ).run()

    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

internal class CodingCoachCli(
    private val input: BufferedReader,
    private val output: PrintWriter,
) {
    fun run(): Int {
        output.println(applicationBanner())
        output.println()
        printProblem(DemoProblem.targetNumber)
        output.println()
        output.print("제출할 Solution.java 경로: ")
        output.flush()

        val submittedPath = input.readLine()?.trim().orEmpty()
        if (submittedPath.isEmpty()) {
            output.println("제출 경로가 입력되지 않았습니다.")
            return INVALID_INPUT
        }

        val sourcePath = Path.of(submittedPath).toAbsolutePath().normalize()
        if (!Files.isRegularFile(sourcePath)) {
            output.println("제출 파일을 찾을 수 없습니다: $sourcePath")
            return INVALID_INPUT
        }

        if (sourcePath.extension.lowercase() != "java") {
            output.println("Java 소스 파일만 제출할 수 있습니다: $sourcePath")
            return INVALID_INPUT
        }

        output.println("제출 파일 확인: $sourcePath")
        output.println("컴파일과 테스트 실행은 다음 구현 단계에서 연결합니다.")
        return SUCCESS
    }

    private fun printProblem(problem: Problem) {
        output.println("[${problem.id}] ${problem.title}")
        output.println(problem.description)
        output.println("제출 규약: ${problem.submissionContract}")
    }

    private companion object {
        const val SUCCESS = 0
        const val INVALID_INPUT = 2
    }
}

internal data class Problem(
    val id: String,
    val title: String,
    val description: String,
    val submissionContract: String,
)

internal object DemoProblem {
    val targetNumber = Problem(
        id = "target-number-demo",
        title = "Target Number",
        description = "주어진 정수에 더하기 또는 빼기를 적용해 target을 만드는 경우의 수를 반환하세요.",
        submissionContract = "public class Solution에 solution(int[] numbers, int target) 메서드를 구현하세요.",
    )
}

internal fun applicationBanner(): String = "Re:Submit — Evidence-based Coding Test Coach"
