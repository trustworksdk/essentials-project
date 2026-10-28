package com.example.shop.orders

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/* RULE: a Kotlin backtick name is listed verbatim, without its backticks. /* @Test fun nested() */ */
class CancelOrderTest {
    private val fake = "@Test fun `from a string`() {}"

    @Test
    fun `cancels a placed order`() {
    }

    // @Test fun `commented out`() {}

    @Test
    @DisplayName("idempotent retry")
    fun `cancelling twice is a no-op`() {
    }

    @ParameterizedTest
    @MethodSource("ids")
    fun rejectsUnknown(id: String) {
    }

    @Nested
    inner class `when unpaid` {
        @Test
        fun `auto cancels`() {
        }
    }

    companion object {
        @JvmStatic
        fun ids() = listOf("o-404")
    }
}
