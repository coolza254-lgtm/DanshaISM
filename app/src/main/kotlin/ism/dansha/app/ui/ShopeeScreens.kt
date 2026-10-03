package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.ShopeeOrder
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.PurchaseCheck
import ism.dansha.core.engine.PurchaseQuery
import ism.dansha.core.engine.SplState
import ism.dansha.core.engine.isSplAccount
import ism.dansha.core.engine.toDec
import java.math.BigDecimal

private fun money(v: Double?) = formatMoney(v?.let { BigDecimal.valueOf(it) })

@Composable
fun ShopeePage(d: DanshaData, c: MainViewModel.Computed?, vm: MainViewModel, onClose: () -> Unit) {
    var recording by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShopeeOrder?>(null) }
    val spl = c?.debts?.values?.filterIsInstance<SplState>().orEmpty()
    val perOrder = spl.flatMap { it.perOrder.entries }.associate { it.key to it.value }
    val stats = remember(d) { Engine.shopeeStats(d) }

    FormPage("Shopee", onClose, onSave = null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { recording = true }) { Text("บันทึกออเดอร์") }
            OutlinedButton(onClick = { checking = true }) { Text("เช็คก่อนซื้อ") }
        }
        spl.forEach { st ->
            Card {
                Text(d.accounts.firstOrNull { it.id == st.accountId }?.name ?: "SPayLater", fontWeight = FontWeight.SemiBold)
                KeyValue("ยอดค้างทั้งหมด", money(st.outstanding), bold = true)
                st.currentBill?.let { KeyValue(if (it.overdue == true) "ค้างจ่าย (เลยกำหนด)" else "บิลเดือนนี้ ครบ ${thaiDate(it.due)}", money(it.amount), if (it.overdue == true) DanshaColors.Negative else DanshaColors.Ink) }
                st.bills.take(4).forEach { b -> KeyValue("ครบ ${thaiDate(b.due)}", money(b.totalDue)) }
                if (st.credit > 0) KeyValue("จ่ายเกิน (เครดิต)", money(st.credit))
            }
        }
        Text("ออเดอร์", fontWeight = FontWeight.SemiBold)
        val orders = d.shopee.sortedWith(compareByDescending<ShopeeOrder> { it.order_date }.thenByDescending { it.created_at })
        if (orders.isEmpty()) Note("ยังไม่มีออเดอร์")
        orders.forEach { o ->
            val po = perOrder[o.id]
            Column(Modifier.fillMaxWidth().clickable { editing = o }.padding(vertical = 6.dp)) {
                Row {
                    Text(o.item, Modifier.weight(1f), color = if (o.status == "cancelled") DanshaColors.Muted else DanshaColors.Ink, maxLines = 2)
                    Text(formatMoney(o.price), fontWeight = FontWeight.SemiBold)
                }
                Text(
                    listOfNotNull(
                        thaiDate(o.order_date),
                        o.shop.ifEmpty { null },
                        if (o.pay_method == "spaylater") "SPayLater ${o.tenor ?: 1} งวด" + (o.installment?.let { " × ${formatMoney(it)}" } ?: "") else "จ่ายเต็ม",
                        if (o.status == "cancelled") "ยกเลิก" else null,
                        po?.let { if (it.left > 0) "เหลือ ${money(it.left)} งวดถัดไป ${it.next?.let(::thaiDate) ?: "-"}" else "ผ่อนครบแล้ว" },
                    ).joinToString(" · "),
                    color = DanshaColors.Muted, fontSize = 13.sp,
                )
            }
            HorizontalDivider(color = DanshaColors.Line)
        }
        Card {
            SectionLabel("สถิติ (ไม่นับที่ยกเลิก)")
            KeyValue("จำนวนออเดอร์", "${stats.count}")
            KeyValue("ยอดรวม / เฉลี่ย", "${money(stats.total)} / ${money(stats.avg)}")
            KeyValue("สัดส่วนที่ผ่อน", "${stats.installmentShare}%")
            KeyValue("ดอกเบี้ยรวมจากการผ่อน", money(stats.interestTotal))
            if (stats.byMonth.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("รายเดือน", fontWeight = FontWeight.Medium)
                stats.byMonth.takeLast(6).forEach { m -> KeyValue("${m.month} (${m.count})", "เต็ม ${money(m.full)} · ผ่อน ${money(m.spaylater)}") }
            }
            if (stats.byShop.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("ร้านที่ซื้อบ่อย", fontWeight = FontWeight.Medium)
                stats.byShop.forEach { s -> KeyValue("${s.shop} (${s.count})", money(s.total)) }
            }
        }
    }
    if (recording) PurchaseForm(d, vm) { recording = false }
    if (checking) CheckPurchasePage(d) { checking = false }
    editing?.let { o -> OrderEditor(d, o, vm) { editing = null } }
}

@Composable
private fun PurchaseForm(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    val accounts = Forms.accountChoices(d)
    var item by remember { mutableStateOf("") }
    var shop by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf(accounts.firstOrNull { isSplAccount(it) }?.id ?: accounts.firstOrNull()?.id.orEmpty()) }
    var tenor by remember { mutableStateOf("1") }
    var installment by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    var categoryId by remember { mutableStateOf(d.categories.firstOrNull { it.name == "Shopee" }?.parent_id ?: "") }
    var subId by remember { mutableStateOf(d.categories.firstOrNull { it.name == "Shopee" }?.id ?: "") }
    var orderNo by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val spl = isSplAccount(d.accounts.firstOrNull { it.id == accountId })

    fun save() {
        vm.edit<Any>(ok = "บันทึกออเดอร์แล้ว", onError = { error = it }, onOk = { onClose() }) {
            recordPurchase(
                item = item.trim(), price = Forms.parseAmount(price) ?: 0.0, accountId = accountId, date = date,
                tenor = tenor.toIntOrNull(), installment = Forms.parseAmount(installment), shop = shop,
                categoryId = categoryId, subcategoryId = subId, note = note, orderNo = orderNo,
            )
        }
    }

    FormPage("บันทึกออเดอร์ Shopee", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        TextInput("สินค้า", item, { item = it })
        TextInput("ร้าน", shop, { shop = it })
        AmountField("ราคา", price, { price = it })
        Picker("จ่ายด้วย", accounts.map { it.id to it.name + if (isSplAccount(it)) " (ผ่อน)" else "" }, accountId, { accountId = it })
        if (spl) {
            AmountField("จำนวนงวด", tenor, { tenor = it.filter(Char::isDigit) })
            if ((tenor.toIntOrNull() ?: 1) > 1) AmountField("ค่างวด (ดูจากแอพ Shopee)", installment, { installment = it }, supporting = "ต้องกรอก ถ้าผ่อนมากกว่า 1 งวด")
            Note("รอบบิลเดือน M = สั่ง 15/M – 14/M+1 → งวดแรกครบกำหนดวันที่ 25 ของเดือนถัดไป")
        } else {
            Note("จ่ายเต็ม: จะสร้างรายจ่าย \"Shopee: …\" ให้อัตโนมัติ")
            val tree = Forms.categoryTree(d, "expense", keep = setOf(categoryId, subId))
            Picker("หมวด", listOf("" to "— ไม่ระบุ —") + tree.map { it.category.id to "${it.category.icon} ${it.category.name}" }, categoryId, { categoryId = it; subId = "" })
            val subs = tree.firstOrNull { it.category.id == categoryId }?.children.orEmpty()
            if (subs.isNotEmpty()) ChoiceChips(listOf("" to "ทั้งหมวด") + subs.map { it.id to it.name }, subId, { subId = it })
        }
        DateField("วันที่สั่ง", date, { date = it })
        TextInput("เลขออเดอร์", orderNo, { orderNo = it })
        TextInput("โน้ต", note, { note = it })
    }
}

@Composable
private fun OrderEditor(d: DanshaData, o: ShopeeOrder, vm: MainViewModel, onClose: () -> Unit) {
    var item by remember { mutableStateOf(o.item) }
    var shop by remember { mutableStateOf(o.shop) }
    var price by remember { mutableStateOf(amountText(o.price)) }
    var tenor by remember { mutableStateOf((o.tenor ?: 1).toString()) }
    var installment by remember { mutableStateOf(amountText(o.installment)) }
    var date by remember { mutableStateOf(o.order_date) }
    var cancelled by remember { mutableStateOf(o.status == "cancelled") }
    var note by remember { mutableStateOf(o.note) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val row = o.copy(
            item = item, shop = shop, price = Forms.parseAmount(price)?.toDec(), order_date = date, note = note,
            tenor = if (o.pay_method == "spaylater") tenor.toIntOrNull() ?: 1 else o.tenor,
            installment = if (o.pay_method == "spaylater") Forms.parseAmount(installment)?.toDec() else o.installment,
            status = if (cancelled) "cancelled" else if (o.status == "cancelled") "active" else o.status,
        )
        vm.edit<ShopeeOrder>(ok = "บันทึกแล้ว", onError = { error = it }, onOk = { onClose() }) { updateShopee(row) }
    }

    FormPage("แก้ไขออเดอร์", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        TextInput("สินค้า", item, { item = it })
        TextInput("ร้าน", shop, { shop = it })
        AmountField("ราคา", price, { price = it }, supporting = if (o.txn_id.isNotEmpty()) "แก้ราคาแล้วรายจ่ายที่ผูกไว้จะแก้ตาม" else null)
        if (o.pay_method == "spaylater") {
            AmountField("จำนวนงวด", tenor, { tenor = it.filter(Char::isDigit) })
            AmountField("ค่างวด", installment, { installment = it })
        }
        DateField("วันที่สั่ง", date, { date = it })
        SwitchRow("ยกเลิก/คืนเงินแล้ว", cancelled, { cancelled = it }, sub = if (o.txn_id.isNotEmpty()) "จ่ายเต็ม: ยกเลิกแล้วรายจ่ายที่ผูกไว้จะถูกลบ" else "ไม่นับในยอดค้าง SPayLater")
        TextInput("โน้ต", note, { note = it })
        DeleteButton("ออเดอร์นี้") {
            vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }, onOk = { onClose() }) { deleteShopee(o.id) }
        }
    }
}

// ---------- เช็คก่อนซื้อ ----------

@Composable
fun CheckPurchasePage(d: DanshaData, onClose: () -> Unit) {
    var price by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("full") }
    var tenor by remember { mutableStateOf("3") }
    var installment by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    val p = Forms.parseAmount(price)
    val result: Result<PurchaseCheck>? = if (p != null && p > 0) runCatching {
        Engine.checkPurchase(
            d, PurchaseQuery(p, method, tenor.toIntOrNull(), Forms.parseAmount(installment), null, date),
        )
    } else null

    FormPage("เช็คก่อนซื้อ", onClose, onSave = null) {
        AmountField("ราคา", price, { price = it })
        ChoiceChips(listOf("full" to "จ่ายเต็ม", "spaylater" to "SPayLater"), method, { method = it })
        if (method == "spaylater") {
            Row {
                AmountField("งวด", tenor, { tenor = it.filter(Char::isDigit) }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                AmountField("ค่างวด", installment, { installment = it }, Modifier.weight(1f), supporting = "ว่าง = ราคา ÷ งวด")
            }
        }
        DateField("วันที่จะซื้อ", date, { date = it })
        result?.exceptionOrNull()?.let { Note(it.message ?: "คำนวณไม่ได้", DanshaColors.Negative) }
        result?.getOrNull()?.let { r -> CheckResult(r) }
    }
}

@Composable
private fun CheckResult(r: PurchaseCheck) {
    val (bg, label) = when (r.verdict) {
        "red" -> Color(0xFFFDE2E2) to "ไม่ควรซื้อตอนนี้"
        "yellow" -> Color(0xFFFFF4D6) to "ซื้อได้ แต่ระวัง"
        else -> Color(0xFFE2F5E7) to "ซื้อได้"
    }
    Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        r.flags.forEach { f ->
            Text("• ${f.text}", color = if (f.level == "red") DanshaColors.Negative else DanshaColors.Ink, fontSize = 14.sp)
        }
        if (r.flags.isEmpty()) Text("ไม่มีคำเตือน", fontSize = 14.sp)
    }
    Card {
        SectionLabel("ผลกระทบแต่ละรอบ (คงเหลือ → หลังซื้อ)")
        r.impact.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.cycle.drop(8) + if (c.extra > 0) " (−${money(c.extra)})" else "", Modifier.weight(1f), fontSize = 13.sp)
                Text("${money(c.remaining)} → ", color = DanshaColors.Muted, fontSize = 13.sp)
                Text(money(c.after), color = if (c.after < 0) DanshaColors.Negative else DanshaColors.Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
        if (r.laterCycles.isNotEmpty()) Note("มีค่างวดในรอบหลังจากนี้อีก: ${r.laterCycles.joinToString { it.drop(8) }}")
        r.maxBurden?.let { Note("ภาระค่างวดหนี้สูงสุด $it% ของรายรับ") }
    }
    Card {
        SectionLabel("ที่เลือก")
        KeyValue("${r.chosen.tenor} งวด × ${money(r.chosen.installment)}", "รวม ${money(r.chosen.total)}")
        if (r.chosen.interest > 0) KeyValue("ดอกเบี้ยรวม (อัตราจริง ~${r.chosen.effectiveRate}%/ปี)", money(r.chosen.interest), DanshaColors.Negative)
        Spacer(Modifier.height(6.dp))
        SectionLabel("เทียบผ่อน (ประมาณที่ 25%/ปี)")
        r.options.forEach { o -> KeyValue("${o.tenor} งวด × ${money(o.installment)}", "ดอก ${money(o.interest)}") }
    }
}
