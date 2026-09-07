package com.itsluminous.samaroh.feature.inventory.di

import com.itsluminous.samaroh.core.data.sync.PostSyncHook
import com.itsluminous.samaroh.feature.inventory.image.ItemImagePrefetcher
import com.itsluminous.samaroh.feature.inventory.image.ItemImageStore
import com.itsluminous.samaroh.feature.inventory.image.LocalItemImageStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class InventoryFeatureModule {
    @Binds abstract fun bindItemImageStore(impl: LocalItemImageStore): ItemImageStore

    /** Post-sync item-photo warm-up so inventory images render fully offline (ADR-062). */
    @Binds
    @IntoSet
    abstract fun bindItemImagePrefetcher(impl: ItemImagePrefetcher): PostSyncHook
}
