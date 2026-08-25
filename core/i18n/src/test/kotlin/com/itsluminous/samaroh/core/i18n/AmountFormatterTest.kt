package com.itsluminous.samaroh.core.i18n

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmountFormatterTest {
    @Test
    fun `formats crore amount with indian grouping`() {
        // ₹1,06,51,161 — the spec §5 example.
        assertThat(AmountFormatter.format(1_06_51_161_00L)).isEqualTo("₹1,06,51,161")
    }

    @Test
    fun `formats lakh amount`() {
        assertThat(AmountFormatter.format(1_06_511_00L)).isEqualTo("₹1,06,511")
    }

    @Test
    fun `amounts up to three digits are not grouped`() {
        assertThat(AmountFormatter.format(0L)).isEqualTo("₹0")
        assertThat(AmountFormatter.format(5_00L)).isEqualTo("₹5")
        assertThat(AmountFormatter.format(999_00L)).isEqualTo("₹999")
    }

    @Test
    fun `four digit amount gets one separator`() {
        assertThat(AmountFormatter.format(1_000_00L)).isEqualTo("₹1,000")
    }

    @Test
    fun `lakh boundary groups by two after last three`() {
        assertThat(AmountFormatter.format(1_00_000_00L)).isEqualTo("₹1,00,000")
        assertThat(AmountFormatter.format(12_34_567_00L)).isEqualTo("₹12,34,567")
    }

    @Test
    fun `non-zero paise are always shown`() {
        assertThat(AmountFormatter.format(1_234_50L)).isEqualTo("₹1,234.50")
        assertThat(AmountFormatter.format(1_05L)).isEqualTo("₹1.05")
    }

    @Test
    fun `showPaise forces two decimals`() {
        assertThat(AmountFormatter.format(1_000_00L, showPaise = true)).isEqualTo("₹1,000.00")
    }

    @Test
    fun `negative amounts carry a leading sign`() {
        assertThat(AmountFormatter.format(-1_06_511_00L)).isEqualTo("-₹1,06,511")
    }

    @Test
    fun `groupIndian handles long digit strings`() {
        assertThat(AmountFormatter.groupIndian("1234567890")).isEqualTo("1,23,45,67,890")
    }
}
