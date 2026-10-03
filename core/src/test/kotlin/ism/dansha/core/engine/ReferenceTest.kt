package ism.dansha.core.engine

import ism.dansha.core.DanshaData
import ism.dansha.core.DataFile
import ism.dansha.core.Dates
import ism.dansha.core.Ids
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.fail

/**
 * เทียบตัวคำนวณ Kotlin กับระบบเดิม (reference/engine/ (JavaScript)) ด้วยข้อมูลและเวลาเดียวกัน — ต้องเท่ากันทุกช่อง
 *
 * - reference/sample-data.json (ข้อมูลสมมติ) ทดสอบเสมอ
 * - ข้อมูลจริงนอก repo: ./gradlew -Pdansha.android=false :core:test -Ddansha.data=/path/file.json
 * - เลือกวันเอง: -Ddansha.dates=2026-10-03T12:00,2026-11-02T09:00
 */
class ReferenceTest {
    private val sample = Reference.dir?.let { File(it, "sample-data.json") }

    private fun requireNode(): Boolean {
        if (Reference.dir != null && Reference.nodeAvailable()) return true
        if (System.getenv("CI") != null) fail("ต้องมี node และโฟลเดอร์ reference/ เพื่อเทียบผลกับระบบเดิม")
        println("ข้ามการเทียบกับระบบเดิม: ไม่พบ node")
        return false
    }

    /** วันที่ทดสอบ: ทุก 2 วัน + วันสำคัญ (14/15/16 ออกบิล, 1/2 ครบกำหนด, 24–26 SPayLater) ช่วง ก.ย. 69 – ก.พ. 70 */
    private fun defaultTimes(): List<String> {
        System.getProperty("dansha.dates")?.let { return it.split(",").map(String::trim).filter(String::isNotEmpty) }
        val out = sortedSetOf<String>()
        var d = LocalDate.parse("2026-09-14")
        val end = LocalDate.parse("2027-02-20")
        while (d <= end) {
            if (d.dayOfMonth % 2 == 0 || d.dayOfMonth in listOf(1, 14, 15, 16, 25, 31)) out += "${d}T12:00"
            d = d.plusDays(1)
        }
        out += listOf("2026-10-03T00:00", "2026-10-03T23:59", "2026-10-16T09:00", "2026-11-02T09:00")
        return out.toList()
    }

    private fun readSteps(d: DanshaData, times: List<String>): JsonArray = buildJsonArray {
        val ascend = d.accounts.filter(::isEngineAccount).map { it.id }
        times.forEach { t ->
            add(buildJsonObject { put("now", t) })
            fun get(vararg kv: Pair<String, Any>) = add(buildJsonObject {
                put("get", JsonObject(kv.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }))
            })
            get("action" to "bootstrap")
            get("action" to "debts")
            get("action" to "outlook", "count" to 6)
            val day = LocalDate.parse(t.substring(0, 10))
            ascend.forEach { id ->
                get("action" to "debtQuote", "account_id" to id)
                get("action" to "debtQuote", "account_id" to id, "date" to day.plusDays(17).toString(), "amount" to 2000)
                get("action" to "debtQuote", "account_id" to id, "date" to day.toString(), "amount" to 50.5)
            }
            get("action" to "checkPurchase", "price" to 1290, "method" to "full")
            get("action" to "checkPurchase", "price" to 20000, "method" to "full")
            get("action" to "checkPurchase", "price" to 3000, "method" to "spaylater", "tenor" to 3, "installment" to 1050)
            get("action" to "checkPurchase", "price" to 500, "method" to "spaylater", "tenor" to 4)
            get("action" to "checkPurchase", "price" to 199, "method" to "spaylater", "tenor" to 1)
            get("action" to "portfolio")
            get("action" to "shopeeStats")
            get("action" to "upcomingDues", "within" to 10)
            get("action" to "reminders")
        }
    }

    /** รันขั้นตอนฝั่ง Kotlin แล้วเทียบกับผลของ JS ทีละขั้น */
    private fun compare(name: String, data: DanshaData, steps: JsonArray, expected: JsonArray): List<String> {
        val diffs = ArrayList<String>()
        var d = data
        var now = Reference.setNow("2026-10-03T12:00")
        var counter = 0
        Ids.suffix = { (++counter).toString(16).padStart(12, '0') }
        val savedClock = Dates.clock
        try {
            steps.forEachIndexed { i, el ->
                if (diffs.size >= 40) return@forEachIndexed
                val step = el.jsonObject
                val exp = expected[i].jsonObject
                val label = "$name ขั้นที่ $i ${step.toString().take(140)}"
                when {
                    "now" in step -> now = Reference.setNow((step["now"] as JsonPrimitive).content)
                    "get" in step -> {
                        val actual: JsonElement = try {
                            JsonObject(mapOf("ok" to JsView.get(d, step["get"]!!.jsonObject, now)))
                        } catch (e: EngineException) {
                            JsonObject(mapOf("error" to JsonPrimitive(e.message)))
                        }
                        JsCompare.diff(exp, actual).forEach { diffs += "$label → $it" }
                    }
                    "post" in step -> {
                        val actual: JsonElement = try {
                            val r = Store.run(d) { JsView.post(this, step["post"]!!.jsonObject) }
                            d = r.data
                            JsonObject(mapOf("ok" to JsonPrimitive(true), "db" to JsView.db(d)))
                        } catch (e: EngineException) {
                            JsonObject(mapOf("error" to JsonPrimitive(e.message), "db" to JsView.db(d)))
                        }
                        JsCompare.diff(exp, actual).forEach { diffs += "$label → $it" }
                    }
                }
            }
        } finally {
            Dates.clock = savedClock
            Ids.suffix = { java.util.UUID.randomUUID().toString().replace("-", "").take(12) }
        }
        return diffs
    }

    private fun check(name: String, file: File, steps: JsonArray) {
        val data = DataFile.parse(file.readText())
        val expected = Reference.run(file, steps)
        val diffs = compare(name, data, steps, expected)
        if (diffs.isNotEmpty()) fail("ผลไม่ตรงกับระบบเดิม ${diffs.size}+ จุด:\n" + diffs.joinToString("\n"))
        println("$name: ตรงกับระบบเดิมทุกช่อง (${steps.size} ขั้น)")
    }

    @Test
    fun sampleDataMatchesReferenceOnManyDates() {
        if (!requireNode()) return
        val data = DataFile.parse(sample!!.readText())
        check("sample", sample, readSteps(data, defaultTimes()))
    }

    @Test
    fun realDataMatchesReferenceOnManyDates() {
        val path = System.getProperty("dansha.data") ?: return
        if (!requireNode()) return
        val file = File(path)
        check("real", file, readSteps(DataFile.parse(file.readText()), defaultTimes()))
    }

    @Test
    fun editsMatchReference() {
        if (!requireNode()) return
        check("edits", sample!!, ReferenceScenarios.edits())
    }
}
