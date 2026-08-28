package com.itsluminous.samaroh.feature.menu.ui.about

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Launches the Donate-via-UPI deep link (§4.4 About). The `upi://pay` URI is data from
 * the shared catalog (`menu.about.donate_upi_uri`, non-translatable); whatever UPI apps
 * are installed claim it, and the system shows its own picker when several do. A device
 * without any UPI app gets a graceful `false` (the caller shows a localized toast)
 * instead of a crash. No INTERNET permission is needed to delegate a link to another app.
 *
 * Deliberately NO `resolveActivity`/`queryIntentActivities` pre-check: Android 11+
 * package visibility filters PackageManager query results (fragile even with a manifest
 * `<queries>` declaration on real devices), while `startActivity` itself is exempt from
 * visibility restrictions. Attempting the launch and catching
 * [ActivityNotFoundException] is the only authoritative "is there a UPI app?" signal.
 */
object UpiDonate {
    /** The exact `ACTION_VIEW` intent [open] fires, exposed so tests can assert on it. */
    fun intent(uri: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    /**
     * Launches the UPI link; returns false when no installed app claims `upi://` links
     * so the caller can surface the localized no-app notice.
     */
    fun open(
        context: Context,
        uri: String,
    ): Boolean =
        try {
            val intent = intent(uri)
            // Launching from a non-Activity context needs its own task.
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}
