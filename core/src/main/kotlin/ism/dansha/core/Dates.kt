package ism.dansha.core

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** เวลาไทยเสมอ ไม่ว่าเครื่องตั้งโซนไหน (เหมือน store.js) */
object Dates {
    val ZONE: ZoneId = ZoneId.of("Asia/Bangkok")
    private val MONTHS_EN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /** นาฬิกาที่ใช้ทั้งแอพ (เปลี่ยนได้ตอนทดสอบ) */
    @Volatile
    var clock: Clock = Clock.system(ZONE)

    fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(ZONE))
    fun today(): LocalDate = now().toLocalDate()
    fun todayStr(): String = today().toString()
    fun nowIso(): String = now().withNano(0).format(ISO_SECONDS)

    /** 'yyyy-MM-dd...' → LocalDate (ตัดส่วนเวลาทิ้ง) */
    fun parse(s: String): LocalDate {
        require(Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(s)) { "รูปแบบวันที่ต้องเป็น yyyy-MM-dd: $s" }
        return LocalDate.parse(s.substring(0, 10))
    }

    /**
     * รอบเงินเดือน: เริ่มวันที่ startDay ถึงวันก่อนหน้าของเดือนถัดไป
     * วันที่น้อยกว่า startDay นับเป็นรอบของเดือนก่อน → "2026-09 Sep-Oct"
     */
    fun payCycleOf(date: LocalDate, startDay: Int = 15): String {
        val start = if (date.dayOfMonth < startDay) date.minusMonths(1) else date
        val m = start.monthValue - 1
        return "%04d-%02d %s-%s".format(start.year, m + 1, MONTHS_EN[m], MONTHS_EN[(m + 1) % 12])
    }

    fun payCycleOf(date: String, startDay: Int = 15): String = payCycleOf(parse(date), startDay)

    /** ช่วงวันที่ของรอบ "yyyy-MM ..." → (start, end) */
    fun payCycleRange(cycle: String, startDay: Int = 15): Pair<LocalDate, LocalDate> {
        val y = cycle.substring(0, 4).toInt()
        val m = cycle.substring(5, 7).toInt()
        val first = LocalDate.of(y, m, 1)
        // เหมือน new Date(y, m, startDay) ของ JS: วันที่เกินเดือนจะล้นไปเดือนถัดไป
        val start = first.plusDays((startDay - 1).toLong())
        val end = first.plusMonths(1).plusDays((startDay - 2).toLong())
        return start to end
    }
}
