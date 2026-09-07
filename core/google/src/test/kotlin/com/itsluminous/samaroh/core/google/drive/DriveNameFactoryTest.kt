package com.itsluminous.samaroh.core.google.drive

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ADR-058: human-readable Drive names — sanitization, timestamp rendering in the given
 * zone, and same-second collision uniquification (with reset when the second rolls over).
 */
class DriveNameFactoryTest {
    private val now = Instant.parse("2026-09-07T06:00:00Z")

    private fun factory(
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
        zone: ZoneId = ZoneOffset.UTC,
    ) = DriveNameFactory(clock, zone)

    @Test
    fun `builds base-timestamp-extension`() {
        assertThat(factory().fileName("Steel Plate", "item", ".webp"))
            .isEqualTo("Steel Plate-20260907-060000.webp")
    }

    @Test
    fun `timestamp renders in the provided zone`() {
        // 06:00 UTC = 11:30 IST — the owner's wall clock, not the sync clock's UTC.
        assertThat(factory(zone = ZoneId.of("Asia/Kolkata")).fileName("Thali", "item", ".webp"))
            .isEqualTo("Thali-20260907-113000.webp")
    }

    @Test
    fun `unsafe and control characters sanitize to single spaces`() {
        assertThat(DriveNameFactory.sanitizeBase("A/B\\C:D*E?F\"G<H>I|J", fallback = "item"))
            .isEqualTo("A B C D E F G H I J")
        assertThat(DriveNameFactory.sanitizeBase("tab\there\nnewline", fallback = "item"))
            .isEqualTo("tab here newline")
        assertThat(DriveNameFactory.sanitizeBase("  lots   of\t spaces  ", fallback = "item"))
            .isEqualTo("lots of spaces")
    }

    @Test
    fun `hindi item names pass through unchanged`() {
        assertThat(DriveNameFactory.sanitizeBase("स्टील थाली", fallback = "item")).isEqualTo("स्टील थाली")
    }

    @Test
    fun `a name that sanitizes to nothing falls back`() {
        assertThat(DriveNameFactory.sanitizeBase("///", fallback = "item")).isEqualTo("item")
        assertThat(DriveNameFactory.sanitizeBase("", fallback = "expense")).isEqualTo("expense")
    }

    @Test
    fun `overlong names truncate`() {
        val long = "x".repeat(300)
        assertThat(DriveNameFactory.sanitizeBase(long, fallback = "item")).hasLength(80)
    }

    @Test
    fun `same base in the same second uniquifies with a numeric suffix`() {
        val factory = factory()
        assertThat(factory.fileName("Thali", "item", ".webp")).isEqualTo("Thali-20260907-060000.webp")
        assertThat(factory.fileName("Thali", "item", ".webp")).isEqualTo("Thali-20260907-060000-2.webp")
        assertThat(factory.fileName("Thali", "item", ".webp")).isEqualTo("Thali-20260907-060000-3.webp")
    }

    @Test
    fun `different bases in the same second do not collide`() {
        val factory = factory()
        assertThat(factory.fileName("Thali", "item", ".webp")).isEqualTo("Thali-20260907-060000.webp")
        assertThat(factory.fileName("Glass", "item", ".webp")).isEqualTo("Glass-20260907-060000.webp")
    }

    @Test
    fun `the uniquifier resets when the second rolls over`() {
        val ticking = MutableClock(now)
        val factory = DriveNameFactory(ticking, ZoneOffset.UTC)
        assertThat(factory.fileName("Thali", "item", ".webp")).isEqualTo("Thali-20260907-060000.webp")
        ticking.advance(Duration.ofSeconds(1))
        assertThat(factory.fileName("Thali", "item", ".webp")).isEqualTo("Thali-20260907-060001.webp")
    }

    /** A fixed clock the test can move forward. */
    private class MutableClock(
        private var instant: Instant,
    ) : Clock() {
        fun advance(by: Duration) {
            instant = instant.plus(by)
        }

        override fun instant(): Instant = instant

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }
}
