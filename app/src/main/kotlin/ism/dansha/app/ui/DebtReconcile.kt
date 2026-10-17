package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.Account
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction
import ism.dansha.core.engine.AscendState
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.PurchaseOut
import ism.dansha.core.engine.toDec
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round

/*
 * เทียบยอดกับแอพ Ascend: ใส่ยอดที่ Ascend เรียกเก็บ → บอกว่าต่างกันเท่าไหร่
 * แสดงรายการซื้อเต็มจำนวนของรอบบิลนี้ (เรียงใหม่→เก่า เหมือนประวัติในแอพ Ascend) ให้ไล่เทียบ
 * หา "อาจซ้ำ" (วันเดียวกัน ยอดเท่ากัน) ลบได้ทันที · ขาด → เพิ่มรายการที่ขาดได้ในปุ่มเดียว
 */

private fun r2(x: Double) = round(x * 100) / 100

@Composable
fun ReconcilePage(d: DanshaData, acc: Account, st: AscendState, vm: MainViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val bill = remember(st) { dueBills(acc, st, 1).firstOrNull() }
    var real by rememberSaveable { mutableStateOf("") }
    var askDelete by remember { mutableStateOf<Transaction?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    FormPage("เทียบยอดกับ Ascend", onClose, onSave = null) {
        if (bill == null) { Note("ยังไม่มีบิลให้เทียบ"); return@FormPage }
        val txById = remember(d) { d.transactions.associateBy { it.id } }
        val items: List<PurchaseOut> = remember(st, bill) {
            st.pendingFull.filter { it.billedDue == bill.due || (it.billedDue == null && !bill.issued) }
                .sortedWith(compareByDescending<PurchaseOut> { it.date }.thenByDescending { txById[it.id]?.created_at.orEmpty() })
        }
        // วันเดียวกัน ยอดเท่ากัน → ตัวที่จดทีหลังขึ้นว่า "อาจซ้ำ"
        val dupIds = remember(items) {
            items.groupBy { it.date to r2(it.amount) }.values.filter { it.size > 1 }
                .flatMap { g -> g.sortedBy { txById[it.id]?.created_at.orEmpty() }.drop(1).map { it.id } }.toSet()
        }
        val instPart = r2(bill.parts.sumOf { it.total })
        val fullPart = r2(bill.amount - instPart)

        // ---- ยอดในแอพ ----
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DueChip(bill.due, bill.overdue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ยอดงวดนี้ในแอพ", fontSize = 13.sp, color = p.muted)
                    Text("฿" + formatMoney(bill.amount), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = p.negative)
                }
            }
            Spacer(Modifier.padding(top = 6.dp))
            KeyValue("งวดผ่อน ${bill.parts.size} ก้อน", formatMoney(instPart))
            KeyValue("ซื้อแบบจ่ายเต็ม ${items.size} รายการ", formatMoney(fullPart))
        }

        // ---- ใส่ยอดจริง ----
        AmountField("ยอดที่ Ascend เรียกเก็บงวดนี้", real, { real = it }, supporting = "ดูที่ \"จำนวนเงินที่ต้องชำระ\" ในแอพ Ascend")
        val realAmt = Forms.parseAmount(real)
        error?.let { Note(it, p.negative) }
        if (realAmt != null && realAmt > 0) {
            val diff = r2(realAmt - bill.amount)
            val (bg, msg) = when {
                abs(diff) < 0.005 -> p.positiveSoft to "✓ ตรงกันเป๊ะแล้ว"
                abs(diff) < 1 -> p.surface to "ต่างกัน ${formatMoney(abs(diff))} บาท (เศษสตางค์) มาจากยอดยกมาของก้อนผ่อน ไม่ต้องแก้รายการ"
                diff > 0 -> p.warningSoft to "ในแอพยังขาด ฿${formatMoney(diff)} — มีรายการที่ยังไม่ได้จด หรือจดยอดน้อยไป"
                else -> p.negativeSoft to "ในแอพเกิน ฿${formatMoney(-diff)} — มีรายการที่จดซ้ำ หรือไม่ได้จ่ายด้วยบัญชีนี้"
            }
            Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(msg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
                if (diff < -0.995) {
                    val dupSum = items.filter { it.id in dupIds }.sumOf { it.amount }
                    if (dupIds.isNotEmpty()) Text("รายการที่ขึ้น \"อาจซ้ำ\" รวม ฿${formatMoney(dupSum)} — กด 🗑 เพื่อลบตัวที่ซ้ำ", fontSize = 13.sp, color = p.ink)
                    else Text("ไล่เทียบรายการข้างล่างกับประวัติในแอพ Ascend แล้วลบ/แก้ตัวที่ไม่มีใน Ascend", fontSize = 13.sp, color = p.ink)
                }
                if (diff >= 0.995) {
                    Text("ถ้าหาไม่เจอว่าขาดตัวไหน กดปุ่มนี้เพื่อเพิ่มยอดที่ขาดเป็นรายการเดียว", fontSize = 13.sp, color = p.ink)
                    Button(onClick = {
                        error = null
                        val stmt = LocalDate.parse(bill.due).minusMonths(1).withDayOfMonth(15).toString()
                        val date = minOf(stmt, Dates.todayStr())
                        // หมวดที่ใช้กับบัญชีนี้บ่อยที่สุด
                        val (cat, sub) = d.transactions.filter { it.account_id == acc.id && it.type == "expense" && it.category_id.isNotEmpty() }
                            .groupingBy { it.category_id to it.subcategory_id }.eachCount().maxByOrNull { it.value }?.key ?: ("" to "")
                        vm.edit<Any>(ok = "เพิ่มยอดที่ขาดแล้ว", onError = { error = it }) {
                            createTransaction(
                                Transaction(
                                    id = "", date = date, type = "expense", account_id = acc.id, amount = diff.toDec(),
                                    currency = acc.currency.ifEmpty { "THB" }, category_id = cat, subcategory_id = sub,
                                    note = "ยอดที่ไม่ได้จด (ปรับให้ตรง Ascend)", source = "manual",
                                )
                            )
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("เพิ่มรายการที่ขาด ฿${formatMoney(diff)}") }
                }
            }
        }

        // ---- รายการซื้อเต็มของรอบนี้ ----
        Text("รายการซื้อแบบจ่ายเต็มรอบนี้ (ใหม่ → เก่า)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.ink)
        if (items.isEmpty()) Note("ไม่มีรายการ")
        else Card {
            items.forEachIndexed { i, u ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp), color = p.line)
                val t = txById[u.id]
                val dup = u.id in dupIds
                Row(
                    Modifier.fillMaxWidth().background(if (dup) p.negativeSoft else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(u.desc.ifEmpty { "(ไม่มีโน้ต)" }, fontSize = 14.sp, color = p.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(
                            shortThaiDate(u.date) + (if (t == null) " · ยอดยกมา" else "") + (if (dup) " · อาจซ้ำ" else ""),
                            fontSize = 12.sp, color = if (dup) p.negative else p.muted, fontWeight = if (dup) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    Text(formatMoney(u.amount), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
                    if (t != null) IconButton(onClick = { askDelete = t }) { Icon(Icons.Outlined.DeleteOutline, "ลบ", tint = if (dup) p.negative else p.muted) }
                    else Spacer(Modifier.width(48.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = p.line)
            KeyValue("รวม", formatMoney(items.sumOf { it.amount }), bold = true)
        }
    }

    askDelete?.let { t ->
        ConfirmDialog(
            "ลบรายการนี้?", "${shortThaiDate(t.date)} ${t.note} ฿${formatMoney(t.amount)}", "ลบ",
            onConfirm = { askDelete = null; vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }) { deleteTransaction(t.id) } },
            onDismiss = { askDelete = null },
        )
    }
}
