package ism.dansha.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.core.Account
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.engine.AscendState
import ism.dansha.core.engine.DebtState
import ism.dansha.core.engine.LoanOut
import ism.dansha.core.engine.Reminders
import ism.dansha.core.engine.SplState
import ism.dansha.core.engine.StatementRow
import ism.dansha.core.engine.num

/*
 * หน้ารายละเอียดหนี้ของแต่ละบัญชี (Ascend PayNext / PayNext Extra / Shopee PayLater)
 * กดการ์ดในแท็บหนี้แล้วเปิดหน้านี้: ยอดรวม บิล ก้อนหนี้พร้อมตารางผ่อนทุกงวด บิลล่วงหน้า ประวัติการชำระ
 */

private fun m(v: Double?) = formatMoney(v)

private val KIND_TH = mapOf(
    "cash" to "เบิกเงินสด", "installment" to "ผ่อนตอนซื้อ", "convert" to "แปลงยอดเป็นผ่อน", "fullpay_snapshot" to "ยอดเต็มจำนวนยกมา",
)

@Composable
fun DebtDetailPage(
    d: DanshaData,
    acc: Account,
    st: DebtState,
    onPay: () -> Unit,
    onQuote: () -> Unit,
    onDraw: () -> Unit,
    onAddLoan: () -> Unit,
    onEditLoan: (String) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    FormPage(acc.name, onClose, onSave = null) {
        // ---- ยอดรวม ----
        Column(Modifier.fillMaxWidth().paperCard(p, p.card, radius = 14.dp).padding(16.dp)) {
            Text("หนี้คงเหลือ", color = p.muted, fontSize = 14.sp)
            Text("฿" + m(st.outstanding), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = p.negative)
            val limit = acc.credit_limit.num()
            if (limit > 0) {
                Spacer(Modifier.height(6.dp))
                ProgressBar((st.outstanding / limit).toFloat(), p.negative, p.negativeSoft)
                Text("ใช้ไป ${m(st.outstanding)} จากวงเงิน ${m(limit)} · ว่าง ${m((limit - st.outstanding).coerceAtLeast(0.0))}", fontSize = 13.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            when (st) {
                is AscendState -> {
                    KeyValue("ดอกสะสมถึงวันนี้", m(st.accruedInterest))
                    KeyValue("ดอกต่อวันประมาณ", m(st.dailyInterest))
                    if (st.fees > 0) KeyValue("ค่าธรรมเนียมค้าง", m(st.fees), p.negative)
                    KeyValue("ปิดหนี้ทั้งหมดวันนี้", m(st.payoffToday), bold = true)
                }
                is SplState -> {
                    if (st.credit > 0) KeyValue("จ่ายเกิน (เครดิต)", m(st.credit), p.positive)
                    KeyValue("ออเดอร์ที่ยังผ่อนอยู่", "${st.perOrder.count { it.value.left > 0.004 }} ออเดอร์")
                }
            }
        }

        // ---- บิลที่ต้องจ่าย ----
        val cur = st.currentBill
        val next = st.nextBill
        if (cur != null || next != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                cur?.let { b ->
                    val late = b.overdue == true || b.due < Dates.todayStr()
                    val days = Reminders.daysUntil(Dates.todayStr(), b.due)
                    BillNote(
                        if (late) "บิลค้าง · เลยกำหนด" else if (days == 0) "ต้องจ่ายวันนี้!" else "บิลนี้ · อีก $days วัน",
                        b.amount, "ครบ ${thaiDate(b.due)}", if (late) p.negativeSoft else p.sticky, Modifier.weight(1f), late,
                    )
                }
                next?.let { b ->
                    if (cur == null || b.due != cur.due) {
                        BillNote(
                            "บิลถัดไป", b.totalDue,
                            "ออก ${shortThaiDate(b.date)} · ครบ ${shortThaiDate(b.due)}" + (b.interest?.let { "\nดอก ${m(it)}" } ?: ""),
                            p.stickyBlue, Modifier.weight(1f), false,
                        )
                    }
                }
            }
        }
        if (st is AscendState) st.lastStatement?.let { ls ->
            Note("บิลล่าสุดที่ออก ${thaiDate(ls.date)} ครบ ${thaiDate(ls.due)} ยอด ${m(ls.totalDue)}")
        }

        // ---- ปุ่ม ----
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPay) { Text("จ่าย/โปะ") }
            if (st is AscendState) {
                OutlinedButton(onClick = onQuote) { Text("ยอดปิด") }
                OutlinedButton(onClick = onDraw) { Text("เบิกเงินสด") }
                OutlinedButton(onClick = onAddLoan) { Text("+ หนี้ย่อย") }
            }
        }

        when (st) {
            is AscendState -> AscendDetail(d, st, onEditLoan)
            is SplState -> SplDetail(st)
        }
    }
}

@Composable
private fun BillNote(title: String, amount: Double, sub: String, bg: Color, modifier: Modifier, alert: Boolean) {
    val p = LocalPalette.current
    Column(modifier.paperCard(p, bg, radius = 10.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (alert) p.negative else p.ink.copy(alpha = 0.8f))
        Text("฿" + m(amount), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.negative)
        Text(sub, fontSize = 13.sp, color = p.ink.copy(alpha = 0.8f))
    }
}

@Composable
private fun DetailHeader(text: String) {
    Text(text, Modifier.padding(top = 8.dp), fontFamily = Hand, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = DanshaColors.Primary)
}

@Composable
private fun AscendDetail(d: DanshaData, st: AscendState, onEditLoan: (String) -> Unit) {
    val p = LocalPalette.current
    if (st.cycleSpend.total > 0) {
        Note("ใช้ในรอบบิล ${thaiDate(st.cycleSpend.start)} – ${thaiDate(st.cycleSpend.end)}: ${m(st.cycleSpend.total)} (ซื้อเต็ม ${m(st.cycleSpend.fullpay)} · ผ่อน/เงินสด ${m(st.cycleSpend.installment)})", p.ink)
    }

    // ก้อนหนี้
    val active = st.loans.filter { it.status != "closed" }
    val closed = st.loans.filter { it.status == "closed" }
    DetailHeader("ก้อนหนี้ (${active.size} ก้อน)")
    active.forEach { l -> androidx.compose.runtime.key(l.id) { LoanCard(l, st.schedule?.get(l.id), d.debts.any { it.id == l.id }) { onEditLoan(l.id) } } }
    if (closed.isNotEmpty()) {
        var showClosed by remember { mutableStateOf(false) }
        Text(
            if (showClosed) "ซ่อนก้อนที่ปิดแล้ว ▴" else "ดูก้อนที่ปิดแล้ว (${closed.size}) ▾",
            Modifier.clickable { showClosed = !showClosed }.padding(vertical = 6.dp), color = p.primary, fontWeight = FontWeight.SemiBold,
        )
        if (showClosed) closed.forEach { l -> androidx.compose.runtime.key(l.id) { LoanCard(l, null, d.debts.any { it.id == l.id }) { onEditLoan(l.id) } } }
    }

    if (st.pendingFull.isNotEmpty()) {
        DetailHeader("ยอดเต็มจำนวนค้าง")
        Card {
            st.pendingFull.forEach { u ->
                KeyValue("${thaiDate(u.date)} ${u.desc}" + (u.billedDue?.let { " · ครบ ${shortThaiDate(it)}" } ?: ""), m(u.remaining))
            }
        }
    }

    st.bills?.takeIf { it.isNotEmpty() }?.let { bills ->
        DetailHeader("บิลล่วงหน้า")
        Note("สมมติว่าจ่ายตรงยอดบิลทุกงวด ตั้งแต่วันนี้")
        Card {
            TableRow(listOf("ครบกำหนด", "ยอดบิล", "ดอก"), header = true)
            bills.take(12).forEach { b -> TableRow(listOf(thaiDate(b.due), m(b.totalDue), m(b.interest))) }
        }
    }

    if (st.payments.isNotEmpty()) {
        DetailHeader("ประวัติการชำระ")
        Card {
            TableRow(listOf("วันที่", "จ่าย", "ดอก / ต้น"), header = true)
            st.payments.forEach { pm ->
                TableRow(listOf(thaiDate(pm.date), m(pm.amount), "${m(pm.interest)} / ${m(pm.principal)}"))
                if ((pm.fee ?: 0.0) > 0) Note("  ค่าธรรมเนียม ${m(pm.fee)}")
            }
        }
    }
}

@Composable
private fun LoanCard(l: LoanOut, rows: List<StatementRow>?, editable: Boolean, onEdit: () -> Unit) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().paperCard(p, if (l.status == "closed") p.surface else p.card, radius = 10.dp, shadow = false)
            .clickable { open = !open }.padding(14.dp).animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.desc.ifEmpty { KIND_TH[l.kind] ?: l.kind }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = p.ink)
                Text("${KIND_TH[l.kind] ?: l.kind} · เริ่ม ${thaiDate(l.start)} · ดอก ${pct(l.rate)}%/ปี", fontSize = 13.sp, color = p.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(m(l.remaining), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = if (l.status == "closed") p.muted else p.negative)
                Text("คงเหลือ", fontSize = 12.sp, color = p.muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        val frac = if (l.tenor > 0) l.paidPeriods.toFloat() / l.tenor else 1f
        ProgressBar(frac, p.positive, p.positiveSoft, height = 6)
        Text(
            "จ่ายแล้ว ${l.paidPeriods}/${l.tenor} งวด · เหลือ ${l.periodsLeft} งวด · งวดละ ${m(l.installment)}",
            fontSize = 13.sp, color = p.ink, modifier = Modifier.padding(top = 4.dp),
        )
        Text("เงินต้น ${m(l.principal)} · ดอกค้าง ${m(l.accruedInterest)}", fontSize = 13.sp, color = p.muted)
        l.openBill?.let { b -> Text("บิลงวดที่ ${b.k} ครบ ${thaiDate(b.due)}: ${m(b.O)} (ต้น ${m(b.principalDue)} ดอก ${m(b.interestDue)})", fontSize = 13.sp, color = p.negative) }
        if (open) {
            if (!rows.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("ตารางผ่อนที่เหลือ", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = p.ink)
                TableRow(listOf("งวด/ครบ", "ต้น", "ดอก", "รวม", "คงเหลือ"), header = true)
                rows.forEach { r -> TableRow(listOf("${r.k} · ${shortThaiDate(r.due)}", m(r.principalDue), m(r.interestDue), m(r.O), m((r.R - r.principalDue).coerceAtLeast(0.0)))) }
            }
            if (editable) Text("แก้ไขก้อนนี้ ›", Modifier.clickable(onClick = onEdit).padding(top = 8.dp), color = p.primary, fontWeight = FontWeight.SemiBold)
        } else {
            Text(if (rows.isNullOrEmpty()) "แตะเพื่อดูเพิ่ม" else "แตะเพื่อดูตารางผ่อนทุกงวด ▾", fontSize = 12.sp, color = p.primary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SplDetail(st: SplState) {
    val p = LocalPalette.current
    if (st.bills.isNotEmpty()) {
        DetailHeader("บิลแต่ละเดือน")
        Card {
            TableRow(listOf("ตัดรอบ", "ครบกำหนด", "ยอด"), header = true)
            st.bills.forEach { b -> TableRow(listOf(shortThaiDate(b.date), thaiDate(b.due), m(b.totalDue))) }
        }
    }
    val byOrder = st.installments.groupBy { it.order }
    val openOrders = byOrder.filter { (_, l) -> l.any { it.left > 0.004 } }
    DetailHeader("ออเดอร์ที่ผ่อนอยู่ (${openOrders.size})")
    openOrders.forEach { (orderId, list) -> androidx.compose.runtime.key(orderId) {
        var open by remember { mutableStateOf(false) }
        val first = list.first()
        val left = list.sumOf { it.left }
        val paid = list.count { it.left <= 0.004 }
        Column(Modifier.fillMaxWidth().paperCard(p, p.card, radius = 10.dp, shadow = false).clickable { open = !open }.padding(14.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(first.item, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 2)
                Text(m(left), fontWeight = FontWeight.Bold, color = p.negative)
            }
            ProgressBar(paid.toFloat() / first.n, p.positive, p.positiveSoft, Modifier.padding(top = 6.dp), height = 6)
            Text("จ่ายแล้ว $paid/${first.n} งวด · งวดถัดไป ${list.firstOrNull { it.left > 0.004 }?.due?.let(::thaiDate) ?: "-"}", fontSize = 13.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
            if (open) {
                TableRow(listOf("งวด", "ครบกำหนด", "ยอด", "เหลือ"), header = true)
                list.forEach { i -> TableRow(listOf("${i.k}/${i.n}", shortThaiDate(i.due), m(i.amount), m(i.left))) }
            }
        }
    } }
    val done = byOrder.size - openOrders.size
    if (done > 0) Note("ผ่อนครบแล้ว $done ออเดอร์")
}

/** แถวตาราง: คอลัมน์แรกชิดซ้าย ที่เหลือชิดขวา */
@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        cells.forEachIndexed { i, c ->
            Text(
                c, Modifier.weight(if (i == 0) 1.4f else 1f), fontSize = 13.sp,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                color = if (header) p.muted else p.ink, fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
    if (header) HorizontalDivider(color = p.line)
}

private fun pct(rate: Double): String = java.math.BigDecimal.valueOf(rate * 100).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

