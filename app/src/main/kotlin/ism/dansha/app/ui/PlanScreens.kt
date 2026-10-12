package ism.dansha.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.Bill
import ism.dansha.core.BillTemplate
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.Outlook
import ism.dansha.core.engine.Plan
import ism.dansha.core.engine.PlanBook
import ism.dansha.core.engine.Store
import ism.dansha.core.engine.isEngineAccount
import ism.dansha.core.engine.isSplAccount
import ism.dansha.core.engine.toDec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

private fun money(v: Double?) = formatMoney(v?.let { BigDecimal.valueOf(it) })

/** ชื่อ + อีโมจิของแต่ละหมวดในแผน */
internal val SECTION_LABELS = linkedMapOf(
    PlanBook.INCOME to ("💰" to "รายรับ"),
    PlanBook.FIXED to ("🏠" to "รายจ่ายประจำ"),
    PlanBook.DEBT to ("💳" to "หนี้สิน"),
    PlanBook.ONCE to ("📌" to "จ่ายครั้งเดียว"),
    PlanBook.TRAVEL to ("🚌" to "ค่าเดินทาง"),
    PlanBook.PLANNED to ("🛍️" to "เงินที่วางแผนใช้"),
)

/** หมวดที่เลือกได้ในฟอร์ม */
private val SECTION_CHOICES = listOf(
    PlanBook.SALARY to "เงินเดือน", PlanBook.INCOME to "รายรับอื่น", PlanBook.FIXED to "รายจ่ายประจำ", PlanBook.DEBT to "หนี้สิน",
    PlanBook.ONCE to "ครั้งเดียว", PlanBook.TRAVEL to "ค่าเดินทาง", PlanBook.PLANNED to "วางแผนใช้",
)

private fun editableSection(b: Bill): String = if (b.section == PlanBook.SALARY) PlanBook.SALARY else PlanBook.sectionOf(b)

@Composable
fun PlanScreen(d: DanshaData, c: MainViewModel.Computed?, vm: MainViewModel, onOpenTemplates: () -> Unit) {
    val p = LocalPalette.current
    val startDay = remember(d) { Engine.startDay(Engine.config(d)) }
    val current = remember(d) { Dates.payCycleOf(Dates.today(), startDay) }
    var cycle by rememberSaveable { mutableStateOf(current) }
    var open by remember { mutableStateOf<Bill?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }
    var slip by remember { mutableStateOf(false) }
    var workDays by remember { mutableStateOf(false) }
    var claims by remember { mutableStateOf(false) }
    var claimReceive by remember { mutableStateOf<Bill?>(null) }
    val cashNow = c?.overview?.cashNow ?: 0.0
    val rows = remember(d, cycle) {
        d.bills.filter { it.pay_cycle == cycle }
            .sortedWith(compareBy({ it.due_date.ifEmpty { "9999" } }, { it.sort ?: Int.MAX_VALUE }, { it.name }))
    }
    val bySection = remember(rows) { rows.groupBy { PlanBook.sectionOf(it) } }
    val summary = remember(d, cycle, cashNow) { Plan.summary(cycle, d.bills, cashNow) }
    val travel = remember(d, cycle) { PlanBook.travel(d, cycle) }
    val openClaims = remember(d) { PlanBook.openClaims(d) }
    val outlook = c?.outlook

    // แถวที่แอพดูแลเอง (หนี้/ค่าเดินทาง) ให้ตรงกับตัวคำนวณเสมอ — คำนวณเบื้องหลัง เขียนเฉพาะเมื่อมีอะไรเปลี่ยน
    var syncTries by remember(cycle) { mutableIntStateOf(0) }
    LaunchedEffect(d, cycle) {
        if (syncTries >= 3 || d.isEmpty()) return@LaunchedEffect
        val needs = withContext(Dispatchers.Default) { runCatching { Store.run(d) { syncPlan(cycle) }.value }.getOrDefault(false) }
        if (needs) {
            syncTries++
            vm.edit<Boolean> { syncPlan(cycle) }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenTitle("แผนบิล") }
        item {
            Row(Modifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { cycle = Outlook.shiftCycle(cycle, -1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "รอบก่อน") }
                val (rs, re) = Dates.payCycleRange(cycle, startDay)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("รอบ ${cycleLabel(cycle)}${if (cycle == current) " (รอบนี้)" else ""}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("${thaiDate(rs.toString())} – ${thaiDate(re.toString())}", color = DanshaColors.Muted, fontSize = 13.sp)
                }
                IconButton(onClick = { cycle = Outlook.shiftCycle(cycle, 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "รอบถัดไป") }
            }
        }

        // สรุปรอบ
        item {
            Card {
                val totalExp = summary.aPending + summary.aPaid + summary.bPending + summary.bPaid
                if (cycle == current) {
                    Text("คาดว่าสิ้นรอบเหลือ", color = p.muted, fontSize = 14.sp)
                    FitText(signedBaht(summary.projected), androidx.compose.ui.text.TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = Sarabun, color = if (summary.projected < 0) p.negative else p.primary))
                    Spacer(Modifier.height(6.dp))
                    PlanLine("เงินที่มีตอนนี้", money(summary.cashNow))
                    PlanLine("+ รายรับที่ยังไม่เข้า", money(summary.incomePending), color = p.positive)
                    PlanLine("− รายจ่ายที่ยังไม่จ่าย", money(summary.aPending + summary.bPending), color = p.negative)
                } else {
                    val inc = summary.incomePending + summary.incomeReceived
                    PlanLine("รายรับตามแผน", money(inc), bold = true, color = p.positive)
                    PlanLine("รายจ่ายตามแผน", money(totalExp), bold = true, color = p.negative)
                    PlanLine("รับ − จ่าย", money(inc - totalExp), bold = true)
                }
                if (totalExp > 0) {
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(((summary.aPaid + summary.bPaid) / totalExp).toFloat(), p.positive, p.positiveSoft)
                    Text("จ่ายแล้ว ${money(summary.aPaid + summary.bPaid)} จาก ${money(totalExp)}", fontSize = 13.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        if (rows.none { it.template_id.isNotEmpty() && !it.template_id.startsWith("auto:") && !it.template_id.startsWith(PlanBook.CLAIM_PREFIX) } && d.billTemplates.any { it.active }) {
            item {
                Card(color = p.sticky) {
                    Text("ยังไม่ได้สร้างรายการประจำของรอบนี้", fontWeight = FontWeight.SemiBold, color = p.ink)
                    Text("กดปุ่มเดียว แอพสร้างจากแม่แบบให้ (ค่าเช่า ค่าเน็ต เงินเดือน …) หนี้สินดึงยอดให้เองอยู่แล้ว", fontSize = 14.sp, color = p.ink.copy(alpha = 0.8f))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        vm.edit(onOk = { list: List<Bill> -> vm.toast(if (list.isEmpty()) "ไม่มีแม่แบบใหม่ให้สร้าง" else "สร้างแผน ${list.size} รายการ") }) {
                            generateCycle(cycle).also { syncPlan(cycle) }
                        }
                    }) { Text("สร้างรายการประจำรอบนี้") }
                }
            }
        }

        // หมวดต่างๆ
        SECTION_LABELS.forEach { (section, label) ->
            val list = bySection[section].orEmpty()
            val alwaysShow = section in listOf(PlanBook.INCOME, PlanBook.FIXED, PlanBook.DEBT, PlanBook.TRAVEL, PlanBook.PLANNED)
            if (!alwaysShow && list.isEmpty()) return@forEach
            item(key = "sec_$section") {
                PlanSection(
                    emoji = label.first, title = label.second, income = section == PlanBook.INCOME, rows = list, d = d,
                    onRow = { b ->
                        when {
                            b.status != "paid" && b.section == PlanBook.CLAIM -> claimReceive = b
                            b.status != "paid" && b.section == PlanBook.SALARY -> slip = true
                            else -> open = b
                        }
                    },
                    onAdd = { creating = if (section == PlanBook.INCOME) PlanBook.SALARY.takeIf { list.none { it.section == PlanBook.SALARY } } ?: PlanBook.INCOME else section },
                    note = when (section) {
                        PlanBook.DEBT -> "ยอดดึงจากตัวคำนวณหนี้อัตโนมัติ · แตะเพื่อจ่าย"
                        PlanBook.PLANNED -> if (list.isEmpty()) "วางแผนเองว่าจะใช้อะไรเท่าไหร่ในรอบนี้" else null
                        else -> null
                    },
                ) {
                    when (section) {
                        PlanBook.INCOME -> Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { slip = true }) { Text("🧾 กรอกเงินเดือนจาก Slip") }
                        }
                        PlanBook.TRAVEL -> TravelSummary(travel, openClaims.size, openClaims.sumOf { it.amount?.toDouble() ?: 0.0 }, onWorkDays = { workDays = true }, onClaims = { claims = true })
                        else -> Unit
                    }
                }
            }
        }

        item {
            WrapRow(Modifier.padding(start = 22.dp, end = 12.dp, top = 12.dp)) {
                OutlinedButton(onClick = {
                    vm.edit(onOk = { list: List<Bill> -> vm.toast(if (list.isEmpty()) "ไม่มีแม่แบบใหม่ให้สร้าง" else "สร้างจากแม่แบบ ${list.size} รายการ") }) {
                        generateCycle(cycle).also { syncPlan(cycle) }
                    }
                }) { Text("สร้างจากแม่แบบ") }
                OutlinedButton(onClick = onOpenTemplates) { Text("แม่แบบ (รายการทุกเดือน)") }
            }
        }

        outlook?.let { cycles ->
            item {
                Card {
                    SectionLabel("ภาพรวมรายรอบ (คงเหลือหลังรายจ่ายตามแผน + ค่างวดหนี้)")
                    Spacer(Modifier.height(6.dp))
                    cycles.forEach { o ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(cycleLabel(o.cycle) + if (o.cycle == current) " (รอบนี้)" else "", fontSize = 14.sp)
                                Text(
                                    "รับ ${money(o.income)} · แผน ${money(o.planned)} · หนี้ ${money(o.debtDue)}" + if (!o.fromPlan) " · จากแม่แบบ" else "",
                                    color = DanshaColors.Muted, fontSize = 13.sp,
                                )
                            }
                            Text(money(o.remaining), color = if (o.remaining < 0) DanshaColors.Negative else DanshaColors.Ink, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    creating?.let { s -> BillEditor(d, null, cycle, vm, initialSection = s) { creating = null } }
    open?.let { b -> BillPage(d, d.bills.firstOrNull { it.id == b.id } ?: b, vm) { open = null } }
    if (slip) SlipPage(d, cycle, vm) { slip = false }
    if (workDays) WorkDaysPage(d, cycle, vm) { workDays = false }
    if (claims) ClaimPage(d, vm) { claims = false }
    claimReceive?.let { b -> ClaimReceivePage(d, b, vm) { claimReceive = null } }
}

@Composable
private fun PlanLine(label: String, value: String, bold: Boolean = false, color: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), color = if (bold) DanshaColors.Ink else DanshaColors.Muted, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp)
        Text(value, color = color ?: DanshaColors.Ink, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp)
    }
}

/** การ์ดหมวด: หัว (อีโมจิ + ชื่อ + ยอดรวม) แล้วเช็คลิสต์ + ปุ่มเพิ่ม */
@Composable
private fun PlanSection(
    emoji: String, title: String, income: Boolean, rows: List<Bill>, d: DanshaData,
    onRow: (Bill) -> Unit, onAdd: () -> Unit, note: String?,
    extra: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val total = rows.sumOf { (if (it.status == "paid") it.actual_amount else it.est_amount)?.toDouble() ?: 0.0 }
    val done = rows.filter { it.status == "paid" }.sumOf { it.actual_amount?.toDouble() ?: 0.0 }
    Card(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text(title, Modifier.weight(1f), fontFamily = Hand, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = p.primary)
            Column(horizontalAlignment = Alignment.End) {
                Text((if (income) "+" else "") + money(total), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (income) p.positive else p.ink)
                if (done > 0) Text((if (income) "รับแล้ว " else "จ่ายแล้ว ") + money(done), fontSize = 12.sp, color = p.muted)
            }
        }
        note?.let { Text(it, fontSize = 13.sp, color = p.muted, modifier = Modifier.padding(top = 2.dp)) }
        if (rows.isNotEmpty()) Spacer(Modifier.height(4.dp))
        rows.forEach { b -> key(b.id) { PlanRow(d, b) { onRow(b) } } }
        extra()
        Text("+ เพิ่ม", Modifier.clickable(onClick = onAdd).padding(top = 8.dp, bottom = 2.dp), color = p.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun PlanRow(d: DanshaData, b: Bill, onClick: () -> Unit) {
    val p = LocalPalette.current
    val paid = b.status == "paid"
    val late = !paid && b.due_date.isNotEmpty() && b.due_date < Dates.todayStr()
    val zeroDebt = !paid && b.to_account_id.isNotEmpty() && (b.est_amount?.signum() ?: 0) == 0
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).border(1.8.dp, if (paid || zeroDebt) p.positive else p.ink, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (paid || zeroDebt) Text("✓", color = p.positive, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                b.name, fontSize = 16.sp, color = if (paid) p.muted else p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (paid) TextDecoration.LineThrough else null,
            )
            val acc = d.accounts.firstOrNull { it.id == b.to_account_id }?.name
            Text(
                listOfNotNull(
                    if (paid) "เสร็จแล้ว ${shortThaiDate(b.paid_date)}"
                    else if (zeroDebt) "ไม่มีบิลรอบนี้"
                    else b.due_date.takeIf { it.isNotEmpty() }?.let { (if (b.group == "income") "เข้า " else "ครบ ") + shortThaiDate(it) + if (late) " · เลยกำหนด!" else "" },
                    acc?.let { "→ $it" }.takeIf { b.section != PlanBook.DEBT && b.template_id != PlanBook.AUTO_DEBT },
                    b.note.takeIf { it.isNotEmpty() },
                ).joinToString(" · ").ifEmpty { "ไม่ระบุวัน" },
                color = if (late) p.negative else if (paid) p.positive else p.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatMoney(if (paid) b.actual_amount else b.est_amount),
            color = if (paid) p.positive else p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        )
    }
}

/** รายละเอียดบิล: จ่าย / ยกเลิกจ่าย / แก้ไข / ลบ */
@Composable
private fun BillPage(d: DanshaData, b: Bill, vm: MainViewModel, onClose: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val paid = b.status == "paid"
    var amount by remember(b.id) { mutableStateOf(amountText(b.est_amount)) }
    var accountId by remember(b.id) { mutableStateOf(b.account_id.ifEmpty { Forms.defaultAccount(d, "expense") }) }
    var toAccountId by remember(b.id) { mutableStateOf(b.to_account_id) }
    var date by remember(b.id) { mutableStateOf(Dates.todayStr()) }
    val accounts = Forms.accountChoices(d, b.account_id).map { it.id to it.name }
    val section = SECTION_LABELS[PlanBook.sectionOf(b)]

    FormPage(b.name, onClose, onSave = { editing = true }, saveLabel = "แก้ไข") {
        error?.let { Note(it, DanshaColors.Negative) }
        Note(
            listOfNotNull(
                section?.let { "${it.first} ${it.second}" },
                "รอบ ${cycleLabel(b.pay_cycle)}",
                b.due_date.takeIf { it.isNotEmpty() }?.let { "ครบ ${thaiDate(it)}" },
                "ประมาณ ${formatMoney(b.est_amount)}",
            ).joinToString(" · "),
            DanshaColors.Ink,
        )
        if (b.note.isNotEmpty()) Note(b.note)
        if (paid) {
            Text("จ่ายแล้ว ${formatMoney(b.actual_amount)} วันที่ ${thaiDate(b.paid_date)}", fontWeight = FontWeight.SemiBold, color = DanshaColors.Positive)
            OutlinedButton(onClick = {
                vm.edit<Bill>(ok = "ยกเลิกการจ่ายแล้ว", onError = { error = it }, onOk = { onClose() }) { unpayBill(b.id) }
            }, modifier = Modifier.fillMaxWidth()) { Text("ยกเลิกการจ่าย (ลบรายการที่สร้างไว้)") }
        } else {
            if (b.template_id == PlanBook.AUTO_TRAVEL) {
                Note("ค่าเดินทางคิดจากวันทำงานที่ติ๊กไว้ — จดค่ารถจริงทุกวันจากการ์ด \"ไปทำงานวันนี้\" ในหน้าภาพรวม ไม่ต้องกดจ่ายรายการนี้", DanshaColors.Ink)
            }
            Text(if (b.group == "income") "บันทึกรับเงิน" else "บันทึกจ่าย", fontWeight = FontWeight.SemiBold)
            AmountField("ยอดจริง", amount, { amount = it })
            Picker(if (b.group == "income") "เข้าบัญชี" else "จ่ายจากบัญชี", accounts, accountId, { accountId = it })
            if (b.group != "income") {
                Picker("โอนเข้าบัญชี (จ่ายหนี้)", listOf("" to "— ไม่ใช่การโอน (รายจ่าย) —") + accounts.filter { it.first != accountId }, toAccountId, { toAccountId = it })
            }
            DateField("วันที่", date, { date = it })
            Button(onClick = {
                val amt = Forms.parseAmount(amount) ?: 0.0
                vm.edit<Bill>(ok = if (b.group == "income") "รับเงินแล้ว" else "จ่ายแล้ว", onError = { error = it }, onOk = { onClose() }) {
                    payBill(b.id, amt, accountId, toAccountId, date, null)
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (b.group == "income") "รับเงินแล้ว" else "จ่ายแล้ว") }
            DeleteButton("แผนนี้") {
                vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }, onOk = { onClose() }) { deleteBill(b.id) }
            }
        }
    }
    if (editing) BillEditor(d, b, b.pay_cycle, vm) { editing = false }
}

@Composable
fun BillEditor(d: DanshaData, existing: Bill?, cycle: String, vm: MainViewModel, initialSection: String = PlanBook.FIXED, onClose: () -> Unit) {
    val e = existing
    var section by remember { mutableStateOf(e?.let(::editableSection) ?: initialSection) }
    var name by remember { mutableStateOf(e?.name ?: if (initialSection == PlanBook.SALARY) "เงินเดือน" else "") }
    var est by remember { mutableStateOf(amountText(e?.est_amount)) }
    var actual by remember { mutableStateOf(amountText(e?.actual_amount)) }
    var due by remember { mutableStateOf(e?.due_date.orEmpty()) }
    var accountId by remember { mutableStateOf(e?.account_id ?: Forms.defaultAccount(d, "expense")) }
    var toAccountId by remember { mutableStateOf(e?.to_account_id.orEmpty()) }
    var categoryId by remember { mutableStateOf(e?.category_id.orEmpty()) }
    var note by remember { mutableStateOf(e?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val group = PlanBook.groupOf(section)

    fun save() {
        val row = (e ?: Bill(id = "", pay_cycle = cycle, status = "planned")).copy(
            group = group, section = section, name = name.trim(), est_amount = (Forms.parseAmount(est) ?: 0.0).toDec(),
            actual_amount = if (e?.status == "paid") Forms.parseAmount(actual)?.toDec() else e?.actual_amount,
            due_date = due, account_id = accountId, to_account_id = if (section == PlanBook.DEBT) toAccountId else "",
            category_id = categoryId, note = note,
        )
        vm.edit<Bill>(ok = "บันทึกแล้ว", onError = { error = it }, onOk = { onClose() }) {
            if (e == null) createBill(row) else updateBill(row)
        }
    }

    FormPage(if (e == null) "เพิ่มแผน (รอบ ${cycleLabel(cycle)})" else "แก้ไขแผน", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        BillFields(d, section, { section = it }, name, { name = it }, est, { est = it }, accountId, { accountId = it }, toAccountId, { toAccountId = it }, categoryId, { categoryId = it })
        if (e?.status == "paid") AmountField("ยอดจริงที่จ่ายแล้ว", actual, { actual = it }, supporting = "แก้แล้วรายการที่ผูกไว้จะแก้ตาม")
        DateField(if (group == "income") "วันที่เงินเข้า" else "วันครบกำหนด", due, { due = it }, allowEmpty = true)
        TextInput("โน้ต", note, { note = it })
    }
}

/** ช่องที่ใช้ร่วมกันระหว่างแผนบิลกับแม่แบบ */
@Composable
private fun BillFields(
    d: DanshaData,
    section: String, onSection: (String) -> Unit,
    name: String, onName: (String) -> Unit,
    est: String, onEst: (String) -> Unit,
    accountId: String, onAccount: (String) -> Unit,
    toAccountId: String, onTo: (String) -> Unit,
    categoryId: String, onCategory: (String) -> Unit,
) {
    val group = PlanBook.groupOf(section)
    Text("หมวดในแผน", fontWeight = FontWeight.Medium)
    ChoiceChips(SECTION_CHOICES, section, onSection)
    TextInput(
        "ชื่อ", name, onName,
        placeholder = when (section) {
            PlanBook.FIXED -> "เช่น ค่าเช่าบ้าน / ค่าเน็ต"
            PlanBook.ONCE -> "เช่น คืนเงินแฟน"
            PlanBook.PLANNED -> "เช่น ของขวัญ / กินข้าวนอกบ้าน"
            PlanBook.SALARY -> "เงินเดือน"
            else -> null
        },
    )
    AmountField(
        if (group == "income") "ยอดที่คาดว่าจะได้" else "ยอดประมาณ", est, onEst,
        supporting = if (section == PlanBook.DEBT) "หนี้ที่ผูกตัวคำนวณ: แอพปรับยอดให้เองอัตโนมัติ" else null,
    )
    val accounts = Forms.accountChoices(d, accountId).map { it.id to it.name }
    Picker(if (group == "income") "เข้าบัญชี" else "จ่ายจากบัญชี", accounts, accountId, onAccount)
    if (section == PlanBook.DEBT) {
        val debtAccounts = d.accounts.filter { (isEngineAccount(it) || isSplAccount(it) || it.type == "revolving_credit") && (it.active || it.id == toAccountId) }
        Picker("ค่างวดของบัญชี", listOf("" to "— เลือกบัญชีหนี้ —") + debtAccounts.map { it.id to it.name }, toAccountId, onTo)
    }
    val type = if (group == "income") "income" else "expense"
    val tree = Forms.categoryTree(d, type, keep = setOf(categoryId))
    Picker(
        "หมวดรายการ (ไม่บังคับ)",
        listOf("" to "— ไม่ระบุ —") + tree.flatMap { n -> listOf(n.category.id to n.category.name) + n.children.map { it.id to "   └ ${it.name}" } },
        categoryId, onCategory,
    )
}

// ---------- แม่แบบ ----------

@Composable
fun TemplatesPage(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<BillTemplate?>(null) }
    var creating by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf(false) }
    FormPage("แม่แบบ (รายการทุกเดือน)", onClose, onSave = { creating = true }, saveLabel = "+ เพิ่ม") {
        OutlinedButton(onClick = { bulk = true }, modifier = Modifier.fillMaxWidth()) { Text("📋 เพิ่มหลายรายการ (วางข้อความ)") }
        Note("รายการที่เกิดทุกเดือน แผนบิลสร้างจากที่นี่ · หนี้สินไม่ต้องใส่ แอพดึงยอดให้เอง")
        val grouped = d.billTemplates.groupBy { if (it.section == PlanBook.SALARY) PlanBook.INCOME else PlanBook.sectionOf(it) }
        SECTION_LABELS.forEach { (section, label) ->
            val list = grouped[section].orEmpty().sortedWith(compareBy({ it.due_day ?: 99 }, { it.sort ?: 0 }))
            if (list.isNotEmpty()) {
                Text("${label.first} ${label.second}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                list.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name + if (!t.active) " (ปิด)" else "", color = if (t.active) DanshaColors.Ink else DanshaColors.Muted)
                            Text(
                                listOfNotNull(
                                    t.due_day?.let { "ทุกวันที่ $it" },
                                    d.accounts.firstOrNull { it.id == t.to_account_id }?.let { "ค่างวด ${it.name}" },
                                    t.note.ifEmpty { null },
                                ).joinToString(" · ").ifEmpty { "ไม่ระบุวัน" },
                                color = DanshaColors.Muted, fontSize = 13.sp, maxLines = 2,
                            )
                        }
                        Text(formatMoney(t.est_amount))
                    }
                    HorizontalDivider(color = DanshaColors.Line)
                }
            }
        }
        if (d.billTemplates.isEmpty()) Note("ยังไม่มีแม่แบบ กด \"+ เพิ่ม\" มุมขวาบน")
    }
    if (creating) TemplateEditor(d, null, vm) { creating = false }
    editing?.let { t -> TemplateEditor(d, t, vm) { editing = null } }
    if (bulk) BulkPage(d, vm) { bulk = false }
}

/** วางข้อความหลายบรรทัด → แม่แบบ (รายการทุกเดือน) + รายการครั้งเดียวในรอบที่เลือก */
@Composable
private fun BulkPage(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val startDay = remember(d) { Engine.startDay(Engine.config(d)) }
    val current = remember(d) { Dates.payCycleOf(Dates.today(), startDay) }
    val next = remember(current) { Outlook.shiftCycle(current, 1) }
    var text by remember { mutableStateOf("") }
    var cycle by remember { mutableStateOf(next) }
    var generate by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val lines = remember(text) { PlanBook.parseBulk(text) }
    val items = lines.mapNotNull { it.item }

    FormPage("เพิ่มหลายรายการ", onClose, onSave = {
        if (items.isEmpty()) { error = "ยังไม่มีรายการที่อ่านได้"; return@FormPage }
        vm.edit<Pair<Int, Int>>(ok = "เพิ่มแล้ว ${items.size} รายการ", onError = { error = it }, onOk = { onClose() }) {
            val res = addBulk(items, cycle)
            if (generate) { generateCycle(cycle); syncPlan(cycle) }
            res
        }
    }, saveLabel = "เพิ่ม ${items.size} รายการ", saveEnabled = items.isNotEmpty()) {
        error?.let { Note(it, p.negative) }
        Note("1 บรรทัดต่อ 1 รายการ:  หมวด | ชื่อ | ยอด | วันที่ | โน้ต\nหมวด: เงินเดือน · รายรับ · ประจำ · ครั้งเดียว · วางแผน · เดินทาง (วันที่กับโน้ตไม่ใส่ก็ได้)", p.ink)
        TextInput("วางข้อความที่นี่", text, { text = it }, singleLine = false, placeholder = "ประจำ | ค่าเช่าบ้าน | 2000 | 4")
        if (lines.isNotEmpty()) {
            Text("ตัวอย่างที่อ่านได้", fontWeight = FontWeight.SemiBold, color = p.ink)
            lines.forEach { l ->
                val it = l.item
                if (it == null) Text("✗ ${l.line} — ${l.error}", fontSize = 13.sp, color = p.negative)
                else Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        "${SECTION_CHOICES.firstOrNull { c -> c.first == it.section }?.second ?: it.section} · ${it.name}" +
                            (it.day?.let { dd -> " · วันที่ $dd" } ?: "") + (if (it.oneOff) " · เฉพาะรอบที่เลือก" else " · ทุกเดือน"),
                        Modifier.weight(1f), fontSize = 14.sp, color = p.ink,
                    )
                    Text(formatMoney(it.amount), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
                }
            }
        }
        Text("รอบที่ใส่รายการครั้งเดียว / สร้างแผน", fontWeight = FontWeight.Medium, color = p.ink)
        ChoiceChips(listOf(current to "รอบนี้ (${cycleLabel(current)})", next to "รอบหน้า (${cycleLabel(next)})"), cycle, { cycle = it })
        SwitchRow("สร้างแผนรอบนั้นจากแม่แบบเลย", generate, { generate = it }, sub = "รายการประจำจะขึ้นในแผนบิลทันที (หนี้สินดึงยอดให้เอง)")
    }
}

@Composable
private fun TemplateEditor(d: DanshaData, existing: BillTemplate?, vm: MainViewModel, onClose: () -> Unit) {
    val e = existing
    var section by remember { mutableStateOf(e?.let { if (it.section == PlanBook.SALARY) PlanBook.SALARY else PlanBook.sectionOf(it) } ?: PlanBook.FIXED) }
    var name by remember { mutableStateOf(e?.name.orEmpty()) }
    var est by remember { mutableStateOf(amountText(e?.est_amount)) }
    var dueDay by remember { mutableStateOf(e?.due_day?.toString().orEmpty()) }
    var accountId by remember { mutableStateOf(e?.account_id ?: Forms.defaultAccount(d, "expense")) }
    var toAccountId by remember { mutableStateOf(e?.to_account_id.orEmpty()) }
    var categoryId by remember { mutableStateOf(e?.category_id.orEmpty()) }
    var active by remember { mutableStateOf(e?.active ?: true) }
    var sort by remember { mutableStateOf(e?.sort?.toString() ?: ((d.billTemplates.mapNotNull { it.sort }.maxOrNull() ?: 0) + 1).toString()) }
    var note by remember { mutableStateOf(e?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val day = dueDay.toIntOrNull()?.coerceIn(1, 31)
        val row = (e ?: BillTemplate(id = "")).copy(
            group = PlanBook.groupOf(section), section = section, name = name.trim(), est_amount = (Forms.parseAmount(est) ?: 0.0).toDec(), due_day = day,
            account_id = accountId, to_account_id = if (section == PlanBook.DEBT) toAccountId else "", category_id = categoryId,
            active = active, sort = sort.toIntOrNull(), note = note,
        )
        vm.edit<BillTemplate>(ok = "บันทึกแล้ว", onError = { error = it }, onOk = { onClose() }) {
            if (e == null) createBillTemplate(row) else updateBillTemplate(row)
        }
    }

    FormPage(if (e == null) "เพิ่มแม่แบบ" else "แก้ไขแม่แบบ", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        BillFields(d, section, { section = it }, name, { name = it }, est, { est = it }, accountId, { accountId = it }, toAccountId, { toAccountId = it }, categoryId, { categoryId = it })
        AmountField(
            "ครบกำหนดทุกวันที่ (1–31)", dueDay, { dueDay = it.filter(Char::isDigit).take(2) },
            supporting = "วันที่ ≥ ${Engine.startDay(Engine.config(d))} อยู่เดือนแรกของรอบ ไม่งั้นอยู่เดือนถัดไป · เกินจำนวนวันใช้วันสุดท้ายของเดือน",
        )
        TextInput("โน้ต", note, { note = it }, placeholder = "เช่น ยอดไม่แน่นอน รู้ยอดวันที่ 21")
        AmountField("ลำดับ", sort, { sort = it.filter(Char::isDigit) })
        SwitchRow("ใช้งาน", active, { active = it })
        if (e != null) DeleteButton("แม่แบบนี้") {
            vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }, onOk = { onClose() }) { deleteBillTemplate(e.id) }
        }
    }
}
