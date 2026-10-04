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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
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
    // คำนวณครั้งเดียวต่อข้อมูลชุดหนึ่ง (ไม่ทำซ้ำทุกครั้งที่หน้าวาดใหม่)
    val recent = remember(d) { d.transactions.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.created_at }).take(6) }
    val accs = remember(d) { d.accounts.associateBy { it.id } }
    val cats = remember(d) { d.categories.associateBy { it.id } }
    val activeAccounts = remember(c) { c?.overview?.accounts?.filter { it.account.active }?.sortedWith(compareBy({ it.account.sort ?: Int.MAX_VALUE }, { it.account.name })).orEmpty() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenTitle("断捨ISM", c?.let { "${greeting()} · รอบ ${cycleLabel(it.overview.payCycle)}" }) {
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
            item {
                Card {
                    Text("คำนวณตัวเลขไม่สำเร็จ", fontWeight = FontWeight.SemiBold, color = p.negative)
                    Text("ข้อมูลยังอยู่ครบ ลองแก้รายการล่าสุด หรือส่งออกไฟล์ข้อมูลแล้วส่งให้ผู้พัฒนาดู", color = p.muted, fontSize = 14.sp)
                }
            }
            return@LazyColumn
        }
        val h = c.home
        val ov = c.overview

        // การ์ดหลัก: วันนี้ใช้ได้ (กระดาษแปะ + เทปวาชิ + ไฮไลต์)
        item {
            val start = Dates.parse(ov.payCycleRange.start)
            val end = Dates.parse(ov.payCycleRange.end)
            val total = ChronoUnit.DAYS.between(start, end).toInt() + 1
            val passed = (total - h.daysLeft + 1).coerceIn(1, total)
            Box(Modifier.padding(start = 36.dp, end = 16.dp, top = 14.dp, bottom = 8.dp).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().paperCard(p).padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 16.dp)) {
                    Text("วันนี้ใช้ได้", color = p.muted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Box {
                        Box(Modifier.matchParentSize().padding(top = 26.dp, bottom = 6.dp).background(p.highlight))
                        Text(
                            (if (h.perDay < 0) "−฿" else "฿") + money(kotlin.math.abs(h.perDay)),
                            fontSize = 42.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp,
                            color = if (h.perDay < 0) p.negative else p.primary,
                        )
                    }
                    Text(
                        (h.projected?.let { "สิ้นรอบเหลือ " + signedBaht(it) } ?: "ยังไม่มีแผนรอบนี้ · คิดจากเงินที่มี") + " · อีก ${h.daysLeft} วัน",
                        color = p.ink, fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    DayBoxes(total, passed)
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(shortThaiDate(ov.payCycleRange.start), Modifier.align(Alignment.CenterStart), fontSize = 13.sp, color = p.muted)
                        Text("วันที่ $passed / $total", Modifier.align(Alignment.Center), fontSize = 13.sp, color = p.ink, fontWeight = FontWeight.Medium)
                        Text(shortThaiDate(ov.payCycleRange.end), Modifier.align(Alignment.CenterEnd), fontSize = 13.sp, color = p.muted)
                    }
                }
                // เทปวาชิ
                Box(Modifier.align(Alignment.TopCenter).offset(y = (-10).dp).size(92.dp, 22.dp).rotate(-3f).background(p.tape))
            }
        }

        // เงินที่มี / หนี้ (บรรทัดในสมุด)
        item {
            Column(Modifier.padding(start = 36.dp, end = 16.dp, top = 4.dp)) {
                LedgerLine("เงินที่มีตอนนี้", signedBaht(h.cashNow), if (h.cashNow < 0) p.negative else p.positive)
                LedgerLine("หนี้คงเหลือ", "฿" + money(h.debtTotal), p.negative, onClick = onOpenDebt)
            }
        }

        // กระดาษโน้ต: บิลถัดไป + 60/40
        item {
            Row(Modifier.padding(start = 36.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                h.nextDebtBill?.let { b ->
                    val days = Reminders.daysUntil(ov.today, b.due)
                    StickyNote(p.sticky, -0.6f, Modifier.weight(1f).clickable(onClick = onOpenDebt)) {
                        Text(
                            when { b.overdue || days < 0 -> "บิลค้าง · เลยกำหนด"; days == 0 -> "บิลถัดไป · วันนี้!"; else -> "บิลถัดไป · อีก $days วัน" },
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (b.overdue || days <= 3) p.negative else p.ink.copy(alpha = 0.8f),
                        )
                        Text(b.accountName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("฿" + money(b.amount), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.negative)
                        Text("ครบ ${thaiDate(b.due)}", fontSize = 13.sp, color = p.ink.copy(alpha = 0.8f))
                    }
                }
                h.copay?.let { cp ->
                    StickyNote(p.stickyBlue, 0.6f, Modifier.weight(1f)) {
                        Text("60/40 · อีก ${cp.daysLeft} วัน", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.ink.copy(alpha = 0.8f))
                        Text("รัฐช่วยได้วันนี้", fontSize = 15.sp, color = p.ink)
                        Text("฿" + money(cp.leftToday), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.primary)
                        Text("ซื้อเต็มได้ถึง ฿" + money(cp.fullPriceForToday), fontSize = 13.sp, color = p.ink.copy(alpha = 0.8f))
                    }
                }
            }
        }
        h.cycleSpend.filter { it.second.total > 0 }.forEach { (name, cs) ->
            item { Text("$name ใช้ในรอบบิล ${thaiDate(cs.start)} – ${thaiDate(cs.end)}: ฿${money(cs.total)}", Modifier.padding(start = 36.dp, end = 16.dp, top = 4.dp), color = p.muted, fontSize = 13.sp) }
        }

        // พอร์ต
        ov.port?.let { port ->
            item {
                Column(Modifier.padding(start = 36.dp, end = 16.dp, top = 8.dp)) {
                    LedgerLine("พอร์ตลงทุน (${if (port.unrealizedThb >= 0) "+" else ""}${port.unrealizedPct}%)", "฿" + money(port.valueThb), if (port.unrealizedThb >= 0) p.positive else p.negative)
                }
            }
        }

        // บัญชี
        item { SectionHeader("บัญชี") }
        item {
            LazyRow(contentPadding = PaddingValues(start = 36.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(activeAccounts, key = { it.account.id }) { a ->
                    AccountCard(a)
                }
            }
        }

        // จดล่าสุด
        if (recent.isNotEmpty()) {
            item { SectionHeader("จดล่าสุด", "ดูทั้งเล่ม ›", onOpenTransactions) }
            recent.forEach { t -> item(key = "r_${t.id}") { TransactionRow(t, accs, cats, showDate = true) { editingTxn = t } } }
        }
    }
    editingTxn?.let { t -> TransactionEditor(d, t, vm) { editingTxn = null } }
}

/** คำทักตามช่วงเวลา */
fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..10 -> "อรุณสวัสดิ์"
    in 11..15 -> "สวัสดีตอนบ่าย"
    in 16..19 -> "สวัสดีตอนเย็น"
    else -> "ราตรีสวัสดิ์"
}

/** ยอดเงินมีเครื่องหมาย: −฿24.80 / ฿1,402.37 */
fun signedBaht(v: Double): String = (if (v < 0) "−฿" else "฿") + money(kotlin.math.abs(v))

/** บรรทัดในสมุด: ชื่อซ้าย ยอดขวา เส้นประใต้ */
@Composable
fun LedgerLine(label: String, value: String, valueColor: Color, onClick: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(40.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(p.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp, color = p.ink)
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

/** กระดาษโน้ตแปะ (เอียงนิดเดียว ให้อ่านง่าย) */
@Composable
fun StickyNote(color: Color, angle: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.rotate(angle)
            .drawBehind { drawRect(p.ink.copy(alpha = if (p.dark) 0.35f else 0.15f), topLeft = Offset(2.dp.toPx(), 3.dp.toPx()), size = size) }
            .background(color).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) { content() }
}

/** กล่องติ๊กวันในรอบ (ผ่านแล้ว = ทึบ, วันนี้ = ไฮไลต์) */
@Composable
fun DayBoxes(total: Int, passed: Int) {
    val p = LocalPalette.current
    val perRow = 15
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (0 until total).chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { i ->
                    Box(
                        Modifier.weight(1f).height(14.dp)
                            .background(if (i < passed - 1) p.ink else if (i == passed - 1) p.highlight else Color.Transparent, RoundedCornerShape(2.dp))
                            .border(1.2.dp, p.ink.copy(alpha = 0.8f), RoundedCornerShape(2.dp)),
                    )
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 16.dp, top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontFamily = Hand, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = DanshaColors.Primary)
        if (action != null && onAction != null) Text(action, Modifier.clickable(onClick = onAction).padding(vertical = 8.dp), color = DanshaColors.Primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** ป้ายเล็กมนๆ */
@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Text(text, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp), color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun StatTile(label: String, value: String, bg: Color, fg: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(14.dp)) {
        Text(label, color = DanshaColors.Muted, fontSize = 13.sp)
        Text(value, color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
    }
}

/** การ์ดบัญชีแบบบัตร */
@Composable
private fun AccountCard(a: AccountView) {
    val p = LocalPalette.current
    Column(
        Modifier.width(160.dp).height(108.dp).paperCard(p, tint(a.account.color)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountBadge(a.account, 28)
            Spacer(Modifier.width(6.dp))
            Text(a.account.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        if (a.isCredit) {
            Text("ใช้ไป", fontSize = 13.sp, color = p.ink.copy(alpha = 0.75f))
            Text("฿" + money(a.used), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.negative, maxLines = 1)
        } else {
            val cur = if (a.account.currency != "THB") " ${a.account.currency}" else ""
            Text("คงเหลือ", fontSize = 13.sp, color = p.ink.copy(alpha = 0.75f))
            Text(money(a.balance) + cur, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = if ((a.balance ?: 0.0) < 0) p.negative else p.ink, maxLines = 1)
        }
    }
}
