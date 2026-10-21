package com.kavach.caller.data.notion

import com.kavach.caller.data.diagnostics.LogEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Maps a [LogEvent] onto the row shape of the Notion database
 * "Kavach Diagnostics — Events".
 *
 * Property names here must match the Notion schema exactly, so this is the one
 * place that knows the mapping. Adding a column in Notion means adding it here.
 */
object NotionEventMapper {

    /** The data source the rows go into. */
    const val DATA_SOURCE_ID = "1d7ea74f-fe10-4ce1-9262-f085eafccc26"

    /** The database those rows live in, used as an alternate parent shape. */
    const val DATABASE_ID = "e8af8aa3f9d044f7896f0525caa1a088"

    /** Notion requires a versioned API; this workspace exposes data sources. */
    const val NOTION_VERSION = "2025-09-03"

    fun toPage(
        event: LogEvent,
        userCode: String,
        appVersion: String,
        os: String,
        network: String,
        parentType: String = "data_source_id",
        parentId: String = DATA_SOURCE_ID,
    ): JsonObject {
        val properties = buildJsonObject {
            put("Log", title(titleFor(event)))
            put("Timestamp", date(event.iso8601()))
            put("Event Name", select(event.eventName))
            put("Level", select(event.logLevel.name.lowercase(Locale.US)))
            put("Platform", select("android"))
            put("Release", select("production"))
            put("Device", select("mobile"))
            put("App Version", richText(appVersion))
            put("OS", richText(os))
            put("Network", richText(network))
            put("Route", richText(event.screenOrRoute))
            put("Session", richText(event.sessionId))
            put("Attempt", richText(event.attemptId.orEmpty()))
            put("User Code", richText(userCode))
            put("Details", richText(detailsFor(event)))
            put("Schema", richText("1.0.0"))
        }
        return buildJsonObject {
            put(
                "parent",
                buildJsonObject {
                    put("type", JsonPrimitive(parentType))
                    put(parentType, JsonPrimitive(parentId))
                },
            )
            put("properties", properties)
        }
    }

    /** A short human line for the row title, so the table reads at a glance. */
    private fun titleFor(event: LogEvent): String {
        val head = event.eventName
        val key = event.properties.entries.take(2).joinToString(" ") { "${it.key}=${it.value}" }
        return if (key.isBlank()) head else "$head · $key"
    }

    private fun detailsFor(event: LogEvent): String =
        event.properties.entries.joinToString("  ") { "${it.key}=${it.value}" }

    // ---------------- Notion property shapes ----------------

    private fun title(text: String): JsonObject = buildJsonObject {
        put("title", JsonArray(listOf(textObject(text))))
    }

    private fun richText(text: String): JsonObject = buildJsonObject {
        put("rich_text", JsonArray(listOf(textObject(text))))
    }

    private fun textObject(text: String): JsonObject = buildJsonObject {
        put(
            "text",
            buildJsonObject {
                // Notion caps a rich_text block at 2000 characters.
                put("content", JsonPrimitive(text.take(1900)))
            },
        )
    }

    private fun select(name: String): JsonObject = buildJsonObject {
        put("select", buildJsonObject { put("name", JsonPrimitive(name)) })
    }

    private fun date(iso: String): JsonObject = buildJsonObject {
        put("date", buildJsonObject { put("start", JsonPrimitive(iso)) })
    }

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
}
