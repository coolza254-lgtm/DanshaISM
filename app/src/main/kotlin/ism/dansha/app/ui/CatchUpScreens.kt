package ism.dansha.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.Account
import ism.dansha.core.Bill
import ism.dansha.core.Category
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.engine.AscendState
import ism.dansha.core.engine.CatchUp
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.Outlook
import ism.dansha.core.engine.PlanBook
import ism.dansha.core.engine.SPayLater
import ism.dansha.core.engine.isEngineAccount
import ism.dansha.core.engine.isSplAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

/*
 * กลับมาจดต่อ: ไม่ได้จดหลายวัน → ทำตามลำดับ (ข้ามข้อที่ไม่มีได้)
 *  จดที่จำได้ (หลายรายการทีเดียว) → บิลที่ถึงกำหนด → ค่าเดินทาง/หนี้ → ใส่ยอดจริงของแต่ละบัญชี (ส่วนต่าง = ยอดที่ไม่ได้จด)
 */

/** การ์ดบนหน้าภาพรวม: ไม่ได้จดมาตั้งแต่ 2 วัน */
@Composable
fun CatchUpCard(d: DanshaData, onOpen: () -> Unit) {
    val p = LocalPalette.current
    val gap = remember(d) { CatchUp.gap(d) }
    val last = gap.lastEntry ?: return
    if (gap.days < CatchUp.REMIND_DAYS) return
    Card(color = p.sticky) {
        Text("ไม่ได้จดมา ${gap.days} วัน", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = p.ink)
        Text(
            "จดล่าสุด ${thaiDate(last)} · กลับมาจดต่อได้ในไม่กี่นาที: จดที่จำได้ → บิลที่ถึงกำหนด → ใส่ยอดจริงของแต่ละบัญชี",
            fontSize = 14.sp, color = p.ink.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpen) { Text("กลับมาจดต่อ") }
    }
}

/** วันทำงานที่ยังจดค่ารถไม่ครบ (ก่อนวันนี้ ไม่เกิน 31 วัน) แยกตามรอบ */
private fun pendingTravel(d: DanshaData, today: String): List<Pair<String, List<PlanBook.TravelDay>>> {
    val startDay = Engine.startDay(Engine.config(d))
    val current = Dates.payCycleOf(today, startDay)
    val from = LocalDate.parse(today).minusDays(31).toString()
    return listOf(Outlook.shiftCycle(current, -1), current).mapNotNull { cycle ->
        val days = PlanBook.travel(d, cycle, today).list.filter { !it.done && it.date < today && it.date >= from }
        if (days.isEmpty()) null else cycle to days
    }
}

@Composable
fun CatchUpPage(d: DanshaData, c: MainViewModel.Computed?, vm: MainViewModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val today = Dates.todayStr()
    val gap = remember(d) { CatchUp.gap(d) }
    var text by rememberSaveable { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<CatchUp.Line>>(emptyList()) }
    val bills = remember(d) { CatchUp.dueBills(d, today) }
    val rows = remember(d) { CatchUp.balanceRows(d, today) }
    val travel = remember(d) { pendingTravel(d, today) }
    val debtAccounts = remember(d) {
        d.accounts.filter { it.active && (isEngineAccount(it) || isSplAccount(it)) }.sortedWith(compareBy({ it.sort ?: Int.MAX_VALUE }, { it.name }))
    }
    val waiting = remember(d) { d.shopee.count { it.status == SPayLater.WAITING } }
    val accs = remember(d) { d.accounts.associateBy { it.id } }
    val cats = remember(d) { d.categories.associateBy { it.id } }
    val actual = remember { mutableStateMapOf<String, String>() }
    var openBill by remember { mutableStateOf<Bill?>(null) }
    var workCycle by remember { mutableStateOf<String?>(null) }
    var reconcile by remember { mutableStateOf<Account?>(null) }
    var shopee by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // อ่านข้อความหลังหยุดพิมพ์ครู่หนึ่ง (เดาหมวดจากประวัติทุกบรรทัด ทำเบื้องหลัง)
    LaunchedEffect(d, text) {
        if (text.isBlank()) {
            lines = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        lines = withContext(Dispatchers.Default) { CatchUp.parse(d, text) }
    }

    FormPage("กลับมาจดต่อ", onClose, onSave = null) {
        val last = gap.lastEntry
        Note(
            (if (last != null) "จดล่าสุด ${thaiDate(last)} (${gap.days} วันก่อน) · " else "") + "ทำตามลำดับ ข้ามข้อที่ไม่มีได้",
            p.ink,
        )
        error?.let { Note(it, p.negative) }
        var step = 0

        // ---- จดที่จำได้ ----
        StepTitle(++step, "จดที่จำได้")
        Note("พิมพ์ทีละบรรทัดแบบจดไว ขึ้นบรรทัดวันที่ก่อน (28/10, 28 ต.ค., เมื่อวาน) — ยังไม่มีบรรทัดวันที่ = วันนี้ · หมวด/บัญชีเดาจากที่เคยจด")
        TextInput("รายการ", text, { text = it }, singleLine = false, placeholder = "28/10\nกาแฟ 35\nข้าวมันไก่ 50 สด\nเมื่อวาน\ngrab 120 tm")
        if (lines.isNotEmpty()) {
            Card {
                var shownDate = ""
                lines.forEachIndexed { i, l ->
                    if (l.date != shownDate) {
                        shownDate = l.date
                        Text(
                            shortThaiDate(l.date), Modifier.padding(top = if (i > 0) 8.dp else 0.dp).background(p.highlight).padding(horizontal = 6.dp),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.ink,
                        )
                    }
                    BackfillRow(l, accs, cats)
                }
            }
            val ok = lines.count { it.ok }
            Button(
                onClick = {
                    error = null
                    vm.edit<Int>(ok = "จดย้อนหลังแล้ว", onError = { error = it }, onOk = { text = "" }) {
                        addBackfill(CatchUp.parse(toData(), text))
                    }
                },
                enabled = ok > 0, modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ok > 0) "จด $ok รายการ" else "ยังไม่มีบรรทัดที่อ่านได้") }
        }

        // ---- บิลที่ถึงกำหนด ----
        StepTitle(++step, "บิลในแผนที่ถึงกำหนดแล้ว")
        if (bills.isEmpty()) Note("ไม่มีบิลค้าง ✓", p.positive)
        else {
            Note("แตะเพื่อบันทึกว่าจ่าย/ได้รับแล้ว (เลือกวันที่จ่ายจริงได้) · ยังไม่ได้จ่ายก็ข้ามไป")
            Card {
                bills.forEachIndexed { i, b ->
                    if (i > 0) HorizontalDivider(color = p.line)
                    DueBillLine(b) { openBill = b }
                }
            }
        }

        // ---- ค่าเดินทาง / หนี้ ----
        if (travel.isNotEmpty() || debtAccounts.isNotEmpty()) {
            StepTitle(++step, if (debtAccounts.isEmpty()) "ค่าเดินทาง" else if (travel.isEmpty()) "หนี้" else "ค่าเดินทาง / หนี้")
            travel.forEach { (cycle, days) ->
                Card {
                    Text("ยังไม่จดค่ารถ ${days.size} วัน", fontWeight = FontWeight.SemiBold, color = p.ink)
                    Note(days.joinToString(" · ") { shortThaiDate(it.date) + if (it.late) " (กะดึก)" else "" })
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { workCycle = cycle }) { Text("เปิดวันทำงานรอบ ${cycleLabel(cycle)}") }
                }
            }
            debtAccounts.forEach { a ->
                val st = c?.debts?.get(a.id)
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountBadge(a, 32)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val bill = st?.let { dueBills(a, it, 1).firstOrNull() }
                            Note(
                                bill?.let { b -> "งวดถัดไป ฿${formatMoney(b.amount)} · ครบ ${shortThaiDate(b.due)}" + if (b.overdue) " · เลยกำหนด" else "" } ?: "ไม่มีบิลค้าง",
                                if (bill?.overdue == true) p.negative else p.muted,
                            )
                        }
                    }
                    if (st is AscendState) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { reconcile = a }) { Text("เทียบยอดกับแอพ Ascend") }
                    }
                    if (isSplAccount(a) && waiting > 0) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { shopee = true }) { Text("ออเดอร์รอกดรับของ $waiting รายการ") }
                    }
                }
            }
            if (debtAccounts.isNotEmpty()) {
                Note("จ่ายหนี้ไปแล้วแต่ยังไม่จด → กดในบิลหนี้สินข้างบน หรือจดเป็น \"โอน\" เข้าบัญชีหนี้ (วันที่จ่ายจริงมีผลกับดอกเบี้ย)")
            }
        }

        // ---- ยอดจริงของแต่ละบัญชี ----
        StepTitle(++step, "ใส่ยอดจริงของแต่ละบัญชี")
        Note("เปิดแอพธนาคาร/วอลเล็ทแล้วใส่ยอดตอนนี้ (เว้นว่าง = ไม่ปรับ) ส่วนต่างที่เหลือคือที่ใช้ไปโดยไม่ได้จด จะบันทึกเป็นรายการเดียว \"ยอดที่ไม่ได้จด\" หมวดอื่นๆ แก้หมวดทีหลังได้")
        Note("มีโอนระหว่างบัญชีหรือจ่ายบัตรที่ยังไม่ได้จด ให้จดเป็น \"โอน\" ก่อน ส่วนต่างจะเหลือน้อยลง")
        if (rows.isEmpty()) Note("ไม่มีบัญชีที่ปรับยอดได้")
        else Card {
            rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = p.line)
                BalanceInput(r, actual[r.account.id].orEmpty()) { actual[r.account.id] = it }
            }
        }
        val pending = rows.mapNotNull { r ->
            Forms.parseAmount(actual[r.account.id].orEmpty())?.takeIf { a -> CatchUp.adjustment(r, a) != null }?.let { a -> r.account.id to a }
        }
        Button(
            onClick = {
                error = null
                vm.edit<Int>(ok = "ปรับยอดแล้ว", onError = { error = it }, onOk = { actual.clear() }) {
                    pending.count { (id, a) -> adjustBalance(id, a) != null }
                }
            },
            enabled = pending.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
        ) { Text(if (pending.isEmpty()) "ใส่ยอดจริงก่อน" else "ปรับยอด ${pending.size} บัญชี") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("เสร็จแล้ว") }
    }

    openBill?.let { b0 ->
        val b = d.bills.firstOrNull { it.id == b0.id } ?: b0
        if (b.section == PlanBook.CLAIM) ClaimReceivePage(d, b, vm) { openBill = null }
        else BillPage(d, b, vm, initialDate = b.due_date.takeIf { it.isNotEmpty() && it < today }) { openBill = null }
    }
    workCycle?.let { cycle -> WorkDaysPage(d, cycle, vm) { workCycle = null } }
    val ra = reconcile
    val rst = ra?.let { c?.debts?.get(it.id) as? AscendState }
    if (ra != null && rst != null) ReconcilePage(d, ra, rst, vm) { reconcile = null }
    if (shopee) ShopeePage(d, c, vm) { shopee = false }
}

@Composable
private fun StepTitle(n: Int, title: String) {
    val p = LocalPalette.current
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            n.toString(), Modifier.background(p.highlight, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 1.dp),
            fontWeight = FontWeight.Bold, color = p.ink,
        )
        Spacer(Modifier.width(8.dp))
        Text(title, fontFamily = Hand, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = p.primary)
    }
}

/** บรรทัดที่อ่านแล้ว: โน้ต · หมวด · บัญชี · ยอด (อ่านไม่ได้/อาจซ้ำ = ตัวแดง) */
@Composable
private fun BackfillRow(l: CatchUp.Line, accs: Map<String, Account>, cats: Map<String, Category>) {
    val p = LocalPalette.current
    val cat = cats[l.subcategoryId.ifEmpty { l.categoryId }]
    val bad = l.error != null
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(l.note.ifEmpty { cat?.name ?: l.text }, fontSize = 15.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (bad) "${l.text} · ${l.error}"
                else listOfNotNull(cat?.name ?: "ไม่มีหมวด", accs[l.accountId]?.name, "อาจซ้ำกับที่จดไว้แล้ว".takeIf { l.duplicate }).joinToString(" · "),
                fontSize = 12.sp, color = if (bad || l.duplicate) p.negative else p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        val amount = l.amount
        if (amount != null && !bad) {
            Spacer(Modifier.width(8.dp))
            Text(
                (if (l.type == "income") "+" else "−") + formatMoney(amount),
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (l.type == "income") p.positive else p.ink,
            )
        }
    }
}

@Composable
private fun DueBillLine(b: Bill, onClick: () -> Unit) {
    val p = LocalPalette.current
    val income = b.group == "income"
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(b.name, fontSize = 15.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (income) "เข้า " else "ครบ ") + shortThaiDate(b.due_date) + " · รอบ ${cycleLabel(b.pay_cycle)}",
                fontSize = 12.sp, color = p.negative,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text((if (income) "+" else "") + formatMoney(b.est_amount), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (income) p.positive else p.ink)
    }
}

@Composable
private fun BalanceInput(r: CatchUp.BalanceRow, value: String, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    val cur = if (r.account.currency != "THB") " ${r.account.currency}" else ""
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountBadge(r.account, 32)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.account.name, fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((if (r.isCredit) "ใช้ไปในแอพ " else "ในแอพ ") + formatMoney(r.app) + cur, fontSize = 13.sp, color = p.muted)
        }
    }
    AmountField(if (r.isCredit) "ยอดใช้ไปจริง" else "ยอดจริงตอนนี้", value, onChange)
    val a = Forms.parseAmount(value) ?: return
    val adj = CatchUp.adjustment(r, a)
    Text(
        if (adj == null) "✓ ตรงกันแล้ว"
        else when {
            adj.type == "expense" -> "ใช้ไปโดยไม่ได้จด ${formatMoney(adj.amount)}$cur"
            r.isCredit -> "จ่ายคืนที่ไม่ได้จด ${formatMoney(adj.amount)}$cur"
            else -> "เงินเข้าที่ไม่ได้จด ${formatMoney(adj.amount)}$cur"
        },
        Modifier.padding(top = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (adj == null) p.positive else p.negative,
    )
}
