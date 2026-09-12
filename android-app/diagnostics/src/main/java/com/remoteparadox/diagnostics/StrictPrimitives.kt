package com.remoteparadox.diagnostics

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal typealias WireLong = @Serializable(with = StrictLongSerializer::class) Long
internal typealias WireInt = @Serializable(with = StrictIntSerializer::class) Int
internal typealias WireBoolean = @Serializable(with = StrictBooleanSerializer::class) Boolean

private val integer = Regex("-?(0|[1-9][0-9]*)")
private fun Decoder.primitive(): JsonPrimitive {
    val value = (this as JsonDecoder).decodeJsonElement() as? JsonPrimitive
    if (value == null || value.isString) throw SerializationException("Expected typed diagnostic value")
    return value
}

internal object StrictLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("DiagnosticLong", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        if (decoder !is JsonDecoder) return decoder.decodeLong()
        val value = decoder.primitive()
        return value.longOrNull?.takeIf { integer.matches(value.content) }
            ?: throw SerializationException("Expected diagnostic integer")
    }
}

internal object StrictIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("DiagnosticInt", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val value = decoder.primitive()
        return value.intOrNull?.takeIf { integer.matches(value.content) }
            ?: throw SerializationException("Expected diagnostic integer")
    }
}

internal object StrictBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("DiagnosticBoolean", PrimitiveKind.BOOLEAN)
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder !is JsonDecoder) return decoder.decodeBoolean()
        return decoder.primitive().booleanOrNull ?: throw SerializationException("Expected diagnostic boolean")
    }
}
