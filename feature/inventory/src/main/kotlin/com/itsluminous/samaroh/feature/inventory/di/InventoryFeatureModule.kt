package com.itsluminous.samaroh.feature.inventory.di

import com.itsluminous.samaroh.feature.inventory.image.ItemImageStore
import com.itsluminous.samaroh.feature.inventory.image.LocalItemImageStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class InventoryFeatureModule {
    @Binds abstract fun bindItemImageStore(impl: LocalItemImageStore): ItemImageStore
}
