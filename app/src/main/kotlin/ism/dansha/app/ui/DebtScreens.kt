package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.Account
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Debt
import ism.dansha.core.Transaction
import ism.dansha.core.engine.AscendState
import ism.dansha.core.engine.DEBT_KINDS
import ism.dansha.core.engine.DebtEngine
import ism.dansha.core.engine.DebtState
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.SplState
import ism.dansha.core.engine.isEngineAccount
import ism.dansha.core.engine.num
import ism.dansha.core.engine.toDec
import java.math.BigDecimal

private fun money(v: Double?) = formatMoney(v?.let { BigDecimal.valueOf(it) })

private val KIND_LABELS = mapOf(
    "cash" to "เบิกเงินสด", "installment" to "ผ่อนตอนซื้อ", "convert" to "แปลงยอดเป็นผ่อน", "fullpay_snapshot" to "ยอดเต็มจำนวนยกมา",
)

/** หน้าที่เปิดซ้อนจากแท็บหนี้ */
private sealed interface DebtAction {
    data class Pay(val accountId: String) : DebtAction
    data class Quote(val accountId: String) : DebtAction
    data class Draw(val accountId: String) : DebtAction
    data class EditDebt(val accountId: String, val debtId: String?) : DebtAction
}

@Composable
fun DebtScreen(d: DanshaData, c: MainViewModel.Computed?, vm: MainViewModel) {
    var action by remember { mutableStateOf<DebtAction?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("หนี้") }
        if (c == null) {
            item { Text("ยังไม่มีข้อมูล", Modifier.padding(20.dp), color = DanshaColors.Muted) }
            return@LazyColumn
        }
        val states = c.debts
        val ascend = states.values.filterIsInstance<AscendState>()
        item {
            Card {
                val total = states.values.sumOf { it.outstanding }
                val interest = ascend.sumOf { it.accruedInterest }
                val payoff = ascend.sumOf { it.payoffToday } + states.values.filterIsInstance<SplState>().sumOf { it.outstanding }
                KeyValue("หนี้รวม (ตัวคำนวณ)", money(total), DanshaColors.Negative, bold = true)
                KeyValue("ดอกสะสมถึงวันนี้", money(interest))
                KeyValue("ปิดทั้งหมดวันนี้", money(payoff))
                if (ascend.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "กลยุทธ์: โปะ PayNext ธรรมดาให้หมดก่อน (ผ่อนสั้น ดอกต่องวดแรงกว่า) ส่วน Extra จ่ายตามบิลพอ",
                        Modifier.background(DanshaColors.Surface, RoundedCornerShape(8.dp)).padding(10.dp),
                        fontSize = 12.sp,
                    )
                }
            }
        }
        val accounts = d.accounts.associateBy { it.id }
        states.values.forEach { st ->
            val acc = accounts[st.accountId] ?: return@forEach
            item(key = st.accountId) { DebtAccountCard(d, acc, st) { action = it } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    val close = { action = null }
    when (val a = action) {
        is DebtAction.Pay -> PayDebtPage(d, c, a.accountId, vm, close)
        is DebtAction.Quote -> QuotePage(d, a.accountId, close)
        is DebtAction.Draw -> CashDrawPage(d, a.accountId, vm, close)
        is DebtAction.EditDebt -> DebtEditor(d, a.accountId, d.debts.firstOrNull { it.id == a.debtId }, vm, close)
        null -> Unit
    }
}

@Composable
private fun DebtAccountCard(d: DanshaData, acc: Account, st: DebtState, onAction: (DebtAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card {
        Text(acc.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(4.dp))
        KeyValue("ใช้ไป / วงเงิน", "${money(st.outstanding)} / ${formatMoney(acc.credit_limit)}", bold = true)
        if (st is AscendState) {
            KeyValue("ดอกสะสมถึงวันนี้", money(st.accruedInterest))
            KeyValue("ดอกต่อวันประมาณ", money(st.dailyInterest))
            KeyValue("ปิดหนี้วันนี้", money(st.payoffToday))
            if (st.fees > 0) KeyValue("ค่าธรรมเนียมค้าง", money(st.fees), DanshaColors.Negative)
        }
        st.currentBill?.let { b ->
            KeyValue(
                if (b.overdue == true || b.due < Dates.todayStr()) "บิลค้าง (เลยกำหนด ${thaiDate(b.due)})" else "บิลปัจจุบัน ครบ ${thaiDate(b.due)}",
                money(b.amount), if (b.overdue == true) DanshaColors.Negative else DanshaColors.Ink, bold = true,
            )
        }
        st.nextBill?.let { b ->
            KeyValue("บิลถัดไป ออก ${thaiDate(b.date)} ครบ ${thaiDate(b.due)}", money(b.totalDue) + (b.interest?.let { " (ดอก ${money(it)})" } ?: ""))
        }
        if (st is AscendState && st.cycleSpend.total > 0) {
            KeyValue("ใช้ในรอบบิล ${thaiDate(st.cycleSpend.start)} – ${thaiDate(st.cycleSpend.end)}", money(st.cycleSpend.total))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAction(DebtAction.Pay(acc.id)) }) { Text("จ่าย/โปะ") }
            if (st is AscendState) {
                OutlinedButton(onClick = { onAction(DebtAction.Quote(acc.id)) }) { Text("ยอดปิด") }
                OutlinedButton(onClick = { onAction(DebtAction.Draw(acc.id)) }) { Text("เบิกเงินสด") }
                OutlinedButton(onClick = { onAction(DebtAction.EditDebt(acc.id, null)) }) { Text("+ หนี้ย่อย") }
            }
        }
        TextButton(onClick = { open = !open }) { Text(if (open) "ซ่อนรายละเอียด" else "ดูหนี้ย่อย / บิลล่วงหน้า") }
        if (open) {
            when (st) {
                is AscendState -> {
                    st.loans.forEach { l ->
                        val editable = d.debts.any { it.id == l.id }
                        HorizontalDivider(color = DanshaColors.Line)
                        Column(
                            Modifier.fillMaxWidth().let { m -> if (editable) m.clickable { onAction(DebtAction.EditDebt(acc.id, l.id)) } else m }.padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(l.desc.ifEmpty { KIND_LABELS[l.kind] ?: l.kind } + if (l.status == "closed") " (ปิดแล้ว)" else "", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("กู้ ${thaiDate(l.start)} · ${money(l.principal)} · ${l.tenor} งวด งวดละ ${money(l.installment)}", color = DanshaColors.Muted, fontSize = 12.sp)
                            Text("จ่ายแล้ว ${l.paidPeriods} งวด เหลือ ${l.periodsLeft} · คงเหลือ ${money(l.remaining)} · ดอกค้าง ${money(l.accruedInterest)}", fontSize = 12.sp)
                            st.schedule?.get(l.id)?.take(3)?.let { rows ->
                                Text(rows.joinToString("  ") { "${thaiDate(it.due)} ${money(it.O)}" }, color = DanshaColors.Muted, fontSize = 11.sp)
                            }
                        }
                    }
                    if (st.pendingFull.isNotEmpty()) {
                        HorizontalDivider(color = DanshaColors.Line)
                        Text("ยอดเต็มจำนวนค้าง", Modifier.padding(top = 6.dp), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        st.pendingFull.forEach { u -> KeyValue("${thaiDate(u.date)} ${u.desc}", money(u.remaining)) }
                    }
                    st.bills?.take(6)?.let { bills ->
                        HorizontalDivider(color = DanshaColors.Line)
                        Text("บิลล่วงหน้า (สมมติจ่ายตรงบิลทุกงวด)", Modifier.padding(top = 6.dp), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        bills.forEach { b -> KeyValue("ครบ ${thaiDate(b.due)}", "${money(b.totalDue)} (ดอก ${money(b.interest)})") }
                    }
                    if (st.payments.isNotEmpty()) {
                        HorizontalDivider(color = DanshaColors.Line)
                        Text("การชำระล่าสุด", Modifier.padding(top = 6.dp), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        st.payments.take(5).forEach { p -> KeyValue("${thaiDate(p.date)} ดอก ${money(p.interest)} ต้น ${money(p.principal)}", money(p.amount)) }
                    }
                }
                is SplState -> {
                    st.installments.filter { it.left > 0.004 }.forEach { i ->
                        KeyValue("${thaiDate(i.due)} ${i.item} (${i.k}/${i.n})", money(i.left))
                    }
                }
            }
        }
    }
}

// ---------- จ่าย / โปะ ----------

@Composable
private fun PayDebtPage(d: DanshaData, c: MainViewModel.Computed?, accountId: String, vm: MainViewModel, onClose: () -> Unit) {
    val acc = d.accounts.first { it.id == accountId }
    val st = c?.debts?.get(accountId)
    val suggested = st?.currentBill?.amount ?: st?.nextBill?.totalDue
    var amount by remember { mutableStateOf(amountText(suggested)) }
    val payFrom = Forms.accountChoices(d).filter { it.type != "revolving_credit" }
    var from by remember { mutableStateOf(payFrom.firstOrNull { it.type == "bank" }?.id ?: payFrom.firstOrNull()?.id.orEmpty()) }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    var note by remember { mutableStateOf("จ่าย ${acc.name}") }
    var error by remember { mutableStateOf<String?>(null) }
    val amt = Forms.parseAmount(amount)
    val quote = if (isEngineAccount(acc) && amt != null && amt > 0) runCatching { Engine.debtQuote(d, accountId, date, amt) }.getOrNull() else null

    fun save() {
        val src = d.accounts.firstOrNull { it.id == from }
        val row = Transaction(
            id = "", date = date, type = "transfer", account_id = from, to_account_id = accountId,
            amount = amt?.toDec(), currency = src?.currency.orEmpty(), note = note, source = "manual",
        )
        vm.edit<Transaction>(ok = "บันทึกการจ่ายแล้ว", onError = { error = it }, onOk = { onClose() }) { createTransaction(row) }
    }

    FormPage("จ่าย ${acc.name}", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        AmountField("จำนวนเงิน", amount, { amount = it }, supporting = suggested?.let { "ยอดบิล ${money(it)}" })
        Picker("จ่ายจากบัญชี", payFrom.map { it.id to it.name }, from, { from = it })
        DateField("วันที่จ่าย", date, { date = it })
        TextInput("โน้ต", note, { note = it })
        quote?.allocation?.let { a ->
            Card {
                SectionLabel("ตัวอย่างการตัดชำระ (ตามกติกา Ascend)")
                KeyValue("ค่าธรรมเนียม", money(a.fee))
                KeyValue("ดอกเบี้ย", money(a.interest))
                KeyValue("เงินต้น", money(a.principal))
                if ((a.unapplied ?: 0.0) > 0) KeyValue("เกินยอดหนี้", money(a.unapplied), DanshaColors.Negative)
                quote.after?.let { KeyValue("คงเหลือหลังจ่าย", money(it.outstanding), bold = true) }
            }
        }
        Note("บันทึกเป็นรายการโอนเข้าบัญชีวงเงิน ตัวคำนวณจะตัดดอก/ต้นให้อัตโนมัติ")
    }
}

// ---------- ยอดปิด ----------

@Composable
private fun QuotePage(d: DanshaData, accountId: String, onClose: () -> Unit) {
    val acc = d.accounts.first { it.id == accountId }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    var amount by remember { mutableStateOf("") }
    val q = runCatching { Engine.debtQuote(d, accountId, date, Forms.parseAmount(amount)) }.getOrNull()
    FormPage("ยอดปิด ${acc.name}", onClose, onSave = null) {
        DateField("ถ้าจ่ายวันที่", date, { date = it })
        if (q != null) {
            Card {
                KeyValue("เงินต้นคงเหลือ", money(q.principal))
                KeyValue("ดอกเบี้ยถึงวันนั้น", money(q.interest))
                if (q.fees > 0) KeyValue("ค่าธรรมเนียม", money(q.fees))
                KeyValue("ปิดหนี้ทั้งหมด", money(q.payoff), bold = true)
            }
        }
        AmountField("ลองจ่าย (บาท)", amount, { amount = it }, supporting = "ดูว่าเงินจะไปตัดดอก/ต้นเท่าไหร่")
        q?.allocation?.let { a ->
            Card {
                KeyValue("ตัดดอกเบี้ย", money(a.interest))
                KeyValue("ตัดเงินต้น", money(a.principal))
                if ((a.fee ?: 0.0) > 0) KeyValue("ค่าธรรมเนียม", money(a.fee))
                q.after?.let { KeyValue("คงเหลือหลังจ่าย", money(it.outstanding), bold = true) }
            }
        }
        Note("จ่ายก่อนกำหนด = ดอกคิดถึงวันที่จ่ายจริง ยิ่งจ่ายเร็วยิ่งประหยัด")
    }
}

// ---------- เบิกเงินสด ----------

@Composable
private fun CashDrawPage(d: DanshaData, accountId: String, vm: MainViewModel, onClose: () -> Unit) {
    val acc = d.accounts.first { it.id == accountId }
    val min = (acc.tenor_min ?: 0).let { if (it == 0) 2 else it }
    val max = (acc.tenor_max ?: 0).let { if (it == 0) 24 else it }
    var amount by remember { mutableStateOf("") }
    var tenor by remember { mutableStateOf(max.toString()) }
    val targets = Forms.accountChoices(d).filter { it.type != "revolving_credit" }
    var to by remember { mutableStateOf(targets.firstOrNull { it.type == "bank" }?.id.orEmpty()) }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val amt = Forms.parseAmount(amount)
    val n = tenor.toIntOrNull()
    val rate = acc.annual_rate.num().let { if (it == 0.0) 25.0 else it } / 100
    val inst = if (amt != null && amt > 0 && n != null && n > 0) DebtEngine.installmentOf(amt, n, rate) else null

    fun save() {
        val desc = note.ifEmpty { "เบิกเงินสด ${formatMoney(amt?.let { BigDecimal.valueOf(it) })} (${n ?: 0} งวด)" }
        vm.edit<Any>(ok = "บันทึกการเบิกเงินสดแล้ว", onError = { error = it }, onOk = { onClose() }) {
            cashDraw(accountId, date, amt ?: 0.0, n ?: 0, to, desc)
        }
    }

    FormPage("เบิกเงินสด ${acc.name}", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        AmountField("จำนวนเงิน", amount, { amount = it })
        AmountField("จำนวนงวด ($min–$max)", tenor, { tenor = it.filter(Char::isDigit) })
        inst?.let { Note("ค่างวดประมาณ ${money(it)} × $n งวด (ดอก ${jsPct(rate)}%/ปี) · งวดสุดท้าย = เงินต้นที่เหลือ", DanshaColors.Ink) }
        Picker("โอนเข้าบัญชี", listOf("" to "— ไม่บันทึกการโอน —") + targets.map { it.id to it.name }, to, { to = it })
        DateField("วันที่", date, { date = it })
        TextInput("คำอธิบาย", note, { note = it }, placeholder = "เบิกเงินสด … (… งวด)")
        Note("สร้างหนี้ย่อย + รายการโอนที่ผูกกัน ลบรายการโอนแล้วหนี้ย่อยจะถูกลบด้วย")
    }
}

private fun jsPct(rate: Double): String = BigDecimal.valueOf(rate * 100).stripTrailingZeros().toPlainString()

// ---------- หนี้ย่อย ----------

@Composable
private fun DebtEditor(d: DanshaData, accountId: String, existing: Debt?, vm: MainViewModel, onClose: () -> Unit) {
    val e = existing
    val acc = d.accounts.first { it.id == accountId }
    var kind by remember { mutableStateOf(e?.kind ?: "cash") }
    var date by remember { mutableStateOf(e?.txn_date ?: Dates.todayStr()) }
    var desc by remember { mutableStateOf(e?.description.orEmpty()) }
    var principal by remember { mutableStateOf(amountText(e?.principal)) }
    var tenor by remember { mutableStateOf(e?.tenor?.toString().orEmpty()) }
    var rate by remember { mutableStateOf(amountText(e?.annual_rate)) }
    var installment by remember { mutableStateOf(amountText(e?.installment)) }
    var hasSnap by remember { mutableStateOf(e?.snap_date?.isNotEmpty() == true) }
    var snapDate by remember { mutableStateOf(e?.snap_date?.ifEmpty { null } ?: acc.debt_since.ifEmpty { Dates.todayStr() }) }
    var snapRemaining by remember { mutableStateOf(amountText(e?.snap_remaining)) }
    var snapPaid by remember { mutableStateOf(e?.snap_paid_periods?.toString().orEmpty()) }
    var snapInterest by remember { mutableStateOf(e?.snap_last_interest_date.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val full = kind == "fullpay_snapshot"

    fun save() {
        val row = (e ?: Debt(id = "", account_id = accountId, status = "active")).copy(
            kind = kind, txn_date = date, description = desc, principal = Forms.parseAmount(principal)?.toDec(),
            tenor = if (full) null else tenor.toIntOrNull(),
            annual_rate = if (full) null else Forms.parseAmount(rate)?.toDec(),
            installment = if (full) null else Forms.parseAmount(installment)?.toDec(),
            snap_date = if (hasSnap && !full) snapDate else "",
            snap_remaining = if (hasSnap && !full) Forms.parseAmount(snapRemaining)?.toDec() else null,
            snap_paid_periods = if (hasSnap && !full) snapPaid.toIntOrNull() else null,
            snap_last_interest_date = if (hasSnap && !full) snapInterest else "",
        )
        vm.edit<Debt>(ok = "บันทึกแล้ว", onError = { error = it }, onOk = { onClose() }) {
            if (e == null) createDebt(row) else updateDebt(row)
        }
    }

    FormPage(if (e == null) "เพิ่มหนี้ย่อย · ${acc.name}" else "แก้ไขหนี้ย่อย", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        ChoiceChips(DEBT_KINDS.map { it to (KIND_LABELS[it] ?: it) }, kind, { kind = it })
        TextInput("คำอธิบาย", desc, { desc = it })
        DateField(if (full) "วันที่" else "วันที่กู้/ทำรายการ", date, { date = it })
        AmountField(if (full) "ยอดเต็มจำนวน" else "เงินต้น", principal, { principal = it })
        if (!full) {
            AmountField("จำนวนงวด", tenor, { tenor = it.filter(Char::isDigit) })
            Row {
                AmountField("ดอกเบี้ย %/ปี", rate, { rate = it }, Modifier.weight(1f), supporting = "ว่าง = ของบัญชี")
                Spacer(Modifier.width(8.dp))
                AmountField("ค่างวด", installment, { installment = it }, Modifier.weight(1f), supporting = "ว่าง = คำนวณ")
            }
            SwitchRow("มียอดยกมา (snapshot)", hasSnap, { hasSnap = it }, sub = "หนี้ที่เริ่มก่อนใช้แอพ: กรอกยอดคงเหลือ ณ วันที่ยกมา")
            if (hasSnap) {
                DateField("วันที่ยกมา", snapDate, { snapDate = it })
                AmountField("เงินต้นคงเหลือ ณ วันนั้น", snapRemaining, { snapRemaining = it })
                AmountField("จ่ายไปแล้วกี่งวด", snapPaid, { snapPaid = it.filter(Char::isDigit) })
                DateField("วันคิดดอกล่าสุด", snapInterest, { snapInterest = it }, allowEmpty = true)
            } else if (kind == "convert") {
                Note("แปลงยอดเต็มจำนวนในบิลปัจจุบันเป็นผ่อน: ตัดยอดเต็มจำนวนที่ออกบิลแล้ว คิดดอกตั้งแต่วันถัดจากวันออกบิล")
            }
        }
        if (e != null) {
            if (e.txn_id.isNotEmpty()) Note("หนี้ย่อยนี้ผูกกับรายการโอน (เบิกเงินสด) ลบรายการโอนในหน้า \"รายการ\" จะลบหนี้ย่อยนี้ด้วย")
            DeleteButton("หนี้ย่อยนี้") {
                vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }, onOk = { onClose() }) { deleteDebt(e.id) }
            }
        }
    }
}
