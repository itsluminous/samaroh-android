package com.itsluminous.samaroh.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.itsluminous.samaroh.core.database.entity.BusinessEntity
import com.itsluminous.samaroh.core.database.entity.BusinessMemberEntity
import com.itsluminous.samaroh.core.database.entity.BusinessSettingsEntity
import com.itsluminous.samaroh.core.database.entity.GoogleAccountLinkEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface BusinessDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(business: BusinessEntity)

    @Query("SELECT * FROM businesses WHERE id = :id")
    suspend fun byId(id: String): BusinessEntity?

    @Query("SELECT * FROM businesses WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE ASC")
    fun allBusinesses(): Flow<List<BusinessEntity>>

    @Query("UPDATE businesses SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

@Dao
interface BusinessMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(member: BusinessMemberEntity)

    @Query("SELECT * FROM business_members WHERE business_id = :businessId AND deleted_at IS NULL ORDER BY display_name COLLATE NOCASE ASC")
    fun membersForBusiness(businessId: String): Flow<List<BusinessMemberEntity>>

    @Query("SELECT * FROM business_members WHERE business_id = :businessId AND user_id = :userId AND deleted_at IS NULL LIMIT 1")
    suspend fun memberForUser(
        businessId: String,
        userId: String,
    ): BusinessMemberEntity?

    @Query("UPDATE business_members SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun tombstone(
        id: String,
        at: Instant,
    )
}

@Dao
interface BusinessSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: BusinessSettingsEntity)

    @Query("SELECT * FROM business_settings WHERE business_id = :businessId")
    fun settingsForBusiness(businessId: String): Flow<BusinessSettingsEntity?>
}

@Dao
interface GoogleAccountLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: GoogleAccountLinkEntity)

    @Query("SELECT * FROM google_accounts WHERE user_id = :userId")
    fun linkForUser(userId: String): Flow<GoogleAccountLinkEntity?>

    @Query("DELETE FROM google_accounts WHERE user_id = :userId")
    suspend fun unlink(userId: String)
}
