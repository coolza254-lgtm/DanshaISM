package ism.dansha.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction
import ism.dansha.core.engine.AccountView
import ism.dansha.core.engine.Reminders
import java.time.temporal.ChronoUnit

private fun money(v: Double?): String = formatMoney(v)

@Composable
internal fun KeyValue(label: String, value: String, color: Color = DanshaColors.Ink, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), color = DanshaColors.Muted, fontSize = 14.sp)
        Text(value, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp)
    }
}

/** แถบความคืบหน้าแบบมน */
@Composable
fun ProgressBar(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier, height: Int = 8) {
    Box(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(50)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

// ---------- ภาพรวม ----------

@Composable
fun HomeScreen(d: DanshaData, c: MainViewModel.Computed?, vm: MainViewModel, onOpenDebt: () -> Unit, onOpenTransactions: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.pickImport(it) }
    }
    var editingTxn by remember { mutableStateOf<Transaction?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenTitle("断捨ISM", c?.let { "รอบ ${it.overview.payCycle.substring(8)}" }) {
                if (c != null) IconButton(onClick = { vm.summaryText()?.let { shareText(ctx, it) } }) {
                    Icon(Icons.Outlined.Share, contentDescription = "แชร์สรุปวันนี้", tint = p.ink)
                }
            }
        }
        if (d.isEmpty()) {
            item {
                Card {
                    Text("ยินดีต้อนรับ 🌸", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = p.ink)
                    Spacer(Modifier.height(6.dp))
                    Text("ข้อมูลทั้งหมดเก็บในเครื่องนี้ ไม่ต้องตั้งค่าอะไร\nถ้ามีไฟล์ข้อมูลจากระบบเดิม (dansha-data-….json) กดนำเข้าได้เลย", color = p.muted)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth()) { Text("นำเข้าไฟล์ข้อมูล") }
                    OutlinedButton(onClick = { vm.startFresh() }, Modifier.fillMaxWidth()) { Text("เริ่มใหม่ (ยังไม่มีข้อมูล)") }
                }
            }
            return@LazyColumn
        }
        if (c == null) {
            item { Text("กำลังคำนวณ…", Modifier.padding(20.dp), color = p.muted) }
            return@LazyColumn
        }
        val h = c.home
        val ov = c.overview

        // การ์ดหลัก: วันนี้ใช้ได้
        item {
            val start = Dates.parse(ov.payCycleRange.start)
            val end = Dates.parse(ov.payCycleRange.end)
            val total = ChronoUnit.DAYS.between(start, end).toInt() + 1
            val passed = (total - h.daysLeft + 1).coerceIn(1, total)
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(p.heroStart, p.heroEnd)))
                    .padding(20.dp),
            ) {
                Text("วันนี้ใช้ได้", color = p.ink.copy(alpha = 0.7f), fontSize = 14.sp)
                Text("฿" + money(h.perDay), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = if (h.perDay < 0) p.negative else p.ink)
                Text(
                    if (h.projected != null) "สิ้นรอบเหลือ ฿${money(h.projected)} · อีก ${h.daysLeft} วัน" else "ยังไม่มีแผนรอบนี้ · คิดจากเงินที่มี · อีก ${h.daysLeft} วัน",
                    color = p.ink.copy(alpha = 0.75f), fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                ProgressBar(passed.toFloat() / total, p.primary, p.card.copy(alpha = 0.6f))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(thaiDate(ov.payCycleRange.start), Modifier.weight(1f), fontSize = 11.sp, color = p.ink.copy(alpha = 0.6f))
                    Text("วันที่ $passed/$total ของรอบ", fontSize = 11.sp, color = p.ink.copy(alpha = 0.6f))
                    Spacer(Modifier.weight(1f))
                    Text(thaiDate(ov.payCycleRange.end), fontSize = 11.sp, color = p.ink.copy(alpha = 0.6f))
                }
            }
        }

        // เงินที่มี / หนี้
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("เงินที่มีตอนนี้", "฿" + money(h.cashNow), p.positiveSoft, if (h.cashNow < 0) p.negative else p.ink, Modifier.weight(1f))
                StatTile("หนี้คงเหลือ", "฿" + money(h.debtTotal), p.negativeSoft, p.ink, Modifier.weight(1f).clickable(onClick = onOpenDebt))
            }
        }

        // บิลถัดไป
        h.nextDebtBill?.let { b ->
            item {
                val days = Reminders.daysUntil(ov.today, b.due)
                Card(Modifier.clickable(onClick = onOpenDebt)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(if (b.overdue) p.negativeSoft else p.warningSoft, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.CreditCard, null, tint = if (b.overdue) p.negative else p.warning)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("บิลถัดไป", color = p.muted, fontSize = 12.sp)
                            Text(b.accountName, fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("ครบ ${thaiDate(b.due)}", color = p.muted, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("฿" + money(b.amount), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.ink)
                            Pill(
                                when { b.overdue || days < 0 -> "เลยกำหนด"; days == 0 -> "วันนี้!"; else -> "อีก $days วัน" },
                                if (b.overdue || days <= 3) p.negativeSoft else p.surface,
                                if (b.overdue || days <= 3) p.negative else p.muted,
                            )
                        }
                    }
                    h.cycleSpend.filter { it.second.total > 0 }.forEach { (name, cs) ->
                        Spacer(Modifier.height(6.dp))
                        Text("$name ใช้ในรอบบิล ${thaiDate(cs.start)} – ${thaiDate(cs.end)}: ฿${money(cs.total)}", color = p.muted, fontSize = 12.sp)
                    }
                }
            }
        }

        // 60/40
        h.copay?.let { cp ->
            item {
                val cap = ov.copay?.config?.totalCap ?: 0.0
                Card(color = if (p.dark) null else Color(0xFFF2F7FF)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🟦 " + cp.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Pill("อีก ${cp.daysLeft} วัน", p.surface, p.muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("รัฐช่วยได้วันนี้อีก", color = p.muted, fontSize = 12.sp)
                            Text("฿" + money(cp.leftToday), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = p.ink)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("ซื้อราคาเต็มได้ถึง", color = p.muted, fontSize = 12.sp)
                            Text("฿" + money(cp.fullPriceForToday), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = p.ink)
                        }
                    }
                    if (cap > 0) {
                        Spacer(Modifier.height(10.dp))
                        ProgressBar(((cap - cp.totalLeft) / cap).toFloat(), Color(0xFF5B8DEF), p.surface)
                        Text("ใช้สิทธิไปแล้ว ฿${money(cap - cp.totalLeft)} จาก ฿${money(cap)}", color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // พอร์ต
        ov.port?.let { port ->
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("พอร์ตลงทุน", color = p.muted, fontSize = 12.sp)
                            Text("฿" + money(port.valueThb), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.ink)
                        }
                        Pill(
                            (if (port.unrealizedThb >= 0) "+" else "") + "${port.unrealizedPct}%",
                            if (port.unrealizedThb >= 0) p.positiveSoft else p.negativeSoft,
                            if (port.unrealizedThb >= 0) p.positive else p.negative,
                        )
                    }
                }
            }
        }

        // บัญชี
        item { SectionHeader("บัญชี") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ov.accounts.filter { it.account.active }.sortedWith(compareBy({ it.account.sort ?: Int.MAX_VALUE }, { it.account.name })), key = { it.account.id }) { a ->
                    AccountCard(a)
                }
            }
        }

        // รายการล่าสุด
        val recent = d.transactions.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.created_at }).take(5)
        if (recent.isNotEmpty()) {
            item { SectionHeader("รายการล่าสุด", "ดูทั้งหมด ›", onOpenTransactions) }
            item {
                val accs = d.accounts.associateBy { it.id }
                val cats = d.categories.associateBy { it.id }
                Card(padding = 0.dp) {
                    recent.forEach { t -> TransactionRow(t, accs, cats) { editingTxn = t } }
                }
            }
        }
    }
    editingTxn?.let { t -> TransactionEditor(d, t, vm) { editingTxn = null } }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = DanshaColors.Ink)
        if (action != null && onAction != null) Text(action, Modifier.clickable(onClick = onAction), color = DanshaColors.Primary, fontSize = 13.sp)
    }
}

/** ป้ายเล็กมนๆ */
@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Text(text, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun StatTile(label: String, value: String, bg: Color, fg: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(14.dp)) {
        Text(label, color = DanshaColors.Muted, fontSize = 12.sp)
        Text(value, color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
    }
}

/** การ์ดบัญชีแบบบัตร */
@Composable
private fun AccountCard(a: AccountView) {
    val p = LocalPalette.current
    Column(
        Modifier.width(156.dp).height(104.dp).clip(RoundedCornerShape(20.dp)).background(tint(a.account.color)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountBadge(a.account, 28)
            Spacer(Modifier.width(6.dp))
            Text(a.account.name, fontSize = 12.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        if (a.isCredit) {
            Text("ใช้ไป", fontSize = 11.sp, color = p.ink.copy(alpha = 0.6f))
            Text("฿" + money(a.used), fontWeight = FontWeight.Bold, color = p.ink, maxLines = 1)
        } else {
            val cur = if (a.account.currency != "THB") " ${a.account.currency}" else ""
            Text("คงเหลือ", fontSize = 11.sp, color = p.ink.copy(alpha = 0.6f))
            Text(money(a.balance) + cur, fontWeight = FontWeight.Bold, color = if ((a.balance ?: 0.0) < 0) p.negative else p.ink, maxLines = 1)
        }
    }
}
