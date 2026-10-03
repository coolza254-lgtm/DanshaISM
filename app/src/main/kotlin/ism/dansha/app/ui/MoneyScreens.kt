package ism.dansha.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.DanshaData
import ism.dansha.core.engine.AccountView
import ism.dansha.core.engine.AscendState
import ism.dansha.core.engine.DebtState
import ism.dansha.core.engine.SplState
import java.math.BigDecimal

private fun money(v: Double?): String = if (v == null) "–" else formatMoney(BigDecimal.valueOf(v))

private fun signColor(v: Double?): Color = when {
    v == null -> DanshaColors.Ink
    v < 0 -> DanshaColors.Negative
    else -> DanshaColors.Ink
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier, color: Color = DanshaColors.Ink, sub: String? = null) {
    Column(modifier.padding(vertical = 6.dp)) {
        Text(label, color = DanshaColors.Muted, fontSize = 12.sp)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        if (sub != null) Text(sub, color = DanshaColors.Muted, fontSize = 11.sp, maxLines = 2)
    }
}

@Composable
private fun KeyValue(label: String, value: String, color: Color = DanshaColors.Ink, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), color = DanshaColors.Muted, fontSize = 14.sp)
        Text(value, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp)
    }
}

// ---------- ภาพรวม ----------

@Composable
fun HomeScreen(d: DanshaData, c: MainViewModel.Computed?, vm: MainViewModel, onOpenDebt: () -> Unit) {
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.pickImport(it) }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("断捨ISM", c?.let { "รอบ ${it.overview.payCycle.substring(8)} · ${thaiDate(it.overview.payCycleRange.start)} – ${thaiDate(it.overview.payCycleRange.end)}" }) }
        if (d.isEmpty()) {
            item {
                Card {
                    Text("ยินดีต้อนรับ", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "ข้อมูลทั้งหมดเก็บในเครื่องนี้ ไม่ต้องตั้งค่าอะไร\nถ้ามีไฟล์ข้อมูลจากระบบเดิม (dansha-data-….json) กดนำเข้าได้เลย",
                        color = DanshaColors.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth()) {
                        Text("นำเข้าไฟล์ข้อมูล")
                    }
                    OutlinedButton(onClick = { vm.startFresh() }, Modifier.fillMaxWidth()) {
                        Text("เริ่มใหม่ (ยังไม่มีข้อมูล)")
                    }
                }
            }
            return@LazyColumn
        }
        if (c == null) {
            item { Text("กำลังคำนวณ…", Modifier.padding(20.dp), color = DanshaColors.Muted) }
            return@LazyColumn
        }
        val h = c.home
        item {
            Card {
                Row {
                    Figure("เงินที่มีตอนนี้", money(h.cashNow), Modifier.weight(1f), signColor(h.cashNow))
                    Figure("หนี้คงเหลือ", money(h.debtTotal), Modifier.weight(1f), if (h.debtTotal > 0) DanshaColors.Negative else DanshaColors.Ink)
                }
                Row {
                    Figure(
                        "ใช้ได้วันละ", money(h.perDay), Modifier.weight(1f), signColor(h.perDay),
                        sub = "อีก ${h.daysLeft} วันถึงสิ้นรอบ",
                    )
                    Figure(
                        "สิ้นรอบเหลือ", money(h.projected ?: h.cashNow), Modifier.weight(1f), signColor(h.projected ?: h.cashNow),
                        sub = if (h.projected == null) "ยังไม่มีแผนรอบนี้" else "หลังหักแผนบิล",
                    )
                }
            }
        }
        h.nextDebtBill?.let { b ->
            item {
                Card(Modifier.clickable(onClick = onOpenDebt)) {
                    SectionLabel(if (b.overdue) "บิลหนี้ เลยกำหนด!" else "บิลถัดไป")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.accountName, fontWeight = FontWeight.SemiBold)
                            Text("ครบกำหนด ${thaiDate(b.due)}", color = if (b.overdue) DanshaColors.Negative else DanshaColors.Muted, fontSize = 13.sp)
                        }
                        Text(money(b.amount), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    h.cycleSpend.filter { it.second.total > 0 }.forEach { (name, cs) ->
                        Spacer(Modifier.height(6.dp))
                        Text("$name ใช้ในรอบบิล ${thaiDate(cs.start)} – ${thaiDate(cs.end)}: ${money(cs.total)}", color = DanshaColors.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        h.copay?.let { cp ->
            item {
                Card {
                    SectionLabel(cp.name)
                    Spacer(Modifier.height(4.dp))
                    KeyValue("รัฐช่วยได้วันนี้อีก", money(cp.leftToday), bold = true)
                    KeyValue("ซื้อราคาเต็มได้ถึง", money(cp.fullPriceForToday))
                    KeyValue("สิทธิเหลือทั้งโครงการ", money(cp.totalLeft))
                    KeyValue("เหลือเวลาอีก", "${cp.daysLeft} วัน")
                }
            }
        }
        c.overview.port?.let { p ->
            item {
                Card {
                    SectionLabel("พอร์ต")
                    KeyValue("มูลค่า", money(p.valueThb), bold = true)
                    KeyValue("กำไร/ขาดทุน", "${money(p.unrealizedThb)} (${p.unrealizedPct}%)", if (p.unrealizedThb < 0) DanshaColors.Negative else DanshaColors.Positive)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)); Text("บัญชี", Modifier.padding(horizontal = 20.dp), fontWeight = FontWeight.SemiBold) }
        items(c.overview.accounts.filter { it.account.active }.sortedWith(compareBy({ it.account.sort ?: Int.MAX_VALUE }, { it.account.name })), key = { it.account.id }) { a ->
            AccountRow(a)
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun AccountRow(a: AccountView) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountBadge(a.account)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.account.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (a.isCredit) "วงเงินเหลือ ${money(a.available)}" else (ACCOUNT_TYPE_LABELS[a.account.type] ?: a.account.type),
                color = DanshaColors.Muted, fontSize = 12.sp,
            )
        }
        val cur = if (a.account.currency != "THB") " ${a.account.currency}" else ""
        if (a.isCredit) Text("ใช้ไป ${money(a.used)}", color = if ((a.used ?: 0.0) > 0) DanshaColors.Negative else DanshaColors.Ink, fontWeight = FontWeight.SemiBold)
        else Text(money(a.balance) + cur, color = signColor(a.balance), fontWeight = FontWeight.SemiBold)
    }
}

// ---------- หนี้ ----------

@Composable
fun DebtScreen(c: MainViewModel.Computed?) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("หนี้", "ดูอย่างเดียว · จ่าย/โปะ คำนวณยอดปิด เบิกเงินสด มาใน Phase 3") }
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
        val names = c.overview.accounts.associate { it.account.id to it.account }
        states.values.forEach { st ->
            item(key = st.accountId) { DebtAccountCard(names[st.accountId]?.name ?: st.accountId, names[st.accountId]?.credit_limit?.toDouble(), st) }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DebtAccountCard(name: String, limit: Double?, st: DebtState) {
    var open by remember { mutableStateOf(false) }
    Card {
        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(4.dp))
        KeyValue("ใช้ไป / วงเงิน", "${money(st.outstanding)} / ${money(limit)}", bold = true)
        if (st is AscendState) {
            KeyValue("ดอกสะสมถึงวันนี้", money(st.accruedInterest))
            KeyValue("ดอกต่อวันประมาณ", money(st.dailyInterest))
            KeyValue("ปิดหนี้วันนี้", money(st.payoffToday))
            if (st.fees > 0) KeyValue("ค่าธรรมเนียมค้าง", money(st.fees), DanshaColors.Negative)
        }
        st.currentBill?.let { b ->
            KeyValue(if (b.overdue == true) "บิลค้าง (เลยกำหนด)" else "บิลปัจจุบัน ครบ ${thaiDate(b.due)}", money(b.amount), if (b.overdue == true) DanshaColors.Negative else DanshaColors.Ink, bold = true)
        }
        st.nextBill?.let { b ->
            KeyValue("บิลถัดไป ออก ${thaiDate(b.date)} ครบ ${thaiDate(b.due)}", money(b.totalDue) + (b.interest?.let { " (ดอก ${money(it)})" } ?: ""))
        }
        if (st is AscendState && st.cycleSpend.total > 0) {
            KeyValue("ใช้ในรอบบิล ${thaiDate(st.cycleSpend.start)} – ${thaiDate(st.cycleSpend.end)}", money(st.cycleSpend.total))
        }
        TextButton(onClick = { open = !open }) { Text(if (open) "ซ่อนรายละเอียด" else "ดูหนี้ย่อย / บิลล่วงหน้า") }
        if (open) {
            when (st) {
                is AscendState -> {
                    st.loans.filter { it.status == "active" }.forEach { l ->
                        HorizontalDivider(color = DanshaColors.Line)
                        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(l.desc.ifEmpty { l.kind }, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(
                                "กู้ ${thaiDate(l.start)} · ${money(l.principal)} · ${l.tenor} งวด งวดละ ${money(l.installment)}",
                                color = DanshaColors.Muted, fontSize = 12.sp,
                            )
                            Text(
                                "จ่ายแล้ว ${l.paidPeriods} งวด เหลือ ${l.periodsLeft} · คงเหลือ ${money(l.remaining)} · ดอกค้าง ${money(l.accruedInterest)}",
                                fontSize = 12.sp,
                            )
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
