package com.itsluminous.samaroh.core.invoice.di

import com.itsluminous.samaroh.core.data.invoice.InvoiceGenerator
import com.itsluminous.samaroh.core.data.invoice.InvoiceNumberAllocator
import com.itsluminous.samaroh.core.invoice.AndroidInvoiceGenerator
import com.itsluminous.samaroh.core.invoice.RoomInvoiceNumberAllocator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class InvoiceModule {
    @Binds abstract fun bindInvoiceGenerator(impl: AndroidInvoiceGenerator): InvoiceGenerator

    @Binds abstract fun bindInvoiceNumberAllocator(impl: RoomInvoiceNumberAllocator): InvoiceNumberAllocator
}
