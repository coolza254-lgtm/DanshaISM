package ism.dansha.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ism.dansha.app.MainViewModel
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.PortTxn
import ism.dansha.core.Schema
import ism.dansha.core.engine.ASSET_TYPES
import ism.dansha.core.engine.Engine
import ism.dansha.core.engine.Forms
import ism.dansha.core.engine.PORT_SIDES
import ism.dansha.core.engine.toDec
import java.math.BigDecimal

private fun money(v: Double?) = formatMoney(v?.let { BigDecimal.valueOf(it) })
private fun num(v: Double?) = v?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: "–"

private val SIDE_LABELS = mapOf("buy" to "ซื้อ", "sell" to "ขาย", "dividend" to "ปันผล", "fee" to "ค่าธรรมเนียม")
private val TYPE_LABELS = mapOf("stock" to "หุ้น", "etf" to "ETF", "fund" to "กองทุน", "gold" to "ทอง", "other" to "อื่นๆ")

@Composable
fun PortPage(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<PortTxn?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pricing by remember { mutableStateOf(false) }
    val pf = remember(d) { runCatching { Engine.portfolio(d) }.getOrNull() }

    FormPage("พอร์ตลงทุน", onClose, onSave = null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }) { Text("+ รายการ") }
            OutlinedButton(onClick = { pricing = true }) { Text("อัปเดตราคา") }
        }
        if (pf == null || d.port.isEmpty()) {
            Note("ยังไม่มีรายการ กด \"+ รายการ\" เพื่อบันทึกการซื้อครั้งแรก (ราคากรอกเอง ไม่ดึงราคาสด)")
        } else {
            val s = pf.summary
            Card {
                KeyValue("มูลค่า", money(s.valueThb), bold = true)
                KeyValue("ต้นทุน", money(s.costThb))
                KeyValue("กำไร/ขาดทุนยังไม่รับรู้", "${money(s.unrealizedThb)} (${s.unrealizedPct}%)", if (s.unrealizedThb < 0) DanshaColors.Negative else DanshaColors.Positive)
                KeyValue("กำไรที่รับรู้แล้ว", money(s.realizedThb))
                KeyValue("ปันผลสุทธิ", money(s.dividendThb))
                KeyValue("ค่าธรรมเนียม / ภาษี", "${money(s.feesThb)} / ${money(s.taxThb)}")
                if (s.missingPrice.isNotEmpty()) Note("ยังไม่มีราคาล่าสุด (ใช้ต้นทุนแทน): ${s.missingPrice.joinToString()}", DanshaColors.Negative)
                s.byType.forEach { t -> KeyValue(TYPE_LABELS[t.type] ?: t.type, money(t.valueThb)) }
            }
            Text("หลักทรัพย์", fontWeight = FontWeight.SemiBold)
            pf.holdings.filter { it.qty > 0 }.forEach { h ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row {
                        Text("${h.symbol} ${h.name}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text(money(h.valueThb ?: h.costThb), fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "${num(h.qty)} หน่วย · ทุนเฉลี่ย ${num(h.avgCost)} ${h.currency} · ราคา ${num(h.price)}" +
                            (h.unrealizedPct?.let { " · $it%" } ?: ""),
                        color = DanshaColors.Muted, fontSize = 13.sp,
                    )
                    if (h.currency != "THB" && h.priceEffectThb != null) {
                        Text("กำไรจากราคา ${money(h.priceEffectThb)} · จากค่าเงิน ${money(h.fxEffectThb)}", color = DanshaColors.Muted, fontSize = 13.sp)
                    }
                }
                HorizontalDivider(color = DanshaColors.Line)
            }
        }
        if (d.port.isNotEmpty()) {
            Text("รายการซื้อขาย", fontWeight = FontWeight.SemiBold)
            d.port.sortedWith(compareByDescending<PortTxn> { it.date }.thenByDescending { it.created_at }).forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${SIDE_LABELS[t.side] ?: t.side} ${t.symbol}", fontSize = 14.sp)
                        Text("${thaiDate(t.date)} · ${t.broker}", color = DanshaColors.Muted, fontSize = 13.sp)
                    }
                    Text(
                        if (t.side == "buy" || t.side == "sell") "${amountText(t.qty)} × ${amountText(t.price)} ${t.currency}" else "${amountText(t.price)} ${t.currency}",
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
    if (creating) PortEditor(d, null, vm) { creating = false }
    editing?.let { t -> PortEditor(d, t, vm) { editing = null } }
    if (pricing) PricesForm(d, vm) { pricing = false }
}

@Composable
private fun PortEditor(d: DanshaData, existing: PortTxn?, vm: MainViewModel, onClose: () -> Unit) {
    val e = existing
    var date by remember { mutableStateOf(e?.date ?: Dates.todayStr()) }
    var symbol by remember { mutableStateOf(e?.symbol.orEmpty()) }
    var name by remember { mutableStateOf(e?.name.orEmpty()) }
    var assetType by remember { mutableStateOf(e?.asset_type?.ifEmpty { "stock" } ?: "stock") }
    var side by remember { mutableStateOf(e?.side ?: "buy") }
    var qty by remember { mutableStateOf(amountText(e?.qty)) }
    var price by remember { mutableStateOf(amountText(e?.price)) }
    var currency by remember { mutableStateOf(e?.currency?.ifEmpty { "THB" } ?: "THB") }
    var fxRate by remember { mutableStateOf(amountText(e?.fx_rate)) }
    var fee by remember { mutableStateOf(amountText(e?.fee)) }
    var tax by remember { mutableStateOf(amountText(e?.tax)) }
    var broker by remember { mutableStateOf(e?.broker?.ifEmpty { "Dime!" } ?: "Dime!") }
    var note by remember { mutableStateOf(e?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val trade = side == "buy" || side == "sell"

    fun save() {
        val row = (e ?: PortTxn(id = "")).copy(
            date = date, symbol = symbol, name = name, asset_type = assetType, side = side,
            qty = if (trade) Forms.parseAmount(qty)?.toDec() else null, price = Forms.parseAmount(price)?.toDec(),
            currency = currency, fx_rate = Forms.parseAmount(fxRate)?.toDec(), fee = Forms.parseAmount(fee)?.toDec(),
            tax = Forms.parseAmount(tax)?.toDec(), broker = broker, note = note,
        )
        vm.edit<PortTxn>(ok = "บันทึกแล้ว", onError = { error = it }, onOk = { onClose() }) {
            if (e == null) createPort(row) else updatePort(row)
        }
    }

    FormPage(if (e == null) "เพิ่มรายการพอร์ต" else "แก้ไขรายการพอร์ต", onClose, ::save) {
        error?.let { Note(it, DanshaColors.Negative) }
        ChoiceChips(PORT_SIDES.map { it to (SIDE_LABELS[it] ?: it) }, side, { side = it })
        Row {
            TextInput("ชื่อย่อ", symbol, { symbol = it.uppercase() }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextInput("ชื่อ", name, { name = it }, Modifier.weight(1f))
        }
        ChoiceChips(ASSET_TYPES.map { it to (TYPE_LABELS[it] ?: it) }, assetType, { assetType = it })
        DateField("วันที่", date, { date = it })
        if (trade) AmountField("จำนวนหน่วย", qty, { qty = it })
        AmountField(if (trade) "ราคาต่อหน่วย" else if (side == "dividend") "ปันผลรวม (ก่อนภาษี)" else "ค่าธรรมเนียม", price, { price = it })
        Picker("สกุลเงิน", Schema.CURRENCIES.map { it to it }, currency, { currency = it })
        if (currency != "THB") AmountField("เรทที่ใช้จริง (บาทต่อ 1 $currency)", fxRate, { fxRate = it })
        Row {
            AmountField("ค่าธรรมเนียม", fee, { fee = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AmountField("ภาษีหัก ณ ที่จ่าย", tax, { tax = it }, Modifier.weight(1f))
        }
        TextInput("โบรกเกอร์", broker, { broker = it })
        TextInput("โน้ต", note, { note = it })
        if (e != null) DeleteButton("รายการนี้") {
            vm.edit<Unit>(ok = "ลบแล้ว", onError = { error = it }, onOk = { onClose() }) { deletePort(e.id) }
        }
    }
}

@Composable
private fun PricesForm(d: DanshaData, vm: MainViewModel, onClose: () -> Unit) {
    val holdings = remember(d) { runCatching { Engine.portfolio(d).holdings.filter { it.qty > 0 } }.getOrDefault(emptyList()) }
    val values = remember { mutableStateMapOf<String, String>().apply { holdings.forEach { put(it.symbol, amountText(it.price)) } } }
    FormPage("อัปเดตราคาล่าสุด", onClose, onSave = {
        val data = holdings.associate { h -> h.symbol to (Forms.parseAmount(values[h.symbol].orEmpty()) to h.currency) }
        vm.edit<Unit>(ok = "อัปเดตราคาแล้ว", onOk = { onClose() }) { setPrices(data) }
    }) {
        if (holdings.isEmpty()) Note("ยังไม่มีหลักทรัพย์ที่ถืออยู่")
        holdings.forEach { h ->
            AmountField("${h.symbol} (${h.currency})", values[h.symbol].orEmpty(), { values[h.symbol] = it },
                supporting = h.priceUpdated?.let { "อัปเดตล่าสุด ${thaiDate(it.take(10))}" })
        }
    }
}
