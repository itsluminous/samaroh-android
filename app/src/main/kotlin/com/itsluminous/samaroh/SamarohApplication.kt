package com.itsluminous.samaroh

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SamarohApplication :
    Application(),
    ImageLoaderFactory {
    /**
     * App-wide Coil loader (ADR-023): `respectCacheHeaders(false)` makes disk-cached item
     * photos render WITHOUT revalidation, so remote Storage photos keep working fully
     * offline. Staleness is a non-issue — every photo upload writes a NEW object path,
     * which changes the cache key.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(this)
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
}
