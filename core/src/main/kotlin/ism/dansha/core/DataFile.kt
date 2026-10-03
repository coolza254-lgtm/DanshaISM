package ism.dansha.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal

class DataFileException(message: String) : Exception(message)

/**
 * อ่าน/เขียนไฟล์ข้อมูลรูปแบบ dansha-data/1
 *
 * { "format": "dansha-data/1", "exported_at": "...", "config": {...}, "tables": { "accounts": [...], ... } }
 *
 * ตอนอ่านจะแปลงชนิดข้อมูลให้ตรงคอลัมน์แบบเดียวกับ normalizeDb ของระบบเดิม
 * (ตัวเลขว่าง = null, ข้อความว่าง = "", boolean ว่าง = false, วันที่ตัดเหลือ yyyy-MM-dd) และตัดแถวที่ไม่มีคีย์ทิ้ง
 */
object DataFile {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // เขียนครบทุกคอลัมน์ เหมือนไฟล์เดิม
    }

    fun parse(text: String): DanshaData {
        val root = try {
            json.parseToJsonElement(text.removePrefix("﻿")).jsonObject
        } catch (e: Exception) {
            throw DataFileException("ไฟล์นี้ไม่ใช่ JSON ที่อ่านได้")
        }
        val format = (root["format"] as? JsonPrimitive)?.content
        if (format != null && format != Schema.FORMAT) {
            throw DataFileException("รูปแบบไฟล์ไม่รองรับ: $format (ต้องเป็น ${Schema.FORMAT})")
        }
        // รองรับทั้งแบบ { config, tables: {...} } และแบบเก่า { config, accounts: [...], ... }
        val tables = (root["tables"] as? JsonObject) ?: root
        if (format == null && Schema.TABLES.none { tables[it] is JsonArray }) {
            throw DataFileException("ไม่พบข้อมูล 断捨ISM ในไฟล์นี้")
        }
        val config = (root["config"] as? JsonObject)?.mapValues { (_, v) ->
            when (v) {
                is JsonNull -> ""
                is JsonPrimitive -> v.content
                else -> v.toString()
            }
        } ?: emptyMap()

        return DanshaData(
            config = config,
            accounts = table(tables, "accounts", Account.serializer()),
            transactions = table(tables, "transactions", Transaction.serializer()),
            bills = table(tables, "bills", Bill.serializer()),
            billTemplates = table(tables, "billTemplates", BillTemplate.serializer()),
            debts = table(tables, "debts", Debt.serializer()),
            shopee = table(tables, "shopee", ShopeeOrder.serializer()),
            port = table(tables, "port", PortTxn.serializer()),
            categories = table(tables, "categories", Category.serializer()),
            fx = table(tables, "fx", FxRate.serializer()),
            prices = table(tables, "prices", Price.serializer()),
        )
    }

    /** เขียนไฟล์ส่งออก: หนึ่งแถวต่อหนึ่งบรรทัด อ่านง่ายและเทียบ diff ได้ */
    fun write(data: DanshaData, exportedAt: String, source: String): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": ").append(str(Schema.FORMAT)).append(",\n")
        sb.append("  \"exported_at\": ").append(str(exportedAt)).append(",\n")
        sb.append("  \"source\": ").append(str(source)).append(",\n")
        sb.append("  \"config\": {")
        data.config.entries.forEachIndexed { i, (k, v) ->
            sb.append(if (i == 0) "\n" else ",\n").append("    ").append(str(k)).append(": ").append(str(v))
        }
        sb.append(if (data.config.isEmpty()) "},\n" else "\n  },\n")
        sb.append("  \"tables\": {\n")
        val parts = listOf(
            rows("accounts", data.accounts, Account.serializer()),
            rows("transactions", data.transactions, Transaction.serializer()),
            rows("bills", data.bills, Bill.serializer()),
            rows("billTemplates", data.billTemplates, BillTemplate.serializer()),
            rows("debts", data.debts, Debt.serializer()),
            rows("shopee", data.shopee, ShopeeOrder.serializer()),
            rows("port", data.port, PortTxn.serializer()),
            rows("categories", data.categories, Category.serializer()),
            rows("fx", data.fx, FxRate.serializer()),
            rows("prices", data.prices, Price.serializer()),
        )
        sb.append(parts.joinToString(",\n"))
        sb.append("\n  }\n}\n")
        return sb.toString()
    }

    private fun str(s: String) = JsonPrimitive(s).toString()

    private fun <T> rows(name: String, list: List<T>, ser: KSerializer<T>): String {
        if (list.isEmpty()) return "    ${str(name)}: []"
        return list.joinToString(",\n", prefix = "    ${str(name)}: [\n", postfix = "\n    ]") {
            "      " + json.encodeToString(ser, it)
        }
    }

    private fun <T> table(tables: JsonObject, name: String, ser: KSerializer<T>): List<T> {
        val arr = tables[name] as? JsonArray ?: return emptyList()
        val key = Schema.keyOf(name)
        return arr.mapIndexedNotNull { i, el ->
            val row = el as? JsonObject ?: return@mapIndexedNotNull null
            val k = row[key]
            if (k == null || k is JsonNull || (k is JsonPrimitive && k.content.isEmpty())) return@mapIndexedNotNull null
            try {
                json.decodeFromJsonElement(ser, normalizeRow(row, ser))
            } catch (e: DataFileException) {
                throw DataFileException("ตาราง $name แถวที่ ${i + 1}: ${e.message}")
            } catch (e: Exception) {
                throw DataFileException("ตาราง $name แถวที่ ${i + 1}: อ่านไม่ได้ (${e.message})")
            }
        }
    }

    /** แปลงแถว JSON หนึ่งแถวเป็นแถวของตาราง (แปลงชนิดแบบเดียวกับตอนนำเข้า) */
    fun <T> decodeRow(row: JsonObject, ser: KSerializer<T>): T = json.decodeFromJsonElement(ser, normalizeRow(row, ser))

    /** แถวของตาราง → JSON (ครบทุกคอลัมน์) */
    fun <T> encodeRow(row: T, ser: KSerializer<T>): JsonElement = json.encodeToJsonElement(ser, row)

    /** แปลงค่าในแถวให้ตรงชนิดคอลัมน์ (normCell_ ของระบบเดิม) */
    private fun normalizeRow(row: JsonObject, ser: KSerializer<*>): JsonObject {
        val d = ser.descriptor
        val out = LinkedHashMap<String, JsonElement>()
        for (i in 0 until d.elementsCount) {
            val col = d.getElementName(i)
            val kind = d.getElementDescriptor(i).kind
            out[col] = normCell(col, kind, row[col])
        }
        return JsonObject(out)
    }

    private fun normCell(col: String, kind: Any, v: JsonElement?): JsonElement {
        val isEmpty = v == null || v is JsonNull || (v is JsonPrimitive && v.isString && v.content.isEmpty())
        if (v != null && v !is JsonPrimitive && v !is JsonNull) throw DataFileException("คอลัมน์ $col ต้องเป็นค่าเดี่ยว")
        val p = v as? JsonPrimitive
        return when {
            col in Schema.BOOLEAN || kind == PrimitiveKind.BOOLEAN ->
                JsonPrimitive(!isEmpty && (p!!.booleanOrNull == true || p.content.equals("true", ignoreCase = true)))
            col in Schema.NUMERIC || kind == PrimitiveKind.INT || kind == PrimitiveKind.DOUBLE -> {
                if (isEmpty) return JsonNull
                val n = try {
                    BigDecimal(p!!.content.trim().replace(",", ""))
                } catch (e: NumberFormatException) {
                    throw DataFileException("คอลัมน์ $col ต้องเป็นตัวเลข แต่ได้ \"${p!!.content}\"")
                }
                if (kind == PrimitiveKind.INT) {
                    val int = try {
                        n.stripTrailingZeros().intValueExact()
                    } catch (e: ArithmeticException) {
                        throw DataFileException("คอลัมน์ $col ต้องเป็นจำนวนเต็ม แต่ได้ ${p!!.content}")
                    }
                    JsonPrimitive(int)
                } else {
                    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                    JsonUnquotedLiteral(n.toJsonNumber())
                }
            }
            col in Schema.DATE_COLS -> {
                if (isEmpty) return JsonPrimitive("")
                val s = p!!.content.trim()
                if (!Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(s)) {
                    throw DataFileException("คอลัมน์ $col ต้องเป็นวันที่ yyyy-MM-dd แต่ได้ \"$s\"")
                }
                JsonPrimitive(s.substring(0, 10))
            }
            // ข้อความต้องเป็นข้อความเสมอ (เช่น "7/11" ห้ามกลายเป็นวันที่)
            else -> JsonPrimitive(if (isEmpty) "" else p!!.content)
        }
    }
}
