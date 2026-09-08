package com.itsluminous.samaroh.core.google.calendar

import com.itsluminous.samaroh.core.google.auth.GoogleAccessTokenProvider
import com.itsluminous.samaroh.core.google.drive.DriveNotAvailableException
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import com.itsluminous.samaroh.core.google.rest.GoogleApiHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal view of a remote event — enough for migration + duplicate repair (ADR-046). */
data class GcalEventRef(
    val id: String,
    val summary: String,
    val description: String,
    /** `extendedProperties.private` map; empty for events pushed before ADR-046. */
    val privateProperties: Map<String, String>,
)

/** Low-level Calendar v3 operations — behind an interface for testable sync logic. */
interface CalendarService {
    /** Inserts [event] into [calendarId] and returns the created event id. */
    suspend fun insertEvent(
        calendarId: String,
        event: GcalEvent,
    ): String

    suspend fun updateEvent(
        calendarId: String,
        eventId: String,
        event: GcalEvent,
    )

    /** Deleting an already-gone event is NOT an error (idempotent cleanup). */
    suspend fun deleteEvent(
        calendarId: String,
        eventId: String,
    )

    /**
     * The calendar's display name (summary), or null when it no longer exists or is
     * inaccessible (ADR-046 find-or-create + ADR-048 rename detection).
     */
    suspend fun calendarSummary(calendarId: String): String?

    /** Creates a secondary calendar named [summary] and returns its id (`calendar.app.created`). */
    suspend fun createCalendar(summary: String): String

    /**
     * Renames an app-created calendar (Calendar v3 `calendars.patch`, ADR-048 — the
     * calendar carries the business name). Throws [GoogleApiException] on rejection
     * (e.g. 403 when the grant does not cover calendar metadata) — callers fall back
     * to create-new + migrate.
     */
    suspend fun renameCalendar(
        calendarId: String,
        summary: String,
    )

    /**
     * Lists events on [calendarId], optionally filtered by one private extended property
     * (`"key=value"`) and/or an RFC3339 time window. Paginates internally;
     * cancelled/deleted events are excluded.
     */
    suspend fun listEvents(
        calendarId: String,
        privateExtendedProperty: String? = null,
        timeMin: String? = null,
        timeMax: String? = null,
    ): List<GcalEventRef>

    /**
     * Moves an event to another calendar (Calendar v3 `events.move`); the event id is
     * preserved. Throws [GoogleApiException] when move is not permitted — callers fall
     * back to delete+recreate.
     */
    suspend fun moveEvent(
        sourceCalendarId: String,
        eventId: String,
        destinationCalendarId: String,
    )
}

private const val BASE_URL = "https://www.googleapis.com/calendar/v3"

/**
 * Off-by-default request-body diagnostics (extends the ADR-046 logcat-evidence idea):
 * `adb shell setprop log.tag.SamarohGcalBody VERBOSE` makes insert/update log the full
 * event JSON they send — the only way to inspect the pushed description on a device
 * without Calendar API credentials. Bodies carry booking PII, hence the explicit gate.
 */
private const val BODY_TAG = "SamarohGcalBody"

private fun logBody(
    method: String,
    url: String,
    body: String,
) {
    if (android.util.Log.isLoggable(BODY_TAG, android.util.Log.VERBOSE)) {
        android.util.Log.v(BODY_TAG, "$method $url\n$body")
    }
}

/** [CalendarService] over the Calendar v3 REST endpoints. */
@Singleton
class RestCalendarService
    @Inject
    constructor(
        private val http: GoogleApiHttp,
        private val tokenProvider: GoogleAccessTokenProvider,
    ) : CalendarService {
        private val json = Json { ignoreUnknownKeys = true }

        private suspend fun token(): String =
            tokenProvider.accessToken() ?: throw DriveNotAvailableException("no google access token available")

        override suspend fun insertEvent(
            calendarId: String,
            event: GcalEvent,
        ): String {
            logBody("POST", "$BASE_URL/calendars/${encode(calendarId)}/events", event.toRequestBody())
            val response =
                http.request(
                    "POST",
                    "$BASE_URL/calendars/${encode(calendarId)}/events",
                    token(),
                    contentType = "application/json; charset=UTF-8",
                    body = event.toRequestBody().toByteArray(),
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            return json
                .parseToJsonElement(response.body)
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        }

        override suspend fun updateEvent(
            calendarId: String,
            eventId: String,
            event: GcalEvent,
        ) {
            // PATCH, not PUT (ADR-047): a full-resource PUT clears every writable field
            // the body omits (user-set reminders, colour, visibility); PATCH updates
            // only the fields the app owns.
            logBody("PATCH", "$BASE_URL/calendars/${encode(calendarId)}/events/${encode(eventId)}", event.toRequestBody())
            val response =
                http.request(
                    "PATCH",
                    "$BASE_URL/calendars/${encode(calendarId)}/events/${encode(eventId)}",
                    token(),
                    contentType = "application/json; charset=UTF-8",
                    body = event.toRequestBody().toByteArray(),
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
        }

        override suspend fun deleteEvent(
            calendarId: String,
            eventId: String,
        ) {
            val response =
                http.request("DELETE", "$BASE_URL/calendars/${encode(calendarId)}/events/${encode(eventId)}", token())
            // 404/410 = already gone; deletion is idempotent.
            if (!response.isSuccess && response.code != 404 && response.code != 410) {
                throw GoogleApiException(response.code, response.body)
            }
        }

        override suspend fun calendarSummary(calendarId: String): String? {
            val response = http.request("GET", "$BASE_URL/calendars/${encode(calendarId)}", token())
            if (response.code == 404 || response.code == 410 || response.code == 403) return null
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            return json
                .parseToJsonElement(response.body)
                .jsonObject["summary"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        }

        override suspend fun renameCalendar(
            calendarId: String,
            summary: String,
        ) {
            val body = buildJsonObject { put("summary", summary) }.toString()
            val response =
                http.request(
                    "PATCH",
                    "$BASE_URL/calendars/${encode(calendarId)}",
                    token(),
                    contentType = "application/json; charset=UTF-8",
                    body = body.toByteArray(),
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
        }

        override suspend fun createCalendar(summary: String): String {
            val body = buildJsonObject { put("summary", summary) }.toString()
            val response =
                http.request(
                    "POST",
                    "$BASE_URL/calendars",
                    token(),
                    contentType = "application/json; charset=UTF-8",
                    body = body.toByteArray(),
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            return json
                .parseToJsonElement(response.body)
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        }

        override suspend fun listEvents(
            calendarId: String,
            privateExtendedProperty: String?,
            timeMin: String?,
            timeMax: String?,
        ): List<GcalEventRef> {
            val events = mutableListOf<GcalEventRef>()
            var pageToken: String? = null
            do {
                val query =
                    buildList {
                        add("maxResults=2500")
                        add("singleEvents=true")
                        add("showDeleted=false")
                        privateExtendedProperty?.let { add("privateExtendedProperty=${encode(it)}") }
                        timeMin?.let { add("timeMin=${encode(it)}") }
                        timeMax?.let { add("timeMax=${encode(it)}") }
                        pageToken?.let { add("pageToken=${encode(it)}") }
                    }.joinToString("&")
                val response =
                    http.request("GET", "$BASE_URL/calendars/${encode(calendarId)}/events?$query", token())
                if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
                val root = json.parseToJsonElement(response.body).jsonObject
                root["items"]?.jsonArray?.forEach { item ->
                    val obj = item.jsonObject
                    if (obj["status"]?.jsonPrimitive?.content == "cancelled") return@forEach
                    events +=
                        GcalEventRef(
                            id = obj.getValue("id").jsonPrimitive.content,
                            summary = obj["summary"]?.jsonPrimitive?.content.orEmpty(),
                            description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
                            privateProperties =
                                obj["extendedProperties"]
                                    ?.jsonObject
                                    ?.get("private")
                                    ?.jsonObject
                                    ?.mapValues { (_, v) -> v.jsonPrimitive.content }
                                    .orEmpty(),
                        )
                }
                pageToken = root["nextPageToken"]?.jsonPrimitive?.content
            } while (pageToken != null)
            return events
        }

        override suspend fun moveEvent(
            sourceCalendarId: String,
            eventId: String,
            destinationCalendarId: String,
        ) {
            val url =
                "$BASE_URL/calendars/${encode(sourceCalendarId)}/events/${encode(eventId)}/move" +
                    "?destination=${encode(destinationCalendarId)}"
            val response = http.request("POST", url, token())
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
        }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }

/** Calendar v3 event resource body. */
internal fun GcalEvent.toRequestBody(): String =
    buildJsonObject {
        put("summary", summary)
        put("description", description)
        // The unused date variant is EXPLICITLY nulled: a PATCH (ADR-047) only clears
        // fields the body names, so a booking switching timed ↔ all-day must null the
        // other pair or the event keeps both and the API rejects it. Inserts ignore
        // the nulls.
        if (isAllDay) {
            putJsonObject("start") {
                put("date", startDate.toString())
                put("dateTime", JsonNull)
                put("timeZone", JsonNull)
            }
            putJsonObject("end") {
                put("date", endDateExclusive.toString())
                put("dateTime", JsonNull)
                put("timeZone", JsonNull)
            }
        } else {
            putJsonObject("start") {
                put("date", JsonNull)
                put("dateTime", startDateTime!!.toRfc3339Local())
                put("timeZone", timeZone)
            }
            putJsonObject("end") {
                put("date", JsonNull)
                put("dateTime", endDateTime!!.toRfc3339Local())
                put("timeZone", timeZone)
            }
        }
        if (privateProperties.isNotEmpty()) {
            putJsonObject("extendedProperties") {
                putJsonObject("private") {
                    for ((key, value) in privateProperties) put(key, value)
                }
            }
        }
    }.toString()

/**
 * Calendar v3 `dateTime` values must be RFC3339, which makes SECONDS mandatory.
 * `LocalDateTime.toString()` omits `:00` seconds (ISO-8601 allows it, RFC3339 does
 * not), and Google rejects the truncated form with a bare HTTP 400 `badRequest` —
 * which is exactly what every whole-minute booking produced (the "calendar exists but
 * stays empty" incident, 2026-09-08). The zone offset is intentionally absent: the
 * body carries an explicit `timeZone` field, which the API documents as the case
 * where the offset may be omitted.
 */
private val RFC3339_LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

internal fun LocalDateTime.toRfc3339Local(): String = format(RFC3339_LOCAL)
