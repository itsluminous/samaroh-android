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

    /** Row lookup for the sync no-op re-apply guard (ADR-051, additive). */
    @Query("SELECT * FROM business_members WHERE id = :id")
    suspend fun byId(id: String): BusinessMemberEntity?

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

    /** Row lookup for the sync no-op re-apply guard (ADR-051, additive). */
    @Query("SELECT * FROM business_settings WHERE business_id = :businessId")
    suspend fun byId(businessId: String): BusinessSettingsEntity?
}

@Dao
interface GoogleAccountLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: GoogleAccountLinkEntity)

    @Query("SELECT * FROM google_accounts WHERE user_id = :userId")
    fun linkForUser(userId: String): Flow<GoogleAccountLinkEntity?>

    /** Row lookup for the sync no-op re-apply guard (ADR-051, additive). */
    @Query("SELECT * FROM google_accounts WHERE user_id = :userId")
    suspend fun byId(userId: String): GoogleAccountLinkEntity?

    /**
     * Any linked Google account on this device (ADR-066, additive). The periodic-calendar
     * reconcile runs at process ON_START, BEFORE the Supabase session restore completes —
     * a session-derived link state races to "not linked" there, so the check must be pure
     * local Room. Sign-out wipes local data (ADR-040), so any row belongs to the current user.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM google_accounts)")
    suspend fun hasAnyLink(): Boolean

    @Query("DELETE FROM google_accounts WHERE user_id = :userId")
    suspend fun unlink(userId: String)
}
