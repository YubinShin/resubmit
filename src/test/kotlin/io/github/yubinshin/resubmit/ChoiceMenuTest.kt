package io.github.yubinshin.resubmit

import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ChoiceMenuTest {
    @Test
    fun `renders highlighted arrow selection and returns confirmed option`() {
        val output = StringWriter()
        val input = object : BufferedReader(StringReader("")), ArrowChoiceInput {
            override fun choose(optionCount: Int, render: (selectedIndex: Int) -> Unit): Int? {
                assertEquals(3, optionCount)
                render(0)
                render(1)
                return 1
            }
        }

        val selected = ChoiceMenu(input, PrintWriter(output, true), TerminalUi.plain()).select(
            title = "Re:Submit",
            description = "메뉴를 선택하세요.",
            options = listOf("문제 선택", "프로필 수정", "도움말"),
        )

        assertEquals(1, selected)
        assertContains(output.toString(), "↑/↓ 이동 · Enter 선택 · q 종료")
        assertContains(output.toString(), "› 2. 프로필 수정")
    }
}
