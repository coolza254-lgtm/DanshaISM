package ism.dansha.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import ism.dansha.core.Bill
import ism.dansha.core.BillTemplate
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.Outlook
import ism.dansha.core.engine.Plan
import ism.dansha.core.engine.isEngineAccount
import ism.dansha.core.engine.isSplAccount
import ism.dansha.core.engine.toDec
import java.math.BigDecimal

private val GROUPS = listOf("income" to "รายรับที่คาด", "A" to "A · บิลประจำ + ค่างวดหนี้", "B" to "B · รายจ่ายยืดหยุ่น")
private fun money(v: Double?) = formatMoney(v?.let { BigDecimal.valueOf(it) })

@Composable
fun PlanScreen(d: DanshaData, c: MainViewModel.Computed?, vm: MainViewModel, onOpenTemplates: () -> Unit) {
    val startDay = remember(d) { Engine.startDay(Engine.config(d)) }
    val current = remember(d) { Dates.payCycleOf(Dates.today(), startDay) }
    var cycle by rememberSaveable { mutableStateOf(current) }
    var open by remember { mutableStateOf<Bill?>(null) }
    var creating by remember { mutableStateOf(false) }
    val cashNow = c?.overview?.cashNow ?: 0.0
    val rows = d.bills.filter { it.pay_cycle == cycle }.sortedWith(compareBy({ it.sort ?: Int.MAX_VALUE }, { it.due_date.ifEmpty { "9999" } }, { it.name }))
    val summary = Plan.summary(cycle, d.bills, cashNow)
    val outlook = c?.outlook

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("แผนบิล") }
        item {
            Row(Modifier.padding(start = 28.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { cycle = Outlook.shiftCycle(cycle, -1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "รอบก่อน") }
                val (rs, re) = Dates.payCycleRange(cycle, startDay)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("รอบ ${cycleLabel(cycle)}${if (cycle == current) " (รอบนี้)" else ""}", fontWeight = FontWeight.SemiBold)
                    Text("${thaiDate(rs.toString())} – ${thaiDate(re.toString())}", color = DanshaColors.Muted, fontSize = 13.sp)
                }
                IconButton(onClick = { cycle = Outlook.shiftCycle(cycle, 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "รอบถัดไป") }
            }
        }
        item {
            Card {
                if (cycle == current) {
                    PlanLine("เงินที่มีตอนนี้", money(summary.cashNow))
                    PlanLine("+ รายรับที่ยังไม่เข้า", money(summary.incomePending))
                    PlanLine("− A ที่ยังไม่จ่าย", money(summary.aPending))
                    PlanLine("− B ที่ยังไม่จ่าย", money(summary.bPending))
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = DanshaColors.Line)
                    PlanLine("คงเหลือคาดการณ์", money(summary.projected), bold = true, negative = summary.projected < 0)
                } else {
                    PlanLine("รายรับตามแผน", money(summary.incomePending + summary.incomeReceived))
                    PlanLine("A", money(summary.aPending + summary.aPaid))
                    PlanLine("B", money(summary.bPending + summary.bPaid))
                    Note("รอบอื่นดูยอดคงเหลือได้ใน \"ภาพรวมรายรอบ\" ด้านล่าง")
                }
            }
        }
        item {
            Row(Modifier.padding(start = 36.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.edit(onOk = { list: List<Bill> -> vm.toast(if (list.isEmpty()) "ไม่มีแม่แบบใหม่ให้สร้าง" else "สร้างจากแม่แบบ ${list.size} รายการ") }) { generateCycle(cycle) }
                }) { Text("สร้างจากแม่แบบ") }
                OutlinedButton(onClick = { creating = true }) { Text("+ เพิ่ม") }
                OutlinedButton(onClick = onOpenTemplates) { Text("แม่แบบ") }
            }
        }
        if (rows.isEmpty()) item { Text("ยังไม่มีแผนในรอบนี้ — กด \"สร้างจากแม่แบบ\" หรือ \"+ เพิ่ม\"", Modifier.padding(start = 36.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), color = DanshaColors.Muted) }
        GROUPS.forEach { (g, label) ->
            val list = rows.filter { it.group == g }
            if (list.isNotEmpty()) {
                item(key = "g_$g") {
                    Text(label, Modifier.padding(start = 36.dp, top = 16.dp, bottom = 4.dp).background(LocalPalette.current.highlight).padding(horizontal = 6.dp), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                list.forEach { b -> item(key = b.id) { BillRow(d, b) { open = b } } }
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
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (creating) BillEditor(d, null, cycle, vm) { creating = false }
    open?.let { b -> BillPage(d, d.bills.firstOrNull { it.id == b.id } ?: b, vm) { open = null } }
}

@Composable
private fun PlanLine(label: String, value: String, bold: Boolean = false, negative: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), color = if (bold) DanshaColors.Ink else DanshaColors.Muted, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        Text(value, color = if (negative) DanshaColors.Negative else DanshaColors.Ink, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun BillRow(d: DanshaData, b: Bill, onClick: () -> Unit) {
    val p = LocalPalette.current
    val paid = b.status == "paid"
    val late = !paid && b.due_date.isNotEmpty() && b.due_date < Dates.todayStr()
    Row(
        Modifier.padding(start = 36.dp, end = 16.dp, top = 4.dp, bottom = 4.dp).fillMaxWidth()
            .paperCard(p, shadow = false).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ช่องติ๊กแบบเขียนมือ: จ่ายแล้ว = ติ๊กเขียว
        Box(
            Modifier.size(24.dp).border(1.8.dp, if (paid) p.positive else p.ink, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (paid) Text("✓", color = p.positive, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                b.name, fontSize = 16.sp, color = if (paid) p.muted else p.ink,
                textDecoration = if (paid) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
            val acc = d.accounts.firstOrNull { it.id == b.to_account_id }?.name
            Text(
                listOfNotNull(
                    if (paid) "จ่ายแล้ว ${thaiDate(b.paid_date)}" else b.due_date.takeIf { it.isNotEmpty() }?.let { "ครบ ${thaiDate(it)}" + if (late) " · เลยกำหนด" else "" },
                    acc?.let { "→ $it" },
                ).joinToString(" · ").ifEmpty { "ไม่ระบุวัน" },
                color = if (late) p.negative else if (paid) p.positive else p.muted, fontSize = 14.sp,
            )
        }
        Text(
            formatMoney(if (paid) b.actual_amount else b.est_amount),
            color = if (paid) p.positive else p.ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
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

    FormPage(b.name, onClose, onSave = { editing = true }, saveLabel = "แก้ไข") {
        error?.let { Note(it, DanshaColors.Negative) }
        Note(
            listOfNotNull(
                GROUPS.firstOrNull { it.first == b.group }?.second,
                "รอบ ${cycleLabel(b.pay_cycle)}",
                b.due_date.takeIf { it.isNotEmpty() }?.let { "ครบ ${thaiDate(it)}" },
                "ประมาณ ${formatMoney(b.est_amount)}",
            ).joinToString(" · "),
            DanshaColors.Ink,
        )
        if (b.note.isNotEmpty()) Note(b.note)
        if (paid) {
            Text("จ่ายแล้ว ${formatMoney(b.actual_amount)} วันที่ ${thaiDate(b.paid_date)}", fontWeight = FontWeight.SemiBold, color = DanshaColors.Positive)
            Note("แก้ยอดจริงได้ที่ปุ่ม \"แก้ไข\" (รายการที่ผูกไว้จะแก้ตาม)")
            OutlinedButton(onClick = {
                vm.edit<Bill>(ok = "ยกเลิกการจ่ายแล้ว", onError = { error = it }, onOk = { onClose() }) { unpayBill(b.id) }
            }, modifier = Modifier.fillMaxWidth()) { Text("ยกเลิกการจ่าย (ลบรายการที่สร้างไว้)") }
        } else {
            Text(if (b.group == "income") "บันทึกรับเงิน" else "บันทึกจ่าย", fontWeight = FontWeight.SemiBold)
            AmountField("ยอดจริง", amount, { amount = it })
            Picker(if (b.group == "income") "เข้าบัญชี" else "จ่ายจากบัญชี", accounts, accountId, { accountId = it })
            if (b.group != "income") {
                Picker("โอนเข้าบัญชี (จ่ายหนี้)", listOf("" to "— ไม่ใช่การโอน (รายจ่าย) —") + accounts.filter { it.first != accountId }, toAccountId, { toAccountId = it })
            }
            DateField("วันที่", date, { date = it })
            Button(onClick = {
                val amt = Forms.parseAmount(amount) ?: 0.0
                vm.edit<Bill>(ok = "บันทึกแล้ว", onError = { error = it }, onOk = { onClose() }) {
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
fun BillEditor(d: DanshaData, existing: Bill?, cycle: String, vm: MainViewModel, onClose: () -> Unit) {
    val e = existing
    var group by remember { mutableStateOf(e?.group ?: "A") }
    var name by remember { mutableStateOf(e?.name.orEmpty()) }
    var est by remember { mutableStateOf(amountText(e?.est_amount)) }
    var actual by remember { mutableStateOf(amountText(e?.actual_amount)) }
    var due by remember { mutableStateOf(e?.due_date.orEmpty()) }
    var accountId by remember { mutableStateOf(e?.account_id ?: Forms.defaultAccount(d, "expense")) }
    var toAccountId by remember { mutableStateOf(e?.to_account_id.orEmpty()) }
    var categoryId by remember { mutableStateOf(e?.category_id.orEmpty()) }
    var note by remember { mutableStateOf(e?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val row = (e ?: Bill(id = "", pay_cycle = cycle, status = "planned")).copy(
            group = group, name = name.trim(), est_amount = (Forms.parseAmount(est) ?: 0.0).toDec(),
            actual_amount = if (e?.status == "paid") Forms.parseAmount(actual)?.toDec() else e?.actual_amount,
            due_date = due, account_id = accountId, to_account_id = if (group == "income") "" else toAccountId,
            category_id = categoryId, note = note,
        )
        vm.edit<Bill>(ok = "บันทึกแล้ว", onError = { error = it }, onOk = { onClose() }) {
            if (e == null) createBill(row) else updateBill(row)
        }
    }

    FormPage(if (e == null) "เพิ่มแผน (รอบ ${cycleLabel(cycle)})" else "แก้ไขแผน", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        BillFields(d, group, { group = it }, name, { name = it }, est, { est = it }, accountId, { accountId = it }, toAccountId, { toAccountId = it }, categoryId, { categoryId = it })
        if (e?.status == "paid") AmountField("ยอดจริงที่จ่ายแล้ว", actual, { actual = it }, supporting = "แก้แล้วรายการที่ผูกไว้จะแก้ตาม")
        DateField("วันครบกำหนด", due, { due = it }, allowEmpty = true)
        TextInput("โน้ต", note, { note = it })
    }
}

/** ช่องที่ใช้ร่วมกันระหว่างแผนบิลกับแม่แบบ */
@Composable
private fun BillFields(
    d: DanshaData,
    group: String, onGroup: (String) -> Unit,
    name: String, onName: (String) -> Unit,
    est: String, onEst: (String) -> Unit,
    accountId: String, onAccount: (String) -> Unit,
    toAccountId: String, onTo: (String) -> Unit,
    categoryId: String, onCategory: (String) -> Unit,
) {
    ChoiceChips(listOf("income" to "รายรับ", "A" to "A บิลประจำ/หนี้", "B" to "B ยืดหยุ่น"), group, onGroup)
    TextInput("ชื่อ", name, onName)
    AmountField("ยอดประมาณ", est, onEst)
    val accounts = Forms.accountChoices(d, accountId).map { it.id to it.name }
    Picker(if (group == "income") "เข้าบัญชี" else "จ่ายจากบัญชี", accounts, accountId, onAccount)
    if (group != "income") {
        val debtAccounts = d.accounts.filter { (isEngineAccount(it) || isSplAccount(it) || it.type == "revolving_credit") && (it.active || it.id == toAccountId) }
        Picker("ค่างวดของบัญชีวงเงิน (ถ้ามี)", listOf("" to "— ไม่ใช่ค่างวดหนี้ —") + debtAccounts.map { it.id to it.name }, toAccountId, onTo)
        if (toAccountId.isNotEmpty()) Note("ตอนสร้างจากแม่แบบ ยอดและวันครบกำหนดจะใช้บิลที่ตัวคำนวณหนี้คำนวณได้")
    }
    val type = if (group == "income") "income" else "expense"
    val tree = Forms.categoryTree(d, type, keep = setOf(categoryId))
    Picker("หมวด", listOf("" to "— ไม่ระบุ —") + tree.flatMap { n -> listOf(n.category.id to "${n.category.icon} ${n.category.name}") + n.children.map { it.id to "   └ ${it.name}" } }, categoryId, onCategory)
}

// ---------- แม่แบบ ----------

@Composable
fun TemplatesPage(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<BillTemplate?>(null) }
    var creating by remember { mutableStateOf(false) }
    FormPage("แม่แบบแผนบิล", onClose, onSave = { creating = true }, saveLabel = "+ เพิ่ม") {
        Note("แม่แบบ = รายการที่เกิดทุกรอบ กด \"สร้างจากแม่แบบ\" ในหน้าแผนบิลเพื่อสร้างรอบใหม่ (กดซ้ำได้ ไม่สร้างซ้ำ)")
        GROUPS.forEach { (g, label) ->
            val list = d.billTemplates.filter { it.group == g }.sortedBy { it.sort ?: 0 }
            if (list.isNotEmpty()) {
                Text(label, fontWeight = FontWeight.SemiBold)
                list.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name + if (!t.active) " (ปิด)" else "", color = if (t.active) DanshaColors.Ink else DanshaColors.Muted)
                            Text(
                                listOfNotNull(
                                    t.due_day?.let { "ทุกวันที่ $it" },
                                    d.accounts.firstOrNull { it.id == t.to_account_id }?.let { "ค่างวด ${it.name}" },
                                ).joinToString(" · ").ifEmpty { "ไม่ระบุวัน" },
                                color = DanshaColors.Muted, fontSize = 13.sp,
                            )
                        }
                        Text(formatMoney(t.est_amount))
                    }
                    HorizontalDivider(color = DanshaColors.Line)
                }
            }
        }
        if (d.billTemplates.isEmpty()) Note("ยังไม่มีแม่แบบ")
    }
    if (creating) TemplateEditor(d, null, vm) { creating = false }
    editing?.let { t -> TemplateEditor(d, t, vm) { editing = null } }
}

@Composable
private fun TemplateEditor(d: DanshaData, existing: BillTemplate?, vm: MainViewModel, onClose: () -> Unit) {
    val e = existing
    var group by remember { mutableStateOf(e?.group ?: "A") }
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
            group = group, name = name.trim(), est_amount = (Forms.parseAmount(est) ?: 0.0).toDec(), due_day = day,
            account_id = accountId, to_account_id = if (group == "income") "" else toAccountId, category_id = categoryId,
            active = active, sort = sort.toIntOrNull(), note = note,
        )
        vm.edit<BillTemplate>(ok = "บันทึกแล้ว", onError = { error = it }, onOk = { onClose() }) {
            if (e == null) createBillTemplate(row) else updateBillTemplate(row)
        }
    }

    FormPage(if (e == null) "เพิ่มแม่แบบ" else "แก้ไขแม่แบบ", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        BillFields(d, group, { group = it }, name, { name = it }, est, { est = it }, accountId, { accountId = it }, toAccountId, { toAccountId = it }, categoryId, { categoryId = it })
        AmountField("ครบกำหนดทุกวันที่ (1–31)", dueDay, { dueDay = it.filter(Char::isDigit).take(2) },
            supporting = "วันที่ ≥ ${Engine.startDay(Engine.config(d))} อยู่เดือนแรกของรอบ ไม่งั้นอยู่เดือนถัดไป · เกินจำนวนวันใช้วันสุดท้ายของเดือน")
        AmountField("ลำดับ", sort, { sort = it.filter(Char::isDigit) })
        SwitchRow("ใช้งาน", active, { active = it })
        TextInput("โน้ต", note, { note = it })
        if (e != null) DeleteButton("แม่แบบนี้") {
            vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }, onOk = { onClose() }) { deleteBillTemplate(e.id) }
        }
    }
}
