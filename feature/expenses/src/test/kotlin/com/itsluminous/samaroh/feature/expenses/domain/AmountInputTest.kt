package com.itsluminous.samaroh.feature.expenses.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmountInputTest {
    @Test
    fun `whole rupees parse to paise`() {
        assertThat(AmountInput.parseToPaise("1250")).isEqualTo(125_000L)
    }

    @Test
    fun `decimals parse with padding`() {
        assertThat(AmountInput.parseToPaise("1250.5")).isEqualTo(125_050L)
        assertThat(AmountInput.parseToPaise("1250.50")).isEqualTo(125_050L)
        assertThat(AmountInput.parseToPaise("0.01")).isEqualTo(1L)
    }

    @Test
    fun `grouping separators are tolerated`() {
        assertThat(AmountInput.parseToPaise("1,06,511")).isEqualTo(1_06_511_00L)
    }

    @Test
    fun `invalid input returns null`() {
        assertThat(AmountInput.parseToPaise("")).isNull()
        assertThat(AmountInput.parseToPaise("abc")).isNull()
        assertThat(AmountInput.parseToPaise("-5")).isNull()
        assertThat(AmountInput.parseToPaise("1.234")).isNull()
        assertThat(AmountInput.parseToPaise("0")).isNull()
        assertThat(AmountInput.parseToPaise("0.00")).isNull()
    }
}
