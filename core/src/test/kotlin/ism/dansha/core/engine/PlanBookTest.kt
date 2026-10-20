package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.Schema
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanBookTest {
    private val base = Schema.seed().copy(
        accounts = listOf(
            Account("acc_cash", "เงินสด", "cash", "THB", opening_balance = BigDecimal(1000), active = true, sort = 1),
            Account("acc_tm", "TrueMoney Wallet", "bank", "THB", opening_balance = BigDecimal(1000), active = true, sort = 2),
            Account("acc_bbl", "Bangkok Bank", "bank", "THB", opening_balance = BigDecimal(5000), active = true, sort = 3),
        ),
    )
    private val cycle = "2099-10 Oct-Nov"

    @Test
    fun claimPayDay() {
        assertEquals("2026-10-30", PlanBook.claimPayDate("2026-10-15")) // ส่งทันวันที่ 15 → 30 เดือนนี้
        assertEquals("2026-11-30", PlanBook.claimPayDate("2026-10-16")) // ไม่ทัน → เดือนหน้า
        assertEquals("2027-02-28", PlanBook.claimPayDate("2027-01-20")) // ก.พ. ไม่มีวันที่ 30
    }

    @Test
    fun grabClaimFlow() {
        val r = Store.run(base) {
            val g1 = logGrab("2026-10-03", 140.0)
            val g2 = logGrab("2026-10-08", 160.0)
            val bill = submitClaim(listOf(g1.id, g2.id), "2026-10-10")
            Triple(g1, bill, receiveClaim(bill.id, 300.0, "2026-10-30"))
        }
        val (g1, bill, paid) = r.value
        assertEquals("acc_tm", g1.account_id) // จ่าย Grab ด้วย TrueMoney
        assertEquals("2026-10-30", bill.due_date)
        assertEquals(300.0, bill.est_amount.num())
        assertEquals("acc_bbl", bill.account_id) // รับเงินเบิกเข้า Bangkok Bank
        assertEquals("paid", paid.status)
        assertTrue(r.data.transactions.filter { it.source == "travel" }.all { it.claim == "paid:2026-10-30" })
    }

    @Test
    fun travelFromWorkDays() {
        val r = Store.run(base) {
            setConfig(mapOf("travel_bus" to "20", "travel_grab" to "150"))
            setWorkDays(cycle, listOf(PlanBook.WorkDay("2099-10-16", false), PlanBook.WorkDay("2099-10-17", true)))
        }
        val row = r.data.bills.single { it.template_id == PlanBook.AUTO_TRAVEL }
        assertEquals(20.0 + 20.0 + 20.0 + 150.0, row.est_amount.num()) // กะดึก: ไปรถเมล์ + กลับ Grab
        assertEquals("B", row.group)
        assertEquals(PlanBook.TRAVEL, PlanBook.sectionOf(row))

        // จดจริง: ไป 12 กลับ 8 → ยอดที่ต้องเตรียมลด 40, ประหยัด 20
        val paid = Store.run(r.data) {
            setTravelLeg("2099-10-16", "go", 12.0); setTravelLeg("2099-10-16", "back", 8.0)
            setTravelLeg("2099-10-16", "go", 8.0) // กดใหม่ = แก้ยอด
            syncPlan(cycle)
        }
        val tp = PlanBook.travel(paid.data, cycle)
        assertEquals(170.0, tp.remaining)
        assertEquals(16.0, tp.spent)
        assertEquals(24.0, tp.saved)
        assertEquals(170.0, paid.data.bills.single { it.template_id == PlanBook.AUTO_TRAVEL }.est_amount.num())
        assertTrue(tp.list.first().done)
    }

    @Test
    fun salaryFromSlip() {
        val r = Store.run(base) { receiveSalary(cycle, 15234.5, "acc_bbl", "2099-10-15", "Slip 14/10") }
        assertEquals("paid", r.value.status)
        assertEquals(PlanBook.SALARY, r.value.section)
        assertEquals(15234.5, r.data.transactions.single { it.type == "income" }.amount.num())
    }
}

class SelfSaveTest {
    @Test
    fun taxAndDiscountJar() {
        val seed = Schema.seed()
        val food = seed.categories.first { it.name == "อาหาร" }
        val salaryCat = seed.categories.first { it.name == "เงินเดือน" }
        val base = seed.copy(
            accounts = listOf(
                Account("acc_cash", "เงินสด", "cash", "THB", opening_balance = BigDecimal(1000), active = true),
                Account("acc_dime", "Dime!", "bank", "THB", active = true),
            ),
        )
        val today = ism.dansha.core.Dates.todayStr()
        val r = Store.run(base) {
            setSelfSave(true, true, 1.0, mapOf(food.id to 10.0), "acc_dime")
            createTransaction(ism.dansha.core.Transaction("", today, type = "expense", account_id = "acc_cash", amount = BigDecimal(25), category_id = food.id, discount = BigDecimal(10)))
            createTransaction(ism.dansha.core.Transaction("", today, type = "income", account_id = "acc_cash", amount = BigDecimal(15000), category_id = salaryCat.id))
        }
        val st = SelfSave.status(r.data)
        assertEquals(2.5 + 150.0, st.taxDue) // อาหาร 10% ของ 25 + รายรับ 1% ของ 15000
        assertEquals(10.0, st.discountDue)
        val paid = Store.run(r.data) { paySelfSave(st.taxDue, st.discountDue, "acc_cash", "acc_dime") }
        assertEquals(0.0, SelfSave.status(paid.data).totalDue)
    }
}

class BulkTest {
    @Test
    fun pasteTemplates() {
        val text = """
            ประจำ | ค่าเช่าบ้าน | 2000 | 4
            ประจำ | ค่าไฟ | 1,888.90 | 15 | รู้ยอดบิลถัดไปวันที่ 21
            เงินเดือน | เงินเดือน | 20000 | 15
            ครั้งเดียว | คืนเงินแฟน | 300
            อะไร | ผิด | 1
        """.trimIndent()
        val lines = PlanBook.parseBulk(text)
        assertEquals(4, lines.count { it.item != null })
        assertTrue(lines.last().error != null)
        val base = Schema.seed().copy(accounts = listOf(Account("acc_bbl", "Bangkok Bank", "bank", "THB", active = true)))
        val r = Store.run(base) { addBulk(lines.mapNotNull { it.item }, "2099-10 Oct-Nov") }
        assertEquals(3 to 1, r.value)
        val fire = r.data.billTemplates.single { it.name == "ค่าไฟ" }
        assertEquals(15, fire.due_day)
        assertEquals("รู้ยอดบิลถัดไปวันที่ 21", fire.note)
        assertEquals(PlanBook.SALARY, r.data.billTemplates.single { it.name == "เงินเดือน" }.section)
        assertEquals("income", r.data.billTemplates.single { it.name == "เงินเดือน" }.group)
        assertEquals(PlanBook.ONCE, r.data.bills.single().section)
        // วางซ้ำ = แก้ของเดิม ไม่สร้างซ้ำ
        val again = Store.run(r.data) { addBulk(PlanBook.parseBulk("ประจำ | ค่าเช่าบ้าน | 2500 | 4").mapNotNull { it.item }, "2099-10 Oct-Nov") }
        assertEquals(3, again.data.billTemplates.size)
        assertEquals(2500.0, again.data.billTemplates.single { it.name == "ค่าเช่าบ้าน" }.est_amount.num())
    }
}

class TravelSkipTest {
    private val base = ism.dansha.core.Schema.seed().copy(
        accounts = listOf(ism.dansha.core.Account("acc_cash", "เงินสด", "cash", "THB", active = true)),
    )

    @org.junit.jupiter.api.Test
    fun skipLegsNotPlanned() {
        val cycle = "2099-10 Oct-Nov"
        val r = Store.run(base) {
            setWorkDays(cycle, listOf(PlanBook.WorkDay("2099-10-20", false), PlanBook.WorkDay("2099-10-21", true)))
            setTravelSkip(cycle, "2099-10-20", skipGo = true, skipBack = false, reason = "แฟนไปส่ง")
            setTravelSkip(cycle, "2099-10-21", skipGo = true, skipBack = true, reason = "")
        }
        val tp = PlanBook.travel(r.data, cycle)
        val d1 = tp.list.first { it.date == "2099-10-20" }
        kotlin.test.assertEquals(0.0, d1.planGo)
        kotlin.test.assertEquals(20.0, d1.planBack)
        kotlin.test.assertEquals("แฟนไปส่ง", d1.reason)
        val d2 = tp.list.first { it.date == "2099-10-21" }
        kotlin.test.assertTrue(d2.done && d2.late && d2.skipAll)
        kotlin.test.assertEquals(20.0, tp.total)
        // เลิกยกเว้น → เหตุผลหาย
        val r2 = Store.run(r.data) { setWorkDays(cycle, listOf(PlanBook.WorkDay("2099-10-20", false))) }
        kotlin.test.assertTrue(r2.data.config.keys.none { it.startsWith("travel_why:") })
    }
}
