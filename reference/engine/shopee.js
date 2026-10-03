/**
 * Shopee Tracker + SPayLater + เช็คก่อนซื้อ
 *
 * SPayLater (บัญชี revolving_credit ที่ debt_engine = 'spaylater'):
 *  - ใช้ค่างวดที่ผู้ใช้กรอกจากแอพ Shopee (ไม่มี statement ให้ตรวจสูตร)
 *  - รอบบิลเดือน M = ออเดอร์ 15/M – 14/M+1 → ครบกำหนดวันที่ 25 ของเดือน M+1 (ตามแอพ Shopee) → งวดถัดไปทุกเดือน
 *  - ชำระ = โอน/รายรับเข้าบัญชี SPayLater → ตัดงวดที่ครบกำหนดก่อนสุดก่อน
 *  - "ยอดใช้ไป" = ค่างวดที่เหลือทั้งหมด (รวมดอกที่ยังไม่ถึงกำหนด) — เป็นค่าประมาณจากยอดที่ Shopee แจ้ง
 */
import { SHEETS } from './schema.js';
import { MONTHS_EN, fmtDate_, now_, parseDate_, payCycleOf_, payCycleRange_, readConfig_, readFx_, readTable_, round2_, today_, updateRow_ } from './store.js';
import { debtStates_, resetDebtMemo_ } from './debt.js';
import { cashNowThb_ } from './plan.js';
import { create_, withBalances_ } from './api.js';
export function isSplAccount_(a) {
  return !!a && a.type === 'revolving_credit' && a.debt_engine === 'spaylater';
}

export function splDueDate_(orderDate, k) {
  // งวดที่ k (เริ่ม 1): วันที่ 25 ของเดือน (เดือนรอบบิล + k) — ออเดอร์ก่อนวันที่ 15 นับเป็นรอบบิลเดือนก่อน
  const d = parseDate_(orderDate);
  const billMonth = d.getMonth() - (d.getDate() < 15 ? 1 : 0);
  return fmtDate_(new Date(d.getFullYear(), billMonth + k, 25));
}

/** วันตัดรอบของบิลที่ครบกำหนด due (วันที่ 15 ของเดือนถัดจากเดือนรอบบิล = เดือนเดียวกับ due) */
export function splStmtDate_(due) {
  return due.slice(0, 8) + '15';
}

export function splInstallments_(orders) {
  const list = [];
  orders.forEach(o => {
    const n = Math.max(1, Number(o.tenor) || 1);
    const inst = n === 1 ? Number(o.price) : (Number(o.installment) || round2_(Number(o.price) / n));
    for (let k = 1; k <= n; k++) {
      // งวดสุดท้ายปรับเศษให้รวมเท่ากับยอดผ่อนทั้งหมด (กรณีผ่อน 0%)
      const amt = (!o.installment && k === n) ? round2_(Number(o.price) - inst * (n - 1)) : inst;
      list.push({ order: o.id, item: o.item, k: k, n: n, due: splDueDate_(o.order_date, k), amount: amt });
    }
  });
  return list.sort((a, b) => a.due.localeCompare(b.due) || a.order.localeCompare(b.order));
}

export function splState_(account, orders, txns, today) {
  const mine = orders.filter(o => o.account_id === account.id && o.pay_method === 'spaylater' && o.status !== 'cancelled');
  const inst = splInstallments_(mine);
  const since = account.debt_since || '0000-00-00';
  let paid = round2_(txns.filter(t => t.date >= since && ((t.type === 'transfer' && t.to_account_id === account.id) || (t.type === 'income' && t.account_id === account.id)))
    .reduce((s, t) => s + Number(t.amount), 0));
  inst.forEach(i => {
    const x = Math.min(paid, i.amount);
    i.paid = round2_(x); i.left = round2_(i.amount - x); paid = round2_(paid - x);
  });
  const open = inst.filter(i => i.left > 0.004);
  const byDue = {};
  open.forEach(i => { byDue[i.due] = round2_((byDue[i.due] || 0) + i.left); });
  const dues = Object.keys(byDue).sort().map(d => ({ due: d, totalDue: byDue[d] }));
  const overdue = dues.filter(d => d.due < today);
  const upcoming = dues.filter(d => d.due >= today);
  const perOrder = {};
  mine.forEach(o => {
    const rows = inst.filter(i => i.order === o.id);
    perOrder[o.id] = { paidCount: rows.filter(i => i.left <= 0.004).length, left: round2_(rows.reduce((s, i) => s + i.left, 0)), next: (rows.filter(i => i.left > 0.004)[0] || {}).due || null };
  });
  const outstanding = round2_(open.reduce((s, i) => s + i.left, 0));
  return {
    accountId: account.id, engine: 'spaylater', asOf: today, outstanding: outstanding, credit: round2_(paid),
    currentBill: overdue.length ? { due: overdue[0].due, amount: round2_(overdue.reduce((s, d) => s + d.totalDue, 0)), overdue: true }
      : (upcoming[0] && upcoming[0].due.slice(0, 7) === today.slice(0, 7) ? { due: upcoming[0].due, amount: upcoming[0].totalDue } : null),
    nextBill: upcoming[0] ? { date: splStmtDate_(upcoming[0].due), due: upcoming[0].due, totalDue: upcoming[0].totalDue } : null,
    bills: upcoming.map(d => ({ date: splStmtDate_(d.due), due: d.due, totalDue: d.totalDue })),
    installments: inst, perOrder: perOrder,
  };
}

// ---------- เช็คก่อนซื้อ ----------

export function shiftCycle_(cycle, delta) {
  let y = Number(cycle.slice(0, 4)), m = Number(cycle.slice(5, 7)) - 1 + delta;
  y += Math.floor(m / 12); m = ((m % 12) + 12) % 12;
  return y + '-' + String(m + 1).padStart(2, '0') + ' ' + MONTHS_EN[m] + '-' + MONTHS_EN[(m + 1) % 12];
}

/**
 * ภาพรวมรายรอบ (รอบนี้ + 5 รอบถัดไป): รายรับที่คาด / รายจ่ายตามแผน / ค่างวดหนี้ / คงเหลือ
 * รอบนี้ใช้ "คงเหลือคาดการณ์" จริง (เงินตอนนี้ + รายรับที่ยังไม่เข้า − ที่ยังไม่จ่าย)
 * รอบถัดไป: ใช้แผนบิลของรอบนั้นถ้ามี ไม่มีใช้แม่แบบ, ค่างวดหนี้มาจากตัวคำนวณหนี้
 */
export function cycleOutlook_(count) {
  const cfg = readConfig_();
  const startDay = Number(cfg.pay_cycle_start_day) || 15;
  const txns = readTable_(SHEETS.TXN);
  const accounts = withBalances_(readTable_(SHEETS.ACCOUNT), txns);
  const fx = readFx_();
  const debt = debtStates_(accounts, txns, true);
  const bills = readTable_(SHEETS.BILL);
  const templates = readTable_(SHEETS.BILL_TEMPLATE).filter(t => t.active !== false);
  const current = payCycleOf_(now_(), startDay);
  const today = today_();
  const out = [];
  for (let i = 0; i < count; i++) {
    const cycle = shiftCycle_(current, i);
    const range = payCycleRange_(cycle, startDay);
    const rows = bills.filter(b => b.pay_cycle === cycle);
    const items = rows.length ? rows.map(b => ({ group: b.group, amount: b.status === 'paid' ? 0 : Number(b.est_amount) || 0, to: b.to_account_id, paid: b.status === 'paid' }))
      : templates.map(t => ({ group: t.group, amount: Number(t.est_amount) || 0, to: t.to_account_id }));
    const covered = {};
    items.forEach(it => { if (it.to) covered[it.to] = true; });
    // ค่างวดหนี้ของรอบ (จากตัวคำนวณ) — แทนยอดในแผนของบัญชีที่ผูก, บวกเพิ่มถ้าไม่มีในแผน
    let debtDue = 0;
    Object.keys(debt).forEach(accId => {
      const st = debt[accId];
      const list = (st.currentBill ? [{ due: st.currentBill.due, totalDue: st.currentBill.amount }] : []).concat(st.bills || []);
      const seen = {};
      list.forEach(b => { if (!seen[b.due] && b.due >= range.start && b.due <= range.end && b.due >= today) { seen[b.due] = 1; debtDue += b.totalDue; } });
    });
    const income = items.filter(x => x.group === 'income').reduce((s, x) => s + x.amount, 0);
    const planned = items.filter(x => x.group !== 'income' && !(x.to && debt[x.to])).reduce((s, x) => s + x.amount, 0);
    const incomeFull = rows.length ? rows.filter(b => b.group === 'income').reduce((s, b) => s + (Number(b.status === 'paid' ? b.actual_amount : b.est_amount) || 0), 0)
      : income;
    const base = i === 0 ? cashNowThb_(accounts, fx) + income : income;
    out.push({
      cycle: cycle, range: range, income: round2_(income), incomeFull: round2_(incomeFull), planned: round2_(planned), debtDue: round2_(debtDue),
      cashNow: i === 0 ? round2_(cashNowThb_(accounts, fx)) : null,
      remaining: round2_(base - planned - debtDue), fromPlan: rows.length > 0,
    });
  }
  return { cycles: out, debt: debt };
}

/** p = { price, method: 'spaylater'|'full', tenor, installment, date } */
export function checkPurchase_(p) {
  const price = Number(p.price);
  if (!(price > 0)) throw new Error('กรุณากรอกราคา');
  const date = p.date || today_();
  const tenor = p.method === 'spaylater' ? Math.max(1, Number(p.tenor) || 1) : 1;
  const inst = p.method === 'spaylater' ? (tenor === 1 ? price : (Number(p.installment) || round2_(price / tenor))) : price;
  const cfg = readConfig_();
  const startDay = Number(cfg.pay_cycle_start_day) || 15;
  const look = cycleOutlook_(7);
  const cycles = look.cycles;

  // ค่างวดใหม่ตกอยู่ในรอบไหน
  const add = {};
  if (p.method === 'spaylater') {
    for (let k = 1; k <= tenor; k++) {
      const c = payCycleOf_(splDueDate_(date, k), startDay);
      add[c] = round2_((add[c] || 0) + (k === tenor && !p.installment && tenor > 1 ? round2_(price - inst * (tenor - 1)) : inst));
    }
  } else {
    add[payCycleOf_(date, startDay)] = price;
  }
  const impact = cycles.map(c => {
    const extra = add[c.cycle] || 0;
    const after = round2_(c.remaining - extra);
    return Object.assign({}, c, { extra: extra, after: after, burden: c.incomeFull > 0 ? round2_(((c.debtDue + (p.method === 'spaylater' ? extra : 0)) / c.incomeFull) * 100) : null,
      burdenBefore: c.incomeFull > 0 ? round2_((c.debtDue / c.incomeFull) * 100) : null });
  });
  const later = Object.keys(add).filter(c => !cycles.some(x => x.cycle === c));

  // เทียบจ่ายเต็ม vs ผ่อน (ประมาณด้วยดอก 25%/ปี ถ้าไม่ได้กรอกค่างวดจริง)
  const rateGuess = (Number(p.annual_rate) || 25) / 100;
  const est = n => { if (n === 1) return price; const r = rateGuess / 12; return round2_(price * r / (1 - Math.pow(1 + r, -n))); };
  const options = [1, 3, 6, 10, 12].map(n => ({ tenor: n, installment: est(n), total: round2_(est(n) * n), interest: round2_(est(n) * n - price), estimated: true }));
  const chosen = { tenor: tenor, installment: inst, total: round2_(p.method === 'spaylater' ? (tenor === 1 ? price : inst * tenor) : price) };
  chosen.interest = round2_(chosen.total - price);
  chosen.effectiveRate = tenor > 1 && chosen.interest > 0 ? round2_(impliedRate_(price, inst, tenor) * 100) : 0;

  const now = impact[0];
  const warnPct = Number(cfg.shop_warn_pct) || 30;
  const buffer = Number(cfg.shop_buffer) || 0;
  const pctOfLeft = now.remaining > 0 ? round2_((price / now.remaining) * 100) : null;
  const firstPayPct = now.remaining > 0 ? round2_(((add[now.cycle] || 0) / now.remaining) * 100) : null;
  const worst = impact.reduce((w, c) => (w == null || c.after < w.after ? c : w), null);
  const withIncome = impact.filter(c => c.burden != null);
  const maxBurden = withIncome.length ? withIncome.reduce((m, c) => Math.max(m, c.burden), 0) : null;

  const flags = [];
  if (pctOfLeft != null && pctOfLeft >= warnPct) flags.push({ level: pctOfLeft >= 50 ? 'red' : 'yellow', text: 'ราคาเต็มคิดเป็น ' + pctOfLeft + '% ของเงินที่เหลือรอบนี้' });
  if (!impact.some(c => c.incomeFull > 0)) flags.push({ level: 'yellow', text: 'ยังไม่มีรายรับในแผน — ตั้งแม่แบบ "เงินเดือน" ในหน้าแผนบิล ผลเช็คจะแม่นขึ้น' });
  if (now.remaining < 0) flags.push({ level: 'red', text: 'รอบนี้เงินคงเหลือคาดการณ์ติดลบอยู่แล้ว' });
  impact.forEach(c => { if (c.extra > 0 && c.after < buffer) flags.push({ level: 'red', text: 'รอบ ' + c.cycle.slice(8) + ' จะเหลือ ' + c.after.toFixed(2) + ' บาท (ต่ำกว่าเงินกันไว้ ' + buffer + ')' }); });
  if (maxBurden >= 40) flags.push({ level: 'red', text: 'ภาระค่างวดหนี้สูงสุด ' + maxBurden + '% ของรายรับ (เกิน 40%)' });
  else if (maxBurden >= 30) flags.push({ level: 'yellow', text: 'ภาระค่างวดหนี้สูงสุด ' + maxBurden + '% ของรายรับ' });
  if (chosen.interest > 0) flags.push({ level: 'yellow', text: 'ผ่อนแล้วเสียดอกรวม ' + chosen.interest.toFixed(2) + ' บาท' });
  const verdict = flags.some(f => f.level === 'red') ? 'red' : flags.some(f => f.level === 'yellow') ? 'yellow' : 'green';

  return {
    price: price, method: p.method, date: date, chosen: chosen, options: options,
    pctOfLeft: pctOfLeft, firstPayPct: firstPayPct, remainingNow: now.remaining,
    impact: impact, laterCycles: later, worst: worst, maxBurden: maxBurden, flags: flags, verdict: verdict,
  };
}

/** หาอัตราดอกเบี้ยต่อปีจากค่างวด (bisection) */
export function impliedRate_(P, A, n) {
  let lo = 0, hi = 2;
  for (let i = 0; i < 60; i++) {
    const mid = (lo + hi) / 2, r = mid / 12;
    const pmt = r === 0 ? P / n : P * r / (1 - Math.pow(1 + r, -n));
    if (pmt > A) hi = mid; else lo = mid;
  }
  return (lo + hi) / 2;
}

/** ซื้อจริง: บันทึกออเดอร์ (+ รายจ่ายถ้าจ่ายเต็ม) + อัปเดตค่างวด SPayLater ในแผนบิลที่สร้างไว้แล้ว */
export function recordPurchase_(p) {
  const price = Number(p.price);
  if (!p.item) throw new Error('กรุณากรอกชื่อสินค้า');
  if (!(price > 0)) throw new Error('กรุณากรอกราคา');
  const date = p.date || today_();
  const accounts = readTable_(SHEETS.ACCOUNT);
  const acc = accounts.filter(a => a.id === p.account_id)[0];
  if (!acc) throw new Error('กรุณาเลือกบัญชีที่จ่าย');
  const method = isSplAccount_(acc) ? 'spaylater' : 'full';
  const tenor = method === 'spaylater' ? Math.max(1, Number(p.tenor) || 1) : 1;
  const order = {
    order_date: date, item: p.item, shop: p.shop || '', price: price, tenor: tenor, annual_rate: p.annual_rate || '',
    installment: method === 'spaylater' ? (tenor === 1 ? price : Number(p.installment) || '') : '',
    account_id: acc.id, pay_method: method, status: 'active', category_id: p.category_id || '', note: p.note || '', order_no: p.order_no || '',
  };
  if (method === 'spaylater' && tenor > 1 && !order.installment) throw new Error('กรุณากรอกค่างวดจากแอพ Shopee');
  let txn = null;
  if (method === 'full') {
    txn = create_('transactions', { date: date, type: 'expense', account_id: acc.id, amount: price, currency: acc.currency,
      category_id: p.category_id || '', subcategory_id: p.subcategory_id || '', note: 'Shopee: ' + p.item, source: 'shopee' });
    order.txn_id = txn.id;
  }
  const saved = create_('shopee', order);
  if (method === 'spaylater') syncSplBills_(acc.id);
  return { order: saved, txn: txn };
}

/** ปรับยอดประมาณของรายการค่างวด SPayLater ในแผนบิลที่ยังไม่จ่าย (ทุกรอบที่สร้างไว้แล้ว) */
export function syncSplBills_(accountId) {
  resetDebtMemo_();
  const txns = readTable_(SHEETS.TXN);
  const acc = readTable_(SHEETS.ACCOUNT).filter(a => a.id === accountId)[0];
  const st = splState_(acc, readTable_(SHEETS.SHOPEE), txns, today_());
  const startDay = Number(readConfig_().pay_cycle_start_day) || 15;
  readTable_(SHEETS.BILL).filter(b => b.to_account_id === accountId && b.status !== 'paid').forEach(b => {
    const range = payCycleRange_(b.pay_cycle, startDay);
    const due = st.bills.filter(x => x.due >= range.start && x.due <= range.end)[0];
    const amt = due ? due.totalDue : 0;
    if (Number(b.est_amount) !== amt) updateRow_(SHEETS.BILL, b.id, { est_amount: amt, due_date: due ? due.due : b.due_date });
  });
}

/** สถิติพฤติกรรมช้อปปิ้ง */
export function shopeeStats_() {
  const orders = readTable_(SHEETS.SHOPEE).filter(o => o.status !== 'cancelled');
  const byMonth = {}, byShop = {};
  let installmentTotal = 0, interestTotal = 0;
  orders.forEach(o => {
    const m = String(o.order_date).slice(0, 7);
    const x = byMonth[m] = byMonth[m] || { month: m, full: 0, spaylater: 0, count: 0 };
    x[o.pay_method === 'spaylater' ? 'spaylater' : 'full'] += Number(o.price); x.count++;
    const s = byShop[o.shop || '(ไม่ระบุร้าน)'] = byShop[o.shop || '(ไม่ระบุร้าน)'] || { shop: o.shop || '(ไม่ระบุร้าน)', total: 0, count: 0 };
    s.total += Number(o.price); s.count++;
    if (o.pay_method === 'spaylater' && Number(o.tenor) > 1) {
      installmentTotal += Number(o.price);
      interestTotal += Math.max(0, Number(o.installment) * Number(o.tenor) - Number(o.price));
    }
  });
  const total = orders.reduce((s, o) => s + Number(o.price), 0);
  return {
    count: orders.length, total: round2_(total), avg: orders.length ? round2_(total / orders.length) : 0,
    installmentShare: total ? round2_((installmentTotal / total) * 100) : 0, interestTotal: round2_(interestTotal),
    byMonth: Object.keys(byMonth).sort().map(k => byMonth[k]),
    byShop: Object.keys(byShop).map(k => byShop[k]).sort((a, b) => b.total - a.total).slice(0, 8),
  };
}
