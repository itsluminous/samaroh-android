package com.itsluminous.samaroh.core.database.di

import android.content.Context
import androidx.room.Room
import com.itsluminous.samaroh.core.database.SamarohDatabase
import com.itsluminous.samaroh.core.database.dao.BookingDao
import com.itsluminous.samaroh.core.database.dao.BookingPaymentDao
import com.itsluminous.samaroh.core.database.dao.BusinessDao
import com.itsluminous.samaroh.core.database.dao.BusinessMemberDao
import com.itsluminous.samaroh.core.database.dao.BusinessSettingsDao
import com.itsluminous.samaroh.core.database.dao.DateBlockDao
import com.itsluminous.samaroh.core.database.dao.ExpenseAttachmentDao
import com.itsluminous.samaroh.core.database.dao.ExpenseDao
import com.itsluminous.samaroh.core.database.dao.GoogleAccountLinkDao
import com.itsluminous.samaroh.core.database.dao.InventoryTransactionDao
import com.itsluminous.samaroh.core.database.dao.MasterItemDao
import com.itsluminous.samaroh.core.database.dao.OutboxDao
import com.itsluminous.samaroh.core.database.dao.PartyDao
import com.itsluminous.samaroh.core.database.dao.PaymentReminderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): SamarohDatabase =
        Room
            .databaseBuilder(context, SamarohDatabase::class.java, SamarohDatabase.DATABASE_NAME)
            .build()

    @Provides fun provideBusinessDao(db: SamarohDatabase): BusinessDao = db.businessDao()

    @Provides fun provideBusinessMemberDao(db: SamarohDatabase): BusinessMemberDao = db.businessMemberDao()

    @Provides fun provideBusinessSettingsDao(db: SamarohDatabase): BusinessSettingsDao = db.businessSettingsDao()

    @Provides fun provideGoogleAccountLinkDao(db: SamarohDatabase): GoogleAccountLinkDao = db.googleAccountLinkDao()

    @Provides fun provideBookingDao(db: SamarohDatabase): BookingDao = db.bookingDao()

    @Provides fun provideDateBlockDao(db: SamarohDatabase): DateBlockDao = db.dateBlockDao()

    @Provides fun provideBookingPaymentDao(db: SamarohDatabase): BookingPaymentDao = db.bookingPaymentDao()

    @Provides fun providePaymentReminderDao(db: SamarohDatabase): PaymentReminderDao = db.paymentReminderDao()

    @Provides fun providePartyDao(db: SamarohDatabase): PartyDao = db.partyDao()

    @Provides fun provideExpenseDao(db: SamarohDatabase): ExpenseDao = db.expenseDao()

    @Provides fun provideExpenseAttachmentDao(db: SamarohDatabase): ExpenseAttachmentDao = db.expenseAttachmentDao()

    @Provides fun provideMasterItemDao(db: SamarohDatabase): MasterItemDao = db.masterItemDao()

    @Provides fun provideInventoryTransactionDao(db: SamarohDatabase): InventoryTransactionDao = db.inventoryTransactionDao()

    @Provides fun provideOutboxDao(db: SamarohDatabase): OutboxDao = db.outboxDao()
}
