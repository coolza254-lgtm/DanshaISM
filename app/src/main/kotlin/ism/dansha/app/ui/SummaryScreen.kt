package ism.dansha.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction
import ism.dansha.core.engine.CategoryTotal
import ism.dansha.core.engine.DayTotal
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Summary
import java.math.BigDecimal

private fun money(v: Double?) = formatMoney(v?.let { BigDecimal.valueOf(it) })

/** แชร์ข้อความผ่าน share sheet ของ Android (LINE ฯลฯ) */
fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "แชร์สรุป").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun SummaryScreen(d: DanshaData, vm: MainViewModel, header: @Composable () -> Unit = {}) {
    val startDay = remember(d) { Engine.startDay(Engine.config(d)) }
    var preset by rememberSaveable { mutableStateOf(Summary.Preset.ThisCycle) }
    var custom by rememberSaveable { mutableStateOf(false) }
    val presetRange = Summary.range(preset, Dates.today(), startDay)
    var start by rememberSaveable { mutableStateOf(presetRange.first) }
    var end by rememberSaveable { mutableStateOf(presetRange.second) }
    val (s0, e0) = if (custom) start to end else presetRange
    val s = remember(d, s0, e0) { Summary.period(d, minOf(s0, e0), maxOf(s0, e0)) }
    var showIncome by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Transaction?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("สรุป", "${thaiDate(s.start)} – ${thaiDate(s.end)}") }
        item { header() }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ChoiceChips(
                    Summary.Preset.entries.map { it.name to it.label } + ("custom" to "เลือกเอง"),
                    if (custom) "custom" else preset.name,
                    { v -> if (v == "custom") { custom = true; start = s0; end = e0 } else { custom = false; preset = Summary.Preset.valueOf(v) } },
                )
                if (custom) {
                    Row {
                        DateField("ตั้งแต่", start, { start = it }, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        DateField("ถึง", end, { end = it }, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Card {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("รายรับ", color = DanshaColors.Muted, fontSize = 13.sp)
                        Text(money(s.income), color = DanshaColors.Positive, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("รายจ่าย", color = DanshaColors.Muted, fontSize = 13.sp)
                        Text(money(s.expense), color = DanshaColors.Negative, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("สุทธิ", color = DanshaColors.Muted, fontSize = 13.sp)
                        Text(money(s.net), color = if (s.net < 0) DanshaColors.Negative else DanshaColors.Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "จ่ายเฉลี่ยวันละ ${money(s.avgExpensePerDay)}" + if (s.govSubsidy > 0) " · รัฐช่วยจ่าย 60/40 ${money(s.govSubsidy)}" else "",
                    color = DanshaColors.Muted, fontSize = 13.sp,
                )
            }
        }
        item {
            Card {
                SectionLabel("รายวัน (แท่งแดง = จ่าย, เขียว = รับ)")
                Spacer(Modifier.height(8.dp))
                DailyChart(s.daily)
            }
        }
        item {
            Card {
                ChoiceChips(listOf(false to "รายจ่ายตามหมวด", true to "รายรับตามหมวด"), showIncome, { showIncome = it })
                Spacer(Modifier.height(8.dp))
                val list = if (showIncome) s.incomeByCategory else s.expenseByCategory
                if (list.isEmpty()) Text("ไม่มีรายการ", color = DanshaColors.Muted)
                list.forEach { CategoryBar(it, if (showIncome) DanshaColors.Positive else DanshaColors.Negative) }
            }
        }
        item { Text("รายการ (${s.transactions.size})", Modifier.padding(start = 20.dp, top = 8.dp), fontWeight = FontWeight.SemiBold) }
        val accounts = d.accounts.associateBy { it.id }
        val categories = d.categories.associateBy { it.id }
        s.transactions.groupBy { it.date }.forEach { (date, list) ->
            item(key = "h_$date") {
                DayHeader(thaiDate(date))
            }
            list.forEach { t -> item(key = t.id) { TransactionRow(t, accounts, categories) { editing = t } } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    editing?.let { t -> TransactionEditor(d, t, vm) { editing = null } }
}

@Composable
private fun CategoryBar(c: CategoryTotal, barColor: androidx.compose.ui.graphics.Color) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(enabled = c.children.isNotEmpty()) { open = !open }.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(tint(c.color), CircleShape), contentAlignment = Alignment.Center) { Text(c.icon, fontSize = 13.sp) }
            Spacer(Modifier.width(8.dp))
            Text(c.name + if (c.children.isNotEmpty()) (if (open) " ▴" else " ▾") else "", Modifier.weight(1f), fontSize = 14.sp)
            Text("${money(c.amount)} · ${c.share}%", fontSize = 13.sp)
        }
        Box(Modifier.padding(start = 34.dp, top = 4.dp).fillMaxWidth().height(6.dp).background(DanshaColors.Surface, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth((c.share / 100).toFloat().coerceIn(0f, 1f)).height(6.dp).background(barColor, RoundedCornerShape(3.dp)))
        }
        if (open) c.children.forEach { ch ->
            Row(Modifier.padding(start = 34.dp, top = 4.dp)) {
                Text("└ ${ch.name}", Modifier.weight(1f), color = DanshaColors.Muted, fontSize = 13.sp)
                Text("${money(ch.amount)} · ${ch.share}%", color = DanshaColors.Muted, fontSize = 13.sp)
            }
        }
    }
}

/** กราฟแท่งรายวัน (วาดเองด้วย Canvas ไม่ต้องใช้ไลบรารีเพิ่ม) */
@Composable
private fun DailyChart(days: List<DayTotal>) {
    val max = (days.maxOfOrNull { maxOf(it.income, it.expense) } ?: 0.0).coerceAtLeast(1.0)
    val red = DanshaColors.Negative
    val green = DanshaColors.Positive
    val line = DanshaColors.Line
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val n = days.size.coerceAtLeast(1)
        val slot = size.width / n
        val bar = (slot * 0.38f).coerceAtLeast(1f)
        drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        days.forEachIndexed { i, d ->
            val x = i * slot + slot * 0.1f
            val he = (d.expense / max * size.height).toFloat()
            val hi = (d.income / max * size.height).toFloat()
            if (he > 0) drawRect(red, Offset(x, size.height - he), Size(bar, he))
            if (hi > 0) drawRect(green, Offset(x + bar, size.height - hi), Size(bar, hi))
        }
    }
    if (days.isNotEmpty()) {
        Row(Modifier.fillMaxWidth()) {
            Text(thaiDate(days.first().date), Modifier.weight(1f), color = DanshaColors.Muted, fontSize = 13.sp)
            Text("สูงสุด ${money(max)}", color = DanshaColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(thaiDate(days.last().date), color = DanshaColors.Muted, fontSize = 13.sp)
        }
    }
}
