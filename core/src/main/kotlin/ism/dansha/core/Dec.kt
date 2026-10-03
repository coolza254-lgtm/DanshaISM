package ism.dansha.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal

/** ตัวเลขทศนิยมแบบแม่นยำ อ่าน/เขียนเป็นตัวเลข JSON ตรงตัว (เช่น 17.6, 1095, 33.519995) */
typealias Dec = @Serializable(with = DecSerializer::class) BigDecimal

object DecSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("ism.dansha.Dec", PrimitiveKind.DOUBLE)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: BigDecimal) {
        val text = value.toJsonNumber()
        if (encoder is JsonEncoder) encoder.encodeJsonElement(JsonUnquotedLiteral(text))
        else encoder.encodeString(text)
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = if (decoder is JsonDecoder) (decoder.decodeJsonElement() as JsonPrimitive).content
        else decoder.decodeString()
        return BigDecimal(text.trim())
    }
}

/** รูปแบบตัวเลขแบบ JavaScript: ไม่มีศูนย์ท้าย ไม่ใช้ exponent (1095, 17.6, 0) */
fun BigDecimal.toJsonNumber(): String {
    val s = stripTrailingZeros()
    return if (s.signum() == 0) "0" else s.toPlainString()
}
