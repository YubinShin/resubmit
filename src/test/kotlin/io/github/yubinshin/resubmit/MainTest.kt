package io.github.yubinshin.resubmit

import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun `application banner identifies the project`() {
        assertEquals(
            "Re:Submit — Evidence-based Coding Test Coach",
            applicationBanner(),
        )
    }
}

