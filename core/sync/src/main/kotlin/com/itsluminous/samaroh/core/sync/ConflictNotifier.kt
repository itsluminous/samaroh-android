package com.itsluminous.samaroh.core.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.itsluminous.samaroh.core.data.sync.ConflictResolution
import com.itsluminous.samaroh.core.i18n.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Surfaces LWW conflicts to the user (§8: never silent). Interface so engine tests can record calls. */
interface ConflictNotifier {
    fun notifyConflict(
        title: String,
        fields: List<String>,
        resolution: ConflictResolution,
    )
}

/** Sync notification channel shared by conflict alerts and the expedited-sync foreground notice. */
object SyncNotifications {
    const val CHANNEL_ID = "samaroh-sync"
    const val FOREGROUND_NOTIFICATION_ID = 41_001
    private const val CONFLICT_ID_BASE = 41_100

    private val nextConflictId = AtomicInteger(CONFLICT_ID_BASE)

    fun conflictNotificationId(): Int = nextConflictId.incrementAndGet()

    /** Idempotent channel registration; all strings come from the catalog (`sync.*`). */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sync_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.sync_notification_channel_description)
            }
        manager.createNotificationChannel(channel)
    }
}

/**
 * Posts the localized "changed on another device" notification (§8). Notifications are
 * optional-permission (§6): when POST_NOTIFICATIONS is denied the in-app banner and the
 * conflict log in Settings → Sync status remain the reliable path.
 */
@Singleton
class NotificationConflictNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ConflictNotifier {
        override fun notifyConflict(
            title: String,
            fields: List<String>,
            resolution: ConflictResolution,
        ) {
            SyncNotifications.ensureChannel(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val bodyRes =
                when (resolution) {
                    ConflictResolution.REBASED -> R.string.sync_notification_conflict_rebased
                    ConflictResolution.DROPPED -> R.string.sync_notification_conflict_dropped
                }
            val body = context.getString(bodyRes, title, fields.joinToString(", "))
            val notification =
                NotificationCompat
                    .Builder(context, SyncNotifications.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(context.getString(R.string.sync_notification_conflict_title))
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(SyncNotifications.conflictNotificationId(), notification)
            } catch (_: SecurityException) {
                // Permission revoked mid-flight; the in-app banner still surfaces the conflict.
            }
        }
    }
