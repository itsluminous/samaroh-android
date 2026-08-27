package com.itsluminous.samaroh.feature.menu.ui.about

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Builds and launches the Donate-via-UPI deep link (§4.4 About). A `upi://pay` link is
 * claimed by whatever UPI apps are installed; launching through a chooser lets the user
 * pick one, and a device without any UPI app gets a graceful `false` (the caller shows a
 * localized toast) instead of a crash. No INTERNET permission is needed to delegate a
 * link to another app.
 */
object UpiDonate {
    /** Developer VPA the donation link pays to (data, not user-visible copy). */
    private const val UPI_PAYEE_VPA = "electricprakash@axisb"

    /** Payee display name shown inside the UPI app (data, not user-visible copy). */
    private const val UPI_PAYEE_NAME = "Samaroh"

    /** The exact `upi://pay` view intent, exposed so tests can assert on it. */
    fun intent(): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri
                .parse("upi://pay")
                .buildUpon()
                .appendQueryParameter("pa", UPI_PAYEE_VPA)
                .appendQueryParameter("cn", UPI_PAYEE_NAME)
                .build(),
        )

    /**
     * Launches the UPI chooser; returns false when no installed app claims `upi://`
     * links so the caller can surface the localized no-app notice.
     */
    fun open(
        context: Context,
        chooserTitle: String,
    ): Boolean {
        // Declared in the manifest <queries> block (Android 11+ package visibility).
        if (intent().resolveActivity(context.packageManager) == null) return false
        return try {
            val chooser = Intent.createChooser(intent(), chooserTitle)
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
