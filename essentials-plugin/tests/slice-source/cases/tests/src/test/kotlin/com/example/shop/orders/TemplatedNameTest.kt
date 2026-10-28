package com.example.shop.orders

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** RULE: a display name the reader cannot evaluate is reported unparsed and left null — never guessed. */
class TemplatedNameTest {
    @Test
    @DisplayName("order $ID is cancelled")
    fun templated() {
    }

    companion object {
        const val ID = "o-1"
    }
}
