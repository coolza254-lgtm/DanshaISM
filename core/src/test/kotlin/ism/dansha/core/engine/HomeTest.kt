package ism.dansha.core.engine

import ism.dansha.core.DataFile
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTest {
    @Test
    fun homeFiguresOnSampleData() {
        val file = File(System.getProperty("dansha.reference"), "sample-data.json")
        val d = DataFile.parse(file.readText())
        val f = Home.figures(Engine.overview(d, LocalDateTime.parse("2026-10-03T12:00:00")))
        assertEquals(24761.0, f.cashNow)
        assertEquals(17261.0, f.projected)
        assertEquals(12, f.daysLeft) // 3–14 ต.ค.
        assertEquals(1438.42, f.perDay)
        // วงเงินยาว 5812.01 + สั้น 1459.5 + ค้างจ่าย 2920 + PayLater 1475 + บัตร (1200 − 850 = 350)
        assertEquals(12016.51, f.debtTotal)
        assertEquals(NextDebtBill("วงเงินค้างจ่าย", "2026-10-01", 385.44, true), f.nextDebtBill)
        val c = f.copay!!
        assertEquals(59, c.daysLeft) // 3 ต.ค. – 30 พ.ย.
        assertEquals(200.0, c.leftToday)
        assertEquals(333.33, c.fullPriceForToday)
        assertEquals(910.0, c.totalLeft)
        // รอบบิล 16 ก.ย. – 15 ต.ค.: เบิกเงินสด 3,000 / ซื้อ 120.5 + 89 + 260 + 75 + ยอดยกมา 350
        assertEquals(listOf("วงเงินผ่อนยาว" to 3000.0, "วงเงินผ่อนสั้น" to 894.5, "วงเงินค้างจ่าย" to 0.0), f.cycleSpend.map { it.first to it.second.total })
    }

    @Test
    fun spentOnDay() {
        val seed = ism.dansha.core.Schema.seed()
        val acc = listOf(
            ism.dansha.core.Account("a", "เงินสด", "cash", "THB", active = true),
            ism.dansha.core.Account("u", "USD", "bank", "USD", active = true),
        )
        fun t(id: String, type: String, acc: String, amt: String, date: String = "2026-11-01") =
            ism.dansha.core.Transaction(id, date, type = type, account_id = acc, to_account_id = if (type == "transfer") "u" else "", amount = java.math.BigDecimal(amt))
        val d = seed.copy(
            accounts = acc,
            fx = listOf(ism.dansha.core.FxRate("USD", manual_rate = java.math.BigDecimal(35))),
            transactions = listOf(t("1", "expense", "a", "35.5"), t("2", "expense", "u", "2"), t("3", "income", "a", "100"), t("4", "transfer", "a", "50"), t("5", "expense", "a", "99", "2026-10-31")),
        )
        assertEquals(105.5, Home.spentOn(d, "2026-11-01"))
    }
}
