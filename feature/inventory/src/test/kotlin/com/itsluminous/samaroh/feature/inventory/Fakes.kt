package com.itsluminous.samaroh.feature.inventory

import com.itsluminous.samaroh.core.data.repository.BusinessRepository
import com.itsluminous.samaroh.core.data.repository.CurrentInventoryLine
import com.itsluminous.samaroh.core.data.repository.InventoryOverviewRepository
import com.itsluminous.samaroh.core.data.repository.InventoryRepository
import com.itsluminous.samaroh.core.model.Business
import com.itsluminous.samaroh.core.model.BusinessSettings
import com.itsluminous.samaroh.core.model.InventoryTransaction
import com.itsluminous.samaroh.core.model.MasterItem
import com.itsluminous.samaroh.core.testing.Fixtures
import com.itsluminous.samaroh.feature.inventory.image.ItemImageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** In-memory [InventoryRepository] + [InventoryOverviewRepository] for ViewModel tests. */
class FakeInventoryRepository :
    InventoryRepository,
    InventoryOverviewRepository {
    val masterItemsFlow = MutableStateFlow<List<MasterItem>>(emptyList())
    val linesFlow = MutableStateFlow<List<CurrentInventoryLine>>(emptyList())
    val transactionsFlow = MutableStateFlow<List<InventoryTransaction>>(emptyList())
    val recordedTransactions = mutableListOf<InventoryTransaction>()
    val savedItems = mutableListOf<MasterItem>()
    val deletedItemIds = mutableListOf<String>()
    val stockByItem = mutableMapOf<String, Double>()
    val canDeleteByItem = mutableMapOf<String, Boolean>()

    override fun masterItems(businessId: String): Flow<List<MasterItem>> = masterItemsFlow

    override suspend fun searchMasterItems(
        businessId: String,
        query: String,
    ): List<MasterItem> = masterItemsFlow.value.filter { it.name.contains(query, ignoreCase = true) }

    override suspend fun saveMasterItem(item: MasterItem) {
        savedItems += item
        masterItemsFlow.value = masterItemsFlow.value.filterNot { it.id == item.id } + item
    }

    override suspend fun deleteMasterItem(id: String) {
        deletedItemIds += id
        masterItemsFlow.value = masterItemsFlow.value.filterNot { it.id == id }
    }

    override fun transactionsForItem(
        businessId: String,
        masterItemId: String,
    ): Flow<List<InventoryTransaction>> = transactionsFlow

    override suspend fun recordTransaction(txn: InventoryTransaction) {
        recordedTransactions += txn
    }

    override suspend fun openAddLotsFifo(
        businessId: String,
        masterItemId: String,
    ): List<InventoryTransaction> = emptyList()

    override suspend fun currentStock(
        businessId: String,
        masterItemId: String,
    ): Double = stockByItem[masterItemId] ?: 0.0

    override fun currentInventory(businessId: String): Flow<List<CurrentInventoryLine>> = linesFlow

    override suspend fun canDeleteMasterItem(id: String): Boolean = canDeleteByItem[id] ?: true

    /** FIFO cost returned for remove transactions; adds derive qty × unit price. */
    var removeCostPaise: Long = 0L

    override suspend fun recordTransactionForValue(txn: InventoryTransaction): Long {
        recordedTransactions += txn
        return when (txn.transactionType) {
            com.itsluminous.samaroh.core.model.TxnType.ADD -> (txn.quantity * txn.unitPricePaise).toLong()
            com.itsluminous.samaroh.core.model.TxnType.REMOVE -> removeCostPaise
        }
    }
}

/** Single-business fake for the interim active-business resolution. */
class FakeActiveBusinessProvider(
    fixedBusiness: Business = Fixtures.business(),
) : com.itsluminous.samaroh.core.data.session.ActiveBusinessProvider {
    override val activeBusiness: Flow<Business?> = flowOf(fixedBusiness)
}

class FakeBusinessRepository(
    private val fixedBusiness: Business = Fixtures.business(),
) : BusinessRepository {
    override fun businesses(): Flow<List<Business>> = flowOf(listOf(fixedBusiness))

    override suspend fun business(id: String): Business? = fixedBusiness.takeIf { it.id == id }

    override suspend fun saveBusiness(business: Business) = Unit

    override fun settings(businessId: String): Flow<BusinessSettings?> = flowOf(null)

    override suspend fun saveSettings(settings: BusinessSettings) = Unit
}

/** Records requests and returns a deterministic path without touching the filesystem. */
class FakeItemImageStore : ItemImageStore {
    val requests = mutableListOf<String>()
    val deletedItemIds = mutableListOf<String>()

    override suspend fun compressItemImage(
        source: android.graphics.Bitmap,
        itemId: String,
    ): String {
        requests += itemId
        return "fake-images/$itemId.webp"
    }

    override suspend fun deleteItemImage(itemId: String) {
        deletedItemIds += itemId
    }
}
