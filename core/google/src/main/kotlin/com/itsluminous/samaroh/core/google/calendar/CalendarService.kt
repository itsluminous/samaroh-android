package com.itsluminous.samaroh.core.google.calendar

import com.itsluminous.samaroh.core.google.auth.GoogleAccessTokenProvider
import com.itsluminous.samaroh.core.google.drive.DriveNotAvailableException
import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import com.itsluminous.samaroh.core.google.rest.GoogleApiHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Low-level Calendar v3 event operations — behind an interface for testable sync logic. */
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
}

private const val BASE_URL = "https://www.googleapis.com/calendar/v3/calendars"

/** [CalendarService] over the Calendar v3 REST endpoints using `calendar.events` scope tokens. */
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
            val response =
                http.request(
                    "POST",
                    "$BASE_URL/${encode(calendarId)}/events",
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
            val response =
                http.request(
                    "PUT",
                    "$BASE_URL/${encode(calendarId)}/events/${encode(eventId)}",
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
            val response = http.request("DELETE", "$BASE_URL/${encode(calendarId)}/events/${encode(eventId)}", token())
            // 404/410 = already gone; deletion is idempotent.
            if (!response.isSuccess && response.code != 404 && response.code != 410) {
                throw GoogleApiException(response.code, response.body)
            }
        }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }

/** Calendar v3 event resource body. */
internal fun GcalEvent.toRequestBody(): String =
    buildJsonObject {
        put("summary", summary)
        put("description", description)
        if (isAllDay) {
            putJsonObject("start") { put("date", startDate.toString()) }
            putJsonObject("end") { put("date", endDateExclusive.toString()) }
        } else {
            putJsonObject("start") {
                put("dateTime", startDateTime.toString())
                put("timeZone", timeZone)
            }
            putJsonObject("end") {
                put("dateTime", endDateTime.toString())
                put("timeZone", timeZone)
            }
        }
    }.toString()
