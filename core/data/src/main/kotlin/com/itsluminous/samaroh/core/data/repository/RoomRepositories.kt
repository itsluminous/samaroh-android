package com.itsluminous.samaroh.core.data.repository

import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.BookingDao
import com.itsluminous.samaroh.core.database.dao.BookingPaymentDao
import com.itsluminous.samaroh.core.database.dao.BusinessDao
import com.itsluminous.samaroh.core.database.dao.BusinessMemberDao
import com.itsluminous.samaroh.core.database.dao.BusinessSettingsDao
import com.itsluminous.samaroh.core.database.dao.DateBlockDao
import com.itsluminous.samaroh.core.database.dao.ExpenseDao
import com.itsluminous.samaroh.core.database.dao.InventoryTransactionDao
import com.itsluminous.samaroh.core.database.dao.MasterItemDao
import com.itsluminous.samaroh.core.database.dao.PartyDao
import com.itsluminous.samaroh.core.model.Booking
import com.itsluminous.samaroh.core.model.BookingPayment
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessMember
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.DateBlock
import com.itsluminous.samaroh.core.model.Expense
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.core.model.Party
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Room-backed implementations (Wave 0). Every write lands in Room and the outbox in the
 * same logical step; UI never touches the network (§4.5). Payload JSON carries Long paise
 * (ADR-002) — the sync engine owns the wire conversion.
 */

private val json = Json { encodeDefaults = true }

private fun tombstonePayload(
    id: String,
    at: Instant,
): String =
    json
        .encodeToString(
            JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("id", id)
                put("deleted_at", at.toString())
            },
        )

@Singleton
class RoomBookingRepository
    @Inject
    constructor(
        private val bookingDao: BookingDao,
        private val paymentDao: BookingPaymentDao,
        private val dateBlockDao: DateBlockDao,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : BookingRepository {
        override fun bookingsBetween(
            businessId: String,
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<Booking>> = bookingDao.bookingsBetween(businessId, from, to).map { list -> list.map { it.toModel() } }

        override suspend fun booking(id: String): Booking? = bookingDao.byId(id)?.toModel()

        override suspend fun saveBooking(booking: Booking) {
            bookingDao.upsert(booking.toEntity())
            outboxWriter.enqueue("bookings", booking.id, OutboxOperation.UPSERT, json.encodeToString(Booking.serializer(), booking))
        }

        override suspend fun deleteBooking(id: String) {
            val now = clock.instant()
            bookingDao.tombstone(id, now)
            outboxWriter.enqueue("bookings", id, OutboxOperation.DELETE, tombstonePayload(id, now))
        }

        override suspend fun countBookingsOn(
            businessId: String,
            date: LocalDate,
        ): Int = bookingDao.countBookingsOn(businessId, date)

        override fun paymentsForBooking(bookingId: String): Flow<List<BookingPayment>> =
            paymentDao.paymentsForBooking(bookingId).map { list -> list.map { it.toModel() } }

        override suspend fun recordPayment(payment: BookingPayment) {
            require(payment.amountPaise > 0) { "payment amount must be positive" }
            paymentDao.upsert(payment.toEntity())
            outboxWriter.enqueue(
                "booking_payments",
                payment.id,
                OutboxOperation.UPSERT,
                json.encodeToString(BookingPayment.serializer(), payment),
            )
        }

        override suspend fun totalPaidPaise(bookingId: String): Long = paymentDao.totalPaidPaise(bookingId)

        override fun dateBlocksBetween(
            businessId: String,
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<DateBlock>> = dateBlockDao.blocksBetween(businessId, from, to).map { list -> list.map { it.toModel() } }

        override suspend fun saveDateBlock(block: DateBlock) {
            dateBlockDao.upsert(block.toEntity())
            outboxWriter.enqueue("date_blocks", block.id, OutboxOperation.UPSERT, json.encodeToString(DateBlock.serializer(), block))
        }

        override suspend fun deleteDateBlock(id: String) {
            val now = clock.instant()
            dateBlockDao.tombstone(id, now)
            outboxWriter.enqueue("date_blocks", id, OutboxOperation.DELETE, tombstonePayload(id, now))
        }
    }

@Singleton
class RoomExpensesRepository
    @Inject
    constructor(
        private val partyDao: PartyDao,
        private val expenseDao: ExpenseDao,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : ExpensesRepository {
        override fun partiesWithBalance(businessId: String): Flow<List<PartyWithNetBalance>> =
            partyDao.partiesWithBalance(businessId).map { rows ->
                rows.map { PartyWithNetBalance(it.party.toModel(), it.netBalancePaise) }
            }

        override suspend fun searchParties(
            businessId: String,
            query: String,
        ): List<Party> = partyDao.searchByName(businessId, query).map { it.toModel() }

        override suspend fun saveParty(party: Party) {
            partyDao.upsert(party.toEntity())
            outboxWriter.enqueue("parties", party.id, OutboxOperation.UPSERT, json.encodeToString(Party.serializer(), party))
        }

        override suspend fun deleteParty(id: String) {
            val now = clock.instant()
            partyDao.tombstone(id, now)
            outboxWriter.enqueue("parties", id, OutboxOperation.DELETE, tombstonePayload(id, now))
        }

        override fun entriesForParty(partyId: String): Flow<List<Expense>> =
            expenseDao.entriesForParty(partyId).map { list -> list.map { it.toModel() } }

        override suspend fun saveExpense(expense: Expense) {
            require(expense.amountPaise > 0) { "expense amount must be positive" }
            expenseDao.upsert(expense.toEntity())
            outboxWriter.enqueue("expenses", expense.id, OutboxOperation.UPSERT, json.encodeToString(Expense.serializer(), expense))
        }

        override suspend fun deleteExpense(id: String) {
            val now = clock.instant()
            expenseDao.tombstone(id, now)
            outboxWriter.enqueue("expenses", id, OutboxOperation.DELETE, tombstonePayload(id, now))
        }
    }

@Singleton
class RoomInventoryRepository
    @Inject
    constructor(
        private val masterItemDao: MasterItemDao,
        private val txnDao: InventoryTransactionDao,
        private val outboxWriter: OutboxWriter,
        private val clock: Clock,
    ) : InventoryRepository {
        override fun masterItems(businessId: String): Flow<List<MasterItem>> =
            masterItemDao.itemsForBusiness(businessId).map { list -> list.map { it.toModel() } }

        override suspend fun searchMasterItems(
            businessId: String,
            query: String,
        ): List<MasterItem> = masterItemDao.searchByName(businessId, query).map { it.toModel() }

        override suspend fun saveMasterItem(item: MasterItem) {
            masterItemDao.upsert(item.toEntity())
            outboxWriter.enqueue("master_items", item.id, OutboxOperation.UPSERT, json.encodeToString(MasterItem.serializer(), item))
        }

        override suspend fun deleteMasterItem(id: String) {
            val now = clock.instant()
            masterItemDao.tombstone(id, now)
            outboxWriter.enqueue("master_items", id, OutboxOperation.DELETE, tombstonePayload(id, now))
        }

        override fun transactionsForItem(
            businessId: String,
            masterItemId: String,
        ): Flow<List<InventoryTransaction>> = txnDao.transactionsForItem(businessId, masterItemId).map { list -> list.map { it.toModel() } }

        override suspend fun recordTransaction(txn: InventoryTransaction) {
            require(txn.quantity > 0) { "quantity must be positive" }
            txnDao.upsert(txn.toEntity())
            outboxWriter.enqueue(
                "inventory_transactions",
                txn.id,
                OutboxOperation.UPSERT,
                json.encodeToString(InventoryTransaction.serializer(), txn),
            )
        }

        override suspend fun openAddLotsFifo(
            businessId: String,
            masterItemId: String,
        ): List<InventoryTransaction> = txnDao.openAddLotsFifo(businessId, masterItemId).map { it.toModel() }

        override suspend fun currentStock(
            businessId: String,
            masterItemId: String,
        ): Double = txnDao.currentStock(businessId, masterItemId)
    }

@Singleton
class RoomBusinessRepository
    @Inject
    constructor(
        private val businessDao: BusinessDao,
        private val settingsDao: BusinessSettingsDao,
        private val outboxWriter: OutboxWriter,
    ) : BusinessRepository {
        override fun businesses(): Flow<List<Business>> = businessDao.allBusinesses().map { list -> list.map { it.toModel() } }

        override suspend fun business(id: String): Business? = businessDao.byId(id)?.toModel()

        override suspend fun saveBusiness(business: Business) {
            businessDao.upsert(business.toEntity())
            outboxWriter.enqueue("businesses", business.id, OutboxOperation.UPSERT, json.encodeToString(Business.serializer(), business))
        }

        override fun settings(businessId: String): Flow<BusinessSettings?> =
            settingsDao.settingsForBusiness(businessId).map { it?.toModel() }

        override suspend fun saveSettings(settings: BusinessSettings) {
            settingsDao.upsert(settings.toEntity())
            outboxWriter.enqueue(
                "business_settings",
                settings.businessId,
                OutboxOperation.UPSERT,
                json.encodeToString(BusinessSettings.serializer(), settings),
            )
        }
    }

@Singleton
class RoomMemberRepository
    @Inject
    constructor(
        private val memberDao: BusinessMemberDao,
        private val outboxWriter: OutboxWriter,
    ) : MemberRepository {
        override fun membersForBusiness(businessId: String): Flow<List<BusinessMember>> =
            memberDao.membersForBusiness(businessId).map { list -> list.map { it.toModel() } }

        override suspend fun memberForUser(
            businessId: String,
            userId: String,
        ): BusinessMember? = memberDao.memberForUser(businessId, userId)?.toModel()

        override suspend fun saveMember(member: BusinessMember) {
            memberDao.upsert(member.toEntity())
            outboxWriter.enqueue(
                "business_members",
                member.id,
                OutboxOperation.UPSERT,
                json.encodeToString(BusinessMember.serializer(), member),
            )
        }
    }
