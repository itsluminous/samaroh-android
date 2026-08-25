package com.itsluminous.samaroh.feature.inventory

import com.google.common.truth.Truth.assertThat
import com.itsluminous.samaroh.feature.inventory.domain.formatQuantity
import com.itsluminous.samaroh.feature.inventory.domain.parseQuantity
import com.itsluminous.samaroh.feature.inventory.domain.parseRupeesToPaise
import org.junit.Test

class InputParsersTest {
    @Test
    fun `parseQuantity accepts up to three decimals and rejects zero and garbage`() {
        assertThat(parseQuantity("10")).isEqualTo(10.0)
        assertThat(parseQuantity(" 2.5 ")).isEqualTo(2.5)
        assertThat(parseQuantity("0.125")).isEqualTo(0.125)
        assertThat(parseQuantity("0")).isNull()
        assertThat(parseQuantity("0.0000")).isNull()
        assertThat(parseQuantity("1.2345")).isNull()
        assertThat(parseQuantity("-3")).isNull()
        assertThat(parseQuantity("abc")).isNull()
        assertThat(parseQuantity("")).isNull()
    }

    @Test
    fun `parseRupeesToPaise converts rupee text to exact paise`() {
        assertThat(parseRupeesToPaise("120")).isEqualTo(12_000L)
        assertThat(parseRupeesToPaise("120.5")).isEqualTo(12_050L)
        assertThat(parseRupeesToPaise("120.50")).isEqualTo(12_050L)
        assertThat(parseRupeesToPaise("0.99")).isEqualTo(99L)
        assertThat(parseRupeesToPaise("0")).isEqualTo(0L)
    }

    @Test
    fun `parseRupeesToPaise rejects invalid input`() {
        assertThat(parseRupeesToPaise("12.345")).isNull()
        assertThat(parseRupeesToPaise("-5")).isNull()
        assertThat(parseRupeesToPaise("₹100")).isNull()
        assertThat(parseRupeesToPaise("")).isNull()
    }

    @Test
    fun `formatQuantity trims trailing zeros`() {
        assertThat(formatQuantity(7.0)).isEqualTo("7")
        assertThat(formatQuantity(2.5)).isEqualTo("2.5")
        assertThat(formatQuantity(0.125)).isEqualTo("0.125")
    }
}
