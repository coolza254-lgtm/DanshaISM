package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.Bill
import ism.dansha.core.BillTemplate
import ism.dansha.core.Category
import ism.dansha.core.DanshaData
import ism.dansha.core.DataFile
import ism.dansha.core.Dates
import ism.dansha.core.Debt
import ism.dansha.core.PortTxn
import ism.dansha.core.ShopeeOrder
import ism.dansha.core.Transaction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** เทียบ JSON สองก้อนแบบละเอียด: ตัวเลขต้องเท่ากันทุกบิต, key ที่ไม่มี = null */
object JsCompare {
    fun diff(expected: JsonElement?, actual: JsonElement?, path: String = "$", out: MutableList<String> = ArrayList(), limit: Int = 30): List<String> {
        if (out.size >= limit) return out
        val e = expected ?: JsonNull
        val a = actual ?: JsonNull
        when {
            e is JsonObject && a is JsonObject -> (e.keys + a.keys).forEach { k -> diff(e[k], a[k], "$path.$k", out, limit) }
            e is JsonArray && a is JsonArray -> {
                if (e.size != a.size) out += "$path: จำนวนแถว JS=${e.size} Kotlin=${a.size}"
                for (i in 0 until minOf(e.size, a.size)) diff(e[i], a[i], "$path[$i]", out, limit)
            }
            e is JsonNull && a is JsonNull -> Unit
            e is JsonPrimitive && a is JsonPrimitive && e !is JsonNull && a !is JsonNull -> {
                val en = if (e.isString) null else e.doubleOrNull
                val an = if (a.isString) null else a.doubleOrNull
                val same = when {
                    en != null && an != null -> en == an
                    else -> e.isString == a.isString && e.content == a.content
                }
                if (!same) out += "$path: JS=$e Kotlin=$a"
            }
            else -> out += "$path: JS=$e Kotlin=$a"
        }
        return out
    }
}

/** แปลงผลของ Kotlin ให้อยู่ในรูปเดียวกับผลของ engine/ (JavaScript) */
object JsView {
    val json = Json { encodeDefaults = true; explicitNulls = true }

    private fun <T> enc(ser: KSerializer<T>, v: T): JsonElement = json.encodeToJsonElement(ser, v)
    private fun <T> rows(list: List<T>, ser: KSerializer<T>): JsonElement = JsonArray(list.map { DataFile.encodeRow(it, ser) })

    fun state(st: DebtState): JsonElement = when (st) {
        is AscendState -> enc(AscendState.serializer(), st)
        is SplState -> enc(SplState.serializer(), st)
    }

    fun states(m: Map<String, DebtState>) = JsonObject(m.mapValues { state(it.value) })

    fun accounts(list: List<AccountView>) = JsonArray(list.map { v ->
        val row = DataFile.encodeRow(v.account, Account.serializer()).jsonObject
        JsonObject(row + buildMap {
            v.balance?.let { put("balance", JsonPrimitive(it)) }
            v.used?.let { put("used", JsonPrimitive(it)) }
            v.available?.let { put("available", JsonPrimitive(it)) }
            v.accruedInterest?.let { put("accruedInterest", JsonPrimitive(it)) }
        })
    })

    fun copay(c: CopayStatus?): JsonElement {
        if (c == null) return buildJsonObject { put("enabled", false) }
        val cfg = enc(CopayConfig.serializer(), c.config).jsonObject
        return JsonObject(cfg + mapOf(
            "today" to JsonPrimitive(c.today),
            "active" to JsonPrimitive(c.active),
            "usage" to enc(CopayUsage.serializer(), c.usage),
            "used" to enc(ListSerializer(CopayUsed.serializer()), c.used),
        ))
    }

    fun bootstrap(d: DanshaData, ov: Overview): JsonElement = JsonObject(mapOf(
        "debt" to states(ov.debt),
        "config" to JsonObject(ov.config.mapValues { JsonPrimitive(it.value) }),
        "accounts" to accounts(ov.accounts),
        "plan" to enc(PlanSummary.serializer(), ov.plan),
        "categories" to rows(d.categories, Category.serializer()),
        "copay" to copay(ov.copay),
        "port" to (ov.port?.let { enc(PortSummary.serializer(), it) } ?: JsonNull),
        "fx" to JsonObject(ov.fx.mapValues { JsonPrimitive(it.value) }),
        "payCycle" to JsonObject(mapOf("current" to JsonPrimitive(ov.payCycle), "range" to enc(DateRange.serializer(), ov.payCycleRange))),
        "recentTransactions" to rows(ov.recentTransactions, Transaction.serializer()),
        "serverTime" to JsonPrimitive(Dates.nowIso()),
    ))

    fun db(d: DanshaData): JsonElement = JsonObject(mapOf(
        "config" to JsonObject(d.config.mapValues { JsonPrimitive(it.value) }),
        "tables" to JsonObject(mapOf(
            "accounts" to rows(d.accounts, Account.serializer()),
            "transactions" to rows(d.transactions, Transaction.serializer()),
            "bills" to rows(d.bills, Bill.serializer()),
            "debts" to rows(d.debts, Debt.serializer()),
            "shopee" to rows(d.shopee, ShopeeOrder.serializer()),
            "port" to rows(d.port, PortTxn.serializer()),
            "categories" to rows(d.categories, Category.serializer()),
            "billTemplates" to rows(d.billTemplates, BillTemplate.serializer()),
            "fx" to rows(d.fx, ism.dansha.core.FxRate.serializer()),
            "prices" to rows(d.prices, ism.dansha.core.Price.serializer()),
        )),
    ))

    /** ตอบคำขอ get แบบเดียวกับ doGet ของระบบเดิม */
    fun get(d: DanshaData, p: JsonObject, now: LocalDateTime): JsonElement {
        fun s(k: String) = (p[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        fun n(k: String) = (p[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { if (it.isString) jsNumber(it.content) else it.doubleOrNull }
        return when (s("action")) {
            "bootstrap" -> bootstrap(d, Engine.overview(d, now))
            "debts" -> JsonObject(mapOf(
                "states" to states(Engine.debtStates(d, now.toLocalDate().toString(), true)),
                "rows" to rows(d.debts.filter { it.status != "deleted" }, Debt.serializer()),
            ))
            "outlook" -> enc(ListSerializer(CycleOutlook.serializer()), Engine.outlook(d, n("count")?.toInt()?.takeIf { it != 0 } ?: 6, now))
            "debtQuote" -> enc(DebtTracker.Quote.serializer(), Engine.debtQuote(d, s("account_id").orEmpty(), s("date"), n("amount")))
            "checkPurchase" -> enc(PurchaseCheck.serializer(), Engine.checkPurchase(d, PurchaseQuery(
                price = n("price") ?: 0.0, method = s("method").orEmpty(), tenor = n("tenor")?.toInt(),
                installment = n("installment"), annualRate = n("annual_rate"), date = s("date"),
            ), now))
            "portfolio" -> {
                val pf = Engine.portfolio(d)
                JsonObject(mapOf(
                    "holdings" to enc(ListSerializer(Holding.serializer()), pf.holdings),
                    "summary" to enc(PortSummary.serializer(), pf.summary),
                    "fx" to JsonObject(Balances.fxMap(d.fx).mapValues { JsonPrimitive(it.value) }),
                ))
            }
            "shopeeStats" -> enc(ShopeeStats.serializer(), Engine.shopeeStats(d))
            "upcomingDues" -> enc(ListSerializer(DueItem.serializer()), Reminders.upcomingDues(d, now.toLocalDate().toString(), n("within")?.toInt() ?: 0))
            "reminders" -> {
                val today = now.toLocalDate().toString()
                val items = Reminders.reminderItems(d, Engine.config(d), today)
                if (items.isEmpty()) JsonNull else JsonObject(mapOf(
                    "count" to JsonPrimitive(items.size),
                    "subject" to JsonPrimitive("[断捨ISM] ใกล้ครบกำหนด ${items.size} รายการ · รวม " + Reminders.money(items.fold(0.0) { s, x -> s + x.amount })),
                ))
            }
            else -> error("unknown action ${s("action")}")
        }
    }

    // ---------- post ----------

    private fun <T> row(obj: JsonObject, ser: KSerializer<T>): T = DataFile.decodeRow(obj, ser)

    /** ทำคำขอ post แบบเดียวกับ doPost ของระบบเดิม (update: รวมกับแถวเดิมก่อน เหมือนตัวรัน JS) */
    fun post(store: Store, body: JsonObject) {
        fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        fun n(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { if (it.isString) jsNumber(it.content) else it.doubleOrNull }
        val action = s(body, "action")
        val table = s(body, "table")
        val id = s(body, "id").orEmpty()
        val data = (body["data"] as? JsonObject) ?: JsonObject(emptyMap())
        val copay = (data["copay"] as? JsonPrimitive)?.let { if (it.isString) it.content == "true" else it.booleanOrNull }

        fun merged(current: JsonElement?): JsonObject = JsonObject((current?.jsonObject ?: emptyMap()) + data)
        fun created(defaultActive: Boolean = false): JsonObject {
            val m = LinkedHashMap<String, JsonElement>(data)
            m["id"] = JsonPrimitive("")
            if (defaultActive && "active" !in m) m["active"] = JsonPrimitive(true)
            return JsonObject(m)
        }
        when (action) {
            "create" -> when (table) {
                "accounts" -> store.createAccount(row(created(true), Account.serializer()))
                "transactions" -> store.createTransaction(row(created(), Transaction.serializer()), copay)
                "bills" -> store.createBill(row(created(), Bill.serializer()))
                "billTemplates" -> store.createBillTemplate(row(created(), BillTemplate.serializer()))
                "debts" -> store.createDebt(row(created(), Debt.serializer()))
                "shopee" -> store.createShopee(row(created(), ShopeeOrder.serializer()))
                "port" -> store.createPort(row(created(), PortTxn.serializer()))
                "categories" -> store.createCategory(row(created(true), Category.serializer()))
                else -> throw EngineException("unknown table: $table")
            }
            "update" -> when (table) {
                "accounts" -> store.updateAccount(row(merged(store.accounts.firstOrNull { it.id == id }?.let { DataFile.encodeRow(it, Account.serializer()) }) + idOf(id), Account.serializer()))
                "transactions" -> store.updateTransaction(row(merged(store.transactions.firstOrNull { it.id == id }?.let { DataFile.encodeRow(it, Transaction.serializer()) }) + idOf(id), Transaction.serializer()), copay)
                "bills" -> store.updateBill(row(merged(store.bills.firstOrNull { it.id == id }?.let { DataFile.encodeRow(it, Bill.serializer()) }) + idOf(id), Bill.serializer()))
                "billTemplates" -> store.updateBillTemplate(row(merged(store.billTemplates.firstOrNull { it.id == id }?.let { DataFile.encodeRow(it, BillTemplate.serializer()) }) + idOf(id), BillTemplate.serializer()))
                "debts" -> store.updateDebt(row(merged(store.debts.firstOrNull { it.id == id }?.let { DataFile.encodeRow(it, Debt.serializer()) }) + idOf(id), Debt.serializer()))
                "shopee" -> store.updateShopee(row(merged(store.shopee.firstOrNull { it.id == id }?.let { DataFile.encodeRow(it, ShopeeOrder.serializer()) }) + idOf(id), ShopeeOrder.serializer()))
                "port" -> store.updatePort(row(merged(store.port.firstOrNull { it.id == id }?.let { DataFile.encodeRow(it, PortTxn.serializer()) }) + idOf(id), PortTxn.serializer()))
                "categories" -> store.updateCategory(row(merged(store.categories.firstOrNull { it.id == id }?.let { DataFile.encodeRow(it, Category.serializer()) }) + idOf(id), Category.serializer()))
                else -> throw EngineException("unknown table: $table")
            }
            "delete" -> when (table) {
                "accounts" -> store.deleteAccount(id)
                "transactions" -> store.deleteTransaction(id)
                "bills" -> store.deleteBill(id)
                "billTemplates" -> store.deleteBillTemplate(id)
                "debts" -> store.deleteDebt(id)
                "shopee" -> store.deleteShopee(id)
                "port" -> store.deletePort(id)
                "categories" -> store.deleteCategory(id)
                else -> throw EngineException("unknown table: $table")
            }
            "generateCycle" -> store.generateCycle(s(body, "cycle").orEmpty())
            "cashDraw" -> store.cashDraw(
                accountId = s(data, "account_id").orEmpty(), date = s(data, "date").orEmpty(), amount = n(data, "amount") ?: 0.0,
                tenor = n(data, "tenor")?.toInt() ?: 0, toAccountId = s(data, "to_account_id").orEmpty(),
                note = s(data, "note").orEmpty(), kind = s(data, "kind").orEmpty(),
            )
            "recordPurchase" -> store.recordPurchase(
                item = s(data, "item").orEmpty(), price = n(data, "price") ?: 0.0, accountId = s(data, "account_id").orEmpty(),
                date = s(data, "date"), tenor = n(data, "tenor")?.toInt(), installment = n(data, "installment"),
                annualRate = n(data, "annual_rate"), shop = s(data, "shop").orEmpty(), categoryId = s(data, "category_id").orEmpty(),
                subcategoryId = s(data, "subcategory_id").orEmpty(), note = s(data, "note").orEmpty(), orderNo = s(data, "order_no").orEmpty(),
            )
            "payBill" -> store.payBill(
                id = s(data, "id").orEmpty(), amount = n(data, "amount") ?: 0.0, accountId = s(data, "account_id"),
                toAccountId = if (data.containsKey("to_account_id")) s(data, "to_account_id").orEmpty() else null,
                date = s(data, "date"), note = s(data, "note"),
            )
            "unpayBill" -> store.unpayBill(id)
            "setPrices" -> store.setPrices(data.mapValues { (_, v) ->
                val o = v.jsonObject
                val p = o["price"] as? JsonPrimitive
                val price = if (p == null || p is JsonNull || (p.isString && p.content.isEmpty())) null else n(o, "price")
                price to s(o, "currency").orEmpty()
            })
            "updateFx" -> store.setFxAuto(data.mapValues { (k, _) -> n(data, k) ?: 0.0 }, s(body, "date"))
            "setFxManual" -> store.setFxManual(data.mapValues { (k, v) ->
                val p = v as? JsonPrimitive
                if (p == null || p is JsonNull || (p.isString && p.content.isEmpty())) null else n(data, k)
            })
            "setConfig" -> store.setConfig(data.mapValues { (_, v) -> (v as JsonPrimitive).content })
            else -> throw EngineException("unknown action: $action")
        }
    }

    private fun idOf(id: String) = mapOf("id" to JsonPrimitive(id))
    private operator fun JsonObject.plus(m: Map<String, JsonElement>) = JsonObject(LinkedHashMap<String, JsonElement>(this).apply { putAll(m) })
}

/** รันตัวคำนวณอ้างอิง (node reference/run.mjs) */
object Reference {
    val dir: File? = System.getProperty("dansha.reference")?.let(::File)?.takeIf { File(it, "run.mjs").exists() }

    fun nodeAvailable(): Boolean = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start().let { it.waitFor(20, TimeUnit.SECONDS) && it.exitValue() == 0 }
    } catch (e: Exception) {
        false
    }

    fun run(data: File, steps: JsonArray): JsonArray {
        val scenario = File.createTempFile("scenario", ".json").apply { writeText(JsonObject(mapOf("steps" to steps)).toString()); deleteOnExit() }
        val out = File.createTempFile("ref-out", ".json").apply { deleteOnExit() }
        val p = ProcessBuilder("node", File(dir, "run.mjs").absolutePath, data.absolutePath, scenario.absolutePath)
            .redirectOutput(out).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        check(p.waitFor(10, TimeUnit.MINUTES) && p.exitValue() == 0) { "node run.mjs ล้มเหลว" }
        return Json.parseToJsonElement(out.readText()) as JsonArray
    }

    /** ตั้งเวลาของฝั่ง Kotlin ให้ตรงกับขั้น { now } */
    fun setNow(iso: String): LocalDateTime {
        val t = LocalDateTime.parse(if (iso.length == 16) "$iso:00" else iso)
        Dates.clock = Clock.fixed(t.atZone(Dates.ZONE).toInstant(), Dates.ZONE)
        return t
    }
}
