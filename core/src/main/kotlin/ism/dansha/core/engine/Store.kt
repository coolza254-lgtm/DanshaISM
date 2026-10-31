package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.Bill
import ism.dansha.core.BillTemplate
import ism.dansha.core.Category
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Debt
import ism.dansha.core.FxRate
import ism.dansha.core.Ids
import ism.dansha.core.PortTxn
import ism.dansha.core.Price
import ism.dansha.core.Schema
import ism.dansha.core.ShopeeOrder
import ism.dansha.core.Transaction
import java.math.BigDecimal

/*
 * การแก้ไขข้อมูลทั้งหมด (port จาก api.js, plan.js, shopee.js, copay.js)
 * ใช้ผ่าน Store.run(data) { ... } : ทำบนสำเนา ถ้า error ระหว่างทางจะไม่มีอะไรเปลี่ยน (เหมือน post() เดิม)
 *
 * update* รับแถวทั้งแถวที่แก้แล้ว (= แถวเดิม + ส่วนที่แก้) แล้วตรวจ/คำนวณใหม่เหมือน update_ เดิม
 */
class Store private constructor(d: DanshaData) {
    val config = LinkedHashMap(d.config)
    val accounts = ArrayList(d.accounts)
    val transactions = ArrayList(d.transactions)
    val bills = ArrayList(d.bills)
    val billTemplates = ArrayList(d.billTemplates)
    val debts = ArrayList(d.debts)
    val shopee = ArrayList(d.shopee)
    val port = ArrayList(d.port)
    val categories = ArrayList(d.categories)
    val fx = ArrayList(d.fx)
    val prices = ArrayList(d.prices)

    fun toData() = DanshaData(
        LinkedHashMap(config), accounts.toList(), transactions.toList(), bills.toList(), billTemplates.toList(), debts.toList(),
        shopee.toList(), port.toList(), categories.toList(), fx.toList(), prices.toList(),
    )

    data class Result<T>(val data: DanshaData, val value: T)

    companion object {
        /** ทำการแก้ไขแบบทั้งหมดหรือไม่มีเลย */
        fun <T> run(data: DanshaData, block: Store.() -> T): Result<T> {
            val s = Store(data)
            val v = s.block()
            return Result(s.toData(), v)
        }
    }

    // ---------- ตัวช่วย ----------

    private fun cfg() = Schema.effectiveConfig(config)
    private fun startDay() = Engine.startDay(cfg())
    private fun today() = Dates.todayStr()

    private fun fail(msg: String): Nothing = throw EngineException(msg)

    private fun date(s: String): String {
        if (s.isEmpty()) return ""
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(s)) fail("รูปแบบวันที่ต้องเป็น yyyy-MM-dd: $s")
        return s.substring(0, 10)
    }

    private fun <T> MutableList<T>.replaceWhere(id: String, key: (T) -> String, row: T) {
        val i = indexOfFirst { key(it) == id }
        if (i < 0) fail("ไม่พบรายการ id=$id")
        this[i] = row
    }

    private fun <T> MutableList<T>.removeOne(id: String, key: (T) -> String) {
        val i = indexOfFirst { key(it) == id }
        if (i < 0) fail("ไม่พบรายการ id=$id")
        removeAt(i)
    }

    private fun data() = toData()

    // ---------- บัญชี ----------

    private fun validateAccount(a: Account): Account {
        if (a.name.isEmpty()) fail("กรุณากรอก ชื่อบัญชี")
        if (a.type !in Schema.ACCOUNT_TYPES) fail("ประเภทบัญชีไม่ถูกต้อง")
        val currency = a.currency.ifEmpty { "THB" }
        if (currency !in Schema.CURRENCIES) fail("สกุลเงินไม่รองรับ")
        return a.copy(currency = currency, opening_balance = a.opening_balance ?: BigDecimal.ZERO, debt_since = date(a.debt_since))
    }

    fun createAccount(a: Account): Account {
        val row = validateAccount(a.copy(id = Ids.newId("acc"), created_at = Dates.nowIso()))
        accounts += row
        return row
    }

    fun updateAccount(a: Account): Account {
        val old = accounts.firstOrNull { it.id == a.id } ?: fail("ไม่พบรายการ id=${a.id}")
        val row = validateAccount(a).copy(created_at = old.created_at)
        accounts.replaceWhere(a.id, { it.id }, row)
        return row
    }

    fun deleteAccount(id: String) {
        if (transactions.any { it.account_id == id || it.to_account_id == id }) {
            fail("บัญชีนี้มีรายการอยู่แล้ว ลบไม่ได้ — ปิดใช้งาน (active = false) แทน")
        }
        accounts.removeOne(id) { it.id }
    }

    // ---------- หมวดหมู่ ----------

    private fun validateCategory(c: Category) {
        if (c.name.isEmpty()) fail("กรุณากรอก ชื่อหมวด")
        if (c.type != "income" && c.type != "expense") fail("ประเภทหมวดไม่ถูกต้อง")
    }

    fun createCategory(c: Category): Category {
        val row = c.copy(id = Ids.newId("cat"))
        validateCategory(row)
        categories += row
        return row
    }

    fun updateCategory(c: Category): Category {
        validateCategory(c)
        categories.replaceWhere(c.id, { it.id }, c)
        return c
    }

    /** ลบหมวดหลัก = ลบหมวดย่อยด้วย, หมวดที่ถูกใช้แล้วลบไม่ได้ */
    fun deleteCategory(id: String) {
        val children = categories.filter { it.parent_id == id }.map { it.id }
        val ids = listOf(id) + children
        if (transactions.any { it.category_id in ids || it.subcategory_id in ids }) {
            fail("หมวดนี้ถูกใช้ในรายการแล้ว ลบไม่ได้ — ปิดใช้งานแทน")
        }
        children.forEach { c -> categories.removeOne(c) { it.id } }
        categories.removeOne(id) { it.id }
    }

    // ---------- รายการ ----------

    /**
     * copay = true: ใช้สิทธิ 60/40 (คำนวณรัฐจ่ายจาก full_price)
     * copay = false ตอนแก้ไข: ยกเลิกสิทธิของรายการนี้, null = ไม่เปลี่ยน
     */
    private fun validateTransaction(t0: Transaction, isNew: Boolean, copay: Boolean?): Transaction {
        var t = t0.copy(date = date(t0.date))
        if (t.date.isEmpty()) fail("กรุณากรอก วันที่")
        if (t.type !in listOf("income", "expense", "transfer")) fail("ประเภทรายการไม่ถูกต้อง")
        if (t.account_id.isEmpty()) fail("กรุณากรอก บัญชี")
        if (!(t.amount.num() > 0)) fail("จำนวนเงินต้องมากกว่า 0")
        if (t.type == "transfer") {
            if (t.to_account_id.isEmpty()) fail("กรุณากรอก บัญชีปลายทาง")
            if (t.to_account_id == t.account_id) fail("บัญชีต้นทาง/ปลายทางต้องต่างกัน")
        }
        if (copay == true) t = applyCopay(t)
        else if (!isNew && t.gov_subsidy.num() > 0 && copay != null) t = t.copy(gov_subsidy = null, full_price = null)
        t = t.copy(pay_cycle = Dates.payCycleOf(t.date, startDay()))
        if (t.source.isEmpty()) t = t.copy(source = "manual")
        return t
    }

    /** 60/40: amount = ราคาเต็ม − รัฐจ่าย */
    private fun applyCopay(t: Transaction): Transaction {
        val cc = Copay.config(cfg())
        if (t.type != "expense") fail("สิทธิร่วมจ่ายใช้ได้กับรายจ่ายเท่านั้น")
        if (cc.accountId.isNotEmpty() && t.account_id != cc.accountId) fail("ต้องจ่ายจากบัญชี G-Wallet ที่ตั้งไว้")
        val price = t.full_price.num()
        if (!(price > 0)) fail("กรุณากรอกราคาเต็ม")
        val usage = Copay.usage(transactions, cc, t.date, t.id)
        val s = Copay.split(price, cc, usage)
        return t.copy(full_price = round2(price).toDec(), gov_subsidy = s.gov.toDec(), amount = s.self.toDec())
    }

    fun createTransaction(t: Transaction, copay: Boolean? = null): Transaction {
        val row = validateTransaction(t.copy(id = Ids.newId("txn"), created_at = Dates.nowIso()), true, copay)
        transactions += row
        syncFoodOrder(row)
        return row
    }

    fun updateTransaction(t: Transaction, copay: Boolean? = null): Transaction {
        val old = transactions.firstOrNull { it.id == t.id } ?: fail("ไม่พบรายการ id=${t.id}")
        val row = validateTransaction(t, false, copay).copy(created_at = old.created_at)
        transactions.replaceWhere(t.id, { it.id }, row)
        syncFoodOrder(row)
        return row
    }

    /**
     * ค่าอาหารที่จ่ายด้วย SPayLater = รายจ่ายบนบัญชี SPayLater (ที่ไม่ได้มาจากหน้า Shopee)
     * → ผูกออเดอร์ประเภท food 1 งวด (ไม่ต้องกดรับ นับรอบบิลจากวันที่จ่าย) ให้ยอดไปอยู่ในบิล SPayLater
     * ย้ายไปบัญชีอื่น/เปลี่ยนเป็นรายรับ → ลบออเดอร์ที่ผูกไว้
     */
    private fun syncFoodOrder(t: Transaction) {
        val linked = shopee.firstOrNull { it.txn_id == t.id && it.kind == SPayLater.FOOD }
        val acc = accounts.firstOrNull { it.id == t.account_id }
        val want = t.type == "expense" && isSplAccount(acc) && t.source != "shopee"
        if (!want) {
            if (linked != null) { shopee.removeOne(linked.id) { it.id }; syncSplBills(linked.account_id) }
            return
        }
        val catName = categories.firstOrNull { it.id == t.subcategory_id.ifEmpty { t.category_id } }?.name.orEmpty()
        val item = t.note.ifEmpty { catName.ifEmpty { "ค่าอาหาร" } }
        val price = t.amount.num().toDec()
        if (linked == null) {
            createShopee(
                ShopeeOrder(
                    id = "", order_date = t.date, item = item, price = price, tenor = 1, installment = price,
                    account_id = t.account_id, pay_method = "spaylater", status = "active",
                    category_id = t.category_id, txn_id = t.id, received_date = t.date, kind = SPayLater.FOOD,
                )
            )
        } else {
            shopee.replaceWhere(linked.id, { it.id }, linked.copy(
                order_date = t.date, received_date = t.date, item = item, price = price, installment = price,
                account_id = t.account_id, category_id = t.category_id,
            ))
            if (linked.account_id != t.account_id) syncSplBills(linked.account_id)
        }
        syncSplBills(t.account_id)
    }

    /** ลบรายการ → ลบหนี้ย่อยที่ผูก, ปลดลิงก์ Shopee, ถ้ามาจากแผนบิลให้แผนกลับเป็น planned */
    fun deleteTransaction(id: String) {
        debts.removeAll { it.txn_id == id }
        // ค่าอาหาร SPayLater: ลบรายจ่าย = ลบออเดอร์ด้วย
        shopee.filter { it.txn_id == id && it.kind == SPayLater.FOOD }.forEach { o -> shopee.removeOne(o.id) { it.id }; syncSplBills(o.account_id) }
        shopee.replaceAll { if (it.txn_id == id) it.copy(txn_id = "") else it }
        val t = transactions.firstOrNull { it.id == id }
        if (t != null && t.source == "bill_plan" && t.ref_id.isNotEmpty() && bills.any { it.id == t.ref_id }) {
            bills.replaceAll { if (it.id == t.ref_id) it.copy(status = "planned", actual_amount = null, paid_date = "", txn_id = "") else it }
        }
        transactions.removeOne(id) { it.id }
    }

    // ---------- แผนบิล / แม่แบบ ----------

    private fun validateBillCommon(group: String, name: String, est: BigDecimal?) {
        if (group !in Plan.GROUPS) fail("กลุ่มไม่ถูกต้อง")
        if (name.isEmpty()) fail("กรุณากรอกชื่อรายการ")
        if (!(est.num() >= 0)) fail("ยอดประมาณไม่ถูกต้อง")
    }

    private fun validateBill(b1: Bill, isNew: Boolean): Bill {
        val b0 = if (b1.section.isNotEmpty()) b1.copy(group = PlanBook.groupOf(b1.section)) else b1
        validateBillCommon(b0.group, b0.name, b0.est_amount)
        var b = b0.copy(due_date = date(b0.due_date), paid_date = date(b0.paid_date))
        if (b.group == "income") b = b.copy(to_account_id = "")
        if (isNew && b.status.isEmpty()) b = b.copy(status = "planned")
        // แก้ยอดจริงของรายการที่จ่ายแล้ว → แก้ยอดของรายการที่ผูกไว้ด้วย
        if (!isNew && b.status == "paid" && b.txn_id.isNotEmpty() && transactions.any { it.id == b.txn_id }) {
            transactions.replaceAll { if (it.id == b.txn_id) it.copy(amount = b.actual_amount.num().toDec()) else it }
        }
        return b
    }

    fun createBill(b: Bill): Bill {
        val row = validateBill(b.copy(id = Ids.newId("bill")), true)
        bills += row
        return row
    }

    fun updateBill(b: Bill): Bill {
        val row = validateBill(b, false)
        bills.replaceWhere(b.id, { it.id }, row)
        return row
    }

    fun deleteBill(id: String) {
        val bill = bills.firstOrNull { it.id == id }
        if (bill != null && bill.status == "paid") fail("รายการนี้จ่ายแล้ว — กด \"ยกเลิกการจ่าย\" ก่อนลบ")
        bills.removeOne(id) { it.id }
    }

    private fun validateTemplate(t1: BillTemplate): BillTemplate {
        val t = if (t1.section.isNotEmpty()) t1.copy(group = PlanBook.groupOf(t1.section)) else t1
        validateBillCommon(t.group, t.name, t.est_amount)
        return if (t.group == "income") t.copy(to_account_id = "") else t
    }

    fun createBillTemplate(t: BillTemplate): BillTemplate {
        val row = validateTemplate(t.copy(id = Ids.newId("btpl")))
        billTemplates += row
        return row
    }

    fun updateBillTemplate(t: BillTemplate): BillTemplate {
        val row = validateTemplate(t)
        billTemplates.replaceWhere(t.id, { it.id }, row)
        return row
    }

    fun deleteBillTemplate(id: String) = billTemplates.removeOne(id) { it.id }

    /** สร้างรายการของรอบจากแม่แบบ (ข้ามแม่แบบที่มีในรอบนี้แล้ว — กดซ้ำได้) */
    fun generateCycle(cycle: String): List<Bill> {
        if (!Regex("^\\d{4}-\\d{2} ").containsMatchIn(cycle)) fail("รอบไม่ถูกต้อง")
        val startDay = startDay()
        val used = bills.filter { it.pay_cycle == cycle }.map { it.template_id }
        val (rs, re) = Dates.payCycleRange(cycle, startDay)
        val range = DateRange(rs.toString(), re.toString())
        var debt: Map<String, DebtState>? = null // คำนวณเฉพาะเมื่อมีแม่แบบค่างวดหนี้
        fun debtDue(accountId: String): Pair<String, Double>? {
            val all = debt ?: Engine.debtStates(data(), today(), true).also { debt = it }
            val st = all[accountId] ?: return null
            val cur = st.currentBill
            val list = (if (cur != null) listOf(cur.due to cur.amount) else emptyList()) + (st.bills ?: emptyList()).map { it.due to it.totalDue }
            return list.firstOrNull { it.first >= range.start && it.first <= range.end }
        }
        val created = ArrayList<Bill>()
        billTemplates.filter { it.active && it.id !in used }
            .sortedBy { it.sort ?: 0 }
            .forEach { t0 ->
                var est = t0.est_amount
                var dueDay = t0.due_day
                // ค่างวดหนี้ที่ผูกกับตัวคำนวณ: ใช้ยอดบิลที่คำนวณได้ และวันครบกำหนดจริง
                val d = if (t0.to_account_id.isNotEmpty()) debtDue(t0.to_account_id) else null
                if (d != null) {
                    est = d.second.toDec()
                    dueDay = d.first.substring(8, 10).toInt()
                }
                val row = Bill(
                    id = Ids.newId("bill"), pay_cycle = cycle, group = t0.group, name = t0.name,
                    category_id = t0.category_id, account_id = t0.account_id, to_account_id = t0.to_account_id,
                    est_amount = est, status = "planned", due_date = Plan.dueDateInCycle(cycle, dueDay, startDay),
                    template_id = t0.id, note = t0.note, sort = t0.sort, section = t0.section,
                )
                bills += row
                created += row
            }
        return created
    }

    /** กดจ่าย/รับเงิน: บันทึกรายการด้วยยอดจริง แล้วผูกกับแผน */
    fun payBill(
        id: String,
        amount: Double,
        accountId: String? = null,
        toAccountId: String? = null,
        date: String? = null,
        note: String? = null,
    ): Bill {
        val bill = bills.firstOrNull { it.id == id } ?: fail("ไม่พบรายการในแผน")
        if (bill.status == "paid") fail("รายการนี้บันทึกจ่ายไปแล้ว")
        if (!(amount > 0)) fail("ยอดจริงต้องมากกว่า 0")
        val accId = accountId?.ifEmpty { null } ?: bill.account_id
        val toId = toAccountId ?: bill.to_account_id
        val account = accounts.firstOrNull { it.id == accId } ?: fail("กรุณาเลือกบัญชี")
        val day = date?.ifEmpty { null } ?: today()
        val txn = createTransaction(
            Transaction(
                id = "", date = day,
                type = if (bill.group == "income") "income" else if (toId.isNotEmpty()) "transfer" else "expense",
                account_id = accId, to_account_id = if (bill.group == "income") "" else toId,
                amount = amount.toDec(), currency = account.currency, category_id = bill.category_id,
                note = note?.ifEmpty { null } ?: bill.name, source = "bill_plan", ref_id = bill.id,
            )
        )
        val row = bill.copy(status = "paid", actual_amount = amount.toDec(), paid_date = day, txn_id = txn.id, account_id = accId)
        bills.replaceWhere(id, { it.id }, row)
        return row
    }

    /** ยกเลิกการจ่าย: ลบรายการที่สร้างไว้ แล้วกลับเป็น planned */
    fun unpayBill(id: String): Bill {
        val bill = bills.firstOrNull { it.id == id } ?: fail("ไม่พบรายการในแผน")
        if (bill.txn_id.isNotEmpty()) transactions.removeAll { it.id == bill.txn_id }
        val row = bill.copy(status = "planned", actual_amount = null, paid_date = "", txn_id = "")
        bills.replaceWhere(id, { it.id }, row)
        return row
    }

    // ---------- หนี้ย่อย ----------

    private fun validateDebt(d0: Debt, isNew: Boolean): Debt {
        val d = d0.copy(
            txn_date = date(d0.txn_date), first_due_date = date(d0.first_due_date),
            snap_date = date(d0.snap_date), snap_last_interest_date = date(d0.snap_last_interest_date),
        )
        if (d.kind !in DEBT_KINDS) fail("ประเภทหนี้ไม่ถูกต้อง")
        if (d.account_id.isEmpty()) fail("ต้องเลือกบัญชี")
        if (d.txn_date.isEmpty()) fail("ต้องระบุวันที่")
        if (!(d.principal.num() > 0)) fail("ยอดเงินต้นต้องมากกว่า 0")
        if (d.kind != "fullpay_snapshot") {
            val acc = accounts.firstOrNull { it.id == d.account_id }
            val n = d.tenor ?: 0
            val min = (acc?.tenor_min ?: 0).let { if (it == 0) 2 else it }
            val max = (acc?.tenor_max ?: 0).let { if (it == 0) 24 else it }
            if (!(n >= 1)) fail("ต้องระบุจำนวนงวด")
            if (d.snap_date.isEmpty() && (n < min || n > max)) fail("บัญชีนี้ผ่อนได้ $min–$max งวด")
        }
        return if (isNew && d.status.isEmpty()) d.copy(status = "active") else d
    }

    fun createDebt(d: Debt): Debt {
        val row = validateDebt(d.copy(id = Ids.newId("debt"), created_at = Dates.nowIso()), true)
        debts += row
        return row
    }

    fun updateDebt(d: Debt): Debt {
        val old = debts.firstOrNull { it.id == d.id } ?: fail("ไม่พบรายการ id=${d.id}")
        val row = validateDebt(d, false).copy(created_at = old.created_at)
        debts.replaceWhere(d.id, { it.id }, row)
        return row
    }

    fun deleteDebt(id: String) = debts.removeOne(id) { it.id }

    data class CashDrawResult(val txn: Transaction?, val debt: Debt)

    /** เบิกเงินสดจากวงเงิน: สร้างหนี้ย่อย (+ รายการโอนที่ผูกกัน ถ้าเลือกบัญชีปลายทาง) */
    fun cashDraw(accountId: String, date: String, amount: Double, tenor: Int, toAccountId: String = "", note: String = "", kind: String = "cash"): CashDrawResult {
        val acc = accounts.firstOrNull { it.id == accountId }
        if (!isEngineAccount(acc)) fail("บัญชีนี้ไม่ได้เปิดใช้ Debt Tracker")
        var debt = Debt(
            id = "", account_id = acc!!.id, txn_date = date, kind = kind.ifEmpty { "cash" },
            description = note.ifEmpty { "เบิกเงินสด" }, principal = amount.toDec(), tenor = tenor,
        )
        debt = validateDebt(debt, true)
        var txn: Transaction? = null
        if (toAccountId.isNotEmpty()) {
            txn = createTransaction(
                Transaction(
                    id = "", date = date, type = "transfer", account_id = acc.id, to_account_id = toAccountId,
                    amount = amount.toDec(), note = debt.description, source = "debt",
                )
            )
            debt = debt.copy(txn_id = txn.id)
        }
        return CashDrawResult(txn, createDebt(debt))
    }

    // ---------- Shopee ----------

    fun createShopee(o: ShopeeOrder): ShopeeOrder {
        val row = o.copy(id = Ids.newId("shp"), created_at = Dates.nowIso(), order_date = date(o.order_date))
        shopee += row
        return row
    }

    fun updateShopee(o0: ShopeeOrder): ShopeeOrder {
        val old = shopee.firstOrNull { it.id == o0.id } ?: fail("ไม่พบรายการ id=${o0.id}")
        var o = o0.copy(created_at = old.created_at, order_date = date(o0.order_date), received_date = date(o0.received_date))
        if (o.received_date.isNotEmpty() && o.received_date < o.order_date) fail("วันที่กดรับของต้องไม่ก่อนวันสั่ง")
        shopee.replaceWhere(o.id, { it.id }, o)
        if (o.pay_method == "spaylater") syncSplBills(o.account_id)
        if (o.txn_id.isNotEmpty() && transactions.any { it.id == o.txn_id }) {
            // จ่ายเต็ม: ยกเลิก/คืนเงิน → ลบรายจ่าย, แก้ราคา → แก้รายจ่าย
            if (o.status == "cancelled") {
                transactions.removeAll { it.id == o.txn_id }
                o = o.copy(txn_id = "")
                shopee.replaceWhere(o.id, { it.id }, o)
            } else {
                val price = o.price.num().toDec()
                transactions.replaceAll { if (it.id == o.txn_id) it.copy(amount = price) else it }
            }
        }
        return shopee.first { it.id == o.id }
    }

    fun deleteShopee(id: String) {
        val o = shopee.firstOrNull { it.id == id }
        if (o != null && o.txn_id.isNotEmpty()) transactions.removeAll { it.id == o.txn_id }
        shopee.removeOne(id) { it.id }
        if (o != null && o.pay_method == "spaylater") syncSplBills(o.account_id)
    }

    data class PurchaseResult(val order: ShopeeOrder, val txn: Transaction?)

    /** บันทึกการซื้อ: จ่ายเต็ม → สร้างรายจ่าย "Shopee: …", SPayLater → อัปเดตค่างวดในแผนบิลที่ยังไม่จ่าย */
    fun recordPurchase(
        item: String,
        price: Double,
        accountId: String,
        date: String? = null,
        tenor: Int? = null,
        installment: Double? = null,
        annualRate: Double? = null,
        shop: String = "",
        categoryId: String = "",
        subcategoryId: String = "",
        note: String = "",
        orderNo: String = "",
        /** null = แบบเดิม (นับจากวันสั่ง), "" = ยังไม่กดรับของ, วันที่ = วันกดรับ */
        receivedDate: String? = null,
    ): PurchaseResult {
        if (item.isEmpty()) fail("กรุณากรอกชื่อสินค้า")
        if (!(price > 0)) fail("กรุณากรอกราคา")
        val day = date?.ifEmpty { null } ?: today()
        val acc = accounts.firstOrNull { it.id == accountId } ?: fail("กรุณาเลือกบัญชีที่จ่าย")
        val spl = isSplAccount(acc)
        val n = if (spl) maxOf(1, (tenor ?: 0).let { if (it == 0) 1 else it }) else 1
        val inst: BigDecimal? = if (spl) (if (n == 1) price.toDec() else installment?.takeIf { it != 0.0 && !it.isNaN() }?.toDec()) else null
        var order = ShopeeOrder(
            id = "", order_date = day, item = item, shop = shop, price = price.toDec(), tenor = n,
            annual_rate = annualRate?.takeIf { it != 0.0 }?.toDec(), installment = inst,
            account_id = acc.id, pay_method = if (spl) "spaylater" else "full",
            status = if (spl && receivedDate != null && receivedDate.isEmpty()) SPayLater.WAITING else "active",
            category_id = categoryId, note = note, order_no = orderNo,
            received_date = if (spl) date(receivedDate.orEmpty()) else "",
        )
        if (spl && n > 1 && inst == null) fail("กรุณากรอกค่างวดจากแอพ Shopee")
        var txn: Transaction? = null
        if (!spl) {
            txn = createTransaction(
                Transaction(
                    id = "", date = day, type = "expense", account_id = acc.id, amount = price.toDec(), currency = acc.currency,
                    category_id = categoryId, subcategory_id = subcategoryId, note = "Shopee: $item", source = "shopee",
                )
            )
            order = order.copy(txn_id = txn.id)
        }
        val saved = createShopee(order)
        if (spl) syncSplBills(acc.id)
        return PurchaseResult(saved, txn)
    }

    /** ปรับยอดประมาณของค่างวด SPayLater ในแผนบิลที่ยังไม่จ่าย (ทุกรอบที่สร้างไว้แล้ว) */
    fun syncSplBills(accountId: String) {
        val acc = accounts.firstOrNull { it.id == accountId } ?: return
        val st = SPayLater.state(acc, shopee, transactions, today(), Engine.receiveDays(cfg()))
        val startDay = startDay()
        bills.replaceAll { b ->
            if (b.to_account_id != accountId || b.status == "paid") return@replaceAll b
            val (rs, re) = Dates.payCycleRange(b.pay_cycle, startDay)
            val due = st.bills.firstOrNull { it.due >= rs.toString() && it.due <= re.toString() }
            val amt = due?.totalDue ?: 0.0
            if (b.est_amount.num() != amt) b.copy(est_amount = amt.toDec(), due_date = due?.due ?: b.due_date) else b
        }
    }

    // ---------- พอร์ต ----------

    private fun validatePort(p0: PortTxn): PortTxn {
        var p = p0
        if (p.side !in PORT_SIDES) fail("ประเภทรายการไม่ถูกต้อง")
        if (p.symbol.isEmpty()) fail("กรุณากรอกชื่อย่อหลักทรัพย์")
        p = p.copy(symbol = p.symbol.trim().uppercase(), date = date(p.date))
        if (p.date.isEmpty()) fail("กรุณาระบุวันที่")
        if (p.currency.isEmpty()) p = p.copy(currency = "THB")
        if (p.currency !in Schema.CURRENCIES) fail("สกุลเงินไม่รองรับ")
        if ((p.side == "buy" || p.side == "sell") && !(p.qty.num() > 0)) fail("จำนวนหน่วยต้องมากกว่า 0")
        if (!(p.price.num() >= 0)) fail("ราคา/จำนวนเงินไม่ถูกต้อง")
        if (p.currency == "THB") p = p.copy(fx_rate = BigDecimal.ONE)
        else if (!(p.fx_rate.num() > 0)) fail("กรุณากรอกเรทแลกเปลี่ยนที่ใช้จริง (บาทต่อ 1 ${p.currency})")
        if (p.asset_type.isEmpty()) p = p.copy(asset_type = "stock")
        if (p.broker.isEmpty()) p = p.copy(broker = "Dime!")
        return p
    }

    fun createPort(p: PortTxn): PortTxn {
        val row = validatePort(p.copy(id = Ids.newId("port"), created_at = Dates.nowIso()))
        port += row
        return row
    }

    fun updatePort(p: PortTxn): PortTxn {
        val old = port.firstOrNull { it.id == p.id } ?: fail("ไม่พบรายการ id=${p.id}")
        val row = validatePort(p).copy(created_at = old.created_at)
        port.replaceWhere(p.id, { it.id }, row)
        return row
    }

    fun deletePort(id: String) = port.removeOne(id) { it.id }

    /** อัปเดตราคาล่าสุด: symbol → (ราคา, สกุลเงิน) */
    fun setPrices(data: Map<String, Pair<Double?, String>>) {
        val now = Dates.nowIso()
        data.forEach { (sym, v) ->
            val price = v.first ?: return@forEach
            if (!(price >= 0)) return@forEach
            val s = sym.uppercase()
            val row = Price(s, price.toDec(), v.second.ifEmpty { "THB" }, now)
            val i = prices.indexOfFirst { it.symbol == s }
            if (i >= 0) prices[i] = row else prices += row
        }
    }

    // ---------- อัตราแลกเปลี่ยน / ตั้งค่า ----------

    /** กรอกเรทเอง: null = กลับไปใช้อัตโนมัติ */
    fun setFxManual(data: Map<String, Double?>) {
        data.forEach { (c, v) ->
            if (c !in Schema.CURRENCIES || c == "THB") return@forEach
            if (v != null && !(v > 0)) fail("เรทไม่ถูกต้อง: $c")
            var i = fx.indexOfFirst { it.currency == c }
            if (i < 0) { fx += FxRate(c); i = fx.size - 1 }
            val row = fx[i].copy(manual_rate = v?.toDec())
            fx[i] = row.copy(rate_to_thb = Balances.fxRateOf(row).toDec())
        }
    }

    /** อัปเดตเรทอัตโนมัติ: สกุลเงิน → บาทต่อ 1 หน่วย */
    fun setFxAuto(rates: Map<String, Double>, date: String? = null) {
        rates.forEach { (c, rate) ->
            if (!(rate > 0) || c !in Schema.CURRENCIES) return@forEach
            var i = fx.indexOfFirst { it.currency == c }
            if (i < 0) { fx += FxRate(c); i = fx.size - 1 }
            val row = fx[i].copy(auto_rate = rate.toDec())
            fx[i] = row.copy(rate_to_thb = Balances.fxRateOf(row).toDec(), updated_note = "auto " + (date?.ifEmpty { null } ?: today()))
        }
    }

    // ---------- แผนบิลแบบแบ่งหมวด (แอพ Android) ----------

    /**
     * อัปเดตแถวที่แอพดูแลเองในรอบนี้: ค่างวดหนี้ (ยอดจากตัวคำนวณ) + ค่าเดินทาง (จากวันทำงานที่ติ๊กไว้)
     * คืน true ถ้ามีอะไรเปลี่ยน
     */
    fun syncPlan(cycle: String): Boolean {
        if (!Regex("^\\d{4}-\\d{2} ").containsMatchIn(cycle)) return false
        var changed = false

        // หนี้สิน
        val dues = PlanBook.debtDues(data(), cycle, Engine.debtStates(data(), today(), true))
        val payFrom = accounts.filter { it.active && it.type != "revolving_credit" }.sortedWith(compareBy({ if (it.type == "bank") 0 else 1 }, { it.sort ?: Int.MAX_VALUE })).firstOrNull()?.id.orEmpty()
        accounts.filter { it.active && (isEngineAccount(it) || isSplAccount(it)) }.forEach { a ->
            val due = dues[a.id]
            val rows = bills.filter { it.pay_cycle == cycle && it.to_account_id == a.id }
            if (rows.isEmpty()) {
                if (due != null && due.second > 0) {
                    bills += Bill(
                        id = Ids.newId("bill"), pay_cycle = cycle, group = "A", section = PlanBook.DEBT, name = a.name,
                        account_id = payFrom, to_account_id = a.id, est_amount = due.second.toDec(), status = "planned",
                        due_date = due.first, template_id = PlanBook.AUTO_DEBT, sort = a.sort,
                    )
                    changed = true
                }
                return@forEach
            }
            bills.replaceAll { b ->
                if (b.pay_cycle != cycle || b.to_account_id != a.id || b.status == "paid") return@replaceAll b
                val amt = due?.second ?: 0.0
                val dd = due?.first ?: b.due_date
                if (b.est_amount.num() != amt || b.due_date != dd || b.section.isEmpty()) {
                    changed = true
                    b.copy(est_amount = amt.toDec(), due_date = dd, section = PlanBook.DEBT)
                } else b
            }
        }

        // ค่าเดินทาง: ยอดที่ยังต้องจ่าย (วันทำงานตั้งแต่วันนี้) — ที่จ่ายไปแล้วอยู่ในเงินที่มีอยู่แล้ว
        val tp = PlanBook.travel(data(), cycle, today())
        val row = bills.firstOrNull { it.pay_cycle == cycle && it.template_id == PlanBook.AUTO_TRAVEL }
        val note = "ทำงาน ${tp.days} วัน (กะดึก ${tp.lateDays}) · เหลือ ${tp.daysLeft} วัน"
        if (row == null) {
            if (tp.days > 0) {
                bills += Bill(
                    id = Ids.newId("bill"), pay_cycle = cycle, group = "B", section = PlanBook.TRAVEL, name = "ค่าเดินทางไปทำงาน",
                    account_id = PlanBook.cashAccount(data()), est_amount = tp.remaining.toDec(), status = "planned",
                    template_id = PlanBook.AUTO_TRAVEL, note = note, sort = 0,
                )
                changed = true
            }
        } else if (row.status != "paid" && (row.est_amount.num() != tp.remaining || row.note != note)) {
            bills.replaceWhere(row.id, { it.id }, row.copy(est_amount = tp.remaining.toDec(), note = note))
            changed = true
        }
        return changed
    }

    /** ตั้งวันทำงานของรอบ แล้วคิดค่าเดินทางใหม่ */
    fun setWorkDays(cycle: String, days: List<PlanBook.WorkDay>) {
        val k = PlanBook.workKey(cycle)
        if (days.isEmpty()) config.remove(k) else config[k] = PlanBook.encodeWorkDays(days)
        // วันที่ไม่ได้ยกเว้นแล้ว → ลบเหตุผลทิ้ง
        val keep = days.filter { it.skipGo || it.skipBack }.map { PlanBook.skipKey(it.date) }.toSet()
        val (rs, re) = Dates.payCycleRange(cycle, startDay())
        val prefix = PlanBook.skipKey("")
        config.keys.filter { k -> k.startsWith(prefix) && k !in keep && k.removePrefix(prefix).let { it >= rs.toString() && it <= re.toString() } }
            .toList().forEach { config.remove(it) }
        syncPlan(cycle)
    }

    /** ยกเว้นค่ารถของวันหนึ่ง (ขาไป/ขากลับ) พร้อมเหตุผล — ไม่ได้อยู่ในแผนจะเพิ่มเป็นวันทำงานให้ */
    fun setTravelSkip(cycle: String, date: String, skipGo: Boolean, skipBack: Boolean, reason: String) {
        val days = PlanBook.workDays(config, cycle)
        val cur = days.firstOrNull { it.date == date } ?: PlanBook.WorkDay(date, false)
        config[PlanBook.workKey(cycle)] = PlanBook.encodeWorkDays(days.filter { it.date != date } + cur.copy(skipGo = skipGo, skipBack = skipBack))
        val k = PlanBook.skipKey(date)
        if ((skipGo || skipBack) && reason.isNotBlank()) config[k] = reason.trim() else config.remove(k)
        syncPlan(cycle)
    }

    /** หมวด (หลัก, ย่อย) จากชื่อหมวดย่อย/หลัก ไม่เจอ = ว่าง */
    private fun categoryByName(name: String, type: String = "expense"): Pair<String, String> {
        val c = categories.firstOrNull { it.active && it.type == type && it.name.equals(name, ignoreCase = true) } ?: return "" to ""
        return if (c.parent_id.isEmpty()) c.id to "" else c.parent_id to c.id
    }

    /** จดค่ารถไปทำงาน (เงินสด): leg = go | back */
    fun logTravel(date: String, leg: String, amount: Double, accountId: String? = null): Transaction {
        if (!(amount > 0)) fail("ยอดต้องมากกว่า 0")
        val acc = accountId?.ifEmpty { null } ?: PlanBook.cashAccount(data())
        val (main, sub) = categoryByName("รถสาธารณะ").let { if (it.first.isEmpty()) categoryByName("เดินทาง") else it }
        return createTransaction(
            Transaction(
                id = "", date = date, type = "expense", account_id = acc, amount = amount.toDec(),
                currency = accounts.firstOrNull { it.id == acc }?.currency ?: "THB",
                category_id = main, subcategory_id = sub, note = if (leg == "go") PlanBook.NOTE_GO else PlanBook.NOTE_BACK, source = "travel",
            )
        )
    }

    /**
     * ตั้งค่ารถของวันนั้น: leg = go | back | grab, amount = null → ลบที่จดไว้
     * จดซ้ำ = แก้ยอดเดิม (กดราคาใหม่ได้เลย) — Grab ที่ส่งเบิกไปแล้วแก้ไม่ได้
     */
    fun setTravelLeg(date: String, leg: String, amount: Double?): Transaction? {
        val (go, back) = PlanBook.legs(data(), date)
        val old = if (leg == "go") go else back
        if (old != null && (old.claim.startsWith("sub:") || old.claim.startsWith("paid:"))) fail("Grab เที่ยวนี้ส่งเบิกไปแล้ว แก้ไม่ได้")
        if (amount == null) {
            if (old != null) deleteTransaction(old.id)
            return null
        }
        if (!(amount > 0)) fail("ยอดต้องมากกว่า 0")
        // ขากลับเปลี่ยนชนิด (รถเมล์ ↔ Grab) = ลบของเดิมแล้วจดใหม่
        if (old != null && ((leg == "grab") != old.claim.isNotEmpty())) {
            deleteTransaction(old.id)
        } else if (old != null) {
            return updateTransaction(old.copy(amount = amount.toDec()))
        }
        return if (leg == "grab") logGrab(date, amount) else logTravel(date, if (leg == "go") "go" else "back", amount)
    }

    /** จด Grab กลับบ้าน (เบิก Staff Claim ได้) */
    fun logGrab(date: String, amount: Double, accountId: String? = null, note: String = ""): Transaction {
        if (!(amount > 0)) fail("ยอดต้องมากกว่า 0")
        val acc = accountId?.ifEmpty { null } ?: PlanBook.claimPayAccount(data())
        val (main, sub) = categoryByName("แท็กซี่/Grab").let { if (it.first.isEmpty()) categoryByName("เดินทาง") else it }
        return createTransaction(
            Transaction(
                id = "", date = date, type = "expense", account_id = acc, amount = amount.toDec(),
                currency = accounts.firstOrNull { it.id == acc }?.currency ?: "THB",
                category_id = main, subcategory_id = sub, note = note.ifEmpty { "Grab กลับบ้าน (เบิกได้)" }, source = "travel", claim = "open",
            )
        )
    }

    /** ตั้ง/ยกเลิกว่ารายการนี้เบิก Staff Claim ได้ */
    fun setClaimable(txnId: String, claimable: Boolean) {
        val t = transactions.firstOrNull { it.id == txnId } ?: fail("ไม่พบรายการ")
        if (t.claim.startsWith("sub:") || t.claim.startsWith("paid:")) fail("รายการนี้ส่งเบิกไปแล้ว")
        transactions.replaceWhere(txnId, { it.id }, t.copy(claim = if (claimable) "open" else ""))
    }

    /** ส่งใบเบิก: รายการที่เลือก → รอเงินคืน + สร้างรายรับในแผนตามวันได้เงิน (ส่งภายใน 15 → 30 เดือนนี้ ไม่งั้น 30 เดือนหน้า) */
    fun submitClaim(txnIds: List<String>, submitted: String): Bill {
        val day = date(submitted.ifEmpty { today() })
        val rows = transactions.filter { it.id in txnIds && it.claim == "open" }
        if (rows.isEmpty()) fail("ยังไม่ได้เลือกรายการที่จะเบิก")
        val total = round2(rows.sumOf { it.amount.num() })
        transactions.replaceAll { if (it.id in txnIds && it.claim == "open") it.copy(claim = "sub:$day") else it }
        val payDay = PlanBook.claimPayDate(day)
        val cycle = Dates.payCycleOf(payDay, startDay())
        val tid = PlanBook.CLAIM_PREFIX + day
        val existing = bills.firstOrNull { it.template_id == tid && it.status != "paid" }
        val note = "Grab ${transactions.count { it.claim == "sub:$day" }} เที่ยว · ส่งเบิก ${day}"
        if (existing != null) {
            val row = existing.copy(est_amount = (existing.est_amount.num() + total).toDec(), note = note)
            bills.replaceWhere(existing.id, { it.id }, row)
            return row
        }
        val (main, sub) = categoryByName("รายรับอื่นๆ", "income")
        val row = Bill(
            id = Ids.newId("bill"), pay_cycle = cycle, group = "income", section = PlanBook.CLAIM, name = "Staff Claim (เบิกค่า Grab)",
            account_id = PlanBook.claimReceiveAccount(data()), category_id = sub.ifEmpty { main }, est_amount = total.toDec(),
            status = "planned", due_date = payDay, template_id = tid, note = note,
        )
        bills += row
        return row
    }

    /** ได้เงินเบิกคืนแล้ว: บันทึกรายรับ + ปิดรายการ Grab ที่อยู่ในใบเบิกนั้น */
    fun receiveClaim(billId: String, amount: Double, date: String? = null, accountId: String? = null): Bill {
        val bill = bills.firstOrNull { it.id == billId } ?: fail("ไม่พบรายการในแผน")
        val paid = payBill(billId, amount, accountId, "", date, null)
        val submitted = bill.template_id.removePrefix(PlanBook.CLAIM_PREFIX)
        transactions.replaceAll { if (it.claim == "sub:$submitted") it.copy(claim = "paid:${paid.paid_date}") else it }
        return paid
    }

    /** รับเงินเดือน (จาก Slip): ใช้แผนเงินเดือนของรอบ ไม่มีก็สร้างให้ แล้วบันทึกรับเงิน */
    fun receiveSalary(cycle: String, net: Double, accountId: String, date: String, note: String = ""): Bill {
        if (!(net > 0)) fail("ยอดสุทธิต้องมากกว่า 0")
        var bill = bills.firstOrNull { it.pay_cycle == cycle && it.status != "paid" && it.group == "income" && (it.section == PlanBook.SALARY || it.name.contains("เงินเดือน")) }
        if (bill == null) {
            val (main, _) = categoryByName("เงินเดือน", "income")
            bill = createBill(
                Bill(
                    id = "", pay_cycle = cycle, group = "income", section = PlanBook.SALARY, name = "เงินเดือน", category_id = main,
                    account_id = accountId, est_amount = net.toDec(), status = "planned", due_date = date,
                )
            )
        }
        return payBill(bill.id, net, accountId, "", date, note.ifEmpty { null })
    }

    /**
     * เพิ่มหลายรายการทีเดียว: รายการประจำ/รายรับ → แม่แบบ (ชื่อซ้ำ = แก้ยอด/วันที่ของแม่แบบเดิม),
     * ครั้งเดียว/วางแผนใช้ → ใส่ในรอบ cycle — คืนจำนวน (แม่แบบ, แผนในรอบ)
     */
    fun addBulk(items: List<PlanBook.BulkItem>, cycle: String): Pair<Int, Int> {
        var tpl = 0
        var one = 0
        val d0 = data()
        fun categoryFor(it: PlanBook.BulkItem): String {
            val type = if (PlanBook.groupOf(it.section) == "income") "income" else "expense"
            if (it.section == PlanBook.SALARY) categoryByName("เงินเดือน", "income").first.takeIf { c -> c.isNotEmpty() }?.let { c -> return c }
            val g = QuickEntry.suggest(d0, (if (type == "income") "+" else "") + it.name).firstOrNull { g -> g.type == type && g.categoryId.isNotEmpty() }
            return g?.let { x -> x.subcategoryId.ifEmpty { x.categoryId } }.orEmpty()
        }
        val acc = Forms.defaultAccount(d0, "expense")
        items.forEach { it ->
            if (it.oneOff) {
                createBill(
                    Bill(
                        id = "", pay_cycle = cycle, group = PlanBook.groupOf(it.section), section = it.section, name = it.name,
                        est_amount = it.amount.toDec(), status = "planned", account_id = acc, category_id = categoryFor(it),
                        due_date = Plan.dueDateInCycle(cycle, it.day, startDay()), note = it.note,
                    )
                )
                one++
            } else {
                val old = billTemplates.firstOrNull { t -> t.name.trim().equals(it.name, ignoreCase = true) }
                val row = (old ?: BillTemplate(id = "", active = true, account_id = acc, sort = (billTemplates.mapNotNull { t -> t.sort }.maxOrNull() ?: 0) + 1)).copy(
                    group = PlanBook.groupOf(it.section), section = it.section, name = it.name, est_amount = it.amount.toDec(),
                    due_day = it.day, note = it.note.ifEmpty { old?.note.orEmpty() },
                    category_id = old?.category_id?.ifEmpty { null } ?: categoryFor(it), active = true,
                )
                if (old == null) createBillTemplate(row) else updateBillTemplate(row)
                tpl++
            }
        }
        return tpl to one
    }

    // ---------- Myself Tax / No Discount ----------

    /** เปิด/ปิด + ตั้งค่า (เปิดครั้งแรก = เริ่มนับตั้งแต่วันนี้) */
    fun setSelfSave(taxOn: Boolean, discountOn: Boolean, incomePct: Double?, rates: Map<String, Double?>, targetAccountId: String) {
        val now = SelfSave.settings(cfg())
        if (taxOn && !now.taxOn) config["mytax_start"] = today()
        if (discountOn && !now.discountOn) config["nodiscount_start"] = today()
        config["mytax_enabled"] = taxOn.toString()
        config["nodiscount_enabled"] = discountOn.toString()
        if (incomePct != null) config["mytax_income_pct"] = incomePct.toString().removeSuffix(".0")
        rates.forEach { (cat, pct) ->
            if (pct == null || pct == 0.0) config.remove("mytax_rate:$cat") else config["mytax_rate:$cat"] = pct.toString().removeSuffix(".0")
        }
        config["savings_account_id"] = targetAccountId
    }

    /** โอนเข้ากระปุก (บัญชีลงทุน) แล้ว: รายการโอนแยกภาษีตัวเอง / ส่วนลด */
    fun paySelfSave(taxAmount: Double, discountAmount: Double, fromAccountId: String, toAccountId: String, date: String? = null): List<Transaction> {
        if (toAccountId.isEmpty()) fail("เลือกบัญชีลงทุนที่จะโอนเข้าก่อน (ตั้งค่าในหน้ากระปุก)")
        if (fromAccountId == toAccountId) fail("บัญชีต้นทางกับปลายทางต้องไม่ใช่บัญชีเดียวกัน")
        val day = date?.ifEmpty { null } ?: today()
        val from = accounts.firstOrNull { it.id == fromAccountId } ?: fail("กรุณาเลือกบัญชีที่โอนออก")
        val out = ArrayList<Transaction>()
        fun one(amount: Double, kind: String, note: String) {
            if (amount <= 0) return
            out += createTransaction(
                Transaction(
                    id = "", date = day, type = "transfer", account_id = from.id, to_account_id = toAccountId,
                    amount = round2(amount).toDec(), currency = from.currency, note = note, source = kind,
                )
            )
        }
        one(taxAmount, SelfSave.TAX, "Myself Tax")
        one(discountAmount, SelfSave.DISCOUNT, "No Discount")
        if (out.isEmpty()) fail("ไม่มียอดที่ต้องโอน")
        return out
    }

    fun setConfig(values: Map<String, String>) {
        config.putAll(values)
    }

    // ---------- กลับมาจดต่อ ----------

    /** จดย้อนหลังหลายรายการ (เฉพาะบรรทัดที่อ่านได้) — คืนจำนวนที่จด */
    fun addBackfill(lines: List<CatchUp.Line>): Int {
        val ok = lines.filter { it.ok }
        if (ok.isEmpty()) fail("ยังไม่มีรายการที่อ่านได้")
        ok.forEach { l ->
            createTransaction(
                Transaction(
                    id = "", date = l.date, type = l.type, account_id = l.accountId, amount = round2(l.amount!!).toDec(),
                    currency = accounts.firstOrNull { it.id == l.accountId }?.currency.orEmpty(),
                    category_id = l.categoryId, subcategory_id = l.subcategoryId, note = l.note, source = "manual",
                )
            )
        }
        return ok.size
    }

    /**
     * ปรับยอดบัญชีให้ตรงยอดจริง: ส่วนต่างบันทึกเป็นรายการเดียว (หมวด อื่นๆ / รายรับอื่นๆ) source = adjust
     * ตรงกันอยู่แล้ว = null · บัญชีหนี้ที่มีตัวคำนวณปรับที่นี่ไม่ได้
     */
    fun adjustBalance(accountId: String, actual: Double, date: String? = null, note: String = ""): Transaction? {
        val acc = accounts.firstOrNull { it.id == accountId } ?: fail("ไม่พบบัญชี")
        if (isEngineAccount(acc) || isSplAccount(acc)) fail("บัญชีหนี้ที่มีตัวคำนวณ ให้เทียบยอดในหน้าหนี้")
        if (actual.isNaN() || actual.isInfinite()) fail("ยอดจริงไม่ถูกต้อง")
        val row = CatchUp.balanceRows(data(), today()).firstOrNull { it.account.id == accountId } ?: fail("บัญชีนี้ปิดใช้อยู่")
        val adj = CatchUp.adjustment(row, actual) ?: return null
        val (main, sub) = if (adj.type == "income") categoryByName("รายรับอื่นๆ", "income") else categoryByName("อื่นๆ")
        return createTransaction(
            Transaction(
                id = "", date = date?.ifEmpty { null } ?: today(), type = adj.type, account_id = acc.id, amount = adj.amount.toDec(),
                currency = acc.currency, category_id = main, subcategory_id = sub,
                note = note.ifEmpty { if (adj.type == "income") "ยอดที่ไม่ได้จด (เงินเข้า)" else "ยอดที่ไม่ได้จด" }, source = CatchUp.ADJUST,
            )
        )
    }
}
