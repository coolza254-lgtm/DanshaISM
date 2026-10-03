/**
 * Debt Tracker — เชื่อมข้อมูลกับ DebtEngine
 * บัญชีที่ debt_engine = 'ascend' จะคำนวณยอดจากหนี้ย่อย (ตาราง debts) + รายการใน Transactions:
 *   - รายจ่ายจากบัญชีนี้            = ยอดเต็มจำนวน (ร้านค้า)
 *   - โอน/รายรับเข้าบัญชีนี้         = ชำระหนี้
 *   - โอนออกจากบัญชีนี้ (เบิกเงินสด) = มีแถวหนี้ย่อยในตาราง debts ผูกไว้ (txn_id)
 * รายการก่อน debt_since ไม่นำมาคิด (ใช้ snapshot ของหนี้ย่อยแทน)
 */
import { SHEETS } from './schema.js';
import { fmtDate_, readTable_, today_ } from './store.js';
import { DebtEngine } from './debt-engine.js';
import { isSplAccount_, splState_ } from './shopee.js';
export const DEBT_KINDS = ['cash', 'installment', 'convert', 'fullpay_snapshot'];

export function isEngineAccount_(a) {
  return !!a && a.type === 'revolving_credit' && a.debt_engine === 'ascend';
}

export function debtInput_(account, debts, txns, until) {
  const since = account.debt_since || '0000-00-00';
  const rate = (Number(account.annual_rate) || 25) / 100;
  const mine = debts.filter(d => d.account_id === account.id && d.status !== 'deleted');
  const loans = mine.filter(d => d.kind !== 'fullpay_snapshot').map(d => ({
    id: d.id, date: d.txn_date, principal: Number(d.principal), tenor: Number(d.tenor), desc: d.description, kind: d.kind,
    rate: d.annual_rate ? Number(d.annual_rate) / 100 : rate,
    installment: d.installment || null,
    fromPurchases: d.kind === 'convert' && !d.snap_date,
    snapshot: d.snap_date ? {
      date: d.snap_date, remaining: d.snap_remaining, paidPeriods: d.snap_paid_periods,
      lastInterestDate: d.snap_last_interest_date || d.snap_date,
    } : null,
  }));
  const purchases = mine.filter(d => d.kind === 'fullpay_snapshot')
    .map(d => ({ id: d.id, date: d.txn_date, amount: Number(d.principal), desc: d.description }))
    .concat(txns.filter(t => t.type === 'expense' && t.account_id === account.id && t.date >= since)
      .map(t => ({ id: t.id, date: t.date, amount: Number(t.amount), desc: t.note })));
  const payments = txns
    .filter(t => t.date >= since && ((t.type === 'transfer' && t.to_account_id === account.id) || (t.type === 'income' && t.account_id === account.id)))
    .map(t => ({ id: t.id, date: t.date, amount: Number(t.amount) }));
  return { rate: rate, loans: loans, purchases: purchases, payments: payments, until: until };
}

/** สรุปหนี้ของบัญชี ณ วันนี้ + บิลปัจจุบัน/ถัดไป (+ ตารางผ่อน) */
export function debtAccountState_(account, debts, txns, today, withSchedule) {
  const input = debtInput_(account, debts, txns, today);
  const sim = DebtEngine.simulate(input);
  const nd = DebtEngine.nextDates(today);
  const openLoans = sim.loans.filter(l => l.openBill && l.openBill.O > 0.004);
  const openFull = sim.purchases.filter(u => u.billedDue && u.billedDue >= today && u.remaining > 0.004);
  const currentBill = (openLoans.length || openFull.length) ? {
    due: openLoans.length ? openLoans[0].openBill.due : openFull[0].billedDue,
    amount: DebtEngine.r2(openLoans.reduce((s, l) => s + l.openBill.O, 0) + openFull.reduce((s, u) => s + u.remaining, 0) + sim.fees),
  } : null;
  // บิลถัดไป: จำลองแค่ถึงวันออกบิลถัดไป (เร็ว) — ตารางเต็ม 2 ปีคำนวณเฉพาะหน้า "หนี้"
  const sched = withSchedule ? DebtEngine.schedule(Object.assign({}, input), today) : null;
  const upto = DebtEngine.simulate(Object.assign({}, input, { autopayFrom: today, until: nd.statement }));
  const ns = upto.statements.filter(x => x.date === nd.statement && x.totalDue > 0)[0];
  const nextBill = ns ? { date: ns.date, due: ns.due, totalDue: ns.totalDue, interest: DebtEngine.r2(ns.rows.reduce((a, b) => a + b.interestDue, 0)) } : null;
  const lastStmt = sim.statements[sim.statements.length - 1] || null;
  const out = {
    accountId: account.id, asOf: today, outstanding: sim.outstanding, accruedInterest: sim.accruedInterest, fees: sim.fees,
    payoffToday: DebtEngine.payoffQuote(sim),
    currentBill: currentBill, nextBill: nextBill,
    lastStatement: lastStmt ? { date: lastStmt.date, due: lastStmt.due, totalDue: lastStmt.totalDue } : null,
    loans: sim.loans, pendingFull: sim.purchases.filter(u => u.remaining > 0.004),
    payments: sim.log.filter(l => l.type === 'payment').slice(-20).reverse(),
    events: sim.log.filter(l => l.type !== 'payment'),
    dailyInterest: DebtEngine.r2(sim.loans.reduce((s, l) => s + l.remaining * l.rate / 365, 0)),
    cycleSpend: ascendCycleSpend_(input, today),
  };
  if (withSchedule) { out.schedule = sched.byLoan; out.bills = sched.bills.slice(0, 26); }
  return out;
}

/** ยอดใช้สะสมในรอบบิลปัจจุบัน (16 – 15): ซื้อเต็มจำนวน + กดเงินสด + ผ่อนตอนซื้อ (ไม่นับการแปลงยอด/snapshot หนี้เก่า) */
export function ascendCycleSpend_(input, today) {
  const y = Number(today.slice(0, 4)), m = Number(today.slice(5, 7)) - 1, dd = Number(today.slice(8, 10));
  const start = fmtDate_(new Date(y, dd >= 16 ? m : m - 1, 16));
  const end = fmtDate_(new Date(y, dd >= 16 ? m + 1 : m, 15));
  const inWin = d => d >= start && d <= end;
  const buys = input.purchases.filter(u => inWin(u.date));
  const loans = input.loans.filter(l => (l.kind === 'cash' || l.kind === 'installment') && !l.snapshot && inWin(l.date));
  return {
    start: start, end: end, count: buys.length + loans.length,
    fullpay: DebtEngine.r2(buys.reduce((s, u) => s + u.amount, 0)),
    installment: DebtEngine.r2(loans.reduce((s, l) => s + l.principal, 0)),
    total: DebtEngine.r2(buys.reduce((s, u) => s + u.amount, 0) + loans.reduce((s, l) => s + l.principal, 0)),
  };
}

let debtMemo_ = null; // คำนวณครั้งเดียวต่อ request
export function resetDebtMemo_() { debtMemo_ = null; }

export function debtStates_(accounts, txns, withSchedule) {
  if (!withSchedule && debtMemo_) return debtMemo_;
  const debts = readTable_(SHEETS.DEBT);
  const today = today_();
  const res = {};
  accounts.filter(isEngineAccount_).forEach(a => { res[a.id] = debtAccountState_(a, debts, txns, today, withSchedule); });
  const spl = accounts.filter(isSplAccount_);
  if (spl.length) {
    const orders = readTable_(SHEETS.SHOPEE);
    spl.forEach(a => { res[a.id] = splState_(a, orders, txns, today); });
  }
  if (!withSchedule) debtMemo_ = res;
  return res;
}

/** ถ้าจ่ายวันที่ date: ยอดปิดหนี้ทั้งหมด + (ถ้าระบุ amount) จะตัดชำระอะไรบ้าง */
export function debtQuote_(p) {
  const account = readTable_(SHEETS.ACCOUNT).filter(a => a.id === p.account_id)[0];
  if (!isEngineAccount_(account)) throw new Error('บัญชีนี้ไม่ได้เปิดใช้ Debt Tracker');
  const txns = readTable_(SHEETS.TXN);
  const debts = readTable_(SHEETS.DEBT);
  const date = p.date || today_();
  const sim = DebtEngine.simulate(debtInput_(account, debts, txns, date));
  const quote = { date: date, principal: sim.outstanding, interest: sim.accruedInterest, fees: sim.fees, payoff: DebtEngine.payoffQuote(sim) };
  if (p.amount) {
    const input = debtInput_(account, debts, txns, date);
    input.payments = input.payments.concat([{ id: 'quote', date: date, amount: Number(p.amount) }]);
    const after = DebtEngine.simulate(input);
    quote.allocation = after.log.filter(l => l.type === 'payment' && l.id === 'quote')[0] || null;
    quote.after = { outstanding: after.outstanding, loans: after.loans.map(l => ({ id: l.id, remaining: l.remaining })) };
  }
  return quote;
}

export function validateDebt_(d, isNew) {
  if (DEBT_KINDS.indexOf(d.kind) < 0) throw new Error('ประเภทหนี้ไม่ถูกต้อง');
  if (!d.account_id) throw new Error('ต้องเลือกบัญชี');
  if (!d.txn_date) throw new Error('ต้องระบุวันที่');
  if (!(Number(d.principal) > 0)) throw new Error('ยอดเงินต้นต้องมากกว่า 0');
  if (d.kind !== 'fullpay_snapshot') {
    const acc = readTable_(SHEETS.ACCOUNT).filter(a => a.id === d.account_id)[0];
    const n = Number(d.tenor);
    const min = Number(acc && acc.tenor_min) || 2;
    const max = Number(acc && acc.tenor_max) || 24;
    if (!(n >= 1)) throw new Error('ต้องระบุจำนวนงวด');
    if (!d.snap_date && (n < min || n > max)) throw new Error('บัญชีนี้ผ่อนได้ ' + min + '–' + max + ' งวด');
  }
  if (isNew && !d.status) d.status = 'active';
}
