package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.DanshaData
import ism.dansha.core.Schema
import ism.dansha.core.Transaction
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickEntryTest {
    private val seed = Schema.seed()
    private fun cat(name: String) = seed.categories.first { it.name == name }

    private val accounts = listOf(
        Account("acc_cash", "เงินสด", "cash", "THB", active = true, sort = 1),
        Account("acc_tm", "TrueMoney Wallet", "bank", "THB", active = true, sort = 2),
        Account("acc_kb", "K-Bank", "bank", "THB", active = true, sort = 3),
        Account("acc_pn", "Ascend PayNext", "revolving_credit", "THB", active = true, sort = 4),
        Account("acc_ex", "Ascend PayNext Extra", "revolving_credit", "THB", active = true, sort = 5),
    )

    private fun txn(id: String, date: String, note: String, sub: String, acc: String, amt: Int, type: String = "expense"): Transaction {
        val s = cat(sub)
        val main = if (s.parent_id.isEmpty()) s.id else s.parent_id
        return Transaction(id, date, type = type, account_id = acc, amount = BigDecimal(amt), category_id = main, subcategory_id = if (s.parent_id.isEmpty()) "" else s.id, note = note)
    }

    private val d = DanshaData(
        accounts = accounts,
        categories = seed.categories,
        transactions = listOf(
            txn("t1", "2026-10-01", "กาแฟอเมซอน", "ขนม/เครื่องดื่ม", "acc_tm", 55),
            txn("t2", "2026-10-03", "กาแฟอเมซอน", "ขนม/เครื่องดื่ม", "acc_tm", 55),
            txn("t3", "2026-10-05", "ข้าวมันไก่", "อาหารหลัก", "acc_cash", 50),
            txn("t4", "2026-10-06", "grab กลับบ้าน", "แท็กซี่/Grab", "acc_tm", 140),
            txn("t5", "2026-10-07", "ค่ารถ", "รถสาธารณะ", "acc_cash", 12),
            txn("t6", "2026-09-15", "เงินเดือน", "เงินเดือน", "acc_kb", 15000, type = "income"),
        ),
    )
    private val today = LocalDate.parse("2026-10-12")

    @Test
    fun parsesAmountAccountAndText() {
        val p = QuickEntry.parse(d, "ข้าวมันไก่ 50 สด")
        assertEquals(50.0, p.amount)
        assertEquals("acc_cash", p.accountId)
        assertEquals("ข้าวมันไก่", p.text)
        assertNull(p.type)
        assertEquals(1200.5, QuickEntry.parse(d, "1,200.50บาท ของขวัญ").amount)
        assertEquals("income", QuickEntry.parse(d, "+500 ขายของ").type)
        assertEquals("acc_tm", QuickEntry.parse(d, "grab 120 tm").accountId)
        assertEquals("acc_ex", QuickEntry.parse(d, "ช้อป 300 ex").accountId)
        assertEquals("acc_pn", QuickEntry.parse(d, "ช้อป 300 pn").accountId)
    }

    @Test
    fun learnsFromHistory() {
        val g = QuickEntry.suggest(d, "กาแฟ 55", today).first()
        assertEquals(cat("ขนม/เครื่องดื่ม").id, g.subcategoryId)
        assertEquals("acc_tm", g.accountId) // ใช้บัญชีที่เคยจ่ายกาแฟ
        assertEquals(55.0, g.amount)
        assertEquals("กาแฟ", g.note)

        val rice = QuickEntry.suggest(d, "ข้าวมันไก่ 45", today).first()
        assertEquals(cat("อาหารหลัก").id, rice.subcategoryId)
        assertEquals("acc_cash", rice.accountId)
    }

    @Test
    fun smarterGuesses() {
        // พิมพ์แค่ชื่อ → เติมยอดที่จ่ายประจำให้
        val coffee = QuickEntry.suggest(d, "กาแฟ", today).first()
        assertEquals(55.0, coffee.amount)
        // พิมพ์แค่ยอด → เดาจากรายการที่เคยจ่ายยอดเท่ากัน
        val fare = QuickEntry.suggest(d, "12", today).first()
        assertEquals(cat("รถสาธารณะ").id, fare.subcategoryId)
        assertEquals("acc_cash", fare.accountId)
    }

    @Test
    fun keywordsWhenNoHistory() {
        val g = QuickEntry.suggest(d, "ค่าไฟ 1888.90", today).first()
        assertEquals(cat("ค่าไฟ").id, g.subcategoryId)
        val fuel = QuickEntry.suggest(d, "เติมน้ำมัน 300", today).first()
        assertEquals(cat("น้ำมัน").id, fuel.subcategoryId)
        val hosp = QuickEntry.suggest(d, "โรงพยาบาล 500", today).first()
        assertEquals(cat("โรงพยาบาล").id, hosp.subcategoryId)
        // บัญชีที่พิมพ์ชนะบัญชีจากประวัติ
        assertEquals("acc_kb", QuickEntry.suggest(d, "กาแฟ 55 kbank", today).first().accountId)
    }

    @Test
    fun incomeAndUnknown() {
        val g = QuickEntry.suggest(d, "+15000 เงินเดือน", today).first()
        assertEquals("income", g.type)
        assertEquals(cat("เงินเดือน").id, g.categoryId)
        val unknown = QuickEntry.suggest(d, "อะไรสักอย่าง 99", today)
        assertTrue(unknown.isNotEmpty())
        assertEquals(99.0, unknown.first().amount)
        assertTrue(QuickEntry.suggest(d, "", today).size <= 3)
    }
}

class SplReceiveTest {
    private fun o(order: String, received: String = "", status: String = "ordered") =
        ism.dansha.core.ShopeeOrder("o", order_date = order, received_date = received, status = status, price = BigDecimal(100), tenor = 1, pay_method = "spaylater")

    @Test
    fun billFollowsReceiveDate() {
        // สั่ง 10 ต.ค. รับ 14 ต.ค. → บิล 25 ต.ค. / รับ 15 ต.ค. → เลื่อนไป 25 พ.ย.
        assertEquals("2026-10-25", SPayLater.installments(listOf(o("2026-10-10", "2026-10-14"))).first().due)
        assertEquals("2026-11-25", SPayLater.installments(listOf(o("2026-10-10", "2026-10-15"))).first().due)
        // ยังไม่กดรับ: คาดว่ารับ สั่ง + 7 วัน = 17 ต.ค. → 25 พ.ย.
        assertEquals("2026-11-25", SPayLater.installments(listOf(o("2026-10-10", status = "waiting"))).first().due)
        // ออเดอร์เก่าไม่มีวันรับ: ใช้วันสั่งเหมือนเดิม
        assertEquals("2026-10-25", SPayLater.installments(listOf(o("2026-10-10"))).first().due)
    }
}
