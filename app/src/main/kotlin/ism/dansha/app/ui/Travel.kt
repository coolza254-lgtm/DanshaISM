@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.Bill
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.PlanBook

/*
 * ค่าเดินทางไปทำงาน + Staff Claim (เบิกค่า Grab) + กรอกเงินเดือนจาก Slip
 */

// ---------- สรุปในแผนบิล ----------

@Composable
fun TravelSummary(tp: PlanBook.TravelPlan, openClaims: Int, openClaimTotal: Double, onWorkDays: () -> Unit, onClaims: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(p.surface).clickable(onClick = onWorkDays).padding(12.dp)) {
        if (tp.days == 0) {
            Text("ยังไม่ได้วางแผนวันไปทำงาน", fontSize = 14.sp, color = p.ink, fontWeight = FontWeight.Medium)
            Text("แตะเพื่อเลือกวันไปทำงานในปฏิทิน แล้วจดค่ารถจริงทีละวัน", fontSize = 13.sp, color = p.muted)
        } else {
            Row {
                TravelStat("เผื่อไว้", tp.total, p.ink, Modifier.weight(1f))
                TravelStat("จ่ายจริง", tp.spent, p.negative, Modifier.weight(1f))
                TravelStat("ยังต้องเตรียม", tp.remaining, p.primary, Modifier.weight(1f))
            }
            Text(
                "ไปทำงาน ${tp.days} วัน (กะดึก ${tp.lateDays}) · จดครบแล้ว ${tp.days - tp.daysLeft} วัน" + if (tp.saved > 0) " · ประหยัดได้ ฿${formatMoney(tp.saved)}" else "",
                fontSize = 13.sp, color = p.muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
        WrapRow(Modifier.padding(top = 8.dp)) {
            Button(onClick = onWorkDays) { Text(if (tp.days == 0) "📅 วางแผนวันทำงาน" else "🚌 จดค่ารถ / แผน") }
            OutlinedButton(onClick = onClaims) { Text(if (openClaims > 0) "🧾 เบิก Grab ($openClaims)" else "🧾 Staff Claim") }
        }
        if (openClaims > 0) Text("Grab รอส่งเบิก $openClaims เที่ยว ฿${formatMoney(openClaimTotal)} — ส่งภายในวันที่ 15 ได้เงินวันที่ 30", fontSize = 13.sp, color = p.warning, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun TravelStat(label: String, value: Double, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = LocalPalette.current.muted)
        FitText("฿" + formatMoney(value), androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Sarabun, color = color), minSize = 11.sp)
    }
}

// ---------- หน้าค่าเดินทาง: วางแผน + จดจริง ----------

private val WEEK_TH = listOf("จ", "อ", "พ", "พฤ", "ศ", "ส", "อา")
private val DAY_TH = listOf("จันทร์", "อังคาร", "พุธ", "พฤหัสฯ", "ศุกร์", "เสาร์", "อาทิตย์")

/**
 * ปฏิทินรอบเงินเดือน 2 โหมด
 *  📅 วางแผน: แตะวัน = ไปทำงาน 🚌 → กะดึก 🌙 (กลับ Grab) → ไม่ไป
 *  ✅ จดค่ารถ: แตะวันทำงาน → กดราคาที่จ่ายจริง แอพติ๊กว่าจ่ายแล้ว ยอดที่ต้องเตรียมลดลง
 */
@Composable
fun WorkDaysPage(d: DanshaData, cycle: String, vm: MainViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val cfg = remember(d) { Engine.config(d) }
    val (rs, re) = remember(cycle) { Dates.payCycleRange(cycle, Engine.startDay(cfg)) }
    val tp = remember(d, cycle) { PlanBook.travel(d, cycle) }
    val byDate = remember(tp) { tp.list.associateBy { it.date } }
    val fares = remember(cfg) { PlanBook.fares(cfg) }
    var planMode by remember { mutableStateOf(tp.days == 0) }
    var openDay by remember { mutableStateOf<String?>(null) }
    var bus by remember { mutableStateOf(amountText(fares.bus)) }
    var grab by remember { mutableStateOf(amountText(fares.grab)) }

    fun setDay(date: String, state: Boolean?) {
        val old = byDate[date]?.work
        val days = tp.list.map { it.work }.filter { it.date != date } +
            listOfNotNull(state?.let { old?.copy(late = it) ?: PlanBook.WorkDay(date, it) })
        vm.edit<Unit> { setWorkDays(cycle, days) }
    }

    FormPage("ค่าเดินทาง · ${cycleLabel(cycle)}", onClose, onSave = null) {
        // สรุป
        Column(Modifier.fillMaxWidth().paperCard(p, p.card, radius = 14.dp).padding(14.dp)) {
            Row {
                TravelStat("เผื่อไว้ทั้งรอบ", tp.total, p.ink, Modifier.weight(1f))
                TravelStat("จ่ายจริงแล้ว", tp.spent, p.negative, Modifier.weight(1f))
                TravelStat("ยังต้องเตรียม", tp.remaining, p.primary, Modifier.weight(1f))
            }
            if (tp.saved > 0) Text("🎉 ประหยัดจากที่เผื่อไว้ ฿${formatMoney(tp.saved)}", fontSize = 14.sp, color = p.positive, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Text("ไปทำงาน ${tp.days} วัน (กะดึก ${tp.lateDays}) · จดครบแล้ว ${tp.days - tp.daysLeft} วัน", fontSize = 13.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
        }

        // โหมด
        TypeTabs(planMode) { planMode = it }
        Note(
            if (planMode) "แตะวัน: 1 ครั้ง = ไปทำงาน 🚌 · 2 ครั้ง = กะ 14:00–23:00 กลับ Grab 🌙 · 3 ครั้ง = ไม่ไป · แตะค้าง = ยกเว้นค่ารถ 🚫"
            else "แตะวันที่ไปทำงาน แล้วกดราคาที่จ่ายจริง (ไม่ตรงก็กดใหม่ได้)",
            p.ink,
        )

        // ปฏิทิน
        Row(Modifier.fillMaxWidth()) { WEEK_TH.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 13.sp, color = p.muted) } }
        val start = rs.minusDays((rs.dayOfWeek.value - 1).toLong())
        var day = start
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            while (!day.isAfter(re)) {
                val week = (0..6).map { day.plusDays(it.toLong()) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { dt ->
                        val inCycle = !dt.isBefore(rs) && !dt.isAfter(re)
                        val key = dt.toString()
                        val td = byDate[key]
                        val partial = td != null && !td.done && (td.go != null || td.back != null)
                        val bg = when {
                            !inCycle -> Color.Transparent
                            td == null -> p.surface
                            td.skipAll -> p.line.copy(alpha = 0.5f)
                            td.done -> p.positiveSoft
                            partial -> p.warningSoft
                            td.late -> p.stickyBlue
                            else -> p.primarySoft
                        }
                        Box(
                            Modifier.weight(1f).aspectRatio(0.82f).clip(RoundedCornerShape(8.dp)).background(bg)
                                .then(if (key == Dates.todayStr()) Modifier.border(2.dp, p.primary, RoundedCornerShape(8.dp)) else Modifier)
                                .then(
                                    if (inCycle) Modifier.combinedClickable(
                                        onLongClick = { if (td == null) setDay(key, false); openDay = key },
                                    ) {
                                        if (planMode) setDay(key, when (td?.late) { null -> false; false -> true; true -> null })
                                        else if (td != null) openDay = key
                                        else setDay(key, false).also { openDay = key }
                                    } else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (inCycle) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(dt.dayOfMonth.toString(), fontSize = 14.sp, color = p.ink, fontWeight = if (td != null) FontWeight.Bold else FontWeight.Normal)
                                if (td != null) {
                                    Text(
                                        when { td.skipAll -> "🚫"; td.done -> "✓"; td.skipGo || td.skipBack -> (if (td.late) "🌙" else "🚌") + "½"; td.late -> "🌙"; else -> "🚌" },
                                        fontSize = 11.sp, color = p.positive, fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        if (td.done || partial) amountText(td.paid) else amountText(td.planGo + td.planBack),
                                        fontSize = 10.sp, color = if (td.done) p.positive else p.muted,
                                    )
                                }
                            }
                        }
                    }
                }
                day = day.plusWeeks(1)
            }
        }
        // คำอธิบายสี
        WrapRow(gap = 10.dp) {
            Legend(p.primarySoft, "🚌 ไปทำงาน")
            Legend(p.stickyBlue, "🌙 กะดึก")
            Legend(p.warningSoft, "จดไม่ครบ")
            Legend(p.positiveSoft, "✓ จ่ายแล้ว")
            Legend(p.line.copy(alpha = 0.5f), "🚫 ยกเว้น")
        }
        if (planMode) WrapRow {
            OutlinedButton(onClick = {
                val days = tp.list.map { it.work }.toMutableList()
                var x = rs
                while (!x.isAfter(re)) { if (x.dayOfWeek.value <= 5 && days.none { it.date == x.toString() }) days += PlanBook.WorkDay(x.toString(), false); x = x.plusDays(1) }
                vm.edit<Unit> { setWorkDays(cycle, days) }
            }) { Text("ติ๊ก จ–ศ ทั้งหมด") }
            OutlinedButton(onClick = { vm.edit<Unit> { setWorkDays(cycle, emptyList()) } }) { Text("ล้างแผน") }
        }

        // วันที่ผ่านไปแล้วแต่ยังจดไม่ครบ
        val missing = tp.list.filter { !it.done && it.date < Dates.todayStr() }
        if (missing.isNotEmpty()) {
            Text("ยังไม่ได้จด (${missing.size} วัน)", fontWeight = FontWeight.SemiBold, color = p.warning)
            missing.forEach { td ->
                Row(Modifier.fillMaxWidth().clickable { openDay = td.date }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dayTitle(td.date) + (if (td.late) " 🌙" else " 🚌") + td.reason.ifEmpty { null }?.let { " · $it" }.orEmpty(), Modifier.weight(1f), color = p.ink)
                    Text("จด ›", color = p.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ราคาที่เผื่อ
        Text("ราคาที่เผื่อไว้ตอนวางแผน", fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AmountField("รถเมล์ (เที่ยวละ)", bus, { bus = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AmountField("Grab กลับ (ประมาณ)", grab, { grab = it }, Modifier.weight(1f))
        }
        Note("เผื่อรถเมล์แพงสุดไว้ก่อน จ่ายจริงเท่าไหร่ค่อยกดตามจริง")
        if (bus != amountText(fares.bus) || grab != amountText(fares.grab)) {
            Button(onClick = {
                vm.edit<Unit>(ok = "บันทึกราคาเผื่อแล้ว") { setConfig(mapOf("travel_bus" to bus.ifEmpty { "20" }, "travel_grab" to grab.ifEmpty { "0" })); syncPlan(cycle) }
            }, modifier = Modifier.fillMaxWidth()) { Text("บันทึกราคาเผื่อ") }
        }
    }

    openDay?.let { date ->
        val td = byDate[date]
        if (td != null) TravelDayDialog(td, fares, vm, cycle, onType = { setDay(date, it) }) { openDay = null }
    }
}

@Composable
private fun TypeTabs(planMode: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Row(Modifier.fillMaxWidth().clip(shape).background(p.surface).border(1.5.dp, p.ink.copy(alpha = 0.6f), shape).padding(3.dp)) {
        listOf(true to "📅 วางแผน", false to "✅ จดค่ารถจริง").forEach { (v, label) ->
            Box(
                Modifier.weight(1f).clip(shape).background(if (v == planMode) p.primary else Color.Transparent).clickable { onChange(v) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (v == planMode) p.onPrimary else p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = LocalPalette.current.muted)
    }
}

private fun dayTitle(date: String): String {
    val d = java.time.LocalDate.parse(date)
    return "${DAY_TH[d.dayOfWeek.value - 1]} ${shortThaiDate(date)}"
}

/** จดค่ารถของวันหนึ่ง: ขาไป + ขากลับ (รถเมล์ หรือ Grab วันกะดึก) */
@Composable
private fun TravelDayDialog(td: PlanBook.TravelDay, fares: PlanBook.TravelFares, vm: MainViewModel, cycle: String, onType: (Boolean?) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(dayTitle(td.date), fontFamily = Hand, fontWeight = FontWeight.Bold, color = p.primary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceChips(listOf(false to "🚌 ไปทำงาน", true to "🌙 กะดึก (กลับ Grab)"), td.late, { onType(it) })
                SkipPicker(td, vm, cycle)
                LegPicker(td, fares, vm, cycle)
                Text(
                    "เผื่อไว้ ฿${formatMoney(td.planGo + td.planBack)} · จ่ายจริง ฿${formatMoney(td.paid)}" +
                        if (td.saved > 0) " · ประหยัด ฿${formatMoney(td.saved)}" else "",
                    fontSize = 13.sp, color = p.muted,
                )
                Text("ไม่ได้ไปทำงานวันนี้ ›", Modifier.clickable { onType(null); onClose() }, color = p.negative, fontSize = 14.sp)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("เสร็จ") } },
    )
}

private val SKIP_REASONS = listOf("มีคนไปส่ง", "บริษัทจ่าย", "ทำงานที่บ้าน", "ลา/ไม่ได้ไป")

/** ยกเว้นค่ารถ: เลือกขาที่ไม่ต้องจ่าย + เหตุผล (ไม่ต้องเผื่อเงิน ไม่นับว่าค้างจด) */
@Composable
private fun SkipPicker(td: PlanBook.TravelDay, vm: MainViewModel, cycle: String) {
    val p = LocalPalette.current
    var open by remember(td.date) { mutableStateOf(td.skipGo || td.skipBack) }
    var reason by remember(td.date) { mutableStateOf(td.reason) }
    fun set(go: Boolean, back: Boolean, why: String = reason) {
        vm.edit<Unit> { setTravelSkip(cycle, td.date, go, back, why) }
    }
    if (!open) {
        Text("🚫 ยกเว้นค่ารถวันนี้ (มีเหตุอื่น) ›", Modifier.clickable { open = true }, color = p.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ยกเว้นค่ารถ (ไม่ต้องเผื่อเงิน)", fontSize = 14.sp, color = p.ink, fontWeight = FontWeight.SemiBold)
        WrapRow {
            SkipChip("ขาไป", td.skipGo) { set(!td.skipGo, td.skipBack) }
            SkipChip(if (td.late) "ขากลับ (Grab)" else "ขากลับ", td.skipBack) { set(td.skipGo, !td.skipBack) }
            SkipChip("ทั้งวัน", td.skipAll) { val v = !td.skipAll; set(v, v) }
        }
        if (td.skipGo || td.skipBack) {
            WrapRow {
                SKIP_REASONS.forEach { r -> SkipChip(r, reason == r) { reason = if (reason == r) "" else r; set(td.skipGo, td.skipBack, reason) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextInput("เหตุผลอื่นๆ", reason, { reason = it }, Modifier.weight(1f))
                if (reason != td.reason) {
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.TextButton(onClick = { set(td.skipGo, td.skipBack) }) { Text("บันทึก") }
                }
            }
        }
    }
}

@Composable
private fun SkipChip(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        (if (on) "✓ " else "") + label,
        Modifier.clip(RoundedCornerShape(50)).background(if (on) p.primary else p.card)
            .border(1.5.dp, if (on) p.primary else p.ink.copy(alpha = 0.4f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (on) p.onPrimary else p.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** ปุ่มจดขาไป / ขากลับ — ใช้ทั้งในหน้าค่าเดินทางและการ์ดหน้าภาพรวม */
@Composable
private fun LegPicker(td: PlanBook.TravelDay, fares: PlanBook.TravelFares, vm: MainViewModel, cycle: String) {
    val p = LocalPalette.current
    fun set(leg: String, amount: Double?) {
        vm.edit<Any?>(ok = amount?.let { "จดค่ารถ ฿${formatMoney(it)}" }) { setTravelLeg(td.date, leg, amount).also { syncPlan(cycle) } }
    }
    if (td.skipGo && td.go == null) Text("ขาไป: 🚫 ยกเว้น" + td.reason.ifEmpty { null }?.let { " · $it" }.orEmpty(), fontSize = 14.sp, color = p.muted)
    else LegRow("ขาไป (รถเมล์)", td.go?.amount?.toDouble(), fares.choices, onPick = { set("go", it) }, onClear = { set("go", null) })
    if (td.skipBack && td.back == null) {
        Text("ขากลับ: 🚫 ยกเว้น" + td.reason.ifEmpty { null }?.let { " · $it" }.orEmpty(), fontSize = 14.sp, color = p.muted)
    } else if (td.late) {
        var grabText by remember(td.back?.id) { mutableStateOf(amountText(td.back?.amount)) }
        Text("ขากลับ (Grab · เบิก Staff Claim ได้)", fontSize = 14.sp, color = p.ink, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AmountField("ค่า Grab", grabText, { grabText = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { Forms.parseAmount(grabText)?.let { set("grab", it) } }) { Text(if (td.back != null) "แก้" else "จด") }
        }
        td.back?.let { b -> Text("✓ จดแล้ว ฿${formatMoney(b.amount)} · ${claimLabel(b.claim)}", fontSize = 13.sp, color = p.positive) }
    } else {
        LegRow("ขากลับ (รถเมล์)", td.back?.amount?.toDouble(), fares.choices, onPick = { set("back", it) }, onClear = { set("back", null) })
    }
}

@Composable
private fun LegRow(label: String, logged: Double?, choices: List<Double>, onPick: (Double) -> Unit, onClear: () -> Unit) {
    val p = LocalPalette.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp, color = p.ink, fontWeight = FontWeight.Medium)
            if (logged != null) Text("ลบ", Modifier.clickable(onClick = onClear).padding(4.dp), fontSize = 13.sp, color = p.negative)
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { f ->
                val sel = logged == f
                Text(
                    (if (sel) "✓ " else "") + "฿" + amountText(f),
                    Modifier.clip(RoundedCornerShape(50)).background(if (sel) p.positive else p.card)
                        .border(1.5.dp, if (sel) p.positive else p.ink.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .clickable { onPick(f) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    color = if (sel) Color.White else p.ink, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (logged != null && logged !in choices) Text("✓ จดแล้ว ฿${formatMoney(logged)}", fontSize = 13.sp, color = p.positive)
    }
}

// ---------- จดค่ารถวันนี้ (หน้าภาพรวม) ----------

@Composable
fun TravelTodayCard(d: DanshaData, vm: MainViewModel) {
    val p = LocalPalette.current
    val today = Dates.todayStr()
    val cfg = remember(d) { Engine.config(d) }
    val cycle = remember(d) { Dates.payCycleOf(Dates.today(), Engine.startDay(cfg)) }
    val td = remember(d) { PlanBook.travel(d, cycle).list.firstOrNull { it.date == today } } ?: return
    val fares = remember(cfg) { PlanBook.fares(cfg) }
    Card(color = if (td.done) p.positiveSoft else p.stickyBlue) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (td.done) "✅" else if (td.late) "🌙" else "🚌", fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (td.skipAll) "วันนี้ไม่มีค่ารถ 🚫" + td.reason.ifEmpty { null }?.let { " ($it)" }.orEmpty()
                else if (td.done) "ค่ารถวันนี้จดครบแล้ว ฿${formatMoney(td.paid)}" else if (td.late) "ไปทำงานวันนี้ · กะ 14:00–23:00" else "ไปทำงานวันนี้",
                Modifier.weight(1f), fontFamily = Hand, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.primary,
            )
        }
        if (!td.done) {
            Spacer(Modifier.height(6.dp))
            LegPicker(td, fares, vm, cycle)
        }
    }
}

/** จด Grab กลับบ้าน (เบิก Staff Claim ได้) */
@Composable
fun GrabPage(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    var accountId by remember { mutableStateOf(PlanBook.claimPayAccount(d)) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    FormPage("จด Grab กลับบ้าน", onClose, onSave = {
        val amt = Forms.parseAmount(amount)
        if (amt == null || amt <= 0) { error = "กรอกค่า Grab ก่อน"; return@FormPage }
        vm.edit<Any>(ok = "จด Grab แล้ว · รอเบิก", onError = { error = it }, onOk = { onClose() }) { logGrab(date, amt, accountId, note) }
    }) {
        error?.let { Note(it, DanshaColors.Negative) }
        AmountField("ค่า Grab", amount, { amount = it })
        DateField("วันที่", date, { date = it })
        Picker("จ่ายจาก", Forms.accountChoices(d, accountId).map { it.id to it.name }, accountId, { accountId = it })
        TextInput("โน้ต (ไม่บังคับ)", note, { note = it }, placeholder = "Grab กลับบ้าน (เบิกได้)")
    }
}

// ---------- Staff Claim ----------

@Composable
fun ClaimPage(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val open = remember(d) { PlanBook.openClaims(d) }
    val selected = remember(d) { mutableStateListOf<String>().apply { addAll(open.map { it.id }) } }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    var grab by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending = remember(d) { d.bills.filter { it.section == PlanBook.CLAIM && it.status != "paid" }.sortedBy { it.due_date } }
    val total = open.filter { it.id in selected }.sumOf { it.amount?.toDouble() ?: 0.0 }
    val payDay = runCatching { PlanBook.claimPayDate(date) }.getOrDefault("")

    FormPage("Staff Claim (เบิกค่า Grab)", onClose, onSave = null) {
        error?.let { Note(it, p.negative) }
        Note("ส่งภายในวันที่ 15 → ได้เงินวันที่ 30 · หลังวันที่ 15 → 30 เดือนหน้า", p.ink)
        OutlinedButton(onClick = { grab = true }, modifier = Modifier.fillMaxWidth()) { Text("+ จด Grab กลับบ้าน") }

        Text("รอส่งเบิก (${open.size} เที่ยว)", fontWeight = FontWeight.SemiBold, color = p.ink)
        if (open.isEmpty()) Note("ไม่มีรายการรอเบิก")
        open.forEach { t ->
            Row(Modifier.fillMaxWidth().clickable { if (t.id in selected) selected.remove(t.id) else selected.add(t.id) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = t.id in selected, onCheckedChange = { if (it) selected.add(t.id) else selected.remove(t.id) })
                Text("${shortThaiDate(t.date)} · ${t.note}", Modifier.weight(1f), fontSize = 14.sp, color = p.ink)
                Text(formatMoney(t.amount), fontWeight = FontWeight.SemiBold, color = p.ink)
            }
        }
        if (open.isNotEmpty()) {
            DateField("วันที่ส่งใบเบิก", date, { date = it })
            Text("เบิก ฿${formatMoney(total)} · คาดว่าได้เงิน ${thaiDate(payDay)}", fontWeight = FontWeight.SemiBold, color = p.primary)
            Button(onClick = {
                vm.edit<Bill>(ok = "ส่งเบิกแล้ว · ได้เงิน ${thaiDate(payDay)}", onError = { error = it }) { submitClaim(selected.toList(), date) }
            }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("ส่งใบเบิกแล้ว") }
        }

        if (pending.isNotEmpty()) {
            Text("รอเงินเข้า", fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.padding(top = 8.dp))
            pending.forEach { b -> androidx.compose.runtime.key(b.id) {
                var receiving by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { receiving = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ได้เงิน ${thaiDate(b.due_date)}", color = p.ink, fontWeight = FontWeight.Medium)
                        Text(b.note, fontSize = 13.sp, color = p.muted)
                    }
                    Text(formatMoney(b.est_amount), fontWeight = FontWeight.SemiBold, color = p.positive)
                }
                if (receiving) ClaimReceivePage(d, b, vm) { receiving = false }
            } }
        }

        Text("บัญชี", fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.padding(top = 8.dp))
        val accounts = Forms.accountChoices(d).map { it.id to it.name }
        Picker("จ่าย Grab จาก", accounts, PlanBook.claimPayAccount(d), { id -> vm.edit<Unit> { setConfig(mapOf("claim_pay_account_id" to id)) } })
        Picker("เงินเบิกคืนเข้า", accounts, PlanBook.claimReceiveAccount(d), { id -> vm.edit<Unit> { setConfig(mapOf("claim_account_id" to id)) } })
    }
    if (grab) GrabPage(d, vm) { grab = false }
}

@Composable
fun ClaimReceivePage(d: DanshaData, b: Bill, vm: MainViewModel, onClose: () -> Unit) {
    var amount by remember { mutableStateOf(amountText(b.est_amount)) }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    var accountId by remember { mutableStateOf(b.account_id.ifEmpty { PlanBook.claimReceiveAccount(d) }) }
    var error by remember { mutableStateOf<String?>(null) }
    FormPage("ได้เงินเบิกคืนแล้ว", onClose, onSave = {
        val amt = Forms.parseAmount(amount) ?: 0.0
        vm.edit<Bill>(ok = "รับเงินเบิกแล้ว", onError = { error = it }, onOk = { onClose() }) { receiveClaim(b.id, amt, date, accountId) }
    }, saveLabel = "รับเงินแล้ว") {
        error?.let { Note(it, DanshaColors.Negative) }
        Note("${b.note} · คาดว่าได้ ${thaiDate(b.due_date)}", DanshaColors.Ink)
        AmountField("ยอดที่ได้จริง", amount, { amount = it })
        DateField("วันที่เงินเข้า", date, { date = it })
        Picker("เข้าบัญชี", Forms.accountChoices(d, accountId).map { it.id to it.name }, accountId, { accountId = it })
    }
}

// ---------- Slip เงินเดือน ----------

private data class SlipLine(val label: String, val plus: Boolean, val amount: String)

private val DEFAULT_SLIP = listOf(
    SlipLine("เงินเดือน", true, ""), SlipLine("ค่าล่วงเวลา (OT)", true, ""), SlipLine("เบี้ยขยัน / อื่นๆ", true, ""),
    SlipLine("ประกันสังคม", false, ""), SlipLine("ภาษีหัก ณ ที่จ่าย", false, ""),
)

private fun decodeSlip(s: String): List<SlipLine> =
    s.split(';').mapNotNull { part ->
        val f = part.split('|')
        if (f.size < 2 || f[0].isBlank()) null else SlipLine(f[0], f[1] != "-", f.getOrNull(2).orEmpty())
    }.ifEmpty { DEFAULT_SLIP }

private fun encodeSlip(lines: List<SlipLine>): String =
    lines.filter { it.label.isNotBlank() }.joinToString(";") { "${it.label.replace("|", " ").replace(";", " ")}|${if (it.plus) "+" else "-"}|${it.amount}" }

/** กรอกยอดจาก Slip (ออกทุกวันที่ 14) → รายรับเงินเดือนของรอบ — จำรายการ/ยอดครั้งก่อนไว้ แก้แค่ตัวที่เปลี่ยน */
@Composable
fun SlipPage(d: DanshaData, cycle: String, vm: MainViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val cfg = remember(d) { Engine.config(d) }
    val lines = remember { mutableStateListOf<SlipLine>().apply { addAll(decodeSlip(cfg["slip_lines"].orEmpty())) } }
    val salaryBill = remember(d, cycle) { d.bills.firstOrNull { it.pay_cycle == cycle && it.group == "income" && (it.section == PlanBook.SALARY || it.name.contains("เงินเดือน")) } }
    val (rs, _) = remember(cycle) { Dates.payCycleRange(cycle, Engine.startDay(cfg)) }
    var accountId by remember { mutableStateOf(salaryBill?.account_id?.ifEmpty { null } ?: cfg["salary_account_id"]?.ifEmpty { null } ?: Forms.defaultAccount(d, "income")) }
    var date by remember { mutableStateOf(if (Dates.todayStr() < rs.toString()) rs.toString() else Dates.todayStr()) }
    var error by remember { mutableStateOf<String?>(null) }
    val net = lines.sumOf { (Forms.parseAmount(it.amount) ?: 0.0) * if (it.plus) 1 else -1 }

    fun save() {
        val summary = lines.filter { (Forms.parseAmount(it.amount) ?: 0.0) != 0.0 }
            .joinToString(" · ") { "${it.label} ${if (it.plus) "" else "−"}${formatMoney(Forms.parseAmount(it.amount))}" }
        vm.edit<Bill>(ok = "รับเงินเดือนแล้ว ฿${formatMoney(net)}", onError = { error = it }, onOk = { onClose() }) {
            setConfig(mapOf("slip_lines" to encodeSlip(lines), "salary_account_id" to accountId))
            receiveSalary(cycle, Math.round(net * 100) / 100.0, accountId, date, "เงินเดือน (Slip: $summary)")
        }
    }

    FormPage("เงินเดือนจาก Slip", onClose, ::save, saveLabel = "รับเงินแล้ว") {
        error?.let { Note(it, p.negative) }
        if (salaryBill?.status == "paid") Note("รอบนี้บันทึกเงินเดือนไปแล้ว ${formatMoney(salaryBill.actual_amount)} — กดบันทึกอีกจะเพิ่มเป็นรายการใหม่", p.negative)
        lines.forEachIndexed { i, l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (l.plus) "+" else "−",
                    Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(if (l.plus) p.positiveSoft else p.negativeSoft)
                        .clickable { lines[i] = l.copy(plus = !l.plus) }.padding(top = 4.dp),
                    textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = if (l.plus) p.positive else p.negative,
                )
                Spacer(Modifier.width(8.dp))
                TextInput("รายการ", l.label, { lines[i] = l.copy(label = it) }, Modifier.weight(1.2f))
                Spacer(Modifier.width(8.dp))
                AmountField("ยอด", l.amount, { lines[i] = l.copy(amount = it) }, Modifier.weight(1f))
            }
        }
        WrapRow {
            OutlinedButton(onClick = { lines.add(SlipLine("", true, "")) }) { Text("+ เพิ่มบรรทัด") }
            if (lines.size > 1) OutlinedButton(onClick = { lines.removeAt(lines.lastIndex) }) { Text("ลบบรรทัดสุดท้าย") }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.stickyBlue).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("รับสุทธิ", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = p.ink)
            Text("฿" + formatMoney(net), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (net < 0) p.negative else p.positive)
        }
        Picker("เงินเข้าบัญชี", Forms.accountChoices(d, accountId).map { it.id to it.name }, accountId, { accountId = it })
        DateField("วันที่เงินเข้า", date, { date = it })
        Spacer(Modifier.height(4.dp))
    }
}
