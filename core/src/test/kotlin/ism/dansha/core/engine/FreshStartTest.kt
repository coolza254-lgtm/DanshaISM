package ism.dansha.core.engine

import ism.dansha.core.DanshaData
import ism.dansha.core.DataFile
import ism.dansha.core.Dates
import ism.dansha.core.Transaction
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreshStartTest {
    private val sample = DataFile.parse(File(System.getProperty("dansha.reference"), "sample-data.json").readText())
    private val today = "2026-11-25"
    private var savedClock: Clock = Dates.clock

    @BeforeTest
    fun fixClock() {
        savedClock = Dates.clock
        Dates.clock = Clock.fixed(LocalDateTime.parse(today + "T12:00:00").atZone(Dates.ZONE).toInstant(), Dates.ZONE)
    }

    @AfterTest
    fun restoreClock() {
        Dates.clock = savedClock
    }

    private fun balances(d: DanshaData) = Engine.accounts(d, Engine.debtStates(d, today, false)).associate { v ->
        v.account.id to (if (v.isCredit) v.used else v.balance)
    }

    @Test
    fun clearsHistoryButKeepsDebtsAndBalances() {
        val cash = sample.accounts.first { it.type == "cash" }
        val before = Engine.debtStates(sample, today, true)
        val balBefore = balances(sample)
        val preview = FreshStart.preview(sample)
        assertTrue(preview.removeTxns > 0 && preview.keepTxns > 0)

        val r = Store.run(sample) { freshStart(mapOf(cash.id to 123.45)) }
        val d = r.data
        assertEquals(preview, r.value)
        assertEquals(preview.keepTxns, d.transactions.size)
        assertTrue(d.bills.isEmpty())
        assertTrue(d.shopee.none { it.pay_method != "spaylater" || it.status == "cancelled" })
        assertEquals(sample.debts, d.debts)
        assertEquals(sample.port, d.port)
        assertEquals(sample.billTemplates, d.billTemplates)
        assertEquals(today, d.config[FreshStart.KEY])

        // หนี้ที่มีตัวคำนวณ: ยอด/บิลเท่าเดิมทุกบัญชี
        val after = Engine.debtStates(d, today, true)
        assertEquals(before.keys, after.keys)
        before.forEach { (id, st) ->
            val st2 = after.getValue(id)
            assertEquals(st.outstanding, st2.outstanding, id)
            assertEquals(st.currentBill, st2.currentBill, id)
            assertEquals(st.nextBill, st2.nextBill, id)
            assertEquals(st.bills, st2.bills, id)
            assertEquals(st.accruedInterest, st2.accruedInterest, id)
        }
        // บัญชีอื่น: ที่กรอก = ยอดจริง, ที่ไม่กรอก = เท่าเดิม
        val balAfter = balances(d)
        assertEquals(123.45, balAfter[cash.id])
        balBefore.filterKeys { it != cash.id }.forEach { (id, v) -> assertEquals(v, balAfter[id], id) }
        // 60/40 ที่ใช้ไปแล้วยังนับอยู่
        val cfg = Engine.config(d)
        assertEquals(Copay.status(sample.transactions, cfg, today)?.usage, Copay.status(d.transactions, cfg, today)?.usage)
    }

    @Test
    fun setupChecklistAfterFreshStart() {
        assertFalse(Setup.show(sample, today)) // ใช้อยู่แล้ว ไม่ขึ้นเช็คลิสต์
        val d = Store.run(sample) { freshStart(emptyMap()) }.data
        assertTrue(Setup.show(d, today))
        val items = Setup.items(d, today).associate { it.step to it.done }
        assertTrue(items.getValue(Setup.Step.Accounts))
        assertTrue(items.getValue(Setup.Step.Income))
        assertTrue(items.getValue(Setup.Step.Fixed))
        assertFalse(items.getValue(Setup.Step.Plan))
        assertFalse(items.getValue(Setup.Step.FirstEntry))
        // ซ่อนแล้วไม่ขึ้นอีก
        val hidden = Store.run(d) { setConfig(mapOf(Setup.DONE_KEY to "true")) }.data
        assertFalse(Setup.show(hidden, today))
        // ผู้ใช้ใหม่ (ยังไม่มีรายการ) ก็ขึ้น
        assertTrue(Setup.show(ism.dansha.core.Schema.seed(), today))
    }

    @Test
    fun logNudge() {
        val acc = ism.dansha.core.Account("acc_cash", "เงินสด", "cash", "THB", active = true)
        fun data(created: String, extra: Map<String, String> = emptyMap()) = ism.dansha.core.Schema.seed().let { s ->
            s.copy(
                accounts = listOf(acc), config = s.config + extra,
                transactions = listOf(Transaction("t1", created.take(10), type = "expense", account_id = acc.id, amount = BigDecimal.TEN, created_at = created)),
            )
        }
        val evening = LocalDateTime.parse(today + "T20:30:00")
        assertNull(Notify.logNudge(data(today + "T08:00:00"), evening)) // วันนี้จดแล้ว
        assertNull(Notify.logNudge(data("2026-11-24T08:00:00"), LocalDateTime.parse(today + "T19:59:00"))) // ยังไม่ถึงเวลา
        assertEquals("วันนี้ยังไม่ได้จดเลย ✏️", Notify.logNudge(data("2026-11-24T08:00:00"), evening)?.title)
        assertEquals("ไม่ได้จดมา 5 วันแล้ว", Notify.logNudge(data("2026-11-20T08:00:00"), evening)?.title)
        assertNull(Notify.logNudge(data("2026-11-20T08:00:00", mapOf("log_reminder_enabled" to "false")), evening))
        assertNull(Notify.logNudge(data("2026-11-20T08:00:00", mapOf("log_reminder_hour" to "22")), evening))
    }
}
