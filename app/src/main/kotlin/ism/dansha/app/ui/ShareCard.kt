package ism.dansha.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ism.dansha.app.MainViewModel
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.engine.Reminders
import ism.dansha.core.engine.Summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.temporal.ChronoUnit

/*
 * แชร์สถานะการเงินเป็นรูปเดียว (การ์ดสมุดน่ารักๆ) — ดูตัวอย่างก่อน แล้วส่งผ่าน LINE ฯลฯ
 * การ์ดใช้สีโหมดสว่างเสมอ รูปจึงหน้าตาเหมือนกันไม่ว่ามือถือจะตั้งโหมดมืดหรือไม่
 */

// สีพาสเทลของการ์ด
private val PinkBg = Color(0xFFFFE9EF)
private val Pink = Color(0xFFF08AA6)
private val PinkSoft = Color(0xFFFFD3DF)
private val Mint = Color(0xFFD9F2E3)
private val Sky = Color(0xFFDCEBFF)
private val Butter = Color(0xFFFFF1A8)
private val Lilac = Color(0xFFEBDDFF)

@Composable
fun ShareCardPage(d: DanshaData, c: MainViewModel.Computed, vm: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var busy by remember { mutableStateOf(false) }
    val p = LocalPalette.current

    FormPage("แชร์ให้แฟนดู", onClose, onSave = null) {
        Note("ตัวอย่างรูปที่จะส่ง (อัปเดตตามข้อมูลล่าสุดทุกครั้ง)")
        Box(
            Modifier.fillMaxWidth().drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalPalette provides LightPalette) { StatusCard(d, c) }
        }
        Button(
            onClick = {
                if (busy) return@Button
                busy = true
                scope.launch {
                    runCatching {
                        val bmp = layer.toImageBitmap().asAndroidBitmap()
                        shareImage(context, bmp)
                    }.onFailure { vm.toast("สร้างรูปไม่สำเร็จ ลองใหม่อีกครั้ง") }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(2.dp, p.ink.copy(alpha = if (p.dark) 0.5f else 1f)),
            colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = p.onPrimary),
        ) {
            Icon(Icons.Outlined.Image, null)
            Spacer(Modifier.width(8.dp))
            Text("ส่งรูปนี้", fontFamily = Hand, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = { vm.summaryText()?.let { shareText(context, it) } }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.ChatBubbleOutline, null)
            Spacer(Modifier.width(8.dp))
            Text("ส่งเป็นข้อความแทน")
        }
    }
}

private suspend fun shareImage(context: Context, bmp: Bitmap) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        File(dir, "dansha-${Dates.todayStr()}.png").also { f ->
            dir.listFiles()?.filter { it != f }?.forEach { it.delete() }
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    context.startActivity(Intent.createChooser(send, "ส่งสรุปการเงิน").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private val THAI_DAYS = listOf("วันจันทร์", "วันอังคาร", "วันพุธ", "วันพฤหัสฯ", "วันศุกร์", "วันเสาร์", "วันอาทิตย์")

/** อารมณ์ของกระเป๋าตังวันนี้ ดูจาก "วันนี้ใช้ได้" */
private fun mood(perDay: Double): Pair<String, String> = when {
    perDay < 0 -> "🥺" to "ช่วงนี้รัดเข็มขัดกันหน่อยนะ"
    perDay < 150 -> "😗" to "ประหยัดนิดนึง ยังไหวอยู่"
    perDay < 400 -> "🙂" to "พอไปได้ สบายๆ"
    else -> "🥰" to "กระเป๋าตังยิ้มได้!"
}

@Composable
private fun StatusCard(d: DanshaData, c: MainViewModel.Computed) {
    val p = LocalPalette.current
    val h = c.home
    val ov = c.overview
    val today = Dates.today()
    val cycleStart = ov.payCycleRange.start
    val spent = remember(d, cycleStart) { runCatching { Summary.period(d, cycleStart, today.toString()) }.getOrNull() }
    val start = Dates.parse(ov.payCycleRange.start)
    val end = Dates.parse(ov.payCycleRange.end)
    val total = ChronoUnit.DAYS.between(start, end).toInt() + 1
    val passed = (total - h.daysLeft + 1).coerceIn(1, total)
    val (face, feel) = mood(h.perDay)

    Box(Modifier.widthIn(max = 380.dp).fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .background(PinkBg, RoundedCornerShape(26.dp))
                .border(2.dp, p.ink, RoundedCornerShape(26.dp))
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // หัว
            Text("♡ สมุดเงินของเรา ♡", fontFamily = Hand, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Pink)
            Text(
                "${THAI_DAYS[today.dayOfWeek.value - 1]} ${thaiDate(today.toString())} · รอบ ${cycleLabel(ov.payCycle)}",
                fontSize = 13.sp, color = p.muted, textAlign = TextAlign.Center,
            )

            // อารมณ์วันนี้
            Row(
                Modifier.background(Color.White, RoundedCornerShape(50)).border(1.5.dp, p.ink, RoundedCornerShape(50)).padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(face, fontSize = 28.sp)
                Spacer(Modifier.width(6.dp))
                Text(feel, fontFamily = Hand, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = p.ink)
            }

            // วันนี้ใช้ได้
            Box(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).border(1.5.dp, p.ink, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("วันนี้ใช้ได้", fontSize = 15.sp, color = p.muted, fontWeight = FontWeight.Medium)
                    Box {
                        Box(Modifier.matchParentSize().padding(top = 24.dp, bottom = 6.dp).background(Butter))
                        Text(
                            (if (h.perDay < 0) "−฿" else "฿") + formatMoney(kotlin.math.abs(h.perDay)),
                            fontSize = 38.sp, fontWeight = FontWeight.Bold, color = if (h.perDay < 0) p.negative else p.primary,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                    Text("อีก ${h.daysLeft} วันเงินเดือนออก 💸", fontSize = 14.sp, color = p.ink)
                    Spacer(Modifier.height(10.dp))
                    // หัวใจนับวันในรอบ
                    HeartTrack(total, passed)
                    Text("วันที่ $passed จาก $total ของรอบ", fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
                }
                // เทปแปะ
                Box(Modifier.align(Alignment.TopCenter).offset(y = (-10).dp).size(84.dp, 20.dp).rotate(-4f).background(PinkSoft.copy(alpha = 0.9f)))
            }

            // กระดาษโน้ต 4 ใบ
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("💰", "เงินที่มี", signedBaht(h.cashNow), Mint, -1.2f, Modifier.weight(1f), if (h.cashNow < 0) p.negative else p.positive)
                Tile("🐷", "สิ้นรอบเหลือ", h.projected?.let { signedBaht(it) } ?: "–", Sky, 1.2f, Modifier.weight(1f), if ((h.projected ?: 0.0) < 0) p.negative else p.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("🛒", "ใช้ไปรอบนี้", "฿" + formatMoney(spent?.expense ?: 0.0), Butter, 1f, Modifier.weight(1f), p.ink)
                Tile("💳", "หนี้คงเหลือ", "฿" + formatMoney(h.debtTotal), Lilac, -1f, Modifier.weight(1f), p.negative)
            }

            // บิลถัดไป / 60-40
            h.nextDebtBill?.let { b ->
                val days = Reminders.daysUntil(ov.today, b.due)
                Line(
                    "📅",
                    "บิลถัดไป ${b.accountName}",
                    "฿${formatMoney(b.amount)} · " + when { b.overdue || days < 0 -> "เลยกำหนดแล้ว!"; days == 0 -> "ครบวันนี้"; else -> "อีก $days วัน" },
                )
            }
            h.copay?.let { cp -> Line("🛍️", "60/40 รัฐช่วยวันนี้อีก", "฿${formatMoney(cp.leftToday)}") }

            // ใช้กับอะไรบ้าง
            val top = spent?.expenseByCategory?.filter { it.amount > 0 }?.take(3).orEmpty()
            if (top.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("รอบนี้หมดไปกับ…", fontFamily = Hand, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pink)
                    top.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryIcon(t.name, t.icon, t.color, size = 30.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(t.name, Modifier.weight(1f), fontSize = 15.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("฿${formatMoney(t.amount)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
                        }
                    }
                }
            }

            Text("จดด้วยรัก · 断捨ISM ✿", fontFamily = Hand, fontSize = 13.sp, color = p.muted)
        }
    }
}

@Composable
private fun HeartTrack(total: Int, passed: Int) {
    val perRow = 16
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        (1..total).chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                row.forEach { i ->
                    Text(
                        when { i < passed -> "♥"; i == passed -> "♥"; else -> "♡" },
                        fontSize = if (i == passed) 17.sp else 13.sp,
                        color = when { i < passed -> Pink; i == passed -> Color(0xFFE0457B); else -> Color(0xFFE8A9BC) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Tile(emoji: String, label: String, value: String, bg: Color, angle: Float, modifier: Modifier, valueColor: Color) {
    val p = LocalPalette.current
    Column(
        modifier.rotate(angle).background(bg, RoundedCornerShape(10.dp)).border(1.5.dp, p.ink, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 16.sp)
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 13.sp, color = p.ink.copy(alpha = 0.8f), fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
    }
}

@Composable
private fun Line(emoji: String, label: String, value: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(PinkSoft, CircleShape), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 15.sp) }
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
    }
}
