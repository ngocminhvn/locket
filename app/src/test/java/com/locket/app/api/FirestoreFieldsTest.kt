package com.locket.app.api

import com.google.firestore.v1.ArrayValue
import com.google.firestore.v1.MapValue
import com.google.firestore.v1.Value
import com.google.protobuf.NullValue
import com.google.protobuf.Timestamp
import com.locket.app.model.CaptionOverlays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FirestoreFieldsTest {
    @Test
    fun readsNestedOverlaysAndTimestampsDirectlyFromProtobuf() {
        val review = map(
            "overlay_type" to text("caption"),
            "data" to map(
                "type" to text("review"),
                "payload" to map("rating" to Value.newBuilder().setDoubleValue(4.5).build(),
                    "future" to array(text("🍜"), Value.newBuilder().setIntegerValue(42).build()))
            )
        )
        val timestamp = Timestamp.newBuilder().setSeconds(1_700_000_000).setNanos(123_456_789).build()
        val fields = ProtoFields(mapOf(
            "overlays" to array(review),
            "date" to Value.newBuilder().setTimestampValue(timestamp).build(),
            "deleted_moment" to Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
        ))
        val overlays = fields.overlays()!!
        val normalized = CaptionOverlays.fromPayload(overlays.toString(), overlays)
        assertEquals(4.5f, normalized!!.rating, 0f)
        assertEquals(123_456_789, fields.timestamp("date")!!.nano)
        assertEquals("🍜", overlays[0].asJsonObject.getAsJsonObject("data").getAsJsonObject("payload")
            .getAsJsonArray("future")[0].asString)
        assertFalse(fields.hasValue("deleted_moment"))
    }

    private fun text(value: String) = Value.newBuilder().setStringValue(value).build()
    private fun array(vararg values: Value) = Value.newBuilder()
        .setArrayValue(ArrayValue.newBuilder().addAllValues(values.asList())).build()
    private fun map(vararg fields: Pair<String, Value>) = Value.newBuilder()
        .setMapValue(MapValue.newBuilder().putAllFields(fields.toMap())).build()
}
