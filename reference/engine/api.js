/**
 * API ของแอพ (เดิมคือ Apps Script Web App) — ทำงานกับ db ในหน่วยความจำ (ดู store.js)
 *  doGet({ action: 'bootstrap' | 'list' | 'debts' | ... })  → ข้อมูล
 *  doPost({ action: 'create' | 'update' | 'delete' | ..., table, id?, data? }) → ผลลัพธ์ (แก้ db ที่โหลดไว้)
 * โยน Error ข้อความภาษาไทยเมื่อข้อมูลไม่ถูกต้อง
 */
import { ACCOUNT_TYPES, CURRENCIES, SCHEMA, SHEETS, TABLES } from './schema.js';
import { deleteRow_, fxRateOf_, hasRow_, insertRow_, newId_, nowIso_, now_, payCycleOf_, payCycleRange_, readConfig_, readFx_, readTable_, round2_, setConfig_, setFxAuto_, updateRow_ } from './store.js';
import { debtQuote_, debtStates_, isEngineAccount_, resetDebtMemo_, validateDebt_ } from './debt.js';
import { checkPurchase_, cycleOutlook_, isSplAccount_, recordPurchase_, shopeeStats_, syncSplBills_ } from './shopee.js';
import { cashNowThb_, generateCycle_, payBill_, planSummary_, unpayBill_, validateBill_ } from './plan.js';
import { portfolio_, setPrices_, validatePort_ } from './port.js';
import { applyCopay_, copayStatus_ } from './copay.js';
export function doGet(p) {
  p = p || {};
  resetDebtMemo_();
  switch (p.action) {
    case 'bootstrap': return bootstrap_();
    case 'list': return list_(p.table, p);
    case 'debts': {
      const txns = readTable_(SHEETS.TXN);
      const accounts = withBalances_(readTable_(SHEETS.ACCOUNT), txns);
      return { states: debtStates_(accounts, txns, true), rows: readTable_(SHEETS.DEBT).filter(d => d.status !== 'deleted') };
    }
    case 'debtQuote': return debtQuote_(p);
    case 'checkPurchase': return checkPurchase_(p);
    case 'shopeeStats': return shopeeStats_();
    case 'portfolio': return portfolio_();
    case 'outlook': return cycleOutlook_(Number(p.count) || 6).cycles;
    case 'prices': return readTable_(SHEETS.PRICES);
    case 'fx': return readTable_(SHEETS.FX);
    case 'ping': return { ok: true, time: nowIso_() };
    default: throw new Error('unknown action: ' + p.action);
  }
}

export function doPost(body) {
  body = body || {};
  resetDebtMemo_();
  try {
    switch (body.action) {
      case 'create': return create_(body.table, body.data || {});
      case 'update': return update_(body.table, body.id, body.data || {});
      case 'delete': return remove_(body.table, body.id);
      case 'generateCycle': return generateCycle_(body.cycle);
      case 'cashDraw': return cashDraw_(body.data || {});
      case 'recordPurchase': return recordPurchase_(body.data || {});
      case 'setPrices': return setPrices_(body.data || {});
      case 'payBill': return payBill_(body.data || {});
      case 'unpayBill': return unpayBill_(body.id);
      case 'updateFx': return setFxAuto_(body.data || {}, body.date);
      case 'setFxManual': return setFxManual_(body.data || {});
      case 'setConfig':
        Object.keys(body.data || {}).forEach(k => setConfig_(k, body.data[k]));
        return readConfig_();
      default: throw new Error('unknown action: ' + body.action);
    }
  } finally {
    resetDebtMemo_();
  }
}

/** กรอกเรทเอง: data = { USD: 36.5 | '' (ว่าง = กลับไปใช้อัตโนมัติ) } */
export function setFxManual_(data) {
  Object.keys(data).forEach(c => {
    if (CURRENCIES.indexOf(c) < 0 || c === 'THB') return;
    const v = data[c] === '' || data[c] == null ? '' : Number(data[c]);
    if (v !== '' && !(v > 0)) throw new Error('เรทไม่ถูกต้อง: ' + c);
    if (!hasRow_(SHEETS.FX, c)) insertRow_(SHEETS.FX, { currency: c });
    const row = updateRow_(SHEETS.FX, c, { manual_rate: v });
    updateRow_(SHEETS.FX, c, { rate_to_thb: fxRateOf_(row) });
  });
  return readTable_(SHEETS.FX);
}

export function tableName_(key) {
  const name = TABLES[key];
  if (!name) throw new Error('unknown table: ' + key);
  return name;
}

// ---------- read ----------

export function bootstrap_() {
  const config = readConfig_();
  const startDay = Number(config.pay_cycle_start_day) || 15;
  const txns = readTable_(SHEETS.TXN);
  const cycle = payCycleOf_(now_(), startDay);
  const accounts = withBalances_(readTable_(SHEETS.ACCOUNT), txns);
  const fx = readFx_();
  const debt = debtStates_(accounts, txns, false);
  return {
    debt: debt,
    config: config,
    accounts: accounts,
    plan: planSummary_(cycle, readTable_(SHEETS.BILL), cashNowThb_(accounts, fx)),
    categories: readTable_(SHEETS.CATEGORY),
    copay: copayStatus_(txns, config),
    port: (function () { try { return readTable_(SHEETS.PORT).length ? portfolio_().summary : null; } catch (e) { return null; } })(),
    fx: fx,
    payCycle: { current: cycle, range: payCycleRange_(cycle, startDay) },
    recentTransactions: txns.slice(-30).reverse(),
    serverTime: nowIso_(),
  };
}

export function list_(key, p) {
  let rows = readTable_(tableName_(key));
  if (key === 'bills' && p.cycle) {
    const cycleBills = rows.filter(r => r.pay_cycle === p.cycle);
    const accounts = withBalances_(readTable_(SHEETS.ACCOUNT), readTable_(SHEETS.TXN));
    return { rows: cycleBills, summary: planSummary_(p.cycle, cycleBills, cashNowThb_(accounts, readFx_())) };
  }
  if (p.cycle) rows = rows.filter(r => r.pay_cycle === p.cycle);
  if (p.from) rows = rows.filter(r => (r.date || '') >= p.from);
  if (p.to) rows = rows.filter(r => (r.date || '') <= p.to);
  return rows;
}

/** ยอด cash/bank = ตั้งต้น + เข้า − ออก | revolving_credit: used = ตั้งต้น(ยอดใช้ไป) − (เข้า − ออก) */
export function withBalances_(accounts, txns) {
  const flow = {};
  txns.forEach(t => {
    const amt = Number(t.amount) || 0;
    if (t.type === 'income') flow[t.account_id] = (flow[t.account_id] || 0) + amt;
    else if (t.type === 'expense') flow[t.account_id] = (flow[t.account_id] || 0) - amt;
    else if (t.type === 'transfer') {
      flow[t.account_id] = (flow[t.account_id] || 0) - amt;
      flow[t.to_account_id] = (flow[t.to_account_id] || 0) + amt;
    }
  });
  const engine = accounts.filter(a => isEngineAccount_(a) || isSplAccount_(a));
  const states = engine.length ? debtStates_(engine, txns, false) : {};
  return accounts.map(a => {
    if (states[a.id]) {
      const used = states[a.id].outstanding;
      return Object.assign({}, a, { used: used, available: round2_((Number(a.credit_limit) || 0) - used), accruedInterest: states[a.id].accruedInterest });
    }
    const f = flow[a.id] || 0;
    const open = Number(a.opening_balance) || 0;
    if (a.type === 'revolving_credit') {
      const used = round2_(open - f);
      return Object.assign({}, a, { used: used, available: round2_((Number(a.credit_limit) || 0) - used) });
    }
    return Object.assign({}, a, { balance: round2_(open + f) });
  });
}

// ---------- write ----------

export const ID_PREFIX = { accounts: 'acc', transactions: 'txn', bills: 'bill', billTemplates: 'btpl', debts: 'debt', shopee: 'shp', port: 'port', categories: 'cat' };

export function create_(key, data) {
  const name = tableName_(key);
  data = Object.assign({}, data);
  data.id = newId_(ID_PREFIX[key]);
  if (SCHEMA[name].includes('created_at')) data.created_at = nowIso_();
  validate_(key, data, true);
  return insertRow_(name, data);
}

export function update_(key, id, data) {
  const name = tableName_(key);
  if (!id) throw new Error('ต้องระบุ id');
  const merged = Object.assign({}, readTable_(name).find(r => r.id === id) || {}, data);
  validate_(key, merged, false);
  delete data.created_at;
  if (key === 'transactions') {
    ['pay_cycle', 'amount', 'full_price', 'gov_subsidy'].forEach(k => { data[k] = merged[k]; });
    delete data.copay;
  }
  const res = updateRow_(name, id, data);
  if (key === 'shopee' && merged.pay_method === 'spaylater') syncSplBills_(merged.account_id);
  if (key === 'shopee' && merged.txn_id && (data.price !== undefined || data.status !== undefined) && hasRow_(SHEETS.TXN, merged.txn_id)) {
    // จ่ายเต็ม: ยกเลิก/คืนเงิน → ลบรายจ่าย, แก้ราคา → แก้รายจ่าย
    if (merged.status === 'cancelled') { deleteRow_(SHEETS.TXN, merged.txn_id); updateRow_(name, id, { txn_id: '' }); }
    else updateRow_(SHEETS.TXN, merged.txn_id, { amount: Number(merged.price) });
  }
  return res;
}

export function remove_(key, id) {
  const name = tableName_(key);
  if (key === 'bills') {
    const bill = readTable_(name).find(b => b.id === id);
    if (bill && bill.status === 'paid') throw new Error('รายการนี้จ่ายแล้ว — กด "ยกเลิกการจ่าย" ก่อนลบ');
  }
  if (key === 'shopee') {
    const o = readTable_(name).filter(r => r.id === id)[0];
    if (o && o.txn_id && hasRow_(SHEETS.TXN, o.txn_id)) deleteRow_(SHEETS.TXN, o.txn_id);
    deleteRow_(name, id);
    if (o && o.pay_method === 'spaylater') syncSplBills_(o.account_id);
    return { id: id };
  }
  if (key === 'transactions') {
    readTable_(SHEETS.DEBT).filter(d => d.txn_id === id).forEach(d => deleteRow_(SHEETS.DEBT, d.id));
    readTable_(SHEETS.SHOPEE).filter(o => o.txn_id === id).forEach(o => updateRow_(SHEETS.SHOPEE, o.id, { txn_id: '' }));
    const t = readTable_(name).find(r => r.id === id);
    if (t && t.source === 'bill_plan' && t.ref_id && hasRow_(SHEETS.BILL, t.ref_id)) {
      updateRow_(SHEETS.BILL, t.ref_id, { status: 'planned', actual_amount: '', paid_date: '', txn_id: '' });
    }
  }
  if (key === 'accounts') {
    const used = readTable_(SHEETS.TXN).some(t => t.account_id === id || t.to_account_id === id);
    if (used) throw new Error('บัญชีนี้มีรายการอยู่แล้ว ลบไม่ได้ — ปิดใช้งาน (active = false) แทน');
  }
  if (key === 'categories') {
    const txns = readTable_(SHEETS.TXN);
    const children = readTable_(SHEETS.CATEGORY).filter(c => c.parent_id === id).map(c => c.id);
    const ids = [id].concat(children);
    if (txns.some(t => ids.includes(t.category_id) || ids.includes(t.subcategory_id))) {
      throw new Error('หมวดนี้ถูกใช้ในรายการแล้ว ลบไม่ได้ — ปิดใช้งานแทน');
    }
    children.forEach(c => deleteRow_(name, c));
  }
  deleteRow_(name, id);
  return { id: id };
}

export function validate_(key, d, isNew) {
  const need = (field, label) => { if (d[field] === '' || d[field] === null || d[field] === undefined) throw new Error('กรุณากรอก ' + label); };
  if (key === 'accounts') {
    need('name', 'ชื่อบัญชี');
    if (!ACCOUNT_TYPES.includes(d.type)) throw new Error('ประเภทบัญชีไม่ถูกต้อง');
    if (!d.currency) d.currency = 'THB';
    if (!CURRENCIES.includes(d.currency)) throw new Error('สกุลเงินไม่รองรับ');
    if (isNew && d.active === undefined) d.active = true;
    if (d.opening_balance === undefined || d.opening_balance === '') d.opening_balance = 0;
  }
  if (key === 'transactions') {
    need('date', 'วันที่');
    if (!['income', 'expense', 'transfer'].includes(d.type)) throw new Error('ประเภทรายการไม่ถูกต้อง');
    need('account_id', 'บัญชี');
    if (!(Number(d.amount) > 0)) throw new Error('จำนวนเงินต้องมากกว่า 0');
    if (d.type === 'transfer') {
      need('to_account_id', 'บัญชีปลายทาง');
      if (d.to_account_id === d.account_id) throw new Error('บัญชีต้นทาง/ปลายทางต้องต่างกัน');
    }
    if (d.copay === true || d.copay === 'true') applyCopay_(d);
    else if (!isNew && Number(d.gov_subsidy) > 0 && d.copay !== undefined) { d.gov_subsidy = ''; d.full_price = ''; }
    delete d.copay;
    d.pay_cycle = payCycleOf_(d.date, Number(readConfig_().pay_cycle_start_day) || 15);
    if (!d.source) d.source = 'manual';
  }
  if (key === 'bills' || key === 'billTemplates') validateBill_(d, isNew);
  if (key === 'debts') validateDebt_(d, isNew);
  if (key === 'port') validatePort_(d);
  if (key === 'bills' && !isNew && d.status === 'paid' && d.actual_amount !== undefined && d.txn_id) {
    // แก้ยอดจริงของรายการที่จ่ายแล้ว → อัปเดต Transaction ที่ผูกอยู่ด้วย
    if (hasRow_(SHEETS.TXN, d.txn_id)) updateRow_(SHEETS.TXN, d.txn_id, { amount: Number(d.actual_amount) });
  }
  if (key === 'categories') {
    need('name', 'ชื่อหมวด');
    if (!['income', 'expense'].includes(d.type)) throw new Error('ประเภทหมวดไม่ถูกต้อง');
    if (isNew && d.active === undefined) d.active = true;
  }
}

/** เบิกเงินสดจากวงเงิน: สร้างรายการโอน + หนี้ย่อยที่ผูกกัน */
export function cashDraw_(p) {
  const acc = readTable_(SHEETS.ACCOUNT).filter(a => a.id === p.account_id)[0];
  if (!isEngineAccount_(acc)) throw new Error('บัญชีนี้ไม่ได้เปิดใช้ Debt Tracker');
  const debt = { account_id: acc.id, txn_date: p.date, kind: p.kind || 'cash', description: p.note || 'เบิกเงินสด', principal: Number(p.amount), tenor: Number(p.tenor) };
  validateDebt_(debt, true);
  let txn = null;
  if (p.to_account_id) {
    txn = create_('transactions', { date: p.date, type: 'transfer', account_id: acc.id, to_account_id: p.to_account_id, amount: Number(p.amount), note: debt.description, source: 'debt' });
    debt.txn_id = txn.id;
  }
  return { txn: txn, debt: create_('debts', debt) };
}
