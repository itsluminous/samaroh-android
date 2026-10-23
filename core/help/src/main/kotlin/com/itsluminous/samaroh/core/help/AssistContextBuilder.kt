package com.itsluminous.samaroh.core.help

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What `Activity.onProvideAssistContent` hands the assistant (ADR-108, contract
 * `shared/docs/assistant-context.md`): the public help page of the current screen in the
 * app language and a compact JSON-LD `WebPage` carrying that screen's tasks.
 *
 * @property pageUrl `setWebUri` value — the screen's Pages URL, or the index for an unknown screen.
 * @property jsonLd `setStructuredData` value — compact JSON, ≤ [AssistContextBuilder.MAX_JSON_LD_CHARS].
 */
data class AssistContext(
    val pageUrl: String,
    val jsonLd: String,
)

/**
 * Pure builder of [AssistContext] from the guide — no Android types, so the payload is
 * unit-tested for content, language, size cap and (by construction) the absence of user
 * data: the ONLY inputs are the public guide, a screen id and a language. Nothing from
 * Room, the session or the business ever reaches this class.
 */
object AssistContextBuilder {
    /** Contract cap: the structured data rides in a Bundle; keep it far from the binder limit. */
    const val MAX_JSON_LD_CHARS = 8 * 1024

    /** `samaroh.app` — reserved for a future web mirror; always this value here. */
    const val APP = HELP_PLATFORM_ANDROID

    private const val SCHEMA_CONTEXT = "https://schema.org"
    private const val SCHEMA_TYPE = "WebPage"

    /** Compact serializer (no pretty printing — contract). */
    private val json = Json

    /**
     * Builds the context for [screenId] in [lang].
     *
     * @param screenId a guide screen id, or anything else (including null) for the index fallback.
     * @param granted drops tasks whose `permission` the member lacks (hide-not-grey; the
     *   assistant should not explain a button the member does not have). Defaults to owner.
     */
    fun build(
        guide: HelpGuide,
        screenId: String?,
        lang: String,
        granted: GrantedPermissions = GrantedPermissions.OWNER,
    ): AssistContext {
        val indexUrl = guide.indexUrl(lang)
        val screen = guide.forPlatform(APP).firstOrNull { it.id == screenId && granted.holds(it.permission) }
        if (screen == null) {
            return AssistContext(
                pageUrl = indexUrl,
                jsonLd =
                    json.encodeToString(
                        JsonObject.serializer(),
                        envelope(lang, indexUrl, null, null, null, indexUrl, JsonArray(emptyList())),
                    ),
            )
        }
        val pageUrl = guide.pageUrl(lang, screen.id)
        var tasks: List<TaskPayload> =
            screen.tasks
                .filter { granted.holds(it.permission) }
                .map { task -> TaskPayload(task.id, task.title[lang], task.steps.map { it[lang] }) }
        var serialized = serialize(lang, pageUrl, screen, indexUrl, tasks)
        // Deterministic truncation (contract): drop step text from the LAST task that still
        // has steps, one task at a time; once no task has steps, drop whole tasks from the
        // end. name/description/url/screenId/language/guideIndexUrl are never dropped.
        while (serialized.length > MAX_JSON_LD_CHARS && tasks.isNotEmpty()) {
            val lastWithSteps = tasks.indexOfLast { it.steps.isNotEmpty() }
            tasks =
                if (lastWithSteps >= 0) {
                    tasks.mapIndexed { index, task -> if (index == lastWithSteps) task.copy(steps = emptyList()) else task }
                } else {
                    tasks.dropLast(1)
                }
            serialized = serialize(lang, pageUrl, screen, indexUrl, tasks)
        }
        return AssistContext(pageUrl = pageUrl, jsonLd = serialized)
    }

    private data class TaskPayload(
        val id: String,
        val title: String,
        val steps: List<String>,
    )

    private fun serialize(
        lang: String,
        pageUrl: String,
        screen: HelpScreen,
        indexUrl: String,
        tasks: List<TaskPayload>,
    ): String {
        val taskArray =
            buildJsonArray {
                tasks.forEach { task ->
                    add(
                        buildJsonObject {
                            put("id", task.id)
                            put("title", task.title)
                            put("steps", JsonArray(task.steps.map(::JsonPrimitive)))
                        },
                    )
                }
            }
        return json.encodeToString(
            JsonObject.serializer(),
            envelope(lang, pageUrl, screen.id, screen.title[lang], screen.summary[lang], indexUrl, taskArray),
        )
    }

    private fun envelope(
        lang: String,
        url: String,
        screenId: String?,
        name: String?,
        description: String?,
        indexUrl: String,
        tasks: JsonArray,
    ): JsonObject =
        buildJsonObject {
            put("@context", SCHEMA_CONTEXT)
            put("@type", SCHEMA_TYPE)
            if (name != null) put("name", name)
            if (description != null) put("description", description)
            put("url", url)
            put("inLanguage", lang)
            put(
                "samaroh",
                buildJsonObject {
                    put("screenId", screenId?.let(::JsonPrimitive) ?: JsonNull)
                    put("language", lang)
                    put("app", APP)
                    put("guideIndexUrl", indexUrl)
                    put("tasks", tasks)
                },
            )
        }
}
