package ism.dansha.core.engine

import ism.dansha.core.PortTxn
import ism.dansha.core.Price
import kotlinx.serialization.Serializable
import kotlin.math.min

/*
 * พอร์ตลงทุน — port จาก port.js (กรอกราคาเอง ไม่ดึงราคาสด)
 * ต้นทุนถัวเฉลี่ย แยกทั้งสกุลเงินของหลักทรัพย์และเป็นบาท (ตามเรทวันที่ซื้อจริง)
 *   buy      qty × price + fee   → เพิ่มจำนวน/ต้นทุน
 *   sell     กำไรรับรู้ = รับจริง − ต้นทุนเฉลี่ย × qty
 *   dividend price = ปันผลรวม, tax = ภาษีหัก ณ ที่จ่าย (นับสุทธิ)
 *   fee      price = ค่าธรรมเนียมอื่น นับเป็นขาดทุนที่รับรู้
 */

val PORT_SIDES = listOf("buy", "sell", "dividend", "fee")
val ASSET_TYPES = listOf("stock", "etf", "fund", "gold", "other")

@Serializable
data class Holding(
    val symbol: String,
    val name: String,
    val asset_type: String,
    val currency: String,
    val broker: String,
    val trades: Int,
    val qty: Double,
    val avgCost: Double,
    val avgFx: Double,
    val cost: Double,
    val costThb: Double,
    val price: Double?,
    val priceUpdated: String?,
    val fxNow: Double,
    val value: Double?,
    val valueThb: Double?,
    val unrealized: Double?,
    val unrealizedThb: Double?,
    val unrealizedPct: Double?,
    /** กำไรจากราคา / จากค่าเงิน (เฉพาะสกุลต่างประเทศ) */
    val priceEffectThb: Double?,
    val fxEffectThb: Double?,
    val realizedThb: Double,
    val dividendsThb: Double,
    val feesThb: Double,
)

@Serializable
data class TypeValue(val type: String, val valueThb: Double)

@Serializable
data class PortSummary(
    val valueThb: Double,
    val costThb: Double,
    val unrealizedThb: Double,
    val unrealizedPct: Double,
    val realizedThb: Double,
    val dividendThb: Double,
    val feesThb: Double,
    val taxThb: Double,
    val missingPrice: List<String>,
    val byType: List<TypeValue>,
)

@Serializable
data class Portfolio(val holdings: List<Holding>, val summary: PortSummary)

object PortfolioCalc {
    private class Pos(val symbol: String, var name: String, val assetType: String, val currency: String, val broker: String) {
        var qty = 0.0
        var cost = 0.0
        var costThb = 0.0
        var realized = 0.0
        var realizedThb = 0.0
        var dividends = 0.0
        var dividendsThb = 0.0
        var fees = 0.0
        var feesThb = 0.0
        var trades = 0
    }

    fun compute(port: List<PortTxn>, prices: List<Price>, fx: Map<String, Double>): Portfolio {
        val trades = port.sortedWith(compareBy<PortTxn> { it.date }.thenBy { it.created_at })
        val priceOf = HashMap<String, Price>()
        prices.forEach { priceOf[it.symbol] = it }
        val pos = LinkedHashMap<String, Pos>()
        var realizedThb = 0.0
        var dividendThb = 0.0
        var feesThb = 0.0
        var taxThb = 0.0

        trades.forEach { t ->
            val sym = t.symbol.uppercase()
            val p = pos.getOrPut(sym) {
                Pos(sym, t.name, t.asset_type.ifEmpty { "stock" }, t.currency.ifEmpty { "THB" }, t.broker)
            }
            if (t.name.isNotEmpty()) p.name = t.name
            val rate = t.fx_rate.num().orIfFalsy(if (t.currency == "THB") 1.0 else (fx[t.currency]?.orIfFalsy(1.0) ?: 1.0))
            val qty = t.qty.num()
            val price = t.price.num()
            val fee = t.fee.num()
            val tax = t.tax.num()
            p.trades++
            when (t.side) {
                "buy" -> {
                    p.qty += qty; p.cost += qty * price + fee; p.costThb += (qty * price + fee) * rate
                    p.fees += fee; p.feesThb += fee * rate; feesThb += fee * rate
                }
                "sell" -> {
                    val avg = if (p.qty > 0) p.cost / p.qty else 0.0
                    val avgThb = if (p.qty > 0) p.costThb / p.qty else 0.0
                    val q = min(qty, p.qty)
                    val proceeds = q * price - fee - tax
                    val r = proceeds - avg * q
                    val rThb = proceeds * rate - avgThb * q
                    p.realized += r; p.realizedThb += rThb; realizedThb += rThb
                    p.cost -= avg * q; p.costThb -= avgThb * q; p.qty -= q
                    p.fees += fee; p.feesThb += fee * rate; feesThb += fee * rate; taxThb += tax * rate
                    if (p.qty < 1e-9) { p.qty = 0.0; p.cost = 0.0; p.costThb = 0.0 }
                }
                "dividend" -> {
                    val net = price - tax
                    p.dividends += net; p.dividendsThb += net * rate; dividendThb += net * rate; taxThb += tax * rate
                }
                "fee" -> {
                    p.fees += price; p.feesThb += price * rate; feesThb += price * rate; realizedThb -= price * rate; p.realizedThb -= price * rate
                }
            }
        }

        val holdings = pos.values.map { p ->
            val pr = priceOf[p.symbol]
            val nowFx = if (p.currency == "THB") 1.0 else (fx[p.currency]?.orIfFalsy(1.0) ?: 1.0)
            val last = pr?.let { it.price.num() }
            val avg = if (p.qty > 0) p.cost / p.qty else 0.0
            val avgFx = if (p.cost > 0) p.costThb / p.cost else nowFx
            val value = last?.let { p.qty * it }
            val valueThb = value?.let { it * nowFx }
            val unrealThb = valueThb?.let { it - p.costThb }
            Holding(
                symbol = p.symbol, name = p.name, asset_type = p.assetType, currency = p.currency, broker = p.broker, trades = p.trades,
                qty = round6(p.qty), avgCost = round6(avg), avgFx = round6(avgFx), cost = round2(p.cost), costThb = round2(p.costThb),
                price = last, priceUpdated = pr?.updated_at, fxNow = nowFx,
                value = value?.let(::round2), valueThb = valueThb?.let(::round2),
                unrealized = value?.let { round2(it - p.cost) }, unrealizedThb = unrealThb?.let(::round2),
                unrealizedPct = if (unrealThb != null && p.costThb > 0) round2((unrealThb / p.costThb) * 100) else null,
                priceEffectThb = if (value != null && p.currency != "THB") round2((value - p.cost) * nowFx) else null,
                fxEffectThb = if (p.currency != "THB" && p.qty > 0) round2(p.cost * (nowFx - avgFx)) else null,
                realizedThb = round2(p.realizedThb), dividendsThb = round2(p.dividendsThb), feesThb = round2(p.feesThb),
            )
        }
        val open = holdings.filter { it.qty > 0 }
        fun shown(h: Holding) = h.valueThb ?: h.costThb
        val valueThb = round2(open.fold(0.0) { s, h -> s + shown(h) })
        val costThb = round2(open.fold(0.0) { s, h -> s + h.costThb })
        // (b.valueThb || b.costThb || 0) − (a.valueThb || a.costThb || 0)
        fun sortKey(h: Holding) = (h.valueThb?.orIfFalsy(h.costThb) ?: h.costThb)
        return Portfolio(
            holdings = holdings.sortedByDescending(::sortKey),
            summary = PortSummary(
                valueThb = valueThb, costThb = costThb, unrealizedThb = round2(valueThb - costThb),
                unrealizedPct = if (costThb > 0) round2(((valueThb - costThb) / costThb) * 100) else 0.0,
                realizedThb = round2(realizedThb), dividendThb = round2(dividendThb), feesThb = round2(feesThb), taxThb = round2(taxThb),
                missingPrice = open.filter { it.price == null }.map { it.symbol },
                byType = ASSET_TYPES.map { t -> TypeValue(t, round2(open.filter { it.asset_type == t }.fold(0.0) { s, h -> s + shown(h) })) }
                    .filter { it.valueThb > 0 },
            ),
        )
    }
}
