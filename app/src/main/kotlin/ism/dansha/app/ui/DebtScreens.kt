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
    val p = LocalPalette.current
    var action by remember { mutableStateOf<DebtAction?>(null) }
    // หน้ารายละเอียดของบัญชี (เปิดค้างไว้ได้ระหว่างจ่าย/แก้ไข)
    var detail by remember { mutableStateOf<String?>(null) }
    val accounts = remember(d) { d.accounts.associateBy { it.id } }
    val states = c?.debts.orEmpty().filterKeys { accounts[it]?.active == true || (c?.debts?.get(it)?.outstanding ?: 0.0) > 0.004 }
    // บิลถัดไปของแต่ละบัญชี เรียงตามวันครบกำหนด
    val upcoming = remember(c) {
        states.values.mapNotNull { st -> accounts[st.accountId]?.let { dueBills(it, st, 1).firstOrNull() } }.sortedBy { it.due }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
        item { ScreenTitle("หนี้สิน") }
        if (c == null || states.isEmpty()) {
            item { Card { Text("ยังไม่มีบัญชีหนี้ที่ผูกตัวคำนวณ", color = p.muted) } }
            return@LazyColumn
        }
        val ascend = states.values.filterIsInstance<AscendState>()
        val total = states.values.sumOf { it.outstanding }
        val payoff = ascend.sumOf { it.payoffToday } + states.values.filterIsInstance<SplState>().sumOf { it.outstanding }
        val limit = states.keys.sumOf { accounts[it]?.credit_limit.num() }

        // ภาพรวม
        item {
            Card {
                Text("หนี้คงเหลือทั้งหมด", color = p.muted, fontSize = 14.sp)
                FitText("฿" + money(total), androidx.compose.ui.text.TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = Sarabun, color = p.negative), minSize = 20.sp)
                if (limit > 0) {
                    Spacer(Modifier.height(6.dp))
                    ProgressBar((total / limit).toFloat(), p.negative, p.negativeSoft, height = 8)
                    Text("ใช้ไป ${(total / limit * 100).toInt()}% ของวงเงินรวม ${formatMoney(limit)}", fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    MiniStat("งวดถัดไปรวม", "฿" + money(upcoming.sumOf { it.amount }), Modifier.weight(1f), p.primary)
                    MiniStat("ดอกสะสมวันนี้", "฿" + money(ascend.sumOf { it.accruedInterest }), Modifier.weight(1f), p.negative)
                    MiniStat("ปิดหมดวันนี้", "฿" + money(payoff), Modifier.weight(1f))
                }
            }
        }

        // ต้องจ่ายเร็วๆ นี้
        if (upcoming.isNotEmpty()) {
            item { SectionHeader("ต้องจ่ายเร็วๆ นี้") }
            item {
                Card {
                    upcoming.forEachIndexed { i, b ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = p.line)
                        DueBillRow(b, showAccount = true, onPay = { action = DebtAction.Pay(b.account.id) })
                    }
                    Text("แตะงวดเพื่อดูว่ามาจากก้อนไหน ต้น/ดอกเท่าไหร่", fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // บัญชี
        item { SectionHeader("แยกตามบัญชี") }
        states.values.forEach { st ->
            val acc = accounts[st.accountId] ?: return@forEach
            item(key = st.accountId) { DebtAccountCard(acc, st, onOpen = { detail = acc.id }, onPay = { action = DebtAction.Pay(acc.id) }) }
        }
        if (ascend.size > 1) {
            item {
                Card(color = p.sticky) {
                    Text("💡 โปะ PayNext ธรรมดาให้หมดก่อน (ผ่อนสั้น ดอกต่องวดแรงกว่า) ส่วน Extra จ่ายตามบิลพอ", fontSize = 13.sp, color = p.ink)
                }
            }
        }
    }

    detail?.let { id ->
        val acc = d.accounts.firstOrNull { it.id == id }
        val st = c?.debts?.get(id)
        if (acc != null && st != null) {
            DebtDetailPage(
                d, acc, st,
                onPay = { action = DebtAction.Pay(id) },
                onQuote = { action = DebtAction.Quote(id) },
                onDraw = { action = DebtAction.Draw(id) },
                onAddLoan = { action = DebtAction.EditDebt(id, null) },
                onEditLoan = { debtId -> action = DebtAction.EditDebt(id, debtId) },
                onClose = { detail = null },
            )
        } else androidx.compose.runtime.LaunchedEffect(Unit) { detail = null }
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

/** การ์ดบัญชีหนี้: ยอดคงเหลือ + แถบวงเงิน + งวดถัดไป + ปุ่มจ่าย/ดูละเอียด */
@Composable
private fun DebtAccountCard(acc: Account, st: DebtState, onOpen: () -> Unit, onPay: () -> Unit) {
    val p = LocalPalette.current
    val next = remember(st) { dueBills(acc, st, 1).firstOrNull() }
    val limit = acc.credit_limit.num()
    Card(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AccountBadge(acc, 36)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(acc.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = p.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val loans = when (st) {
                    is AscendState -> "${st.loans.count { it.status != "closed" }} ก้อน · ดอก/วัน ฿${money(st.dailyInterest)}"
                    is SplState -> "${st.perOrder.count { it.value.left > 0.004 }} ออเดอร์ที่ผ่อนอยู่"
                }
                Text(loans, fontSize = 12.sp, color = p.muted)
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text("฿" + money(st.outstanding), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.negative, maxLines = 1)
                Text("คงเหลือ", fontSize = 11.sp, color = p.muted)
            }
        }
        if (limit > 0) {
            Spacer(Modifier.height(8.dp))
            ProgressBar((st.outstanding / limit).toFloat(), p.negative, p.negativeSoft, height = 6)
            Text("วงเงิน ${formatMoney(limit)} · ว่าง ${money((limit - st.outstanding).coerceAtLeast(0.0))}", fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 3.dp))
        }
        next?.let { b ->
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).background(if (b.overdue) p.negativeSoft else p.surface, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val days = ism.dansha.core.engine.Reminders.daysUntil(Dates.todayStr(), b.due)
                    Text(
                        if (b.overdue) "งวดค้าง · เลยกำหนด!" else "งวดถัดไป · ${if (days == 0) "วันนี้" else "อีก $days วัน"}",
                        fontSize = 12.sp, color = if (b.overdue) p.negative else p.muted, fontWeight = FontWeight.SemiBold,
                    )
                    Text("ครบ ${thaiDate(b.due)}" + if (!b.issued) " (ประมาณ)" else "", fontSize = 13.sp, color = p.ink)
                }
                Text("฿" + money(b.amount), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.ink)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = onPay, modifier = Modifier.weight(1f)) { Text("จ่าย / โปะ") }
            OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("ดูทุกงวด ›") }
        }
    }
}

// ---------- จ่าย / โปะ ----------

@Composable
private fun PayDebtPage(d: DanshaData, c: MainViewModel.Computed?, accountId: String, vm: MainViewModel, onClose: () -> Unit) {
    val acc = d.accounts.firstOrNull { it.id == accountId } ?: run { androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }; return }
    val st = c?.debts?.get(accountId)
    val suggested = st?.currentBill?.amount ?: st?.nextBill?.totalDue
    var amount by remember { mutableStateOf(amountText(suggested)) }
    val payFrom = Forms.accountChoices(d).filter { it.type != "revolving_credit" }
    var from by remember { mutableStateOf(payFrom.firstOrNull { it.type == "bank" }?.id ?: payFrom.firstOrNull()?.id.orEmpty()) }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    var note by remember { mutableStateOf("จ่าย ${acc.name}") }
    var error by remember { mutableStateOf<String?>(null) }
    val amt = Forms.parseAmount(amount)
    val quote = remember(d, accountId, date, amt) {
        if (isEngineAccount(acc) && amt != null && amt > 0) runCatching { Engine.debtQuote(d, accountId, date, amt) }.getOrNull() else null
    }

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
    }
}

// ---------- ยอดปิด ----------

@Composable
private fun QuotePage(d: DanshaData, accountId: String, onClose: () -> Unit) {
    val acc = d.accounts.firstOrNull { it.id == accountId } ?: run { androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }; return }
    var date by remember { mutableStateOf(Dates.todayStr()) }
    var amount by remember { mutableStateOf("") }
    val tryAmount = Forms.parseAmount(amount)
    val q = remember(d, accountId, date, tryAmount) { runCatching { Engine.debtQuote(d, accountId, date, tryAmount) }.getOrNull() }
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
    }
}

// ---------- เบิกเงินสด ----------

@Composable
private fun CashDrawPage(d: DanshaData, accountId: String, vm: MainViewModel, onClose: () -> Unit) {
    val acc = d.accounts.firstOrNull { it.id == accountId } ?: run { androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }; return }
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
    }
}

private fun jsPct(rate: Double): String = BigDecimal.valueOf(rate * 100).stripTrailingZeros().toPlainString()

// ---------- หนี้ย่อย ----------

@Composable
private fun DebtEditor(d: DanshaData, accountId: String, existing: Debt?, vm: MainViewModel, onClose: () -> Unit) {
    val e = existing
    val acc = d.accounts.firstOrNull { it.id == accountId } ?: run { androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }; return }
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
