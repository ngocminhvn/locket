package com.locket.app.api

import com.google.firestore.v1.FirestoreGrpc
import com.google.firestore.v1.ListenRequest
import com.google.firestore.v1.ListenResponse
import com.google.firestore.v1.StructuredQuery
import com.google.firestore.v1.Target
import com.google.firestore.v1.TargetChange
import com.google.firestore.v1.Document
import com.google.firestore.v1.Value
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.grpc.Context
import io.grpc.Metadata
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer

/** Firestore bidirectional Listen RPC, authenticated by the existing ID token.
 * Query removals (window eviction) are not document deletes. */
internal class FirestoreListen {
    data class Delta(val documents: List<JsonObject> = emptyList(), val deletedIds: Set<String> = emptySet(), val ready: Boolean = false)

    fun listen(database: String, parent: String, collection: String, orderField: String, limit: Int?, token: String) = callbackFlow {
        val channel = OkHttpChannelBuilder.forAddress("firestore.googleapis.com", 443).useTransportSecurity().build()
        val rpcContext = Context.current().withCancellation()
        val headers = Metadata().apply {
            put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer $token")
            put(Metadata.Key.of("google-cloud-resource-prefix", Metadata.ASCII_STRING_MARSHALLER), database)
        }
        val initial = linkedMapOf<String, JsonObject>()
        val canonicalIds = mutableMapOf<String, String>()
        var current = false
        val activeNames = mutableSetOf<String>()
        fun offer(delta: Delta) {
            if (trySend(delta).isFailure) close(IllegalStateException("Firestore listener buffer closed"))
        }
        val observer = object : StreamObserver<ListenResponse> {
            override fun onNext(value: ListenResponse) {
                try {
                    when (value.responseTypeCase) {
                        ListenResponse.ResponseTypeCase.DOCUMENT_CHANGE -> {
                            val doc = value.documentChange.document
                            activeNames.add(doc.name)
                            val json = documentJson(doc)
                            canonicalIds[doc.name] = doc.fieldsMap["canonical_uid"]?.stringValue?.takeIf { it.isNotBlank() }
                                ?: doc.name.substringAfterLast('/')
                            if (current) offer(Delta(documents = listOf(json))) else initial[doc.name] = json
                        }
                        ListenResponse.ResponseTypeCase.DOCUMENT_DELETE -> {
                            val name = value.documentDelete.document
                            initial.remove(name)
                            activeNames.remove(name)
                            offer(Delta(deletedIds = setOf(canonicalIds.remove(name) ?: name.substringAfterLast('/'))))
                        }
                        ListenResponse.ResponseTypeCase.DOCUMENT_REMOVE -> {
                            val name = value.documentRemove.document
                            initial.remove(name)
                            activeNames.remove(name)
                            if (limit == null) offer(Delta(deletedIds = setOf(canonicalIds.remove(name) ?: name.substringAfterLast('/'))))
                        }
                        ListenResponse.ResponseTypeCase.TARGET_CHANGE -> {
                            val change = value.targetChange
                            when (change.targetChangeType) {
                                TargetChange.TargetChangeType.CURRENT -> {
                                    current = true
                                    offer(Delta(documents = initial.values.toList(), ready = true))
                                    initial.clear()
                                }
                                TargetChange.TargetChangeType.RESET -> { current = false; initial.clear(); activeNames.clear() }
                                TargetChange.TargetChangeType.REMOVE -> close(IllegalStateException("Firestore Listen: " + change.cause.message))
                                else -> Unit
                            }
                        }
                        ListenResponse.ResponseTypeCase.FILTER -> {
                            // Re-open to recover existence-filter mismatches.
                            if (value.filter.count != activeNames.size)
                                close(IllegalStateException("Firestore requested target reconciliation"))
                        }
                        else -> Unit
                    }
                } catch (e: Exception) { close(e) }
                catch (e: LinkageError) { close(e) }
                catch (e: AssertionError) { close(IllegalStateException("Firestore response processing failed", e)) }
            }
            override fun onError(t: Throwable) { close(t) }
            override fun onCompleted() { close() }
        }
        try {
            val query = StructuredQuery.newBuilder()
                .addFrom(StructuredQuery.CollectionSelector.newBuilder().setCollectionId(collection))
                .addOrderBy(StructuredQuery.Order.newBuilder().setField(StructuredQuery.FieldReference.newBuilder().setFieldPath(orderField))
                    .setDirection(StructuredQuery.Direction.DESCENDING))
                .apply {
                    if (orderField != "__name__") addOrderBy(StructuredQuery.Order.newBuilder()
                        .setField(StructuredQuery.FieldReference.newBuilder().setFieldPath("__name__"))
                        .setDirection(StructuredQuery.Direction.DESCENDING))
                    if (limit != null) setLimit(com.google.protobuf.Int32Value.of(limit))
                }.build()
            val request = ListenRequest.newBuilder().setDatabase(database)
                .setAddTarget(Target.newBuilder().setTargetId(1)
                    .setQuery(Target.QueryTarget.newBuilder().setParent(parent).setStructuredQuery(query))).build()
            // Initialize descriptor accessors on the caller before entering gRPC's
            // asynchronous executor. The request is real, not fabricated data.
            request.allFields
            Target.getDefaultInstance().allFields
            StructuredQuery.getDefaultInstance().allFields
            ListenResponse.getDefaultInstance().allFields
            Document.getDefaultInstance().allFields
            Value.getDefaultInstance().allFields
            TargetChange.getDefaultInstance().allFields
            FirestoreGrpc.getListenMethod()
            rpcContext.run {
                val stream = FirestoreGrpc.newStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .withDeadlineAfter(50, java.util.concurrent.TimeUnit.MINUTES).listen(observer)
                stream.onNext(request)
            }
            awaitClose()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { close(e) }
        catch (e: LinkageError) { close(e) }
        catch (e: AssertionError) { close(IllegalStateException("Firestore transport initialization failed", e)) }
        finally { rpcContext.cancel(null); channel.shutdownNow() }
    }.buffer(256)

    private fun documentJson(doc: Document): JsonObject = JsonObject().apply {
        addProperty("name", doc.name)
        add("fields", fieldsJson(doc.fieldsMap))
    }
    private fun fieldsJson(fields: Map<String, Value>): JsonObject = JsonObject().apply {
        fields.forEach { (name, value) -> add(name, valueJson(value)) }
    }
    private fun valueJson(v: Value): JsonObject = JsonObject().apply {
        when (v.valueTypeCase) {
            Value.ValueTypeCase.STRING_VALUE -> addProperty("stringValue", v.stringValue)
            Value.ValueTypeCase.INTEGER_VALUE -> addProperty("integerValue", v.integerValue.toString())
            Value.ValueTypeCase.DOUBLE_VALUE -> addProperty("doubleValue", v.doubleValue)
            Value.ValueTypeCase.BOOLEAN_VALUE -> addProperty("booleanValue", v.booleanValue)
            Value.ValueTypeCase.REFERENCE_VALUE -> addProperty("referenceValue", v.referenceValue)
            Value.ValueTypeCase.TIMESTAMP_VALUE -> addProperty("timestampValue",
                java.time.Instant.ofEpochSecond(v.timestampValue.seconds, v.timestampValue.nanos.toLong()).toString())
            Value.ValueTypeCase.MAP_VALUE -> add("mapValue", JsonObject().apply { add("fields", fieldsJson(v.mapValue.fieldsMap)) })
            Value.ValueTypeCase.ARRAY_VALUE -> add("arrayValue", JsonObject().apply {
                add("values", JsonArray().apply { v.arrayValue.valuesList.forEach { add(valueJson(it)) } })
            })
            else -> add("nullValue", com.google.gson.JsonNull.INSTANCE)
        }
    }
}
