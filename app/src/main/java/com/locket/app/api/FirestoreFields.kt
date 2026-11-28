package com.locket.app.api

import com.google.firestore.v1.Value
import com.google.firestore.v1.Value.ValueTypeCase
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.time.Instant

/** Read the fields used by both snapshot formats without converting a Listen document to REST JSON. */
internal interface FirestoreFields {
    fun text(name: String): String
    fun timestamp(name: String): Instant?
    fun boolean(name: String): Boolean?
    fun strings(name: String): List<String>
    fun map(name: String): FirestoreFields?
    fun maps(name: String): List<FirestoreFields>
    fun hasValue(name: String): Boolean
}

internal class ProtoFields(private val fields: Map<String, Value>) : FirestoreFields {
    fun hasField(name: String): Boolean = fields.containsKey(name)
    override fun text(name: String) = fields[name]?.stringValue.orEmpty()
    override fun timestamp(name: String): Instant? {
        val value = fields[name] ?: return null
        return when (value.valueTypeCase) {
            ValueTypeCase.TIMESTAMP_VALUE -> value.timestampValue.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) }
            ValueTypeCase.MAP_VALUE -> value.mapValue.fieldsMap["seconds"]
                ?.takeIf { it.valueTypeCase == ValueTypeCase.INTEGER_VALUE }?.let { Instant.ofEpochSecond(it.integerValue) }
            else -> null
        }
    }
    override fun boolean(name: String) = fields[name]?.takeIf { it.valueTypeCase == ValueTypeCase.BOOLEAN_VALUE }?.booleanValue
    override fun strings(name: String) = fields[name]?.arrayValue?.valuesList.orEmpty()
        .mapNotNull { it.takeIf { value -> value.valueTypeCase == ValueTypeCase.STRING_VALUE }?.stringValue }
    override fun map(name: String) = fields[name]?.takeIf { it.valueTypeCase == ValueTypeCase.MAP_VALUE }
        ?.let { ProtoFields(it.mapValue.fieldsMap) }
    override fun maps(name: String) = fields[name]?.arrayValue?.valuesList.orEmpty().mapNotNull {
        it.takeIf { value -> value.valueTypeCase == ValueTypeCase.MAP_VALUE }?.let { value -> ProtoFields(value.mapValue.fieldsMap) }
    }
    override fun hasValue(name: String) = fields[name]?.valueTypeCase?.let { it != ValueTypeCase.NULL_VALUE } == true

    fun overlays(): JsonArray? = fields["overlays"]?.takeIf { it.valueTypeCase == ValueTypeCase.ARRAY_VALUE }
        ?.let { plainValue(it, 0).asJsonArray }

    private fun plainValue(value: Value, depth: Int): JsonElement {
        require(depth <= 64) { "Firestore overlay nesting exceeds limit" }
        return when (value.valueTypeCase) {
            ValueTypeCase.STRING_VALUE -> JsonPrimitive(value.stringValue)
            ValueTypeCase.INTEGER_VALUE -> JsonPrimitive(value.integerValue)
            ValueTypeCase.DOUBLE_VALUE -> value.doubleValue.let { if (it.isFinite()) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
            ValueTypeCase.BOOLEAN_VALUE -> JsonPrimitive(value.booleanValue)
            ValueTypeCase.TIMESTAMP_VALUE -> JsonPrimitive(Instant.ofEpochSecond(value.timestampValue.seconds, value.timestampValue.nanos.toLong()).toString())
            ValueTypeCase.REFERENCE_VALUE -> JsonObject().apply { addProperty("referenceValue", value.referenceValue) }
            ValueTypeCase.BYTES_VALUE -> JsonObject().apply {
                addProperty("bytesValue", java.util.Base64.getEncoder().encodeToString(value.bytesValue.toByteArray()))
            }
            ValueTypeCase.GEO_POINT_VALUE -> JsonObject().apply {
                add("geoPointValue", JsonObject().apply {
                    addProperty("latitude", value.geoPointValue.latitude)
                    addProperty("longitude", value.geoPointValue.longitude)
                })
            }
            ValueTypeCase.MAP_VALUE -> JsonObject().apply {
                value.mapValue.fieldsMap.forEach { (key, field) -> add(key, plainValue(field, depth + 1)) }
            }
            ValueTypeCase.ARRAY_VALUE -> JsonArray().apply {
                value.arrayValue.valuesList.forEach { add(plainValue(it, depth + 1)) }
            }
            else -> JsonNull.INSTANCE
        }
    }
}

internal class RestFields(private val fields: JsonObject?) : FirestoreFields {
    override fun text(name: String): String = primitive(field(name), "stringValue")?.asString.orEmpty()
    override fun timestamp(name: String): Instant? {
        val value = field(name)
        primitive(value, "timestampValue")?.asString?.let { return runCatching { Instant.parse(it) }.getOrNull() }
        val seconds = primitive(objectField(objectField(objectField(value, "mapValue"), "fields"), "seconds"), "integerValue")
            ?.asString?.toLongOrNull() ?: return null
        return runCatching { Instant.ofEpochSecond(seconds) }.getOrNull()
    }
    override fun boolean(name: String): Boolean? = primitive(field(name), "booleanValue")?.asBoolean
    override fun strings(name: String): List<String> = array(name).mapNotNull {
        primitive(it.takeIf { item -> item.isJsonObject }?.asJsonObject, "stringValue")?.asString
    }
    override fun map(name: String): FirestoreFields? =
        objectField(objectField(field(name), "mapValue"), "fields")?.let(::RestFields)
    override fun maps(name: String): List<FirestoreFields> = array(name).mapNotNull {
        objectField(objectField(it.takeIf { item -> item.isJsonObject }?.asJsonObject, "mapValue"), "fields")?.let(::RestFields)
    }
    override fun hasValue(name: String): Boolean = field(name)?.let { !it.has("nullValue") } == true

    private fun field(name: String): JsonObject? = objectField(fields, name)
    private fun array(name: String): JsonArray =
        objectField(field(name), "arrayValue")?.get("values")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun objectField(obj: JsonObject?, name: String) = obj?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun primitive(obj: JsonObject?, name: String) = obj?.get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
}
