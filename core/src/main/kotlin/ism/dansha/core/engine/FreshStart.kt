package ism.dansha.core.engine

import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction

/*
 * เริ่มใหม่ (ล้างประวัติ) — แอพ Android
 * ลบรายการเก่า แผนบิล และออเดอร์จ่ายเต็ม แต่เก็บสิ่งที่ยังใช้คำนวณอยู่:
 *  - บัญชี หมวดหมู่ แม่แบบ ตั้งค่า หนี้ย่อย พอร์ต อัตราแลกเปลี่ยน
 *  - รายการของบัญชี Ascend/SPayLater ตั้งแต่ debt_since (ตัวคำนวณหนี้ใช้ ลบแล้วยอดหนี้ผิด)
 *  - รายการที่ใช้สิทธิ 60/40 ในช่วงโครงการ (นับเพดานที่ใช้ไปแล้ว)
 *  - ออเดอร์ SPayLater ที่ไม่ได้ยกเลิก (ยอดจ่ายรวมตัดงวดตามลำดับ ลบงวดที่จ่ายครบแล้วยอดจะเพี้ยน)
 * ยอดตั้งต้นของบัญชีอื่นตั้งใหม่ ให้คงเหลือเท่ายอดจริงที่กรอก (ไม่กรอก = เท่ายอดในแอพตอนนี้)
 */
object FreshStart {
    /** config: วันที่เริ่มใหม่ */
    const val KEY = "fresh_start"

    /** รายการนี้ต้องเก็บไว้ไหม */
    fun keeps(d: DanshaData): (Transaction) -> Boolean {
        val since = d.accounts.filter { isEngineAccount(it) || isSplAccount(it) }.associate { it.id to it.debt_since.ifEmpty { "0000-00-00" } }
        val cc = Copay.config(Engine.config(d))
        return { t ->
            val a = since[t.account_id]
            val b = since[t.to_account_id]
            (a != null && t.date >= a) || (b != null && t.date >= b) ||
                (cc.enabled && t.gov_subsidy.num() > 0 && t.date >= cc.start && t.date <= cc.end)
        }
    }

    /** ออเดอร์ที่ลบได้: จ่ายเต็ม (เป็นแค่ประวัติ) และที่ยกเลิกแล้ว */
    private fun removableOrder(payMethod: String, status: String) = payMethod != "spaylater" || status == "cancelled"

    data class Preview(val removeTxns: Int, val keepTxns: Int, val removeBills: Int, val removeOrders: Int)

    fun preview(d: DanshaData): Preview {
        val keep = keeps(d)
        val kept = d.transactions.count(keep)
        return Preview(
            removeTxns = d.transactions.size - kept, keepTxns = kept, removeBills = d.bills.size,
            removeOrders = d.shopee.count { removableOrder(it.pay_method, it.status) },
        )
    }

    /** ใช้ใน Store.freshStart */
    internal fun removable(o: ism.dansha.core.ShopeeOrder) = removableOrder(o.pay_method, o.status)

    /** ผลรวมเงินเข้า−ออกของแต่ละบัญชีจากรายการชุดหนึ่ง (เหมือน Balances) */
    internal fun flows(txns: List<Transaction>): Map<String, Double> {
        val flow = HashMap<String, Double>()
        txns.forEach { t ->
            val amt = t.amount.num()
            when (t.type) {
                "income" -> flow[t.account_id] = (flow[t.account_id] ?: 0.0) + amt
                "expense" -> flow[t.account_id] = (flow[t.account_id] ?: 0.0) - amt
                "transfer" -> {
                    flow[t.account_id] = (flow[t.account_id] ?: 0.0) - amt
                    flow[t.to_account_id] = (flow[t.to_account_id] ?: 0.0) + amt
                }
            }
        }
        return flow
    }
}

/*
 * เช็คลิสต์เริ่มต้นใช้งาน (หน้าภาพรวม) หลังเริ่มใหม่ หรือยังไม่มีรายการ — ซ่อนได้ (config setup_done)
 */
object Setup {
    const val DONE_KEY = "setup_done"

    enum class Step { Accounts, Income, Fixed, Plan, FirstEntry }

    data class Item(val step: Step, val title: String, val hint: String, val done: Boolean)

    fun items(d: DanshaData, today: String = Dates.todayStr()): List<Item> {
        val cfg = Engine.config(d)
        val cycle = Dates.payCycleOf(today, Engine.startDay(cfg))
        val tpl = d.billTemplates.filter { it.active }
        val since = cfg[FreshStart.KEY].orEmpty()
        return listOf(
            Item(Step.Accounts, "บัญชีที่ใช้จริง + ยอดตอนนี้", "เงินสด ธนาคาร วอลเล็ท (บัตร/หนี้เพิ่มในหน้าบัญชีได้)",
                d.accounts.any { it.active && it.type != "revolving_credit" }),
            Item(Step.Income, "รายรับประจำ", "เงินเดือน วันที่เงินเข้า — ใช้คิดว่าสิ้นรอบเหลือเท่าไหร่", tpl.any { it.group == "income" }),
            Item(Step.Fixed, "รายจ่ายประจำ", "ค่าเช่า ค่าเน็ต ค่าโทรศัพท์ … (หนี้ที่มีตัวคำนวณดึงยอดให้เอง)", tpl.any { it.group != "income" }),
            Item(Step.Plan, "สร้างแผนรอบนี้", "กดปุ่มเดียวในแผนบิล แอพสร้างจากแม่แบบให้",
                d.bills.any { it.pay_cycle == cycle && it.template_id.isNotEmpty() && !it.template_id.startsWith("auto:") && !it.template_id.startsWith(PlanBook.CLAIM_PREFIX) }),
            Item(Step.FirstEntry, "จดรายการแรก", "พิมพ์สั้นๆ เช่น กาแฟ 35 แล้วจดทุกวัน (แอพเตือนตอน 2 ทุ่มถ้ายังไม่ได้จด)",
                d.transactions.any { it.source == "manual" && it.created_at.take(10) >= since }),
        )
    }

    /** แสดงการ์ดไหม: ยังไม่ซ่อน + (เพิ่งเริ่มใหม่ หรือยังไม่มีรายการ) + ยังทำไม่ครบ */
    fun show(d: DanshaData, today: String = Dates.todayStr()): Boolean {
        val cfg = Engine.config(d)
        if (cfg[DONE_KEY] == "true") return false
        if (!cfg.containsKey(FreshStart.KEY) && d.transactions.isNotEmpty()) return false
        return items(d, today).any { !it.done }
    }
}
