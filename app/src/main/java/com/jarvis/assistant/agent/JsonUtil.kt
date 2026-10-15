package com.jarvis.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/** Converts org.json values into plain Kotlin maps/lists so SDKs can serialize them. */
fun jsonToPlain(value: Any?): Any? = when (value) {
    is JSONObject -> value.keys().asSequence().associateWith { jsonToPlain(value.get(it)) }
    is JSONArray -> (0 until value.length()).map { jsonToPlain(value.get(it)) }
    JSONObject.NULL -> null
    else -> value
}

/** Small DSL for tool schemas: obj("x" to str("desc"), required = listOf("x")). */
fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
    JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        .put("required", JSONArray(required))

fun str(description: String, enum: List<String>? = null): JSONObject =
    JSONObject().put("type", "string").put("description", description)
        .apply { if (enum != null) put("enum", JSONArray(enum)) }

fun int(description: String): JSONObject = JSONObject().put("type", "integer").put("description", description)

fun bool(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)
