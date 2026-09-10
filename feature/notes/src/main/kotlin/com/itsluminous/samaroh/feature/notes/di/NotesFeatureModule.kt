package com.itsluminous.samaroh.feature.notes.di

import com.itsluminous.samaroh.core.data.sync.PostSyncHook
import com.itsluminous.samaroh.feature.notes.sweep.NotesPostSyncHook
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Feature wiring: the Trash purge sweep joins the post-sync hook set (ADR-077). */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotesFeatureModule {
    @Binds
    @IntoSet
    abstract fun bindNotesPostSyncHook(impl: NotesPostSyncHook): PostSyncHook
}
