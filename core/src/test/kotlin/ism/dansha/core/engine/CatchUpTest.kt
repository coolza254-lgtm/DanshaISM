package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.Bill
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Schema
import ism.dansha.core.Transaction
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatchUpTest {
    private val seed = Schema.seed()
    private fun cat(name: String) = seed.categories.first { it.name == name }
    private val today = LocalDate.parse("2026-10-31")
    private var savedClock: Clock = Dates.clock

    @BeforeTest
    fun fixClock() {
        savedClock = Dates.clock
        Dates.clock = Clock.fixed(today.atTime(12, 0).atZone(Dates.ZONE).toInstant(), Dates.ZONE)
    }

    @AfterTest
    fun restoreClock() {
        Dates.clock = savedClock
    }

    private fun txn(id: String, date: String, note: String, sub: String, acc: String, amt: Int, type: String = "expense", created: String = date + "T09:00:00"): Transaction {
        val s = cat(sub)
        val main = if (s.parent_id.isEmpty()) s.id else s.parent_id
        return Transaction(
            id, date, type = type, account_id = acc, amount = BigDecimal(amt), category_id = main,
            subcategory_id = if (s.parent_id.isEmpty()) "" else s.id, note = note, created_at = created,
        )
    }

    private val d = seed.copy(
        accounts = listOf(
            Account("acc_cash", "เงินสด", "cash", "THB", opening_balance = BigDecimal(1000), active = true, sort = 1),
            Account("acc_tm", "TrueMoney Wallet", "bank", "THB", opening_balance = BigDecimal(500), active = true, sort = 2),
            Account("acc_card", "บัตรเครดิต", "revolving_credit", "THB", opening_balance = BigDecimal(200), credit_limit = BigDecimal(10000), active = true, sort = 3),
            Account("acc_pn", "Ascend PayNext", "revolving_credit", "THB", active = true, sort = 4, debt_engine = "ascend", debt_since = "2026-10-01"),
            Account("acc_old", "บัญชีเก่า", "bank", "THB", active = false, sort = 5),
        ),
        transactions = listOf(
            txn("t1", "2026-10-20", "กาแฟอเมซอน", "ขนม/เครื่องดื่ม", "acc_tm", 55),
            txn("t2", "2026-10-22", "ข้าวมันไก่", "อาหารหลัก", "acc_cash", 50),
            txn("t3", "2026-10-24", "grab กลับบ้าน", "แท็กซี่/Grab", "acc_tm", 140, created = "2026-10-25T21:00:00"),
        ),
    )

    @Test
    fun gapFromLastEntry() {
        val g = CatchUp.gap(d, today)
        assertEquals("2026-10-25", g.lastEntry) // ใช้วันที่บันทึก ไม่ใช่วันที่ของรายการ
        assertEquals(6, g.days)
        assertEquals(CatchUp.Gap(null, 0), CatchUp.gap(DanshaData(), today))
    }

    @Test
    fun leadingDates() {
        fun date(s: String) = CatchUp.leadingDate(s, today)
        assertEquals("2026-10-28" to "", date("28/10"))
        assertEquals("2026-10-28" to "กาแฟ 35", date("28/10 กาแฟ 35"))
        assertEquals("2026-10-28" to "", date("28/10/2569"))
        assertEquals("2026-10-28" to "", date("28/10/69"))
        assertEquals("2026-10-28" to "", date("28 ต.ค."))
        assertEquals("2026-10-28" to "ข้าว 50", date("28 ตุลาคม ข้าว 50"))
        assertEquals("2026-10-28" to "", date("2026-10-28:"))
        assertEquals("2026-10-30" to "ข้าว 40", date("เมื่อวาน ข้าว 40"))
        assertEquals("2026-10-29" to "", date("เมื่อวานซืน"))
        assertEquals("2026-10-31" to "", date("วันนี้"))
        // ไม่ระบุปี + เดือนที่ยังไม่ถึง = ปีที่แล้ว
        assertEquals("2025-12-25" to "", date("25/12"))
        // วันที่ผิด
        assertEquals("" to "", date("31/02"))
        // ไม่ใช่วันที่
        assertNull(date("กาแฟ 35"))
        assertNull(date("50 ข้าว"))
        assertNull(date("7/11 ขนม 45"))
        assertNull(date("28.50 ข้าว"))
    }

    @Test
    fun parsesLinesUnderDateHeaders() {
        val lines = CatchUp.parse(
            d,
            """
            กาแฟ 55
            28/10
            ข้าวมันไก่ 45 สด
            grab 120 tm
            # หมายเหตุ ข้ามบรรทัดนี้
            29 ต.ค. กาแฟ 55
            ขนม
            31/02 ข้าว 50
            +500 ขายของ
            1/11 ข้าว 50
            """.trimIndent(),
            today,
        )
        assertEquals(8, lines.size)
        val (coffeeToday, rice, grab, coffee29) = lines
        assertEquals("2026-10-31", coffeeToday.date) // ก่อนมีบรรทัดวันที่ = วันนี้
        assertEquals("acc_tm", coffeeToday.accountId) // เดาบัญชีจากประวัติ
        assertEquals(cat("ขนม/เครื่องดื่ม").id, coffeeToday.subcategoryId)

        assertEquals("2026-10-28", rice.date)
        assertEquals(45.0, rice.amount)
        assertEquals("acc_cash", rice.accountId)
        assertEquals(cat("อาหารหลัก").id, rice.subcategoryId)
        assertEquals("ข้าวมันไก่", rice.note)

        assertEquals("2026-10-28", grab.date)
        assertEquals(cat("แท็กซี่/Grab").id, grab.subcategoryId)
        assertEquals("2026-10-29", coffee29.date)

        assertEquals("ไม่มียอดเงิน", lines[4].error) // "ขนม" ไม่มียอด (ไม่เติมยอดเดาให้)
        assertEquals("วันที่ไม่ถูกต้อง", lines[5].error)
        assertEquals("income", lines[6].type)
        assertEquals("2026-10-29", lines[6].date) // วันที่ผิดไม่เปลี่ยนวันของบรรทัดถัดไป
        assertEquals("1/11 ข้าว 50" to "วันที่ยังไม่ถึง", lines[7].text to lines[7].error)
        assertEquals(5, lines.count { it.ok })
    }

    @Test
    fun flagsPossibleDuplicates() {
        val lines = CatchUp.parse(d, "22/10\nข้าวมันไก่ 50 สด\nข้าวมันไก่ 60 สด", today)
        assertTrue(lines[0].duplicate)
        assertTrue(!lines[1].duplicate)
    }

    @Test
    fun backfillCreatesTransactions() {
        val lines = CatchUp.parse(d, "28/10\nข้าวมันไก่ 45 สด\nขนม\ngrab 120 tm", today)
        val r = Store.run(d) { addBackfill(lines) }
        assertEquals(2, r.value)
        val added = r.data.transactions.drop(d.transactions.size)
        assertEquals(listOf("2026-10-28", "2026-10-28"), added.map { it.date })
        assertEquals(listOf("acc_cash", "acc_tm"), added.map { it.account_id })
        assertEquals("2026-10 Oct-Nov", added[0].pay_cycle)
        assertEquals("manual", added[0].source)
    }

    @Test
    fun balanceRowsSkipDebtEngineAndInactive() {
        val rows = CatchUp.balanceRows(d, today.toString())
        assertEquals(listOf("acc_cash", "acc_tm", "acc_card"), rows.map { it.account.id })
        assertEquals(950.0, rows[0].app)
        assertEquals(305.0, rows[1].app)
        assertEquals(200.0, rows[2].app)
        assertTrue(rows[2].isCredit)
    }

    @Test
    fun adjustmentDirection() {
        val (cash, _, card) = CatchUp.balanceRows(d, today.toString())
        assertEquals(CatchUp.Adjustment("expense", 150.0), CatchUp.adjustment(cash, 800.0))
        assertEquals(CatchUp.Adjustment("income", 50.0), CatchUp.adjustment(cash, 1000.0))
        assertNull(CatchUp.adjustment(cash, 950.004))
        // วงเงิน: ใช้ไปเพิ่ม = รายจ่าย, ลดลง = จ่ายคืน
        assertEquals(CatchUp.Adjustment("expense", 300.0), CatchUp.adjustment(card, 500.0))
        assertEquals(CatchUp.Adjustment("income", 200.0), CatchUp.adjustment(card, 0.0))
    }

    @Test
    fun adjustBalanceMatchesActual() {
        val r = Store.run(d) {
            listOf(adjustBalance("acc_cash", 800.0), adjustBalance("acc_tm", 400.5), adjustBalance("acc_card", 500.0), adjustBalance("acc_tm", 400.5))
        }
        val (cash, tm, card, again) = r.value
        assertEquals("expense", cash!!.type)
        assertEquals(0, cash.amount!!.compareTo(BigDecimal(150)))
        assertEquals(CatchUp.ADJUST, cash.source)
        assertEquals("2026-10-31", cash.date)
        assertEquals(cat("อื่นๆ").id, cash.category_id)
        assertEquals("income", tm!!.type)
        assertEquals(cat("รายรับอื่นๆ").id, tm.category_id)
        assertEquals("expense", card!!.type)
        assertNull(again) // ตรงแล้ว ไม่จดซ้ำ
        val after = CatchUp.balanceRows(r.data, today.toString()).associate { it.account.id to it.app }
        assertEquals(800.0, after["acc_cash"])
        assertEquals(400.5, after["acc_tm"])
        assertEquals(500.0, after["acc_card"])
        // หนี้ที่มีตัวคำนวณ ปรับที่นี่ไม่ได้
        val err = runCatching { Store.run(d) { adjustBalance("acc_pn", 100.0) } }.exceptionOrNull()
        assertTrue(err is EngineException)
    }

    @Test
    fun dueBillsInRecentCycles() {
        fun bill(id: String, cycle: String, due: String, status: String = "planned", tpl: String = "", to: String = "", est: Int = 100) =
            Bill(id, pay_cycle = cycle, group = "A", name = id, est_amount = BigDecimal(est), status = status, due_date = due, template_id = tpl, to_account_id = to)
        val data = d.copy(
            bills = listOf(
                bill("rent", "2026-10 Oct-Nov", "2026-10-25"),
                bill("today", "2026-10 Oct-Nov", "2026-10-31"),
                bill("later", "2026-10 Oct-Nov", "2026-11-05"),
                bill("paid", "2026-10 Oct-Nov", "2026-10-20", status = "paid"),
                bill("travel", "2026-10 Oct-Nov", "2026-10-20", tpl = PlanBook.AUTO_TRAVEL),
                bill("nodebt", "2026-10 Oct-Nov", "2026-10-20", to = "acc_pn", est = 0),
                bill("prev", "2026-09 Sep-Oct", "2026-10-01"),
                bill("old", "2026-08 Aug-Sep", "2026-09-01"),
            ),
        )
        assertEquals(listOf("prev", "rent", "today"), CatchUp.dueBills(data, today.toString()).map { it.id })
    }
}
