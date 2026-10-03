/**
 * Bill Plan — วางแผนรายรับ/รายจ่ายล่วงหน้าต่อรอบเงินเดือน
 * group: 'income' = รายรับที่คาด | 'A' = บิลประจำ + ค่างวดหนี้ | 'B' = รายจ่ายประมาณการแบบยืดหยุ่น
 * status: 'planned' | 'paid'
 */
import { SHEETS } from './schema.js';
import { deleteRow_, fmtDate_, hasRow_, insertRow_, newId_, payCycleRange_, readConfig_, readTable_, round2_, today_, updateRow_ } from './store.js';
import { debtStates_ } from './debt.js';
import { create_, withBalances_ } from './api.js';
export const BILL_GROUPS = ['income', 'A', 'B'];

/** วันครบกำหนดของ due_day ภายในรอบ (15 → 14 เดือนถัดไป) */
export function dueDateInCycle_(cycle, dueDay, startDay) {
  if (!dueDay) return '';
  const y = Number(cycle.slice(0, 4));
  const m = Number(cycle.slice(5, 7)) - 1;
  const month = dueDay >= startDay ? m : m + 1;
  const last = new Date(y, month + 1, 0).getDate(); // due_day 31 ในเดือนที่มี 30 วัน → วันสุดท้าย
  return fmtDate_(new Date(y, month, Math.min(dueDay, last)));
}

/** สร้างรายการของรอบจากแม่แบบ (ข้ามแม่แบบที่มีในรอบนี้แล้ว — กดซ้ำได้) */
export function generateCycle_(cycle) {
  if (!/^\d{4}-\d{2} /.test(cycle || '')) throw new Error('รอบไม่ถูกต้อง');
  const startDay = Number(readConfig_().pay_cycle_start_day) || 15;
  const existing = readTable_(SHEETS.BILL).filter(b => b.pay_cycle === cycle);
  const used = existing.map(b => b.template_id);
  const created = [];
  const range = payCycleRange_(cycle, startDay);
  let debt = null; // คำนวณเฉพาะเมื่อมีแม่แบบค่างวดหนี้
  const debtDue = accountId => {
    if (!debt) {
      const txns = readTable_(SHEETS.TXN);
      debt = debtStates_(withBalances_(readTable_(SHEETS.ACCOUNT), txns), txns, true);
    }
    const st = debt[accountId];
    if (!st) return null;
    const bills = (st.currentBill ? [{ due: st.currentBill.due, totalDue: st.currentBill.amount }] : []).concat(st.bills || []);
    return bills.filter(b => b.due >= range.start && b.due <= range.end)[0] || null;
  };
  readTable_(SHEETS.BILL_TEMPLATE)
    .filter(t => t.active !== false && !used.includes(t.id))
    .sort((a, b) => (a.sort || 0) - (b.sort || 0))
    .forEach(t => {
      // ค่างวดหนี้ที่ผูกกับ Debt Tracker: ใช้ยอดบิลที่คำนวณได้ และวันครบกำหนดจริง
      const d = t.to_account_id ? debtDue(t.to_account_id) : null;
      if (d) t = Object.assign({}, t, { est_amount: d.totalDue, due_day: Number(d.due.slice(8, 10)) });
      created.push(insertRow_(SHEETS.BILL, {
        id: newId_('bill'), pay_cycle: cycle, group: t.group, name: t.name,
        category_id: t.category_id, account_id: t.account_id, to_account_id: t.to_account_id,
        est_amount: t.est_amount, status: 'planned', due_date: dueDateInCycle_(cycle, t.due_day, startDay),
        template_id: t.id, note: t.note, sort: t.sort,
      }));
    });
  return created;
}

/** กดจ่าย/รับเงิน: บันทึก Transaction ด้วยยอดจริง แล้วผูกกับรายการในแผน */
export function payBill_(p) {
  const bill = readTable_(SHEETS.BILL).find(b => b.id === p.id);
  if (!bill) throw new Error('ไม่พบรายการในแผน');
  if (bill.status === 'paid') throw new Error('รายการนี้บันทึกจ่ายไปแล้ว');
  const amount = Number(p.amount);
  if (!(amount > 0)) throw new Error('ยอดจริงต้องมากกว่า 0');
  const accountId = p.account_id || bill.account_id;
  const toAccountId = p.to_account_id !== undefined ? p.to_account_id : bill.to_account_id;
  const account = readTable_(SHEETS.ACCOUNT).find(a => a.id === accountId);
  if (!account) throw new Error('กรุณาเลือกบัญชี');
  const date = p.date || today_();

  const txn = create_('transactions', {
    date: date,
    type: bill.group === 'income' ? 'income' : (toAccountId ? 'transfer' : 'expense'),
    account_id: accountId,
    to_account_id: bill.group === 'income' ? '' : (toAccountId || ''),
    amount: amount,
    currency: account.currency,
    category_id: bill.category_id,
    note: p.note || bill.name,
    source: 'bill_plan',
    ref_id: bill.id,
  });
  return updateRow_(SHEETS.BILL, bill.id, {
    status: 'paid', actual_amount: amount, paid_date: date, txn_id: txn.id, account_id: accountId,
  });
}

/** ยกเลิกการจ่าย: ลบ Transaction ที่สร้างไว้ แล้วกลับเป็น planned */
export function unpayBill_(id) {
  const bill = readTable_(SHEETS.BILL).find(b => b.id === id);
  if (!bill) throw new Error('ไม่พบรายการในแผน');
  if (bill.txn_id && hasRow_(SHEETS.TXN, bill.txn_id)) deleteRow_(SHEETS.TXN, bill.txn_id);
  return updateRow_(SHEETS.BILL, id, { status: 'planned', actual_amount: '', paid_date: '', txn_id: '' });
}

/** สรุปแผนของรอบ: คงเหลือคาดการณ์ = เงินตอนนี้ + รายรับที่ยังไม่เข้า − รายจ่ายที่ยังไม่จ่าย */
export function planSummary_(cycle, bills, cashNow) {
  const rows = bills.filter(b => b.pay_cycle === cycle);
  const sum = (g, st) => round2_(rows.filter(b => b.group === g && b.status === st)
    .reduce((s, b) => s + (Number(st === 'paid' ? b.actual_amount : b.est_amount) || 0), 0));
  const s = {
    cycle: cycle, count: rows.length, cashNow: round2_(cashNow),
    incomePending: sum('income', 'planned'), incomeReceived: sum('income', 'paid'),
    aPending: sum('A', 'planned'), aPaid: sum('A', 'paid'),
    bPending: sum('B', 'planned'), bPaid: sum('B', 'paid'),
  };
  s.projected = round2_(s.cashNow + s.incomePending - s.aPending - s.bPending);
  return s;
}

export function cashNowThb_(accounts, fx) {
  return accounts
    .filter(a => a.active !== false && a.type !== 'revolving_credit')
    .reduce((s, a) => s + a.balance * (fx[a.currency] || 1), 0);
}

export function validateBill_(d, isNew) {
  if (!BILL_GROUPS.includes(d.group)) throw new Error('กลุ่มไม่ถูกต้อง');
  if (!d.name) throw new Error('กรุณากรอกชื่อรายการ');
  if (!(Number(d.est_amount) >= 0)) throw new Error('ยอดประมาณไม่ถูกต้อง');
  if (d.group === 'income') d.to_account_id = '';
  if (isNew && d.status === undefined && 'pay_cycle' in d) d.status = 'planned';
}
