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
        return row
    }

    fun updateTransaction(t: Transaction, copay: Boolean? = null): Transaction {
        val old = transactions.firstOrNull { it.id == t.id } ?: fail("ไม่พบรายการ id=${t.id}")
        val row = validateTransaction(t, false, copay).copy(created_at = old.created_at)
        transactions.replaceWhere(t.id, { it.id }, row)
        return row
    }

    /** ลบรายการ → ลบหนี้ย่อยที่ผูก, ปลดลิงก์ Shopee, ถ้ามาจากแผนบิลให้แผนกลับเป็น planned */
    fun deleteTransaction(id: String) {
        debts.removeAll { it.txn_id == id }
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

    private fun validateBill(b0: Bill, isNew: Boolean): Bill {
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

    private fun validateTemplate(t: BillTemplate): BillTemplate {
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
                    template_id = t0.id, note = t0.note, sort = t0.sort,
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
        var o = o0.copy(created_at = old.created_at, order_date = date(o0.order_date))
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
            account_id = acc.id, pay_method = if (spl) "spaylater" else "full", status = "active",
            category_id = categoryId, note = note, order_no = orderNo,
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
        val st = SPayLater.state(acc, shopee, transactions, today())
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

    fun setConfig(values: Map<String, String>) {
        config.putAll(values)
    }
}
