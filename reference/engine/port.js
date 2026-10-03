/**
 * Port ลงทุน — หุ้นรายตัว / ETF / กองทุนรวม (กรอกราคาเอง ไม่ดึงราคาสด)
 * วิธีคิดต้นทุน: ถัวเฉลี่ย (average cost) แยกทั้งสกุลเงินของหลักทรัพย์ และเป็นบาท (ตามเรทวันที่ซื้อจริง)
 *
 * side:
 *   buy      qty × price + fee            → เพิ่มจำนวน/ต้นทุน
 *   sell     qty × price − fee            → กำไรที่รับรู้แล้ว = รับจริง − ต้นทุนเฉลี่ย × qty (ต้นทุนเฉลี่ยไม่เปลี่ยน)
 *   dividend price = เงินปันผลรวม (ก่อนภาษี), tax = ภาษีหัก ณ ที่จ่าย
 *   fee      price = ค่าธรรมเนียมอื่นๆ (เช่น ค่า custodian) นับเป็นขาดทุนที่รับรู้
 * fx_rate = บาทต่อ 1 หน่วยสกุลเงินของรายการ (THB = 1)
 */
import { CURRENCIES, SHEETS } from './schema.js';
import { hasRow_, insertRow_, nowIso_, readFx_, readTable_, round2_, updateRow_ } from './store.js';
export const PORT_SIDES = ['buy', 'sell', 'dividend', 'fee'];
export const ASSET_TYPES = ['stock', 'etf', 'fund', 'gold', 'other'];

export function portfolio_() {
  const trades = readTable_(SHEETS.PORT).slice().sort((a, b) => a.date.localeCompare(b.date) || String(a.created_at).localeCompare(String(b.created_at)));
  const prices = {};
  readTable_(SHEETS.PRICES).forEach(p => { prices[p.symbol] = p; });
  const fx = readFx_();
  const pos = {};
  let realizedThb = 0, dividendThb = 0, feesThb = 0, taxThb = 0;

  trades.forEach(t => {
    const sym = String(t.symbol).toUpperCase();
    const p = pos[sym] = pos[sym] || { symbol: sym, name: t.name || '', asset_type: t.asset_type || 'stock', currency: t.currency || 'THB', broker: t.broker || '',
      qty: 0, cost: 0, costThb: 0, realized: 0, realizedThb: 0, dividends: 0, dividendsThb: 0, fees: 0, feesThb: 0, trades: 0 };
    if (t.name) p.name = t.name;
    const rate = Number(t.fx_rate) || (t.currency === 'THB' ? 1 : (fx[t.currency] || 1));
    const qty = Number(t.qty) || 0, price = Number(t.price) || 0, fee = Number(t.fee) || 0, tax = Number(t.tax) || 0;
    p.trades++;
    if (t.side === 'buy') {
      p.qty += qty; p.cost += qty * price + fee; p.costThb += (qty * price + fee) * rate;
      p.fees += fee; p.feesThb += fee * rate; feesThb += fee * rate;
    } else if (t.side === 'sell') {
      const avg = p.qty > 0 ? p.cost / p.qty : 0, avgThb = p.qty > 0 ? p.costThb / p.qty : 0;
      const q = Math.min(qty, p.qty);
      const proceeds = q * price - fee - tax;
      const r = proceeds - avg * q, rThb = proceeds * rate - avgThb * q;
      p.realized += r; p.realizedThb += rThb; realizedThb += rThb;
      p.cost -= avg * q; p.costThb -= avgThb * q; p.qty -= q;
      p.fees += fee; p.feesThb += fee * rate; feesThb += fee * rate; taxThb += tax * rate;
      if (p.qty < 1e-9) { p.qty = 0; p.cost = 0; p.costThb = 0; }
    } else if (t.side === 'dividend') {
      const net = price - tax;
      p.dividends += net; p.dividendsThb += net * rate; dividendThb += net * rate; taxThb += tax * rate;
    } else if (t.side === 'fee') {
      p.fees += price; p.feesThb += price * rate; feesThb += price * rate; realizedThb -= price * rate; p.realizedThb -= price * rate;
    }
  });

  const holdings = Object.keys(pos).map(k => pos[k]).map(p => {
    const pr = prices[p.symbol];
    const nowFx = p.currency === 'THB' ? 1 : (fx[p.currency] || 1);
    const last = pr ? Number(pr.price) : null;
    const avg = p.qty > 0 ? p.cost / p.qty : 0;
    const avgFx = p.cost > 0 ? p.costThb / p.cost : nowFx;
    const value = last != null ? p.qty * last : null;
    const valueThb = value != null ? value * nowFx : null;
    const unrealThb = valueThb != null ? valueThb - p.costThb : null;
    return {
      symbol: p.symbol, name: p.name, asset_type: p.asset_type, currency: p.currency, broker: p.broker, trades: p.trades,
      qty: round6_(p.qty), avgCost: round6_(avg), avgFx: round6_(avgFx), cost: round2_(p.cost), costThb: round2_(p.costThb),
      price: last, priceUpdated: pr ? pr.updated_at : null, fxNow: nowFx,
      value: value != null ? round2_(value) : null, valueThb: valueThb != null ? round2_(valueThb) : null,
      unrealized: value != null ? round2_(value - p.cost) : null, unrealizedThb: unrealThb != null ? round2_(unrealThb) : null,
      unrealizedPct: unrealThb != null && p.costThb > 0 ? round2_((unrealThb / p.costThb) * 100) : null,
      // กำไรจากราคา vs จากค่าเงิน (เฉพาะสกุลต่างประเทศ)
      priceEffectThb: value != null && p.currency !== 'THB' ? round2_((value - p.cost) * nowFx) : null,
      fxEffectThb: p.currency !== 'THB' && p.qty > 0 ? round2_(p.cost * (nowFx - avgFx)) : null,
      realizedThb: round2_(p.realizedThb), dividendsThb: round2_(p.dividendsThb), feesThb: round2_(p.feesThb),
    };
  });
  const open = holdings.filter(h => h.qty > 0);
  const valueThb = round2_(open.reduce((s, h) => s + (h.valueThb != null ? h.valueThb : h.costThb), 0));
  const costThb = round2_(open.reduce((s, h) => s + h.costThb, 0));
  return {
    holdings: holdings.sort((a, b) => (b.valueThb || b.costThb || 0) - (a.valueThb || a.costThb || 0)),
    summary: {
      valueThb: valueThb, costThb: costThb, unrealizedThb: round2_(valueThb - costThb), unrealizedPct: costThb > 0 ? round2_(((valueThb - costThb) / costThb) * 100) : 0,
      realizedThb: round2_(realizedThb), dividendThb: round2_(dividendThb), feesThb: round2_(feesThb), taxThb: round2_(taxThb),
      missingPrice: open.filter(h => h.price == null).map(h => h.symbol),
      byType: ASSET_TYPES.map(t => ({ type: t, valueThb: round2_(open.filter(h => h.asset_type === t).reduce((s, h) => s + (h.valueThb != null ? h.valueThb : h.costThb), 0)) })).filter(x => x.valueThb > 0),
    },
    fx: fx,
  };
}

export function round6_(n) { return Math.round(n * 1e6) / 1e6; }

/** อัปเดตราคาล่าสุด: data = { SYMBOL: { price, currency } } */
export function setPrices_(data) {
  const now = nowIso_();
  Object.keys(data || {}).forEach(sym => {
    const v = data[sym];
    const price = Number(v.price);
    if (!(price >= 0) || v.price === '' || v.price == null) return;
    const s = String(sym).toUpperCase();
    const row = { symbol: s, price: price, currency: v.currency || 'THB', updated_at: now };
    if (hasRow_(SHEETS.PRICES, s)) updateRow_(SHEETS.PRICES, s, row);
    else insertRow_(SHEETS.PRICES, row);
  });
  return readTable_(SHEETS.PRICES);
}

export function validatePort_(d) {
  if (PORT_SIDES.indexOf(d.side) < 0) throw new Error('ประเภทรายการไม่ถูกต้อง');
  if (!d.symbol) throw new Error('กรุณากรอกชื่อย่อหลักทรัพย์');
  d.symbol = String(d.symbol).trim().toUpperCase();
  if (!d.date) throw new Error('กรุณาระบุวันที่');
  if (!d.currency) d.currency = 'THB';
  if (CURRENCIES.indexOf(d.currency) < 0) throw new Error('สกุลเงินไม่รองรับ');
  if ((d.side === 'buy' || d.side === 'sell') && !(Number(d.qty) > 0)) throw new Error('จำนวนหน่วยต้องมากกว่า 0');
  if (!(Number(d.price) >= 0)) throw new Error('ราคา/จำนวนเงินไม่ถูกต้อง');
  if (d.currency === 'THB') d.fx_rate = 1;
  else if (!(Number(d.fx_rate) > 0)) throw new Error('กรุณากรอกเรทแลกเปลี่ยนที่ใช้จริง (บาทต่อ 1 ' + d.currency + ')');
  if (!d.asset_type) d.asset_type = 'stock';
  if (!d.broker) d.broker = 'Dime!';
}
