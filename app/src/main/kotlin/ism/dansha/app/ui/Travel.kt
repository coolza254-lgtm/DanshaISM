package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(p.surface).padding(12.dp)) {
        if (tp.days == 0) {
            Text("ยังไม่ได้ติ๊กวันทำงานของรอบนี้", fontSize = 14.sp, color = p.ink)
            Text("ติ๊กวันไปทำงาน + วันกะ 14:00–23:00 (กลับ Grab) แล้วแอพคิดค่ารถทั้งรอบให้", fontSize = 13.sp, color = p.muted)
        } else {
            Text("ทำงาน ${tp.days} วัน (กะดึก ${tp.lateDays} วัน) · ประมาณทั้งรอบ ฿${formatMoney(tp.total)}", fontSize = 14.sp, color = p.ink)
            Text("จดค่ารถจริงแล้ว ฿${formatMoney(tp.spent)} · ยังเหลือ ${tp.daysLeft} วัน ≈ ฿${formatMoney(tp.remaining)}", fontSize = 13.sp, color = p.muted)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onWorkDays) { Text("📅 วันทำงาน") }
            OutlinedButton(onClick = onClaims) { Text(if (openClaims > 0) "🧾 เบิก Grab ($openClaims)" else "🧾 Staff Claim") }
        }
        if (openClaims > 0) Text("Grab รอส่งเบิก $openClaims เที่ยว ฿${formatMoney(openClaimTotal)} — ส่งภายในวันที่ 15 ได้เงินวันที่ 30", fontSize = 13.sp, color = p.warning, modifier = Modifier.padding(top = 6.dp))
    }
}

// ---------- วันทำงาน ----------

private val WEEK_TH = listOf("จ", "อ", "พ", "พฤ", "ศ", "ส", "อา")

/** ปฏิทินรอบเงินเดือน: แตะวัน = ไปทำงาน, แตะอีก = กะดึก (Grab กลับ), แตะอีก = ไม่ไป */
@Composable
fun WorkDaysPage(d: DanshaData, cycle: String, vm: MainViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val cfg = remember(d) { Engine.config(d) }
    val (rs, re) = remember(cycle) { Dates.payCycleRange(cycle, Engine.startDay(cfg)) }
    val state = remember { mutableStateMapOf<String, Boolean>().apply { PlanBook.workDays(cfg, cycle).forEach { put(it.date, it.late) } } }
    val fares = PlanBook.fares(cfg)
    var go by remember { mutableStateOf(amountText(fares.go)) }
    var back by remember { mutableStateOf(amountText(fares.back)) }
    var grab by remember { mutableStateOf(amountText(fares.grab)) }
    var error by remember { mutableStateOf<String?>(null) }

    val g = Forms.parseAmount(go) ?: 0.0
    val b = Forms.parseAmount(back) ?: 0.0
    val gr = Forms.parseAmount(grab) ?: 0.0
    val normal = state.count { !it.value }
    val late = state.count { it.value }

    fun save() {
        val days = state.map { (date, l) -> PlanBook.WorkDay(date, l) }
        vm.edit<Unit>(ok = "บันทึกวันทำงานแล้ว", onError = { error = it }, onOk = { onClose() }) {
            setConfig(mapOf("travel_go" to go.ifEmpty { "0" }, "travel_back" to back.ifEmpty { "0" }, "travel_grab" to grab.ifEmpty { "0" }))
            setWorkDays(cycle, days)
        }
    }

    FormPage("วันทำงาน รอบ ${cycleLabel(cycle)}", onClose, ::save) {
        error?.let { Note(it, p.negative) }
        Note("แตะ 1 ครั้ง = ไปทำงาน 🚌 · แตะอีก = กะ 14:00–23:00 กลับ Grab 🌙 · แตะอีก = ไม่ไป", p.ink)
        // หัววัน
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
                        val st = state[key]
                        val bg = when { !inCycle -> Color.Transparent; st == null -> p.surface; st -> p.stickyBlue; else -> p.positiveSoft }
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(bg)
                                .then(if (key == Dates.todayStr()) Modifier.border(2.dp, p.primary, RoundedCornerShape(8.dp)) else Modifier)
                                .then(
                                    if (inCycle) Modifier.clickable {
                                        when (st) { null -> state[key] = false; false -> state[key] = true; true -> state.remove(key) }
                                    } else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (inCycle) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(dt.dayOfMonth.toString(), fontSize = 14.sp, color = p.ink, fontWeight = if (st != null) FontWeight.Bold else FontWeight.Normal)
                                if (st != null) Text(if (st) "🌙" else "🚌", fontSize = 11.sp)
                            }
                        }
                    }
                }
                day = day.plusWeeks(1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                var x = rs
                while (!x.isAfter(re)) { if (x.dayOfWeek.value <= 5 && state[x.toString()] == null) state[x.toString()] = false; x = x.plusDays(1) }
            }) { Text("ติ๊ก จ–ศ ทั้งหมด") }
            OutlinedButton(onClick = { state.clear() }) { Text("ล้าง") }
        }
        Text("ทำงาน ${normal + late} วัน (กะดึก $late) · ประมาณ ฿${formatMoney(normal * (g + b) + late * (g + gr))}", fontWeight = FontWeight.SemiBold, color = p.ink)
        Text("ค่าเดินทางโดยประมาณ (ใช้วางแผน)", fontWeight = FontWeight.Medium)
        Row {
            AmountField("ขาไป", go, { go = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AmountField("ขากลับ", back, { back = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AmountField("Grab กลับ", grab, { grab = it }, Modifier.weight(1f))
        }
        Note("วันจริงกดจดค่ารถได้จากการ์ด \"ไปทำงานวันนี้\" ในหน้าภาพรวม (8 / 12 / 20 บาท หรือ Grab)")
    }
}

// ---------- จดค่ารถวันนี้ (หน้าภาพรวม) ----------

@Composable
fun TravelTodayCard(d: DanshaData, vm: MainViewModel) {
    val p = LocalPalette.current
    val today = Dates.todayStr()
    val cfg = remember(d) { Engine.config(d) }
    val cycle = remember(d) { Dates.payCycleOf(Dates.today(), Engine.startDay(cfg)) }
    val work = remember(d) { PlanBook.workDays(cfg, cycle).firstOrNull { it.date == today } } ?: return
    val todays = remember(d) { d.transactions.filter { it.date == today && PlanBook.isTravelTxn(it) } }
    val wentTo = todays.firstOrNull { it.note == "ค่ารถไปทำงาน" }
    val cameBack = todays.firstOrNull { it.note == "ค่ารถกลับบ้าน" || it.claim.isNotEmpty() }
    val fares = remember(cfg) { cfg["travel_fares"].orEmpty().split(',').mapNotNull { it.trim().toDoubleOrNull() }.ifEmpty { listOf(8.0, 12.0, 20.0) } }
    var grab by remember { mutableStateOf(false) }

    Card(color = p.stickyBlue) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (work.late) "🌙" else "🚌", fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text(if (work.late) "ไปทำงานวันนี้ · กะ 14:00–23:00" else "ไปทำงานวันนี้", Modifier.weight(1f), fontFamily = Hand, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.primary)
        }
        FareRow("ขาไป", wentTo?.amount?.toDouble(), fares, null) { amt -> vm.edit<Any>(ok = "จดค่ารถไป ฿${formatMoney(amt)}") { logTravel(today, "go", amt) } }
        FareRow("ขากลับ", cameBack?.amount?.toDouble(), fares, if (work.late) "Grab (เบิกได้)" else "Grab") { amt ->
            if (amt < 0) grab = true else vm.edit<Any>(ok = "จดค่ารถกลับ ฿${formatMoney(amt)}") { logTravel(today, "back", amt) }
        }
    }
    if (grab) GrabPage(d, vm) { grab = false }
}

/** แถวปุ่มค่ารถ: ยังไม่จด = ปุ่ม 8/12/20 (+ Grab), จดแล้ว = ✓ ยอด */
@Composable
private fun FareRow(label: String, logged: Double?, fares: List<Double>, grabLabel: String?, onPick: (Double) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), color = p.ink, fontWeight = FontWeight.Medium)
        if (logged != null) {
            Text("✓ จดแล้ว ฿${formatMoney(logged)}", color = p.positive, fontWeight = FontWeight.SemiBold)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fares.forEach { f ->
                    Text(
                        "฿" + amountText(f),
                        Modifier.clip(RoundedCornerShape(50)).background(p.card).border(1.5.dp, p.ink.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .clickable { onPick(f) }.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = p.ink, fontWeight = FontWeight.SemiBold,
                    )
                }
                grabLabel?.let {
                    Text(
                        it,
                        Modifier.clip(RoundedCornerShape(50)).background(p.primary).clickable { onPick(-1.0) }.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = p.onPrimary, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
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
        Note("รายการนี้จะไปรอใน Staff Claim — ส่งใบเบิกภายในวันที่ 15 ได้เงินคืนวันที่ 30 เดือนนั้น ไม่ทันได้เดือนถัดไป")
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
        Note("กติกา: ส่งใบเบิกภายในวันที่ 15 → ได้เงินวันที่ 30 เดือนนั้น · ส่งหลังวันที่ 15 → ได้วันที่ 30 เดือนถัดไป", p.ink)
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
        Note("กรอกตาม Slip (ออกวันที่ 14) แอพจำรายการและยอดครั้งก่อนไว้ แก้เฉพาะตัวที่เปลี่ยน", p.ink)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
