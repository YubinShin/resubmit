package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter

internal fun interface ArrowChoiceInput {
    fun choose(optionCount: Int, render: (selectedIndex: Int) -> Unit): Int?
}

internal class ChoiceMenu(
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val terminalUi: TerminalUi,
) {
    fun select(title: String, description: String, options: List<String>): Int? {
        require(options.isNotEmpty()) { "선택 메뉴에는 하나 이상의 항목이 필요합니다." }
        val arrowInput = input as? ArrowChoiceInput
        if (arrowInput != null) {
            return arrowInput.choose(options.size) { selectedIndex ->
                draw(title, description, options, selectedIndex, errorMessage = null)
                output.println("↑/↓ 이동 · Enter 선택 · q 종료")
                output.flush()
            }
        }

        var errorMessage: String? = null
        while (true) {
            draw(title, description, options, selectedIndex = null, errorMessage)
            output.print("번호 선택 (q: 종료): ")
            output.flush()
            val answer = input.readLine()?.trim() ?: return null
            if (answer.equals("q", ignoreCase = true) || answer == "ㅂ") return null
            val selected = answer.toIntOrNull()?.minus(1)
            if (selected != null && selected in options.indices) return selected
            errorMessage = "목록에 있는 번호를 입력해주세요."
        }
    }

    private fun draw(
        title: String,
        description: String,
        options: List<String>,
        selectedIndex: Int?,
        errorMessage: String?,
    ) {
        terminalUi.clearScreen(output)
        output.println(terminalUi.bold(title))
        output.println(description)
        output.println()
        errorMessage?.let { output.println(terminalUi.warning(it)) }
        options.forEachIndexed { index, option ->
            val marker = if (index == selectedIndex) "›" else " "
            val label = "${index + 1}. $option"
            output.println(if (index == selectedIndex) terminalUi.bold("$marker $label") else "$marker $label")
        }
    }
}
