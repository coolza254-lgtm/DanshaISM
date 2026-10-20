package ism.dansha.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.core.Account
import ism.dansha.core.Dates
import ism.dansha.core.engine.AscendState
import ism.dansha.core.engine.DebtState
import ism.dansha.core.engine.Reminders
import ism.dansha.core.engine.SplState

/*
 * ชิ้นส่วนหน้าหนี้: บิลแต่ละงวด (แยกว่าเป็นของก้อนไหน ต้นเท่าไหร่ ดอกเท่าไหร่) + ป้ายวันที่
 */

/** ส่วนของบิลหนึ่งงวด: ก้อนไหน งวดที่เท่าไหร่ ต้น/ดอก */
data class BillPart(val name: String, val k: Int, val n: Int, val principal: Double?, val interest: Double?, val total: Double)

/** บิลหนึ่งงวดของบัญชีหนึ่ง */
data class DueBillView(
    val account: Account,
    val due: String,
    val amount: Double,
    val overdue: Boolean,
    /** ออกบิลแล้ว (ยอดแน่นอน) หรือยังเป็นประมาณการ */
    val issued: Boolean,
    val parts: List<BillPart>,
)

private val KIND_SHORT = mapOf("cash" to "เบิกเงินสด", "installment" to "ผ่อนสินค้า", "convert" to "แปลงยอดผ่อน", "fullpay_snapshot" to "ยอดเต็มยกมา")

/** บิลที่จะถึงของบัญชี (งวดที่ค้าง/งวดนี้ก่อน แล้วงวดถัดๆ ไป) */
fun dueBills(acc: Account, st: DebtState, limit: Int = 12): List<DueBillView> {
    val today = Dates.todayStr()
    val out = ArrayList<DueBillView>()
    when (st) {
        is AscendState -> {
            val names = st.loans.associate { it.id to it.desc.ifEmpty { KIND_SHORT[it.kind] ?: it.kind } }
            val tenor = st.loans.associate { it.id to it.tenor }
            st.currentBill?.let { b ->
                val parts = st.loans.mapNotNull { l ->
                    l.openBill?.takeIf { it.due == b.due && it.O > 0.004 }?.let { ob -> BillPart(names[l.id].orEmpty(), ob.k, l.tenor, ob.principalDue, ob.interestDue, ob.O) }
                }
                out += DueBillView(acc, b.due, b.amount, b.overdue == true || b.due < today, true, parts)
            }
            val sched = st.schedule.orEmpty()
            val rowsByDue = sched.flatMap { (id, rows) -> rows.map { id to it } }.groupBy { it.second.due }
            (st.bills ?: listOfNotNull(st.nextBill)).filter { b -> out.none { it.due == b.due } }.forEach { b ->
                val parts = rowsByDue[b.due].orEmpty().map { (id, r) -> BillPart(names[id].orEmpty(), r.k, tenor[id] ?: r.tenor, r.principalDue, r.interestDue, r.O) }
                out += DueBillView(acc, b.due, b.totalDue, false, false, parts)
            }
        }
        is SplState -> {
            val byDue = st.installments.filter { it.left > 0.004 }.groupBy { it.due }
            byDue.keys.sorted().forEach { due ->
                val list = byDue.getValue(due)
                out += DueBillView(
                    acc, due, list.sumOf { it.left }, due < today, due.take(7) <= today.take(7),
                    list.map { BillPart((if (it.kind == ism.dansha.core.engine.SPayLater.FOOD) "🍜 " else "") + it.item, it.k, it.n, null, null, it.left) },
                )
            }
        }
    }
    return out.sortedBy { it.due }.take(limit)
}

/** ป้ายวันที่: วัน/เดือน + เหลือกี่วัน */
@Composable
fun DueChip(due: String, overdue: Boolean) {
    val p = LocalPalette.current
    val days = Reminders.daysUntil(Dates.todayStr(), due)
    val bg = when { overdue || days < 0 -> p.negativeSoft; days <= 3 -> p.warningSoft; else -> p.surface }
    val fg = when { overdue || days < 0 -> p.negative; days <= 3 -> p.warning; else -> p.ink }
    Column(
        Modifier.width(58.dp).clip(RoundedCornerShape(10.dp)).background(bg).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(due.takeLast(2).trimStart('0'), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = fg, lineHeight = 22.sp)
        Text(shortThaiDate(due).substringAfter(' '), fontSize = 12.sp, color = fg)
        Text(
            when { overdue || days < 0 -> "เลยกำหนด"; days == 0 -> "วันนี้"; else -> "อีก $days วัน" },
            fontSize = 10.sp, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

/** แถวบิลหนึ่งงวด: แตะเพื่อดูว่าประกอบจากก้อนไหนบ้าง */
@Composable
fun DueBillRow(b: DueBillView, showAccount: Boolean, onPay: (() -> Unit)? = null) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 6.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DueChip(b.due, b.overdue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (showAccount) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountBadge(b.account, 20)
                        Spacer(Modifier.width(6.dp))
                        Text(b.account.name, fontSize = 14.sp, color = p.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    (if (b.issued) "บิลออกแล้ว" else "ประมาณการ") + " · ${b.parts.size} รายการ" + if (open) " ▴" else " ▾",
                    fontSize = 13.sp, color = p.muted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("฿" + formatMoney(b.amount), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (b.overdue) p.negative else p.ink, maxLines = 1)
                if (onPay != null && b.issued) Text("จ่าย ›", Modifier.clickable(onClick = onPay).padding(top = 2.dp), color = p.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        if (open && b.parts.isNotEmpty()) BillPartsTable(b.parts)
    }
}

/** ตารางแยกบิลรายก้อน: ก้อน (งวดที่) · ต้น · ดอก · รวม */
@Composable
fun BillPartsTable(parts: List<BillPart>) {
    val p = LocalPalette.current
    val hasSplit = parts.any { it.principal != null }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(p.surface).padding(10.dp)) {
        Row {
            Text("รายการ (งวดที่)", Modifier.weight(1.6f), fontSize = 12.sp, color = p.muted)
            if (hasSplit) {
                Text("ต้น", Modifier.weight(1f), fontSize = 12.sp, color = p.muted, textAlign = TextAlign.End)
                Text("ดอก", Modifier.weight(0.8f), fontSize = 12.sp, color = p.muted, textAlign = TextAlign.End)
            }
            Text("รวม", Modifier.weight(1f), fontSize = 12.sp, color = p.muted, textAlign = TextAlign.End)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = p.line)
        parts.forEach { x ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.6f)) {
                    Text(x.name, fontSize = 13.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("งวด ${x.k}/${x.n}" + if (x.k >= x.n) " · งวดสุดท้าย 🎉" else "", fontSize = 11.sp, color = p.muted)
                }
                if (hasSplit) {
                    Text(formatMoney(x.principal), Modifier.weight(1f), fontSize = 13.sp, color = p.ink, textAlign = TextAlign.End)
                    Text(formatMoney(x.interest), Modifier.weight(0.8f), fontSize = 13.sp, color = p.negative, textAlign = TextAlign.End)
                }
                Text(formatMoney(x.total), Modifier.weight(1f), fontSize = 13.sp, color = p.ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            }
        }
        if (hasSplit) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = p.line)
            Row {
                Text("รวม", Modifier.weight(1.6f), fontSize = 13.sp, color = p.ink, fontWeight = FontWeight.SemiBold)
                Text(formatMoney(parts.sumOf { it.principal ?: 0.0 }), Modifier.weight(1f), fontSize = 13.sp, color = p.ink, textAlign = TextAlign.End)
                Text(formatMoney(parts.sumOf { it.interest ?: 0.0 }), Modifier.weight(0.8f), fontSize = 13.sp, color = p.negative, textAlign = TextAlign.End)
                Text(formatMoney(parts.sumOf { it.total }), Modifier.weight(1f), fontSize = 13.sp, color = p.ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            }
        }
    }
}

/** ตัวเลขสั้นๆ ในการ์ด: ชื่อเล็ก + ตัวเลข */
@Composable
fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = LocalPalette.current.ink) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = LocalPalette.current.muted, maxLines = 1)
        FitText(value, androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Sarabun, color = color), minSize = 11.sp)
    }
}

@Suppress("unused")
@Composable
private fun Dot() = Box(Modifier.size(4.dp))
