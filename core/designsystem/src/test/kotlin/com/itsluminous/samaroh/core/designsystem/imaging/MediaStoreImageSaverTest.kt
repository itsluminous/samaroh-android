package com.itsluminous.samaroh.core.designsystem.imaging

import android.app.Application
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The viewer's save-to-Downloads action (ADR-053): MediaStore path + legacy fallback. */
@RunWith(RobolectricTestRunner::class)
class MediaStoreImageSaverTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var source: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = tempFolder.newFile("bill.jpg").apply { writeBytes(ByteArray(64) { it.toByte() }) }
    }

    @Test
    @Config(sdk = [34])
    fun `api 29+ inserts into MediaStore Downloads with relative path and publishes`() =
        runTest {
            val result =
                MediaStoreImageSaver.saveImage(
                    context = context,
                    source = source,
                    displayName = "bill.jpg",
                    mimeType = "image/jpeg",
                    ioDispatcher = UnconfinedTestDispatcher(),
                )

            assertThat(result).isEqualTo(MediaStoreImageSaver.SaveResult.Saved(MediaStoreImageSaver.LOCATION_LABEL))
            val shadow = shadowOf(context.contentResolver)
            val insert = shadow.insertStatements.single()
            assertThat(insert.uri).isEqualTo(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            assertThat(insert.contentValues.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)).isEqualTo("bill.jpg")
            assertThat(insert.contentValues.getAsString(MediaStore.MediaColumns.MIME_TYPE)).isEqualTo("image/jpeg")
            assertThat(insert.contentValues.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
                .isEqualTo("Download/Samaroh")
            assertThat(insert.contentValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING)).isEqualTo(1)
            // Publish step: IS_PENDING cleared so other apps can see the file.
            val update = shadow.updateStatements.single()
            assertThat(update.contentValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING)).isEqualTo(0)
        }

    @Test
    @Config(sdk = [28])
    fun `legacy api without the write permission asks for it instead of failing silently`() =
        runTest {
            val result =
                MediaStoreImageSaver.saveImage(
                    context = context,
                    source = source,
                    displayName = "bill.jpg",
                    mimeType = "image/jpeg",
                    ioDispatcher = UnconfinedTestDispatcher(),
                )

            assertThat(result).isEqualTo(MediaStoreImageSaver.SaveResult.NeedsPermission)
        }

    @Test
    @Config(sdk = [28])
    fun `legacy api with the permission writes into the public Downloads subfolder`() =
        runTest {
            shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

            val result =
                MediaStoreImageSaver.saveImage(
                    context = context,
                    source = source,
                    displayName = "bill.jpg",
                    mimeType = "image/jpeg",
                    ioDispatcher = UnconfinedTestDispatcher(),
                )

            assertThat(result).isEqualTo(MediaStoreImageSaver.SaveResult.Saved(MediaStoreImageSaver.LOCATION_LABEL))
            val written =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "${MediaStoreImageSaver.TARGET_SUBDIR}/bill.jpg",
                )
            assertThat(written.exists()).isTrue()
            assertThat(written.readBytes()).isEqualTo(source.readBytes())
        }

    @Test
    @Config(sdk = [28])
    fun `legacy name collision uniquifies instead of overwriting`() =
        runTest {
            shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val dir =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    MediaStoreImageSaver.TARGET_SUBDIR,
                ).apply { mkdirs() }
            File(dir, "bill.jpg").writeBytes(byteArrayOf(9))

            MediaStoreImageSaver.saveImage(
                context = context,
                source = source,
                displayName = "bill.jpg",
                mimeType = "image/jpeg",
                ioDispatcher = UnconfinedTestDispatcher(),
            )

            assertThat(File(dir, "bill (1).jpg").exists()).isTrue()
            assertThat(File(dir, "bill.jpg").readBytes()).isEqualTo(byteArrayOf(9))
        }
}
