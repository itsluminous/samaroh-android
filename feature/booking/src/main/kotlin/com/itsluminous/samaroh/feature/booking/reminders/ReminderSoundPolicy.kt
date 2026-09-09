package com.itsluminous.samaroh.feature.booking.reminders

import android.app.NotificationManager
import android.net.Uri
import android.provider.Settings

/**
 * Sound + notification-channel identity for booking reminders (ADR-073).
 *
 * An UNSET `booking_reminder_sound_uri` preference means the SYSTEM DEFAULT
 * notification sound — never silence. Channels are immutable after creation, so the
 * default-sound change ships as a new channel GENERATION (`_v2` ids): existing
 * installs keep their old channels' frozen sound unless we retire them, which
 * [deleteLegacyChannels] does on every ensure pass (reminder notifications re-post
 * daily, so a cancelled-by-deletion notification is at most a one-day gap).
 */
object ReminderSoundPolicy {
    /** Channel-id generation suffix — bump when a channel DEFAULT changes (ADR-073). */
    private const val GENERATION = "_v2"

    /**
     * The sound a reminder actually plays: the user's explicit ringtone pick, or the
     * system default notification sound when the preference is unset.
     */
    fun effectiveSoundUri(prefUri: String?): Uri = prefUri?.let(Uri::parse) ?: Settings.System.DEFAULT_NOTIFICATION_URI

    /**
     * Channel id of the current generation: the base (default-sound) channel for plain
     * notifications, or a per-sound variant (standard immutable-channel workaround)
     * for the full-screen styles' explicit sound.
     */
    fun channelId(
        base: String,
        soundUri: String?,
    ): String = if (soundUri == null) "$base$GENERATION" else "$base${GENERATION}_${soundUri.hashCode()}"

    /** Whether [id] belongs to the CURRENT channel generation of [base]. */
    fun isCurrentGeneration(
        base: String,
        id: String,
    ): Boolean = id == "$base$GENERATION" || id.startsWith("$base${GENERATION}_")

    /**
     * Whether [id] is a RETIRED reminder channel of [base]: the pre-v2 base channel or
     * one of its old per-sound variants. Channels of other features never match.
     */
    fun isLegacy(
        base: String,
        id: String,
    ): Boolean = (id == base || id.startsWith("${base}_")) && !isCurrentGeneration(base, id)

    /** Deletes every retired reminder channel so system settings list only live ones. */
    fun deleteLegacyChannels(
        manager: NotificationManager,
        vararg bases: String,
    ) {
        manager.notificationChannels
            .mapNotNull { it.id }
            .filter { id -> bases.any { base -> isLegacy(base, id) } }
            .forEach(manager::deleteNotificationChannel)
    }
}
