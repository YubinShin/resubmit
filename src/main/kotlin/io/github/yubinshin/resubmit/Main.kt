package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.nio.file.Path
import java.time.Clock
import kotlin.system.exitProcess

fun main() {
    val exitCode = CodingCoachCli(
        input = System.`in`.bufferedReader(),
        output = PrintWriter(System.out, true),
        workspaceFactory = SessionWorkspaceFactory(
            projectRoot = Path.of(".").toAbsolutePath().normalize(),
            clock = Clock.systemDefaultZone(),
        ),
    ).run()

    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

internal class CodingCoachCli(
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val workspaceFactory: SessionWorkspaceFactory,
) {
    fun run(): Int {
        val problem = DemoProblem.targetNumber
        output.println(applicationBanner())
        output.println()
        printProblem(problem)
        output.println()
        val workspace = workspaceFactory.create(problem)
        output.println("작업 파일을 만들었습니다.")
        output.println("  문제: ${workspace.problemFile.toAbsolutePath().normalize()}")
        output.println("  제출: ${workspace.solutionFile.toAbsolutePath().normalize()}")
        output.println()
        output.print("Solution.java를 편집한 뒤 Enter를 눌러 제출하세요. [q: 저장 후 종료] ")
        output.flush()

        return when (input.readLine()?.trim()?.lowercase()) {
            "q" -> {
                output.println("세션을 저장했습니다: ${workspace.sessionId}")
                SUCCESS
            }
            null -> {
                output.println("입력이 종료되어 세션을 저장했습니다: ${workspace.sessionId}")
                SUCCESS
            }
            "" -> {
                output.println("제출 파일 확인: ${workspace.solutionFile.toAbsolutePath().normalize()}")
                output.println("컴파일과 테스트 실행은 다음 구현 단계에서 연결합니다.")
                SUCCESS
            }
            else -> {
                output.println("사용할 수 없는 명령입니다. 가능한 명령: Enter, q")
                INVALID_INPUT
            }
        }
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
