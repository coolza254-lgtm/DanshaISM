package ism.dansha.core

/** ชนิดคอลัมน์และค่าตั้งต้น (ตรงกับ engine/schema.js) */
object Schema {
    const val FORMAT = "dansha-data/1"

    /** ชื่อตารางตามลำดับในไฟล์ส่งออก */
    val TABLES = listOf(
        "accounts", "transactions", "bills", "billTemplates", "debts",
        "shopee", "port", "categories", "fx", "prices",
    )

    /** คอลัมน์ที่เป็นคีย์ของแต่ละตาราง */
    fun keyOf(table: String): String = when (table) {
        "fx" -> "currency"
        "prices" -> "symbol"
        else -> "id"
    }

    val NUMERIC = setOf(
        "opening_balance", "credit_limit", "sort", "amount", "est_amount", "actual_amount", "principal", "tenor",
        "annual_rate", "installment", "price", "qty", "fx_rate", "fee", "tax", "due_day", "auto_rate", "manual_rate",
        "rate_to_thb", "full_price", "gov_subsidy", "tenor_min", "tenor_max", "snap_remaining", "snap_paid_periods",
    )
    val BOOLEAN = setOf("active")
    val DATE_COLS = setOf(
        "date", "txn_date", "first_due_date", "order_date", "due_date", "paid_date", "debt_since",
        "snap_date", "snap_last_interest_date",
    )

    val CURRENCIES = listOf("THB", "USD", "JPY", "CNY")
    val ACCOUNT_TYPES = listOf("cash", "bank", "revolving_credit")

    /** ค่าตั้งต้นของ config: key → (ค่า, คำอธิบาย) */
    val DEFAULT_CONFIG: Map<String, Pair<String, String>> = linkedMapOf(
        "base_currency" to ("THB" to "สกุลเงินหลัก"),
        "pay_cycle_start_day" to ("15" to "วันเริ่มรอบเงินเดือน"),
        "theme" to ("mono" to "pastel | mono"),
        "summary_emails" to ("" to "อีเมลผู้รับสรุป (ระบบเดิม ไม่ใช้ในแอพนี้)"),
        "reminder_emails" to ("" to "อีเมลรับแจ้งเตือน (ระบบเดิม ไม่ใช้ในแอพนี้)"),
        "daily_summary_enabled" to ("false" to "แจ้งเตือนสรุปรายวัน"),
        "daily_summary_hour" to ("21" to "ชั่วโมงที่แจ้งสรุปรายวัน (0-23)"),
        "debt_reminder_enabled" to ("true" to "แจ้งเตือนครบกำหนดหนี้"),
        "debt_reminder_days" to ("3,1" to "แจ้งล่วงหน้ากี่วัน คั่นด้วย ,"),
        "bill_reminder_enabled" to ("false" to "แจ้งเตือนบิลในแผนบิล"),
        "bill_reminder_days" to ("1" to "แจ้งบิลล่วงหน้ากี่วัน"),
        "shop_warn_pct" to ("30" to "เช็คก่อนซื้อ: เตือนเมื่อราคาเกินกี่ % ของเงินที่เหลือรอบนี้"),
        "shop_buffer" to ("0" to "เช็คก่อนซื้อ: เงินกันไว้ขั้นต่ำต่อรอบ (บาท)"),
        "copay_enabled" to ("true" to "โครงการร่วมจ่าย (ไทยช่วยไทยพลัส 60/40)"),
        "copay_name" to ("ไทยช่วยไทยพลัส 60/40 เฟส 2" to "ชื่อโครงการ"),
        "copay_account_id" to ("" to "บัญชี G-Wallet (id ของบัญชี)"),
        "copay_gov_rate" to ("60" to "รัฐช่วยจ่ายกี่ %"),
        "copay_daily_cap" to ("200" to "รัฐช่วยสูงสุดต่อวัน (บาท)"),
        "copay_monthly_cap" to ("1000" to "รัฐช่วยสูงสุดต่อเดือน ไม่สะสมข้ามเดือน"),
        "copay_total_cap" to ("1000" to "รัฐช่วยสูงสุดทั้งโครงการ"),
        "copay_start" to ("2026-10-01" to "วันเริ่มใช้สิทธิ"),
        "copay_end" to ("2026-11-30" to "วันสุดท้าย"),
        "copay_hours" to ("06:00-23:00" to "ช่วงเวลาสแกนจ่ายได้"),
    )

    /** config ที่ใช้จริง = ค่าตั้งต้น + ค่าที่บันทึกไว้ทับ (เหมือน readConfig_) */
    fun effectiveConfig(stored: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        DEFAULT_CONFIG.forEach { (k, v) -> out[k] = v.first }
        out.putAll(stored)
        return out
    }

    /** หมวดหมู่ตั้งต้น: ชื่อ, type, สี, ไอคอน, หมวดย่อย */
    private data class SeedCategory(val name: String, val type: String, val color: String, val icon: String, val subs: List<String>)

    private val DEFAULT_CATEGORIES = listOf(
        SeedCategory("เงินเดือน", "income", "#B8E0D2", "💼", listOf()),
        SeedCategory("รายได้เสริม", "income", "#C7E9F1", "✨", listOf("ฟรีแลนซ์", "ขายของ")),
        SeedCategory("ดอกเบี้ย/ปันผล", "income", "#D6EADF", "🌱", listOf()),
        SeedCategory("รายรับอื่นๆ", "income", "#E2F0CB", "➕", listOf()),
        SeedCategory("อาหาร", "expense", "#FFD6E0", "🍜", listOf("อาหารหลัก", "ขนม/เครื่องดื่ม", "ร้านสะดวกซื้อ", "เดลิเวอรี่")),
        SeedCategory("เดินทาง", "expense", "#C9E4FF", "🚆", listOf("รถสาธารณะ", "แท็กซี่/Grab", "น้ำมัน")),
        SeedCategory("ที่พัก/บ้าน", "expense", "#E7D8FF", "🏠", listOf("ค่าเช่า", "ค่าไฟ", "ค่าน้ำ", "อินเทอร์เน็ต")),
        SeedCategory("โทรศัพท์/สมาชิก", "expense", "#D4F1F4", "📱", listOf("ค่าโทรศัพท์", "Subscription")),
        SeedCategory("ช้อปปิ้ง", "expense", "#FFE5EC", "🛍️", listOf("Shopee", "เสื้อผ้า", "ของใช้")),
        SeedCategory("สุขภาพ", "expense", "#D8F3DC", "💊", listOf("ยา", "โรงพยาบาล")),
        SeedCategory("บันเทิง", "expense", "#FDE2E4", "🎮", listOf("เกม", "หนัง/เพลง", "เที่ยว")),
        SeedCategory("ครอบครัว/แฟน", "expense", "#FAD2E1", "💗", listOf()),
        SeedCategory("ชำระหนี้", "expense", "#E2ECF9", "💳", listOf("ดอกเบี้ย", "ค่าธรรมเนียม")),
        SeedCategory("ลงทุน", "expense", "#DDF3F5", "📈", listOf()),
        SeedCategory("อื่นๆ", "expense", "#EEEEEE", "📦", listOf()),
    )

    /** ข้อมูลตั้งต้นของผู้ใช้ใหม่: config + หมวดหมู่ + สกุลเงิน (เหมือน seedDb) */
    fun seed(): DanshaData {
        val cats = ArrayList<Category>()
        var sort = 0
        DEFAULT_CATEGORIES.forEach { c ->
            val id = Ids.newId("cat")
            cats += Category(id, c.name, c.type, "", c.color, c.icon, sort++, true)
            c.subs.forEach { s -> cats += Category(Ids.newId("cat"), s, c.type, id, c.color, c.icon, sort++, true) }
        }
        val one = java.math.BigDecimal.ONE
        return DanshaData(
            config = DEFAULT_CONFIG.mapValues { it.value.first },
            categories = cats,
            fx = CURRENCIES.map { c ->
                if (c == "THB") FxRate(c, one, null, one, "") else FxRate(c)
            },
        )
    }
}

object Ids {
    /** id แบบเดิม: prefix_ + 12 ตัวอักษร hex เช่น txn_51d64cd50a4f */
    fun newId(prefix: String): String =
        prefix + "_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
}
