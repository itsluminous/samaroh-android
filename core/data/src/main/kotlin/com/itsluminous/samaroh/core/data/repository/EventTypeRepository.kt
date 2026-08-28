package com.itsluminous.samaroh.core.data.repository

import android.content.Context
import android.content.res.Configuration
import com.itsluminous.samaroh.core.data.sync.OutboxOperation
import com.itsluminous.samaroh.core.data.sync.OutboxWriter
import com.itsluminous.samaroh.core.database.dao.EventTypeDao
import com.itsluminous.samaroh.core.database.entity.EventTypeEntity
import com.itsluminous.samaroh.core.model.EventType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Event-type preset persistence (ADR-032): per-business, user-managed booking event
 * types backed by the synced `event_types` table (shared migration 006). Additive
 * extension of the frozen repository contracts.
 */

interface EventTypeRepository {
    /** Live presets of a business in picker/manage order (sort_order, then label). */
    fun presets(businessId: String): Flow<List<EventType>>

    /** One-shot variant of [presets]. */
    suspend fun presetsOnce(businessId: String): List<EventType>

    suspend fun preset(id: String): EventType?

    /** Upserts locally and enqueues an outbox push. */
    suspend fun savePreset(preset: EventType)

    /** Tombstones locally and enqueues a delete push (old bookings keep their recorded type). */
    suspend fun deletePreset(id: String)

    /**
     * Whether another LIVE preset of the business already uses [label]
     * (case-insensitive — stricter than the server's case-sensitive unique index, so a
     * near-duplicate never reaches a confusing server rejection).
     */
    suspend fun labelInUse(
        businessId: String,
        label: String,
        excludingId: String? = null,
    ): Boolean

    /**
     * Seeds the built-in presets from `shared/event-types.json` for a NEWLY CREATED
     * business (ADR-032). No-op when the business already has any preset rows (live or
     * tombstoned) — existing businesses were seeded by server migration 006 and must
     * never be reseeded.
     */
    suspend fun seedDefaults(businessId: String)
}

/** One seed entry resolved from `shared/event-types.json` (the seed template). */
data class EventTypeSeed(
    val label: String,
    val icon: String,
    val color: String?,
    val sortOrder: Int,
)

/** Source of the seed template; interface so tests can fake it without assets. */
interface EventTypeSeedTemplate {
    fun seeds(): List<EventTypeSeed>
}

/**
 * Parses `event-types.json` (copied from the shared submodule into this module's
 * generated assets at build time) and resolves each entry's `label_key` from the string
 * catalog in ENGLISH — deliberately locale-independent, matching what server migration
 * 006 seeded for existing businesses, so every business's built-in rows carry identical
 * labels regardless of the creating device's language (see shared
 * docs/event-type-presets.md; ADR-032 records the choice).
 */
@Singleton
class AssetEventTypeSeedTemplate
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : EventTypeSeedTemplate {
        @Serializable
        private data class EventTypesFile(
            @SerialName("event_types") val eventTypes: List<Entry>,
        )

        @Serializable
        private data class Entry(
            val key: String,
            val emoji: String,
            @SerialName("label_key") val labelKey: String,
            val color: String? = null,
        )

        private val json = Json { ignoreUnknownKeys = true }

        private val parsed: List<EventTypeSeed> by lazy {
            val raw =
                context.assets
                    .open("event-types.json")
                    .bufferedReader()
                    .use { it.readText() }
            val english =
                context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
                )
            json
                .decodeFromString(EventTypesFile.serializer(), raw)
                .eventTypes
                .mapIndexedNotNull { index, entry ->
                    // A future template key without a catalog string is skipped, not crashed on.
                    labelResFor(entry.key)?.let { res ->
                        EventTypeSeed(
                            label = english.getString(res),
                            icon = entry.emoji,
                            color = entry.color,
                            sortOrder = index,
                        )
                    }
                }
        }

        override fun seeds(): List<EventTypeSeed> = parsed

        private fun labelResFor(key: String): Int? =
            when (key) {
                "engagement" -> com.itsluminous.samaroh.core.i18n.R.string.booking_event_type_engagement
                "tilak" -> com.itsluminous.samaroh.core.i18n.R.string.booking_event_type_tilak
                "wedding" -> com.itsluminous.samaroh.core.i18n.R.string.booking_event_type_wedding
                "room_booking" -> com.itsluminous.samaroh.core.i18n.R.string.booking_event_type_room_booking
                "birthday" -> com.itsluminous.samaroh.core.i18n.R.string.booking_event_type_birthday
                "anniversary" -> com.itsluminous.samaroh.core.i18n.R.string.booking_event_type_anniversary
                "custom" -> com.itsluminous.samaroh.core.i18n.R.string.booking_event_type_custom
                else -> null
            }
    }

@Singleton
class RoomEventTypeRepository
    @Inject
    constructor(
        private val eventTypeDao: EventTypeDao,
        private val outboxWriter: OutboxWriter,
        private val seedTemplate: EventTypeSeedTemplate,
        private val clock: Clock,
    ) : EventTypeRepository {
        private val json = Json { encodeDefaults = true }

        override fun presets(businessId: String): Flow<List<EventType>> =
            eventTypeDao.presetsForBusiness(businessId).map { list -> list.map { it.toModel() } }

        override suspend fun presetsOnce(businessId: String): List<EventType> =
            eventTypeDao.presetsForBusinessOnce(businessId).map { it.toModel() }

        override suspend fun preset(id: String): EventType? = eventTypeDao.byId(id)?.toModel()

        override suspend fun savePreset(preset: EventType) {
            eventTypeDao.upsert(preset.toEntity())
            outboxWriter.enqueue(
                "event_types",
                preset.id,
                OutboxOperation.UPSERT,
                json.encodeToString(EventType.serializer(), preset),
            )
        }

        override suspend fun deletePreset(id: String) {
            val now = clock.instant()
            eventTypeDao.tombstone(id, now)
            outboxWriter.enqueue("event_types", id, OutboxOperation.DELETE, eventTypeTombstonePayload(id, now))
        }

        override suspend fun labelInUse(
            businessId: String,
            label: String,
            excludingId: String?,
        ): Boolean = eventTypeDao.countLabelUses(businessId, label.trim(), excludingId.orEmpty()) > 0

        override suspend fun seedDefaults(businessId: String) {
            // ANY existing row (live or tombstoned) means the business was already
            // seeded — by this client, another device, or server migration 006.
            if (eventTypeDao.countAllForBusiness(businessId) > 0) return
            val now = clock.instant()
            seedTemplate.seeds().forEach { seed ->
                savePreset(
                    EventType(
                        id = UUID.randomUUID().toString(),
                        businessId = businessId,
                        label = seed.label,
                        icon = seed.icon,
                        color = seed.color,
                        sortOrder = seed.sortOrder,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

internal fun EventTypeEntity.toModel() = EventType(id, businessId, label, icon, color, sortOrder, createdAt, updatedAt, deletedAt)

internal fun EventType.toEntity() = EventTypeEntity(id, businessId, label, icon, color, sortOrder, createdAt, updatedAt, deletedAt)

private fun eventTypeTombstonePayload(
    id: String,
    at: Instant,
): String =
    Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("id", id)
            put("deleted_at", at.toString())
        },
    )
