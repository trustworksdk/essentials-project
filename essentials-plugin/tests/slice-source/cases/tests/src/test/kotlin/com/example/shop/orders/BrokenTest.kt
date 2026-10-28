package com.example.shop.orders

import org.junit.jupiter.api.Test

/** RULE: a test file the lexer cannot read is unparsed, and contributes no class. */
class BrokenTest {
    @Test
    fun broken() {
        val s = "never closed
    }
}
