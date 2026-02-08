/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.kotlin.types

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AmountTest {
    @Test
    fun `equals`() {
        val amount1 = Amount("100.50")
        val amount2 = Amount("100.50")

        assertThat(amount1).isEqualTo(amount2)
    }

    @Test
    fun `equals with different values`() {
        val amount1 = Amount("100.00")
        val amount2 = Amount("100.01")

        assertThat(amount1).isNotEqualTo(amount2)
    }

    @Test
    fun `should add amounts correctly`() {
        val amount1 = Amount("100.50")
        val amount2 = Amount("50.25")
        val result = amount1 + amount2

        assertThat(result.value).isEqualByComparingTo(BigDecimal("150.75"))
    }

    @Test
    fun `should subtract amounts correctly`() {
        val amount1 = Amount("100.50")
        val amount2 = Amount("50.25")
        val result = amount1 - amount2

        assertThat(result.value).isEqualByComparingTo(BigDecimal("50.25"))
    }

    @Test
    fun `should multiply amounts correctly`() {
        val amount1 = Amount("100.50")
        val amount2 = Amount("2.00")
        val result = amount1 * amount2

        assertThat(result.value).isEqualByComparingTo(BigDecimal("201.00"))
    }

    @Test
    fun `should divide amounts correctly`() {
        val amount1 = Amount("100.50")
        val amount2 = Amount("2.00")
        val result = amount1 / amount2

        assertThat(result.value).isEqualByComparingTo(BigDecimal("50.25"))
    }

    @Test
    fun `should compare amounts correctly`() {
        val amount1 = Amount("100.50")
        val amount2 = Amount("50.25")

        assertThat(amount1).isGreaterThan(amount2)
        assertThat(amount2).isLessThan(amount1)
    }

    @Test
    fun `should return unary plus correctly`() {
        val amount = Amount("100.50")
        val result = +amount

        assertThat(result.value).isEqualByComparingTo(BigDecimal("100.50"))
    }

    @Test
    fun `should return unary minus correctly`() {
        val amount = Amount("100.50")
        val result = -amount

        assertThat(result.value).isEqualByComparingTo(BigDecimal("-100.50"))
    }

    @Test
    fun `should return absolute value correctly`() {
        val amount = Amount("-100.50")
        val result = amount.abs()

        assertThat(result.value).isEqualByComparingTo(BigDecimal("100.50"))
    }
}