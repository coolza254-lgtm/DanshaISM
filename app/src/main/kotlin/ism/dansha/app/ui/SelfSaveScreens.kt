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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.SelfSave

/*
 * กระปุกออม: Myself Tax (ภาษีตัวเองจากรายจ่ายแต่ละหมวด + ภาษีเงินได้ 1%) และ No Discount (ส่วนลดเข้ากระปุก)
 * ยอดสะสมทุกวัน → โอนเข้าบัญชีลงทุนเองเมื่อสะดวก แล้วกด "โอนแล้ว"
 */

/** การ์ดในหน้าภาพรวม: ยอดค้างโอน */
@Composable
fun SelfSaveCard(d: DanshaData, vm: MainViewModel) {
    val st = remember(d) { SelfSave.status(d) }
    if (!st.enabled || st.totalDue <= 0) return
    val p = LocalPalette.current
    var paying by remember { mutableStateOf(false) }
    Card(color = p.positiveSoft, modifier = Modifier.clickable { paying = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🐷", fontSize = 26.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("กระปุกออมรอโอน", fontFamily = Hand, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.positive)
                Text(
                    listOfNotNull(
                        st.taxDue.takeIf { it > 0 }?.let { "Myself Tax ฿${formatMoney(it)}" },
                        st.discountDue.takeIf { it > 0 }?.let { "No Discount ฿${formatMoney(it)}" },
                        st.today.takeIf { it > 0 }?.let { "วันนี้ +฿${formatMoney(it)}" },
                    ).joinToString(" · "),
                    fontSize = 13.sp, color = p.ink,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("฿" + formatMoney(st.totalDue), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = p.positive)
                Text("โอนแล้ว ›", fontSize = 13.sp, color = p.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (paying) PaySelfSavePage(d, st, vm) { paying = false }
}

@Composable
fun PaySelfSavePage(d: DanshaData, st: SelfSave.Status, vm: MainViewModel, onClose: () -> Unit) {
    var tax by remember { mutableStateOf(amountText(st.taxDue)) }
    var disc by remember { mutableStateOf(amountText(st.discountDue)) }
    var from by remember { mutableStateOf(Forms.defaultAccount(d, "expense")) }
    var to by remember { mutableStateOf(st.settings.targetAccountId) }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    var error by remember { mutableStateOf<String?>(null) }
    val accounts = Forms.accountChoices(d).filter { it.type != "revolving_credit" }.map { it.id to it.name }
    FormPage("โอนเข้ากระปุก", onClose, onSave = {
        vm.edit<Any>(ok = "โอนเข้ากระปุกแล้ว 🐷", onError = { error = it }, onOk = { onClose() }) {
            paySelfSave(Forms.parseAmount(tax) ?: 0.0, Forms.parseAmount(disc) ?: 0.0, from, to, date)
        }
    }, saveLabel = "โอนแล้ว") {
        error?.let { Note(it, DanshaColors.Negative) }
        if (st.settings.taxOn) AmountField("Myself Tax", tax, { tax = it }, supporting = "ค้าง ${formatMoney(st.taxDue)}")
        if (st.settings.discountOn) AmountField("No Discount (ส่วนลด)", disc, { disc = it }, supporting = "ค้าง ${formatMoney(st.discountDue)}")
        Picker("โอนจาก", accounts, from, { from = it })
        Picker("เข้าบัญชีลงทุน", listOf("" to "— เลือก —") + accounts, to, { to = it })
        DateField("วันที่โอน", date, { date = it })
    }
}

/** หน้ากระปุกออม: ยอด + ตั้งค่า + ที่มาของยอด */
@Composable
fun SelfSavePage(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val st = remember(d) { SelfSave.status(d) }
    val s = st.settings
    var taxOn by remember { mutableStateOf(s.taxOn) }
    var discOn by remember { mutableStateOf(s.discountOn) }
    var incomePct by remember { mutableStateOf(amountText(s.incomePct)) }
    var target by remember { mutableStateOf(s.targetAccountId) }
    val rates = remember { mutableStateMapOf<String, String>().apply { s.rates.forEach { (k, v) -> put(k, amountText(v)) } } }
    var paying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cats = remember(d) { Forms.categoryTree(d, "expense") }
    val catNames = remember(d) { d.categories.associateBy { it.id } }
    val accounts = Forms.accountChoices(d).filter { it.type != "revolving_credit" }.map { it.id to it.name }

    fun save() {
        val r = cats.associate { it.category.id to Forms.parseAmount(rates[it.category.id].orEmpty()) }
        vm.edit<Unit>(ok = "บันทึกการตั้งค่ากระปุกแล้ว", onError = { error = it }, onOk = { onClose() }) {
            setSelfSave(taxOn, discOn, Forms.parseAmount(incomePct), r, target)
        }
    }

    FormPage("กระปุกออม 🐷", onClose, ::save) {
        error?.let { Note(it, p.negative) }
        if (st.enabled) {
            Column(Modifier.fillMaxWidth().paperCard(p, p.positiveSoft, radius = 14.dp).padding(16.dp)) {
                Text("รอโอนเข้าบัญชีลงทุน", color = p.ink.copy(alpha = 0.8f), fontSize = 14.sp)
                FitText("฿" + formatMoney(st.totalDue), androidx.compose.ui.text.TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = Sarabun, color = p.positive))
                if (s.taxOn) KeyValue("Myself Tax (สะสม ${formatMoney(st.taxAccrued)} · โอนแล้ว ${formatMoney(st.taxPaid)})", formatMoney(st.taxDue), bold = true)
                if (s.discountOn) KeyValue("No Discount (สะสม ${formatMoney(st.discountAccrued)} · โอนแล้ว ${formatMoney(st.discountPaid)})", formatMoney(st.discountDue), bold = true)
                if (st.today > 0) KeyValue("เกิดวันนี้", "+" + formatMoney(st.today), p.positive)
                if (st.totalDue > 0) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { paying = true }, modifier = Modifier.fillMaxWidth()) { Text("โอนเข้ากระปุกแล้ว") }
                }
            }
        }

        // Myself Tax
        SwitchRow("Myself Tax", taxOn, { taxOn = it }, sub = "ทุกรายจ่ายเสียภาษีให้ตัวเองตาม % ของหมวด + ทุกรายรับหัก % เข้ากระปุก (เริ่มนับตั้งแต่วันที่เปิด)")
        if (taxOn) {
            AmountField("ภาษีเงินได้ของตัวเอง (% ของทุกรายรับ)", incomePct, { incomePct = it }, supporting = "ค่าตั้งต้น 1% · ไม่นับเงินเบิก Staff Claim")
            Text("ภาษีรายจ่ายแต่ละหมวด (%) — เว้นว่าง = ไม่เก็บ", fontWeight = FontWeight.Medium, color = p.ink)
            cats.forEach { n ->
                val c = n.category
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryIcon(c.name, c.icon, c.color, size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(c.name, Modifier.weight(1f), color = p.ink)
                    AmountField("%", rates[c.id].orEmpty(), { rates[c.id] = it }, Modifier.width(96.dp))
                }
            }
        }
        HorizontalDivider(color = p.line)

        // No Discount
        SwitchRow("No Discount", discOn, { discOn = it }, sub = "ได้ส่วนลดเท่าไหร่ ไม่นับว่าประหยัด แต่เก็บส่วนต่างเข้ากระปุกทันที (ไม่มีข้อยกเว้น)")
        if (discOn) Note("ตอนจดรายจ่ายจะมีช่อง \"ราคาปกติ (ก่อนลด)\" เช่น กาแฟปกติ 35 จ่าย 25 → ออม 10 บาท", p.ink)
        HorizontalDivider(color = p.line)

        Picker("บัญชีลงทุนที่โอนเข้า", listOf("" to "— เลือก —") + accounts, target, { target = it })

        // ที่มาของยอด
        if (st.lines.isNotEmpty()) {
            Text("ที่มาของยอดล่าสุด", fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.padding(top = 8.dp))
            st.lines.take(40).forEach { l ->
                val t = l.txn
                val cat = catNames[t.subcategory_id.ifEmpty { t.category_id }] ?: catNames[t.category_id]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(shortThaiDate(t.date), Modifier.width(52.dp), fontSize = 13.sp, color = p.muted)
                    Column(Modifier.weight(1f)) {
                        Text(t.note.ifEmpty { cat?.name ?: if (t.type == "income") "รายรับ" else "-" }, fontSize = 14.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (l.kind == SelfSave.DISCOUNT) "ส่วนลด (จ่าย ${formatMoney(t.amount)})" else "${formatMoney(l.base)} × ${amountText(l.pct)}%",
                            fontSize = 12.sp, color = p.muted,
                        )
                    }
                    Text(
                        "+" + formatMoney(l.amount),
                        Modifier.clip(RoundedCornerShape(50)).background(if (l.kind == SelfSave.DISCOUNT) p.positiveSoft else p.stickyBlue).padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.ink,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    if (paying) PaySelfSavePage(d, st, vm) { paying = false }
}
