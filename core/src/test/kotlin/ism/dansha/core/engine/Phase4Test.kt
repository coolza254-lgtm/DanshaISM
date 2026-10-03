package ism.dansha.core.engine

import ism.dansha.core.DataFile
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase4Test {
    private val d = DataFile.parse(File(System.getProperty("dansha.reference"), "sample-data.json").readText())

    @Test
    fun periodSummary() {
        val s = Summary.period(d, "2026-10-01", "2026-10-14")
        // รายจ่าย: 20 + 40 + 260 + 599 + 350 + 259 + 75 · รายรับ 20 USD × 33.5
        assertEquals(1603.0, s.expense)
        assertEquals(670.0, s.income)
        assertEquals(-933.0, s.net)
        assertEquals(listOf("cat_shop" to 869.0, "cat_bill" to 599.0, "cat_food" to 135.0), s.expenseByCategory.map { it.categoryId to it.amount })
        assertEquals(54.21, s.expenseByCategory[0].share)
        assertEquals(14, s.daily.size)
        assertEquals(599.0, s.daily.first { it.date == "2026-10-05" }.expense)
        assertEquals(90.0, s.govSubsidy)
        assertEquals(114.5, s.avgExpensePerDay)
        // โอนไม่นับเป็นรายรับ/รายจ่าย แต่แสดงในรายการ
        assertTrue(s.transactions.any { it.type == "transfer" })
    }

    @Test
    fun presets() {
        val t = LocalDate.parse("2026-10-03")
        assertEquals("2026-09-15" to "2026-10-14", Summary.range(Summary.Preset.ThisCycle, t, 15))
        assertEquals("2026-08-15" to "2026-09-14", Summary.range(Summary.Preset.LastCycle, t, 15))
        assertEquals("2026-09-01" to "2026-09-30", Summary.range(Summary.Preset.LastMonth, t, 15))
        assertEquals("2026-09-27" to "2026-10-03", Summary.range(Summary.Preset.Last7, t, 15))
    }

    @Test
    fun notifyTiming() {
        val on = mapOf("daily_summary_enabled" to "true", "daily_summary_hour" to "21")
        assertEquals(emptyList(), Notify.dueJobs(on, 20, false, false))
        assertEquals(listOf(Notify.Job.Summary, Notify.Job.Remind), Notify.dueJobs(on, 21, false, false))
        assertEquals(listOf(Notify.Job.Remind), Notify.dueJobs(on, 23, true, false))
        val off = mapOf("daily_summary_enabled" to "false")
        assertEquals(emptyList(), Notify.dueJobs(off, 8, false, false))
        assertEquals(listOf(Notify.Job.Remind), Notify.dueJobs(off, 9, false, false))
    }

    @Test
    fun messages() {
        // ตรงกับหัวข้ออีเมลของระบบเดิม: "ใกล้ครบกำหนด 1 รายการ · รวม ฿477.50"
        val r = Notify.reminders(d, LocalDateTime.parse("2026-10-30T12:00:00"))!!
        assertEquals("ใกล้ครบกำหนด 1 รายการ · รวม ฿477.50", r.title)
        val s = Notify.dailySummary(d, LocalDateTime.parse("2026-10-03T21:00:00"))
        assertEquals("断捨ISM สรุป 3 ต.ค.", s.title)
        assertTrue(s.body.startsWith("เงินที่มีตอนนี้ ฿24,761.00\nหนี้รวม ฿12,016.51\nคงเหลือหลังแผนรอบนี้ ฿17,261.00"), s.body)
        assertTrue(s.body.contains("โครงการร่วมจ่ายตัวอย่าง: รัฐยังช่วยได้วันนี้ ฿200.00"), s.body)
    }

    @Test
    fun fxFeed() {
        val body = """{"result":"success","base_code":"THB","rates":{"THB":1,"USD":0.02983,"JPY":4.5,"EUR":0.027}}"""
        assertEquals(mapOf("USD" to 33.523299, "JPY" to 0.222222), FxFeed.parse(body, listOf("THB", "USD", "JPY", "CNY")))
    }
}
