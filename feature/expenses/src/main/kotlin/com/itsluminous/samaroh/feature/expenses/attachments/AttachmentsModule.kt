package com.itsluminous.samaroh.feature.expenses.attachments

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides [AttachmentCompressor] with its production IO dispatcher (tests construct it directly). */
@Module
@InstallIn(SingletonComponent::class)
object AttachmentsModule {
    @Provides
    @Singleton
    fun provideAttachmentCompressor(
        @ApplicationContext context: Context,
    ): AttachmentCompressor = AttachmentCompressor(context)
}
