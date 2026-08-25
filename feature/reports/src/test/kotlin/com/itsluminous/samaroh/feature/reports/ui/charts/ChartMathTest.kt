package com.itsluminous.samaroh.feature.reports.ui.charts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChartMathTest {
    private fun entry(vararg values: Long) = ChartEntry(label = "l", fullLabel = "full", values = values.toList())

    @Test
    fun `max stack value sums segments and never returns zero`() {
        val entries = listOf(entry(100L, 200L), entry(50L))

        assertThat(ChartMath.maxStackValue(entries)).isEqualTo(300L)
        assertThat(ChartMath.maxStackValue(listOf(entry(0L)))).isEqualTo(1L)
        assertThat(ChartMath.maxStackValue(emptyList())).isEqualTo(1L)
    }

    @Test
    fun `bar index maps taps and rejects out-of-plot offsets`() {
        assertThat(ChartMath.barIndex(x = 10f, plotWidth = 100f, barCount = 4)).isEqualTo(0)
        assertThat(ChartMath.barIndex(x = 99f, plotWidth = 100f, barCount = 4)).isEqualTo(3)
        assertThat(ChartMath.barIndex(x = -1f, plotWidth = 100f, barCount = 4)).isNull()
        assertThat(ChartMath.barIndex(x = 100f, plotWidth = 100f, barCount = 4)).isNull()
        assertThat(ChartMath.barIndex(x = 10f, plotWidth = 100f, barCount = 0)).isNull()
    }

    @Test
    fun `line bounds always include zero and never collapse`() {
        assertThat(ChartMath.lineBounds(listOf(500L, -200L))).isEqualTo(-200L to 500L)
        assertThat(ChartMath.lineBounds(listOf(300L, 700L))).isEqualTo(0L to 700L)
        assertThat(ChartMath.lineBounds(listOf(-300L))).isEqualTo(-300L to 0L)
        assertThat(ChartMath.lineBounds(emptyList())).isEqualTo(0L to 1L)
    }
}

class CompactAmountTest {
    @Test
    fun `magnitudes split at thousand, lakh and crore`() {
        assertThat(CompactAmount.of(999_00L).magnitude).isEqualTo(CompactAmount.Magnitude.PLAIN)
        assertThat(CompactAmount.of(1_500_00L)).isEqualTo(
            CompactAmount.Compact(display = "1.5", magnitude = CompactAmount.Magnitude.THOUSAND, negative = false),
        )
        assertThat(CompactAmount.of(2_50_000_00L)).isEqualTo(
            CompactAmount.Compact(display = "2.5", magnitude = CompactAmount.Magnitude.LAKH, negative = false),
        )
        assertThat(CompactAmount.of(3_00_00_000_00L)).isEqualTo(
            CompactAmount.Compact(display = "3", magnitude = CompactAmount.Magnitude.CRORE, negative = false),
        )
    }

    @Test
    fun `negative amounts keep their sign flag`() {
        val compact = CompactAmount.of(-5_00_000_00L)

        assertThat(compact.negative).isTrue()
        assertThat(compact.magnitude).isEqualTo(CompactAmount.Magnitude.LAKH)
        assertThat(compact.display).isEqualTo("5")
    }

    @Test
    fun `trim decimal drops a trailing point zero`() {
        assertThat(CompactAmount.trimDecimal(12.0)).isEqualTo("12")
        assertThat(CompactAmount.trimDecimal(4.54)).isEqualTo("4.5")
        assertThat(CompactAmount.trimDecimal(4.56)).isEqualTo("4.6")
    }
}
