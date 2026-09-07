package com.itsluminous.samaroh.core.data.image

import android.content.Context
import java.io.File

/*
 * The app-private directory holding device-local item photos (ADR-055). ONE convention,
 * shared by its writer (`feature:inventory`'s `LocalItemImageStore`, which stores the
 * cropped WebP as `{itemId}.webp`) and its readers (the Drive item-photo mirror in
 * `core:google`, which uploads the file keyed by the item id). Keeping it here means the
 * file-name contract can never silently drift between modules.
 */

/** Directory under `filesDir` where item photos live before/after mirroring. */
fun itemImageDir(context: Context): File = File(context.filesDir, "inventory-images")

/** The device-local photo file of [itemId] — exists only for photos added on THIS device. */
fun localItemImageFile(
    context: Context,
    itemId: String,
): File = File(itemImageDir(context), "$itemId.webp")
