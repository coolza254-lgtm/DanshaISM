package ism.dansha.core.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ขั้นตอนแก้ไขข้อมูลบน reference/sample-data.json ที่รันทั้งในระบบเดิม (JS) และ Kotlin แล้วเทียบฐานข้อมูลทุกขั้น
 * id ที่สร้างใหม่เป็นเลขเรียงฐาน 16 ตามลำดับที่สร้าง (txn_000000000001, …)
 */
object ReferenceScenarios {
    private fun v(x: Any?): JsonElement = when (x) {
        null -> JsonNull
        is JsonElement -> x
        is Number -> JsonPrimitive(x)
        is Boolean -> JsonPrimitive(x)
        is Map<*, *> -> obj(*x.entries.map { it.key as String to it.value }.toTypedArray())
        else -> JsonPrimitive(x.toString())
    }

    private fun obj(vararg kv: Pair<String, Any?>) = JsonObject(kv.associate { (k, x) -> k to v(x) })

    private fun id(prefix: String, n: Int) = prefix + "_" + n.toString(16).padStart(12, '0')

    private class Steps {
        val list = ArrayList<JsonElement>()
        fun now(t: String) { list += obj("now" to t) }
        fun get(vararg kv: Pair<String, Any?>) { list += obj("get" to obj(*kv)) }
        fun post(vararg kv: Pair<String, Any?>) { list += obj("post" to obj(*kv)) }
        fun create(table: String, vararg data: Pair<String, Any?>) = post("action" to "create", "table" to table, "data" to obj(*data))
        fun update(table: String, rowId: String, vararg data: Pair<String, Any?>) = post("action" to "update", "table" to table, "id" to rowId, "data" to obj(*data))
        fun delete(table: String, rowId: String) = post("action" to "delete", "table" to table, "id" to rowId)
    }

    fun edits(): JsonArray {
        val s = Steps()
        val c2 = "2026-10 Oct-Nov"
        s.now("2026-10-03T12:00")

        // ---- รายการ + 60/40 ----
        s.create("transactions", "date" to "2026-10-03", "type" to "expense", "account_id" to "acc_cash", "amount" to 35, "category_id" to "cat_food", "note" to "7/11") // 1
        s.create("transactions", "date" to "2026-10-03", "type" to "expense", "account_id" to "acc_wallet", "amount" to 150, "full_price" to 150, "copay" to true, "category_id" to "cat_food") // 2
        s.create("transactions", "date" to "2026-10-03", "type" to "expense", "account_id" to "acc_wallet", "amount" to 400, "full_price" to 400, "copay" to true) // 3 ชนเพดานรายวัน
        s.create("transactions", "date" to "2026-10-03", "type" to "expense", "account_id" to "acc_bank", "amount" to 100, "full_price" to 100, "copay" to true) // 4 ผิดบัญชี
        s.create("transactions", "date" to "2026-10-03", "type" to "transfer", "account_id" to "acc_bank", "to_account_id" to "acc_bank", "amount" to 10) // 5
        s.create("transactions", "date" to "2026-10-03", "type" to "expense", "account_id" to "acc_bank", "amount" to 0) // 6
        s.create("transactions", "date" to "", "type" to "expense", "account_id" to "acc_bank", "amount" to 5) // 7
        s.create("transactions", "date" to "2026-12-31", "type" to "income", "account_id" to "acc_usd", "amount" to "12.5", "currency" to "USD") // 8
        s.update("transactions", id("txn", 1), "amount" to 55, "note" to "แก้แล้ว", "date" to "2026-10-15")
        s.update("transactions", id("txn", 2), "copay" to false)
        s.update("transactions", id("txn", 3), "copay" to true, "full_price" to 300)
        s.create("transactions", "date" to "2026-11-30", "type" to "expense", "account_id" to "acc_wallet", "amount" to 900, "full_price" to 900, "copay" to true) // 9 เพดานรายเดือน/ทั้งโครงการ
        s.create("transactions", "date" to "2026-12-01", "type" to "expense", "account_id" to "acc_wallet", "amount" to 90, "full_price" to 90, "copay" to true) // 10 นอกช่วง
        s.get("action" to "bootstrap")

        // ---- แผนบิล ----
        s.post("action" to "payBill", "data" to obj("id" to "bill_bonus", "amount" to 650, "date" to "2026-10-03")) // 11
        s.post("action" to "payBill", "data" to obj("id" to "bill_bonus", "amount" to 650))
        s.update("bills", "bill_bonus", "actual_amount" to 700)
        s.post("action" to "unpayBill", "id" to "bill_bonus")
        s.post("action" to "payBill", "data" to obj("id" to "bill_rent", "amount" to 4500)) // 12
        s.delete("transactions", id("txn", 0x12))
        s.post("action" to "payBill", "data" to obj("id" to "bill_extra", "amount" to 1500, "date" to "2026-10-03")) // 13 โอนเข้าวงเงิน
        s.post("action" to "payBill", "data" to obj("id" to "bill_food", "amount" to 0))
        s.post("action" to "payBill", "data" to obj("id" to "bill_food", "amount" to 300, "to_account_id" to "acc_card")) // 14
        s.delete("bills", "bill_net")
        s.delete("transactions", "txn_salary")
        s.post("action" to "generateCycle", "cycle" to c2)
        s.post("action" to "generateCycle", "cycle" to c2)
        s.post("action" to "generateCycle", "cycle" to "2026-11 Nov-Dec")
        s.post("action" to "generateCycle", "cycle" to "bad")
        s.create("billTemplates", "group" to "A", "name" to "ค่ามือถือ", "est_amount" to 399, "due_day" to 20, "active" to true)
        s.create("billTemplates", "group" to "income", "name" to "รายรับ", "est_amount" to 100, "to_account_id" to "acc_extra", "active" to true)
        s.create("bills", "pay_cycle" to c2, "group" to "B", "name" to "ของใช้", "est_amount" to 500)
        s.create("bills", "pay_cycle" to c2, "group" to "X", "name" to "ผิดกลุ่ม", "est_amount" to 5)
        s.create("bills", "pay_cycle" to c2, "group" to "B", "name" to "", "est_amount" to 5)
        s.update("billTemplates", "btpl_food", "est_amount" to 2500)
        s.delete("billTemplates", "btpl_off")

        // ---- หนี้ ----
        s.post("action" to "cashDraw", "data" to obj("account_id" to "acc_extra", "date" to "2026-10-03", "amount" to 2000, "tenor" to 12, "to_account_id" to "acc_bank"))
        s.post("action" to "cashDraw", "data" to obj("account_id" to "acc_short", "date" to "2026-10-03", "amount" to 700, "tenor" to 12, "to_account_id" to "acc_bank"))
        s.post("action" to "cashDraw", "data" to obj("account_id" to "acc_short", "date" to "2026-10-04", "amount" to 700, "tenor" to 5, "note" to "เบิกไม่โอน"))
        s.post("action" to "cashDraw", "data" to obj("account_id" to "acc_bank", "date" to "2026-10-03", "amount" to 700, "tenor" to 5))
        s.get("action" to "debts")
        s.delete("transactions", "txn_draw")
        s.create("debts", "account_id" to "acc_short", "txn_date" to "2026-10-03", "kind" to "cash", "description" to "เพิ่มเอง", "principal" to 1000, "tenor" to 3)
        s.create("debts", "account_id" to "acc_short", "txn_date" to "2026-10-03", "kind" to "loan", "principal" to 1000, "tenor" to 3)
        s.create("debts", "account_id" to "acc_short", "txn_date" to "2026-10-03", "kind" to "cash", "principal" to 1000)
        s.create("debts", "account_id" to "acc_extra", "txn_date" to "2025-01-03", "kind" to "cash", "principal" to 9000, "tenor" to 30,
            "snap_date" to "2026-09-17", "snap_remaining" to 3000, "snap_paid_periods" to 20)
        s.create("debts", "account_id" to "acc_short", "txn_date" to "2026-10-03", "kind" to "fullpay_snapshot", "description" to "ยกมา", "principal" to 80)
        s.update("debts", "debt_e2", "installment" to 230)
        s.delete("debts", "debt_e4")
        s.get("action" to "debts")

        // ---- Shopee ----
        s.post("action" to "recordPurchase", "data" to obj("item" to "เสื้อ", "price" to 390, "account_id" to "acc_bank", "category_id" to "cat_shop", "shop" to "ร้าน D"))
        s.post("action" to "recordPurchase", "data" to obj("item" to "พัดลม", "price" to 1200, "account_id" to "acc_spl", "tenor" to 3, "installment" to 420, "date" to "2026-10-20"))
        s.post("action" to "recordPurchase", "data" to obj("item" to "ไม่กรอกค่างวด", "price" to 600, "account_id" to "acc_spl", "tenor" to 3))
        s.post("action" to "recordPurchase", "data" to obj("item" to "ถุงเท้า", "price" to 89, "account_id" to "acc_spl", "tenor" to 1))
        s.post("action" to "recordPurchase", "data" to obj("item" to "", "price" to 89, "account_id" to "acc_spl"))
        s.post("action" to "recordPurchase", "data" to obj("item" to "x", "price" to 89, "account_id" to "acc_none"))
        s.update("shopee", "shp_5", "price" to 279)
        s.update("shopee", "shp_5", "status" to "cancelled")
        s.update("shopee", "shp_3", "installment" to 110)
        s.delete("shopee", "shp_2")
        s.get("action" to "shopeeStats")

        // ---- บัญชี / หมวด ----
        s.delete("accounts", "acc_bank")
        s.delete("accounts", "acc_old")
        s.create("accounts", "name" to "บัญชีใหม่", "type" to "bank", "opening_balance" to "")
        s.create("accounts", "name" to "ผิดประเภท", "type" to "loan")
        s.create("accounts", "name" to "", "type" to "bank")
        s.create("accounts", "name" to "สกุลแปลก", "type" to "bank", "currency" to "EUR")
        s.update("accounts", "acc_card", "opening_balance" to 1500, "name" to "บัตรเครดิต (แก้)")
        s.delete("categories", "cat_food")
        s.delete("categories", "cat_unused")
        s.create("categories", "name" to "หมวดใหม่", "type" to "expense", "icon" to "🎁")
        s.create("categories", "name" to "ผิด", "type" to "other")
        s.update("categories", "cat_shop", "name" to "ช้อปปิ้งออนไลน์")

        // ---- อัตราแลกเปลี่ยน / ราคา / พอร์ต / ตั้งค่า ----
        s.post("action" to "setFxManual", "data" to obj("USD" to 36.5, "JPY" to ""))
        s.post("action" to "setFxManual", "data" to obj("USD" to -1))
        s.post("action" to "setFxManual", "data" to obj("CNY" to ""))
        s.post("action" to "updateFx", "data" to obj("USD" to 34.1, "EUR" to 40, "JPY" to 0, "CNY" to 4.75), "date" to "2026-10-03")
        s.post("action" to "setPrices", "data" to obj("aapl" to obj("price" to 230, "currency" to "USD"), "NEW" to obj("price" to 5), "X" to obj("price" to "")))
        s.create("port", "date" to "2026-10-03", "symbol" to " tsla ", "side" to "buy", "qty" to 1, "price" to 250, "currency" to "USD", "fx_rate" to 34)
        s.create("port", "date" to "2026-10-03", "symbol" to "MSFT", "side" to "buy", "qty" to 1, "price" to 250, "currency" to "USD")
        s.create("port", "date" to "2026-10-03", "symbol" to "SET50", "side" to "sell", "qty" to 0, "price" to 11)
        s.create("port", "date" to "2026-10-03", "symbol" to "SET50", "side" to "sell", "qty" to 40, "price" to 11.4, "fee" to 3)
        s.update("port", "port_2", "price" to 10.6)
        s.get("action" to "portfolio")
        s.post("action" to "setConfig", "data" to obj("copay_daily_cap" to 150, "shop_buffer" to "500"))
        s.get("action" to "bootstrap")

        // ---- เวลาผ่านไป ----
        s.now("2026-10-20T09:00")
        s.create("transactions", "date" to "2026-10-20", "type" to "expense", "account_id" to "acc_wallet", "amount" to 300, "full_price" to 300, "copay" to true)
        s.create("transactions", "date" to "2026-10-20", "type" to "transfer", "account_id" to "acc_bank", "to_account_id" to "acc_short", "amount" to 777)
        s.post("action" to "payBill", "data" to obj("id" to "bill_spl2", "amount" to 100))
        s.get("action" to "bootstrap")
        s.get("action" to "debts")
        s.get("action" to "outlook", "count" to 7)
        s.get("action" to "checkPurchase", "price" to 2500, "method" to "spaylater", "tenor" to 6, "installment" to 460)
        s.get("action" to "checkPurchase", "price" to 0, "method" to "full")
        s.get("action" to "debtQuote", "account_id" to "acc_short", "date" to "2026-10-31", "amount" to 5000)
        s.get("action" to "debtQuote", "account_id" to "acc_bank")
        s.now("2026-12-02T08:00")
        s.get("action" to "bootstrap")
        s.get("action" to "debts")
        return JsonArray(s.list)
    }
}
