package com.itsluminous.samaroh.core.google.drive

import com.itsluminous.samaroh.core.google.rest.GoogleApiException
import com.itsluminous.samaroh.core.google.rest.GoogleApiHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Low-level Drive REST v3 operations — kept behind an interface so upload logic is testable with fakes. */
interface DriveService {
    /** Id of a live (non-trashed) folder named [name] under [parentId], or null. Null parent = Drive root. */
    suspend fun findFolder(
        name: String,
        parentId: String?,
    ): String?

    /** Creates a folder named [name] under [parentId] and returns its id. */
    suspend fun createFolder(
        name: String,
        parentId: String?,
    ): String

    /** Multipart upload of [sourceFile] into folder [parentId]. */
    suspend fun uploadFile(
        name: String,
        mimeType: String,
        parentId: String,
        sourceFile: File,
    ): DriveFileRef

    /** Permanently deletes a file the app created (used by backup retention, best-effort). */
    suspend fun deleteFile(fileId: String)
}

private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name"

/** [DriveService] over the plain REST v3 endpoints using `drive.file` scope tokens. */
@Singleton
class RestDriveService
    @Inject
    constructor(
        private val http: GoogleApiHttp,
        private val tokenProvider: com.itsluminous.samaroh.core.google.auth.GoogleAccessTokenProvider,
    ) : DriveService {
        private val json = Json { ignoreUnknownKeys = true }

        private suspend fun token(): String =
            tokenProvider.accessToken() ?: throw DriveNotAvailableException("no google access token available")

        override suspend fun findFolder(
            name: String,
            parentId: String?,
        ): String? {
            val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
            val parentClause = if (parentId != null) " and '$parentId' in parents" else ""
            val query = "name = '$escaped' and mimeType = '$FOLDER_MIME' and trashed = false$parentClause"
            val url = "$FILES_URL?q=${URLEncoder.encode(query, Charsets.UTF_8.name())}&fields=files(id,name)&pageSize=1"
            val response = http.request("GET", url, token())
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            val files = json.parseToJsonElement(response.body).jsonObject["files"]?.jsonArray ?: return null
            return files
                .firstOrNull()
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content
        }

        override suspend fun createFolder(
            name: String,
            parentId: String?,
        ): String {
            val metadata =
                buildJsonObject {
                    put("name", name)
                    put("mimeType", FOLDER_MIME)
                    if (parentId != null) put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
                }
            val response =
                http.request(
                    "POST",
                    "$FILES_URL?fields=id",
                    token(),
                    contentType = "application/json; charset=UTF-8",
                    body = metadata.toString().toByteArray(),
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            return json
                .parseToJsonElement(response.body)
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        }

        override suspend fun uploadFile(
            name: String,
            mimeType: String,
            parentId: String,
            sourceFile: File,
        ): DriveFileRef {
            val metadata =
                buildJsonObject {
                    put("name", name)
                    put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
                }
            val boundary = "samaroh-${System.currentTimeMillis()}"
            val body =
                ByteArrayOutputStream().use { out ->
                    fun writeText(text: String) = out.write(text.toByteArray())
                    writeText("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
                    writeText(metadata.toString())
                    writeText("\r\n--$boundary\r\nContent-Type: $mimeType\r\n\r\n")
                    sourceFile.inputStream().use { it.copyTo(out) }
                    writeText("\r\n--$boundary--")
                    out.toByteArray()
                }
            val response =
                http.request(
                    "POST",
                    UPLOAD_URL,
                    token(),
                    contentType = "multipart/related; boundary=$boundary",
                    body = body,
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            val obj = json.parseToJsonElement(response.body).jsonObject
            return DriveFileRef(
                fileId = obj.getValue("id").jsonPrimitive.content,
                fileName = obj["name"]?.jsonPrimitive?.content ?: name,
            )
        }

        override suspend fun deleteFile(fileId: String) {
            val response = http.request("DELETE", "$FILES_URL/$fileId", token())
            if (!response.isSuccess && response.code != 404) throw GoogleApiException(response.code, response.body)
        }
    }
