package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import ism.dansha.app.MainViewModel
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.Outlook
import ism.dansha.core.engine.toDec
import java.math.BigDecimal

private val TYPE_LABELS = listOf("expense" to "รายจ่าย", "income" to "รายรับ", "transfer" to "โอน")

// ---------- รายการ ----------

@Composable
fun TransactionsScreen(d: DanshaData, vm: MainViewModel, header: @Composable () -> Unit = {}) {
    val startDay = remember(d) { Engine.startDay(Engine.config(d)) }
    val current = remember(d) { Dates.payCycleOf(Dates.today(), startDay) }
    var cycle by rememberSaveable { mutableStateOf(current) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Transaction?>(null) }

    val accounts = remember(d) { d.accounts.associateBy { it.id } }
    val categories = remember(d) { d.categories.associateBy { it.id } }
    val searching = query.isNotBlank()
    val rows = remember(d, cycle, query) {
        (if (searching) Forms.search(d, query) else d.transactions.filter { it.pay_cycle == cycle })
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.created_at })
    }
    val income = remember(rows) { rows.filter { it.type == "income" }.sumOf { it.amount ?: BigDecimal.ZERO } }
    val expense = remember(rows) { rows.filter { it.type == "expense" }.sumOf { it.amount ?: BigDecimal.ZERO } }
    val byDay = remember(rows) { rows.groupBy { it.date } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { ScreenTitle("รายการ") }
            item { header() }
            item {
                Column(Modifier.padding(start = 36.dp, end = 16.dp)) {
                    TextInput("ค้นหา (โน้ต หมวด บัญชี ยอด)", query, { query = it })
                    if (!searching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { cycle = Outlook.shiftCycle(cycle, -1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "รอบก่อน") }
                            val (rs, re) = Dates.payCycleRange(cycle, startDay)
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("รอบ ${cycleLabel(cycle)}${if (cycle == current) " (รอบนี้)" else ""}", fontWeight = FontWeight.SemiBold)
                                Text("${thaiDate(rs.toString())} – ${thaiDate(re.toString())}", color = DanshaColors.Muted, fontSize = 13.sp)
                            }
                            IconButton(onClick = { cycle = Outlook.shiftCycle(cycle, 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "รอบถัดไป") }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("รับ ${formatMoney(income)}", Modifier.weight(1f), color = DanshaColors.Positive, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("จ่าย ${formatMoney(expense)}", Modifier.weight(1f), color = DanshaColors.Negative, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                    }
                }
            }
            if (rows.isEmpty()) item { Text(if (searching) "ไม่พบรายการ" else "ยังไม่มีรายการในรอบนี้", Modifier.padding(start = 36.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), color = DanshaColors.Muted) }
            byDay.forEach { (date, list) ->
                item(key = "h_$date") {
                    val net = list.sumOf { t -> when (t.type) { "income" -> t.amount ?: BigDecimal.ZERO; "expense" -> (t.amount ?: BigDecimal.ZERO).negate(); else -> BigDecimal.ZERO } }
                    DayHeader(thaiDate(date), (if (net.signum() < 0) "−" else if (net.signum() > 0) "+" else "") + formatMoney(net.abs()))
                }
                items(list, key = { it.id }, contentType = { "txn" }) { t ->
                    TransactionRow(t, accounts, categories) { editing = t }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editing?.let { t -> TransactionEditor(d, t, vm) { editing = null } }
}

/** แถวในสมุด: (วันที่) · วงอักษรหมวด · โน้ต/บัญชี · ยอด */
@Composable
fun TransactionRow(
    t: Transaction,
    accounts: Map<String, ism.dansha.core.Account>,
    categories: Map<String, ism.dansha.core.Category>,
    showDate: Boolean = false,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val cat = categories[t.subcategory_id.ifEmpty { t.category_id }] ?: categories[t.category_id]
    val main = categories[t.category_id] ?: cat
    val (sign, color) = when (t.type) {
        "income" -> "+" to p.positive
        "expense" -> "−" to p.negative
        else -> "" to p.ink
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 36.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDate) {
            Text(shortThaiDate(t.date), Modifier.width(48.dp), fontSize = 13.sp, color = p.muted)
        }
        CategoryIcon(cat?.name, cat?.icon, main?.color ?: cat?.color, size = 36.dp, transfer = t.type == "transfer")
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.note.ifEmpty { cat?.name ?: if (t.type == "transfer") "โอน" else "-" }, fontSize = 16.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                append(accounts[t.account_id]?.name ?: "?")
                if (t.type == "transfer") append(" → ").append(accounts[t.to_account_id]?.name ?: "?")
                else if (cat != null) append(" · ").append(cat.name)
                val gov = t.gov_subsidy
                if (gov != null && gov.signum() > 0) append(" · 60/40 รัฐจ่าย ").append(formatMoney(gov))
            }
            Text(sub, color = p.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text("$sign${formatMoney(t.amount)}", color = color, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

/** หัววันที่แบบไฮไลต์ปากกาเหลือง */
@Composable
fun DayHeader(text: String, right: String? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 16.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, Modifier.background(p.highlight).padding(horizontal = 6.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
        Spacer(Modifier.weight(1f))
        if (right != null) Text(right, fontSize = 14.sp, color = p.muted)
    }
}

private val TH_MONTH_SHORT = listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")

/** "2026-10-02" → "2 ต.ค." */
fun shortThaiDate(iso: String): String {
    val parts = iso.split("-").mapNotNull { it.toIntOrNull() }
    return if (parts.size < 3) iso else "${parts[2]} ${TH_MONTH_SHORT[parts[1] - 1]}"
}
