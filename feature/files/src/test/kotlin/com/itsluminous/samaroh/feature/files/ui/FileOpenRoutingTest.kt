package com.itsluminous.samaroh.feature.files.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * ADR-090 open routing: a non-image file is handed to the system as LOCAL bytes through
 * the module's FileProvider with its MIME (never a `drive.google.com` VIEW that the Drive
 * app would capture), and the Drive-viewer Custom Tab is pinned to a browser package.
 */
@RunWith(RobolectricTestRunner::class)
class FileOpenRoutingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context as Application

    /** What the module's FileProvider hands out for a `files-cache/` row (Robolectric cannot canonicalize the real provider roots). */
    private fun cachedPdfUri(): Uri = Uri.parse("content://${context.packageName}.files.fileprovider/files_cache/row-1")

    private fun registerViewer(
        component: ComponentName,
        filter: IntentFilter,
    ) {
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(component, filter)
    }

    @Test
    fun `no viewer installed - returns false and fires nothing`() {
        shadowOf(application).checkActivities(true)
        assertThat(launchViewer(context, viewIntent(cachedPdfUri(), "application/pdf"))).isFalse()
        assertThat(shadowOf(application).nextStartedActivity).isNull()
    }

    @Test
    fun `viewer installed - fires ACTION_VIEW on a content uri from the files FileProvider with the MIME and a read grant`() {
        registerViewer(
            ComponentName("com.example.pdf", "com.example.pdf.ViewerActivity"),
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType("application/pdf")
            },
        )

        assertThat(launchViewer(context, viewIntent(cachedPdfUri(), "application/pdf"))).isTrue()

        val chooser = shadowOf(application).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(target.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(target.type).isEqualTo("application/pdf")
        assertThat(target.data?.scheme).isEqualTo("content")
        assertThat(target.data?.authority).isEqualTo("${context.packageName}.files.fileprovider")
        assertThat(target.data?.host).doesNotContain("drive.google.com")
        assertThat(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun `the FileProvider path config covers the files-cache and files-staging dirs`() {
        val xml = context.resources.getXml(com.itsluminous.samaroh.feature.files.R.xml.files_file_paths)
        val paths = mutableListOf<String>()
        while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "files-path") {
                paths += xml.getAttributeValue(null, "path")
            }
            xml.next()
        }
        assertThat(paths).containsExactly("files-cache/", "files-staging/")
    }

    @Test
    fun `browser pinning - resolves the default https handler when no Custom Tabs provider exists`() {
        assertThat(browserPackage(context)).isNull()
        registerViewer(
            ComponentName("com.example.browser", "com.example.browser.Main"),
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            },
        )
        assertThat(browserPackage(context)).isEqualTo("com.example.browser")
    }
}
