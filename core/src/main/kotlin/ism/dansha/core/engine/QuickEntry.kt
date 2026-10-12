package ism.dansha.core.engine

import ism.dansha.core.Category
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp

/**
 * จดไว: พิมพ์สั้นๆ เช่น "กาแฟ 35", "ข้าวมันไก่ 50 สด", "grab 120 tm", "+15000 เงินเดือน"
 * แล้วเดาประเภท / หมวด / บัญชี จากรายการที่เคยจดไว้ (เรียนรู้จากประวัติในเครื่อง ไม่ส่งข้อมูลไปไหน)
 *
 * วิธีให้คะแนน:
 *  1. โน้ตของรายการเก่าที่คล้ายข้อความที่พิมพ์ (ตรงเป๊ะ > ขึ้นต้นเหมือน > มีคำร่วม > ตัวอักษรคล้าย)
 *     ถ่วงด้วยความใหม่ (ครึ่งชีวิต ~60 วัน) และจำนวนครั้งที่ใช้
 *  2. ชื่อหมวด/หมวดย่อยอยู่ในข้อความ หรือคำที่เกี่ยวข้อง (ข้าว → อาหาร, grab → แท็กซี่/Grab)
 *  บัญชี: ชื่อเล่นบัญชีในข้อความ (สด, tm, kbank…) > บัญชีที่ใช้บ่อยกับโน้ตนี้ > บัญชีล่าสุด
 */
object QuickEntry {
    data class Parsed(
        val amount: Double?,
        /** ข้อความที่เหลือ (ใช้เป็นโน้ต) */
        val text: String,
        /** บัญชีที่ระบุในข้อความ */
        val accountId: String?,
        /** ประเภทที่ระบุชัด ("+" นำหน้า / คำว่ารับ = income, โอน = transfer) */
        val type: String?,
    )

    data class Guess(
        val type: String,
        val categoryId: String,
        val subcategoryId: String,
        val accountId: String,
        val note: String,
        val amount: Double?,
        /** 0–1 */
        val score: Double,
    )

    private val NUMBER = Regex("""^[+]?฿?(\d{1,3}(,\d{3})+|\d+)(\.\d{1,2})?(บ(าท)?|฿|b)?$""", RegexOption.IGNORE_CASE)
    private val INCOME_WORDS = listOf("รับ", "ได้เงิน", "เงินเข้า", "income")
    private val TRANSFER_WORDS = listOf("โอน", "transfer")

    /** ชื่อเล่นบัญชี → คำที่ต้องเจอในชื่อบัญชี (หรือ "cash" = บัญชีชนิดเงินสด) */
    private val ACCOUNT_ALIASES: List<Pair<List<String>, List<String>>> = listOf(
        listOf("สด", "เงินสด", "cash") to listOf("cash"),
        listOf("tm", "true", "ทรู", "truemoney") to listOf("true"),
        listOf("kbank", "kb", "กสิกร", "kplus") to listOf("k-bank", "kbank", "กสิกร"),
        listOf("ktb", "กรุงไทย", "next") to listOf("krungthai", "ktb", "กรุงไทย"),
        listOf("bbl", "bualuang", "กรุงเทพ", "bangkok") to listOf("bangkok", "bbl", "กรุงเทพ"),
        listOf("g", "gw", "เป๋า", "เป๋าตัง", "gwallet") to listOf("g-wallet", "เป๋า", "paotang"),
        listOf("spl", "spaylater", "paylater") to listOf("paylater"),
        listOf("pn", "paynext") to listOf("paynext"),
        listOf("ex", "extra") to listOf("extra"),
        listOf("scb", "ไทยพาณิชย์") to listOf("scb", "ไทยพาณิชย์"),
    )

    /** คำที่เกี่ยวกับหมวด (ชื่อหมวดหรือหมวดย่อยตามหมวดตั้งต้น) */
    private val CATEGORY_WORDS: List<Pair<List<String>, String>> = listOf(
        listOf("กาแฟ", "coffee", "อเมซอน", "amazon", "starbucks", "ชา", "ชานม") to "ขนม/เครื่องดื่ม",
        listOf("ขนม", "น้ำเปล่า", "น้ำแข็ง", "ไอติม", "เค้ก", "โกโก้", "นมเย็น") to "ขนม/เครื่องดื่ม",
        listOf("เซเว่น", "7-11", "711", "เซเว่นฯ", "lotus", "โลตัส", "บิ๊กซี", "family", "แฟมิลี่") to "ร้านสะดวกซื้อ",
        listOf("lineman", "ไลน์แมน", "grabfood", "foodpanda", "robinhood", "เดลิ") to "เดลิเวอรี่",
        listOf("ข้าว", "ก๋วยเตี๋ยว", "ส้มตำ", "หมูกระทะ", "บุฟเฟ่", "กะเพรา", "มื้อ", "อาหาร") to "อาหารหลัก",
        listOf("grab", "แกร็บ", "แท็กซี่", "taxi", "bolt", "วิน", "มอไซ") to "แท็กซี่/Grab",
        listOf("รถเมล์", "bts", "mrt", "รถไฟ", "สองแถว", "รถตู้", "ค่ารถ") to "รถสาธารณะ",
        listOf("น้ำมัน", "เติม", "ปั๊ม") to "น้ำมัน",
        listOf("ค่าเช่า", "เช่าห้อง") to "ค่าเช่า",
        listOf("ค่าไฟ", "ไฟฟ้า") to "ค่าไฟ",
        listOf("ค่าน้ำ", "ประปา") to "ค่าน้ำ",
        listOf("เน็ต", "internet", "wifi", "3bb", "ais fibre", "true online") to "อินเทอร์เน็ต",
        listOf("youtube", "netflix", "spotify", "claude", "chatgpt", "icloud", "premium") to "Subscription",
        listOf("ค่าโทร", "เติมเงิน") to "ค่าโทรศัพท์",
        listOf("shopee", "ช้อปปี้") to "Shopee",
        listOf("เสื้อ", "กางเกง", "รองเท้า") to "เสื้อผ้า",
        listOf("ยา", "pharmacy") to "ยา",
        listOf("หมอ", "โรงพยาบาล", "คลินิก") to "โรงพยาบาล",
        listOf("เกม", "steam", "game") to "เกม",
        listOf("หนัง", "major", "sf") to "หนัง/เพลง",
        listOf("เงินเดือน", "salary") to "เงินเดือน",
        listOf("ดอกเบี้ย", "ปันผล") to "ดอกเบี้ย/ปันผล",
    )

    /** ตัดข้อความเป็น ยอด / บัญชี / ประเภท / ข้อความที่เหลือ */
    fun parse(d: DanshaData, input: String): Parsed {
        val raw = input.trim()
        var type: String? = when {
            raw.startsWith("+") -> "income"
            raw.startsWith("-") || raw.startsWith("−") -> "expense"
            else -> null
        }
        val tokens = raw.removePrefix("+").removePrefix("-").removePrefix("−").split(Regex("\\s+")).filter { it.isNotEmpty() }
        var amount: Double? = null
        var accountId: String? = null
        val rest = ArrayList<String>()
        for (tok in tokens) {
            if (amount == null && NUMBER.matches(tok)) {
                amount = Forms.parseAmount(tok.lowercase().replace(Regex("บาท|บ|฿|b|\\+"), ""))
                continue
            }
            if (accountId == null) {
                val acc = accountFor(d, tok)
                if (acc != null) { accountId = acc; continue }
            }
            rest += tok
        }
        val text = rest.joinToString(" ")
        val low = text.lowercase()
        if (type == null) {
            if (TRANSFER_WORDS.any { low.startsWith(it) }) type = "transfer"
            else if (INCOME_WORDS.any { low.startsWith(it) }) type = "income"
        }
        return Parsed(amount, text, accountId, type)
    }

    /** คำนี้หมายถึงบัญชีไหน (เฉพาะบัญชีที่เปิดใช้) */
    private fun accountFor(d: DanshaData, token: String): String? {
        val t = token.lowercase()
        if (t.length < 1) return null
        val active = d.accounts.filter { it.active }
        // ชื่อบัญชีตรงทั้งคำ
        active.firstOrNull { it.name.lowercase() == t }?.let { return it.id }
        for ((aliases, needles) in ACCOUNT_ALIASES) {
            if (t !in aliases) continue
            val hit = if (needles == listOf("cash")) active.firstOrNull { it.type == "cash" }
            else active.filter { a -> needles.any { a.name.lowercase().contains(it) || a.icon.lowercase().contains(it) } }
                // "paynext" ต้องไม่ใช่ Extra / "extra" ต้องเป็น Extra
                .sortedBy { a -> if (t in listOf("pn", "paynext") && a.name.lowercase().contains("extra")) 1 else 0 }
                .firstOrNull()
            if (hit != null) return hit.id
        }
        return null
    }

    /** ข้อความสำหรับเทียบ: ตัวพิมพ์เล็ก ตัดเลข/เครื่องหมาย */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[0-9.,฿+\\-−()\\[\\]\"'!?:;/]"), " ").replace(Regex("\\s+"), " ").trim()

    /** ความคล้ายของข้อความ 0–1 */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (b.startsWith(a) || a.startsWith(b)) return 0.6 + 0.35 * minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length)
        val wa = a.split(' ').toSet()
        val wb = b.split(' ').toSet()
        val common = wa.intersect(wb).filter { it.length >= 2 }
        val wordScore = if (common.isNotEmpty()) 0.55 + 0.25 * common.size / maxOf(wa.size, wb.size) else 0.0
        val containScore = if (a.length >= 2 && (b.contains(a) || a.contains(b))) 0.6 else 0.0
        val dice = dice(a, b) * 0.7
        return maxOf(wordScore, containScore, dice)
    }

    private fun bigrams(s: String): List<String> = s.replace(" ", "").windowed(2)

    private fun dice(a: String, b: String): Double {
        val x = bigrams(a)
        val y = bigrams(b).toMutableList()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var hit = 0
        for (g in x) { val i = y.indexOf(g); if (i >= 0) { hit++; y.removeAt(i) } }
        return 2.0 * hit / (x.size + bigrams(b).size)
    }

    private data class Key(val type: String, val cat: String, val sub: String)

    /**
     * คำแนะนำเรียงจากน่าจะใช่ที่สุด (สูงสุด limit รายการ หมวดไม่ซ้ำกัน)
     * ไม่มีข้อความ = แนะนำจากรายการที่จดบ่อยช่วงนี้
     */
    fun suggest(d: DanshaData, input: String, today: LocalDate = Dates.today(), limit: Int = 3, hour: Int = java.time.LocalTime.now().hour): List<Guess> {
        val p = parse(d, input)
        val q = normalize(p.text)
        val cats = d.categories.associateBy { it.id }
        val activeAcc = d.accounts.filter { it.active }.map { it.id }.toSet()
        val scores = HashMap<Key, Double>()
        val accVotes = HashMap<Key, HashMap<String, Double>>()
        val notes = HashMap<Key, Pair<String, Double>>()
        // ยอดที่จ่ายบ่อยของรายการนั้น (เช่น กาแฟ 55 ทุกวัน) → เติมยอดให้เองเมื่อพิมพ์แค่ชื่อ
        val typical = HashMap<Key, Pair<Double, Double>>()

        // 1) จากประวัติ
        for (t in d.transactions) {
            if (t.type == "transfer" || t.category_id.isEmpty()) continue
            if (p.type != null && t.type != p.type) continue
            val n = normalize(t.note.ifEmpty { cats[t.subcategory_id]?.name ?: cats[t.category_id]?.name.orEmpty() })
            val sim = if (q.isEmpty()) 0.3 else similarity(q, n)
            if (sim < 0.45 && q.isNotEmpty()) continue
            val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(t.date), today).coerceAtLeast(0) }.getOrDefault(365L)
            var base = sim
            // ยอดเท่ากันเป๊ะ = น่าจะเป็นรายการเดิม (กาแฟ 55, ค่ารถ 12)
            val amt = t.amount.num()
            if (p.amount != null && amt > 0) {
                val diff = kotlin.math.abs(amt - p.amount)
                base += when { diff < 0.005 -> 0.5; diff <= amt * 0.1 -> 0.1; else -> 0.0 }
            }
            // จดช่วงเวลาเดียวกันของวัน (±2 ชม.) มักเป็นรายการเดิม เช่น กาแฟเช้า ข้าวเที่ยง
            val h = t.created_at.drop(11).take(2).toIntOrNull()
            if (h != null && kotlin.math.abs(h - hour) <= 2) base *= 1.25
            val w = base * (0.4 + 0.6 * exp(-age / 60.0))
            val k = Key(t.type, t.category_id, t.subcategory_id)
            if (amt > 0 && sim >= 0.6 && w > (typical[k]?.second ?: 0.0)) typical[k] = amt to w
            scores[k] = (scores[k] ?: 0.0) + w
            if (t.account_id in activeAcc) accVotes.getOrPut(k) { HashMap() }.merge(t.account_id, w, Double::plus)
            if (t.note.isNotEmpty() && sim > (notes[k]?.second ?: 0.0)) notes[k] = t.note to sim
        }
        // ปรับคะแนนประวัติให้อยู่ราว 0–1 (ยิ่งใช้บ่อยยิ่งมั่นใจ แต่ไม่เกิน 1)
        val hist = scores.mapValues { (_, s) -> 1 - exp(-s / 1.2) }.toMutableMap()

        // 2) จากชื่อหมวด / คำที่เกี่ยวข้อง
        if (q.isNotEmpty()) {
            for (c in d.categories) {
                if (!c.active || (p.type != null && c.type != p.type)) continue
                val name = c.name.lowercase()
                // ชื่อสั้น (เช่น "ยา") ต้องตรงทั้งคำ ไม่งั้น "โรงพยาบาล" จะกลายเป็นหมวดยา
                val hit = q.split(' ').any { it == name } || (name.length >= 3 && q.contains(name)) || (name.length >= 3 && q.length >= 3 && name.contains(q))
                if (!hit) continue
                val k = keyOf(c, cats)
                hist[k] = maxOf(hist[k] ?: 0.0, (0.7 + 0.01 * name.length).coerceAtMost(0.8))
            }
            val tokens = q.split(' ')
            for ((words, catName) in CATEGORY_WORDS) {
                // ภาษาไทยไม่เว้นวรรค จึงดูว่ามีคำนี้อยู่ในข้อความ (คำสั้น 1–2 ตัวต้องตรงทั้งคำ) คำยาวกว่าได้คะแนนมากกว่า
                val best = words.filter { w -> tokens.any { it == w } || (w.length >= 3 && q.contains(w)) }.maxOfOrNull { it.length } ?: continue
                val c = d.categories.firstOrNull { it.active && it.name.equals(catName, ignoreCase = true) && (p.type == null || it.type == p.type) } ?: continue
                val k = keyOf(c, cats)
                hist[k] = maxOf(hist[k] ?: 0.0, (0.5 + 0.02 * best).coerceAtMost(0.7))
            }
        }

        val fallbackType = p.type ?: "expense"
        val ranked = hist.entries.sortedByDescending { it.value }.take(limit)
        return ranked.map { (k, s) ->
            val acc = p.accountId
                ?: accVotes[k]?.maxByOrNull { it.value }?.key
                ?: Forms.defaultAccount(d, k.type)
            Guess(
                k.type, k.cat, k.sub, acc, p.text.ifEmpty { notes[k]?.first.orEmpty() },
                p.amount ?: typical[k]?.first?.takeIf { q.isNotEmpty() }, s.coerceIn(0.0, 1.0),
            )
        }.ifEmpty {
            if (p.amount == null && p.text.isEmpty()) emptyList()
            else listOf(Guess(fallbackType, "", "", p.accountId ?: Forms.defaultAccount(d, fallbackType), p.text, p.amount, 0.0))
        }
    }

    private fun keyOf(c: Category, cats: Map<String, Category>): Key =
        if (c.parent_id.isEmpty()) Key(c.type, c.id, "") else Key(c.type, cats[c.parent_id]?.id ?: c.parent_id, c.id)
}
