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
}
