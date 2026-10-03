package ism.dansha.core.engine

import ism.dansha.core.DanshaData
import java.time.LocalDateTime

/*
 * แจ้งเตือนในเครื่อง + ข้อความแชร์สรุป (แทนอีเมลของระบบเดิม mail.js)
 */

data class NotifyMessage(val title: String, val body: String)

object Notify {
    enum class Job { Summary, Remind }

    /**
     * งานที่ถึงเวลาแจ้งตอนนี้ (dueMailJobs_ เดิม): แจ้งเมื่อถึงชั่วโมงที่ตั้งแล้ว (เลยมาได้) และยังไม่ได้แจ้งวันนี้
     * แจ้งเตือนครบกำหนดใช้ชั่วโมงเดียวกับสรุปรายวัน หรือ 9 โมงถ้าปิดสรุปรายวัน
     */
    fun dueJobs(cfg: Map<String, String>, hour: Int, sentSummary: Boolean, sentRemind: Boolean): List<Job> {
        val summaryOn = cfg["daily_summary_enabled"] == "true"
        val sendHour = if (summaryOn) jsNumber(cfg["daily_summary_hour"]).toInt() else 9
        if (hour < sendHour) return emptyList()
        val jobs = ArrayList<Job>()
        if (summaryOn && !sentSummary) jobs += Job.Summary
        if (!sentRemind) jobs += Job.Remind
        return jobs
    }

    /** แจ้งเตือนครบกำหนดหนี้/บิล (null = ไม่มีอะไรต้องแจ้ง) */
    fun reminders(d: DanshaData, now: LocalDateTime): NotifyMessage? {
        val items = Reminders.reminderItems(d, Engine.config(d), now.toLocalDate().toString())
        if (items.isEmpty()) return null
        val total = items.fold(0.0) { s, x -> s + x.amount }
        val lines = items.joinToString("\n") { x ->
            val whenText = when {
                x.overdue -> "เลยกำหนด"
                x.days == 0 -> "วันนี้!"
                else -> "อีก ${x.days} วัน"
            }
            "• ${x.name} ${Reminders.money(x.amount)} ($whenText · ${thaiDateShort(x.due)})"
        }
        return NotifyMessage("ใกล้ครบกำหนด ${items.size} รายการ · รวม ${Reminders.money(total)}", lines)
    }

    /** สรุปรายวัน: ใช้ทั้งแจ้งเตือนและปุ่มแชร์ให้แฟน */
    fun dailySummary(d: DanshaData, now: LocalDateTime): NotifyMessage {
        val ov = Engine.overview(d, now)
        val h = Home.figures(ov)
        val today = ov.today
        val tx = d.transactions.filter { it.date == today }
        val inc = tx.filter { it.type == "income" }.sumOf { it.amount.num() }
        val exp = tx.filter { it.type == "expense" }.sumOf { it.amount.num() }
        val sb = StringBuilder()
        sb.append("เงินที่มีตอนนี้ ").append(Reminders.money(h.cashNow)).append('\n')
        sb.append("หนี้รวม ").append(Reminders.money(h.debtTotal)).append('\n')
        sb.append("คงเหลือหลังแผนรอบนี้ ").append(if (ov.plan.count > 0) Reminders.money(ov.plan.projected) else "— (ยังไม่มีแผน)").append('\n')
        sb.append("ใช้ได้วันละ ").append(Reminders.money(h.perDay)).append(" (อีก ${h.daysLeft} วัน)").append('\n')
        ov.port?.let { sb.append("พอร์ต ").append(Reminders.money(it.valueThb)).append(" (").append(jsNumStr(it.unrealizedPct)).append("%)").append('\n') }
        sb.append('\n').append("วันนี้: รับ ").append(Reminders.money(inc)).append(" · จ่าย ").append(Reminders.money(exp)).append(" · ${tx.size} รายการ")
        val cats = d.categories.associateBy { it.id }
        tx.take(10).forEach { t ->
            val c = cats[t.subcategory_id.ifEmpty { t.category_id }] ?: cats[t.category_id]
            val sign = when (t.type) { "income" -> "+"; "expense" -> "−"; else -> "" }
            sb.append('\n').append("• ").append(t.note.ifEmpty { c?.name ?: if (t.type == "transfer") "โอน" else "" }).append(' ').append(sign)
                .append(Reminders.money(t.amount.num()).removePrefix("−"))
        }
        val dues = Reminders.upcomingDues(d, today, 10)
        if (dues.isNotEmpty()) {
            sb.append("\n\nครบกำหนดใน 10 วัน")
            dues.forEach { x -> sb.append('\n').append("• ").append(x.name).append(' ').append(Reminders.money(x.amount)).append(" (").append(if (x.overdue) "เลยกำหนด " else "").append(thaiDateShort(x.due)).append(')') }
        }
        ov.copay?.takeIf { it.active }?.let { c ->
            sb.append("\n\n").append(c.config.name).append(": รัฐยังช่วยได้วันนี้ ").append(Reminders.money(c.usage.leftToday))
                .append(" · เดือนนี้ใช้ไป ").append(Reminders.money(c.usage.month))
        }
        return NotifyMessage("断捨ISM สรุป ${thaiDateShort(today)}", sb.toString())
    }

    private val TH_MONTH = listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")

    /** "2026-10-03" → "3 ต.ค." */
    fun thaiDateShort(iso: String): String {
        val p = iso.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size < 3) return iso
        return "${p[2]} ${TH_MONTH[p[1] - 1]}"
    }
}

/** อ่านอัตราแลกเปลี่ยนจากเว็บ (ฐาน THB) → บาทต่อ 1 หน่วยของแต่ละสกุล */
object FxFeed {
    /** body ของ open.er-api.com/v6/latest/THB หรือ api.frankfurter.app/latest?from=THB → { USD: 33.5, ... } */
    fun parse(body: String, currencies: List<String>): Map<String, Double> {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
        val rates = root["rates"] as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        currencies.filter { it != "THB" }.forEach { c ->
            val perThb = (rates[c] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: return@forEach
            if (perThb > 0) out[c] = round6(1 / perThb)
        }
        return out
    }
}
