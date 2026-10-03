/**
 * อีเมลอัตโนมัติ — สร้างเนื้อหาอีเมล (ส่งจริงโดย GitHub Actions: scripts/notify.mjs)
 *  - สรุปรายวัน: ส่งตามชั่วโมงที่ตั้งไว้ (ครั้งเดียวต่อวัน)
 *  - แจ้งเตือนครบกำหนดหนี้/บิลล่วงหน้า N วัน → ส่งตอนชั่วโมงเดียวกับสรุป (หรือ 9 โมงถ้าปิดสรุปรายวัน)
 * ผู้รับ: summary_emails (Id + แฟน) · แจ้งเตือน: reminder_emails (ว่าง = คนแรกใน summary_emails)
 */
import { SHEETS } from './schema.js';
import { now_, parseDate_, readTable_, today_ } from './store.js';
import { debtStates_ } from './debt.js';
import { portfolio_ } from './port.js';
import { bootstrap_, withBalances_ } from './api.js';

/**
 * งานที่ต้องส่งตอนนี้: sent = { summary: true, remind: true } ของวันนี้ที่ส่งไปแล้ว
 * ส่งเมื่อถึงชั่วโมงที่ตั้งไว้แล้ว (เลยมาได้ เผื่อ GitHub Actions เริ่มช้า) และยังไม่ได้ส่งวันนี้
 */
export function dueMailJobs_(cfg, sent) {
  sent = sent || {};
  const hour = now_().getHours();
  const summaryOn = String(cfg.daily_summary_enabled) === 'true';
  const sendHour = summaryOn ? Number(cfg.daily_summary_hour) : 9;
  if (hour < sendHour) return [];
  const jobs = [];
  if (summaryOn && !sent.summary) jobs.push('summary');
  if (!sent.remind) jobs.push('remind');
  return jobs;
}

/** อีเมลทดสอบ (ปุ่มในหน้าตั้งค่า): แจ้งเตือนทุกรายการใน 31 วัน */
export function testReminderConfig_(cfg) {
  return Object.assign({}, cfg, { debt_reminder_enabled: 'true', bill_reminder_enabled: 'true', debt_reminder_days: '0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31', bill_reminder_days: '0,1,2,3,4,5,6,7,8,9,10,11,12,13,14' });
}

export function recipients_(cfg, kind) {
  const list = s => String(s || '').split(/[,\s;]+/).map(x => x.trim()).filter(x => /@/.test(x));
  const all = list(cfg.summary_emails);
  if (kind === 'remind') return list(cfg.reminder_emails).length ? list(cfg.reminder_emails) : all.slice(0, 1);
  return all;
}

export const fmtMoney_ = n => (n < 0 ? '−' : '') + '฿' + Math.abs(Number(n) || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export const TH_MONTH_ = ['ม.ค.', 'ก.พ.', 'มี.ค.', 'เม.ย.', 'พ.ค.', 'มิ.ย.', 'ก.ค.', 'ส.ค.', 'ก.ย.', 'ต.ค.', 'พ.ย.', 'ธ.ค.'];
export const fmtDateTh_ = s => { const p = String(s).split('-').map(Number); return p[2] + ' ' + TH_MONTH_[p[1] - 1] + ' ' + p[0]; };
export const daysUntil_ = (from, to) => Math.round((parseDate_(to) - parseDate_(from)) / 864e5);

/** รายการที่ใกล้ครบกำหนด: หนี้ (PayNext/SPayLater) + บิลในแผนที่ยังไม่จ่าย */
export function upcomingDues_(within) {
  const today = today_();
  const txns = readTable_(SHEETS.TXN);
  const accounts = withBalances_(readTable_(SHEETS.ACCOUNT), txns);
  const debt = debtStates_(accounts, txns, false);
  const out = [];
  Object.keys(debt).forEach(id => {
    const st = debt[id], acc = accounts.filter(a => a.id === id)[0];
    const b = st.currentBill || (st.nextBill && st.nextBill.date <= today ? { due: st.nextBill.due, amount: st.nextBill.totalDue } : null);
    if (b && b.amount > 0) out.push({ kind: 'debt', name: acc.name, due: b.due, amount: b.amount, days: daysUntil_(today, b.due), overdue: !!b.overdue || b.due < today });
  });
  readTable_(SHEETS.BILL).filter(b => b.status !== 'paid' && b.group !== 'income' && b.due_date && !(b.to_account_id && debt[b.to_account_id]))
    .forEach(b => out.push({ kind: 'bill', name: b.name, due: b.due_date, amount: b.est_amount, days: daysUntil_(today, b.due_date), overdue: b.due_date < today }));
  return out.filter(x => x.days <= within).sort((a, b) => a.due.localeCompare(b.due));
}

/** → { to, subject, html, count } หรือ null ถ้าไม่มีอะไรต้องส่ง */
export function buildReminders_(cfg) {
  const to = recipients_(cfg, 'remind');
  if (!to.length) return null;
  const debtDays = String(cfg.debt_reminder_days || '').split(',').map(Number).filter(n => n >= 0);
  const billDays = String(cfg.bill_reminder_days || '').split(',').map(Number).filter(n => n >= 0);
  const items = upcomingDues_(Math.max.apply(null, debtDays.concat(billDays).concat([0]))).filter(x =>
    x.overdue ? x.kind === 'debt' && String(cfg.debt_reminder_enabled) === 'true'
      : (x.kind === 'debt' ? String(cfg.debt_reminder_enabled) === 'true' && debtDays.indexOf(x.days) >= 0
        : String(cfg.bill_reminder_enabled) === 'true' && billDays.indexOf(x.days) >= 0));
  if (!items.length) return null;
  const rows = items.map(x => '<tr><td style="padding:8px 0">' + (x.kind === 'debt' ? '💳 ' : '🧾 ') + esc_(x.name) + '<br><span style="color:#9A90A0;font-size:12px">'
    + (x.overdue ? '<b style="color:#D64B6A">เลยกำหนด</b> ' : x.days === 0 ? '<b style="color:#D64B6A">วันนี้!</b> ' : 'อีก ' + x.days + ' วัน · ') + fmtDateTh_(x.due) + '</span></td>'
    + '<td style="text-align:right;font-weight:700">' + fmtMoney_(x.amount) + '</td></tr>').join('');
  const html = mailFrame_('⏰ ใกล้ครบกำหนดชำระ', '<table style="width:100%;border-collapse:collapse">' + rows + '</table>'
    + '<p style="color:#9A90A0;font-size:12px">💡 จ่ายหนี้ PayNext ก่อนวันครบกำหนด = ดอกคิดถึงวันที่จ่ายจริง ยิ่งเร็วยิ่งประหยัด</p>');
  return { to: to, subject: '[断捨ISM] ใกล้ครบกำหนด ' + items.length + ' รายการ · รวม ' + fmtMoney_(items.reduce((s, x) => s + Number(x.amount), 0)), html: html, count: items.length };
}

/** → { to, subject, html } หรือ null ถ้ายังไม่ได้ตั้งผู้รับ */
export function buildDailySummary_(cfg) {
  const to = recipients_(cfg, 'summary');
  if (!to.length) return null;
  const today = today_();
  const b = bootstrap_();
  const fx = b.fx;
  const cash = b.accounts.filter(a => a.active !== false && a.type !== 'revolving_credit').reduce((s, a) => s + a.balance * (fx[a.currency] || 1), 0);
  const debtTotal = b.accounts.filter(a => a.active !== false && a.type === 'revolving_credit').reduce((s, a) => s + (a.used || 0) * (fx[a.currency] || 1), 0);
  const txToday = readTable_(SHEETS.TXN).filter(t => t.date === today);
  const inc = txToday.filter(t => t.type === 'income').reduce((s, t) => s + Number(t.amount), 0);
  const exp = txToday.filter(t => t.type === 'expense').reduce((s, t) => s + Number(t.amount), 0);
  const accName = id => (b.accounts.filter(a => a.id === id)[0] || {}).name || '';
  const cat = id => b.categories.filter(c => c.id === id)[0];
  let port = null;
  try { port = portfolio_().summary; } catch (e) { /* ยังไม่มีพอร์ต */ }
  const dues = upcomingDues_(10);

  const card = (label, value, color, sub) => '<td style="padding:6px;width:50%;vertical-align:top"><div style="background:' + color + ';border-radius:14px;padding:12px 14px">'
    + '<div style="font-size:12px;color:#7A7080">' + label + '</div><div style="font-size:20px;font-weight:700;margin-top:2px">' + value + '</div>'
    + (sub ? '<div style="font-size:11px;color:#9A90A0;margin-top:2px">' + sub + '</div>' : '') + '</div></td>';
  let html = '<table style="width:100%;border-collapse:collapse"><tr>'
    + card('ยอดเงินสดจริง', fmtMoney_(cash), '#FFF0F5')
    + card('หนี้รวม', '<span style="color:#D64B6A">' + fmtMoney_(debtTotal) + '</span>', '#EEF6FE') + '</tr><tr>'
    + card('คงเหลือหลังหักแผนรอบนี้', fmtMoney_(b.plan.projected), '#F3FBF6', b.plan.count ? '' : 'ยังไม่มีแผนรอบนี้')
    + card('มูลค่าพอร์ต', port ? fmtMoney_(port.valueThb) : '—', '#FFF8E8', port ? 'กำไร/ขาดทุน ' + fmtMoney_(port.unrealizedThb) + ' (' + port.unrealizedPct + '%)' : '')
    + '</tr></table>';

  html += '<h3 style="margin:18px 0 6px;font-size:15px">วันนี้ (' + fmtDateTh_(today) + ')</h3>'
    + '<div style="font-size:13px;color:#7A7080">รับ <b style="color:#2E9E6B">' + fmtMoney_(inc) + '</b> · จ่าย <b style="color:#D64B6A">' + fmtMoney_(exp) + '</b> · ' + txToday.length + ' รายการ</div>';
  if (txToday.length) {
    html += '<table style="width:100%;border-collapse:collapse;font-size:13px;margin-top:6px">' + txToday.map(t => {
      const c = cat(t.subcategory_id || t.category_id);
      const title = t.note || (c ? c.name : t.type === 'transfer' ? 'โอน' : '');
      const sign = t.type === 'income' ? '+' : t.type === 'expense' ? '−' : '';
      const color = t.type === 'income' ? '#2E9E6B' : t.type === 'expense' ? '#D64B6A' : '#3A3440';
      return '<tr style="border-top:1px solid #F3E4EC"><td style="padding:6px 0">' + (c ? c.icon + ' ' : '') + esc_(title)
        + '<br><span style="color:#9A90A0;font-size:11px">' + esc_(accName(t.account_id)) + (t.to_account_id ? ' → ' + esc_(accName(t.to_account_id)) : '') + '</span></td>'
        + '<td style="text-align:right;color:' + color + ';font-weight:600">' + sign + fmtMoney_(t.amount).replace('−', '') + '</td></tr>';
    }).join('') + '</table>';
  }
  if (dues.length) {
    html += '<h3 style="margin:18px 0 6px;font-size:15px">ครบกำหนดใน 10 วัน</h3><table style="width:100%;border-collapse:collapse;font-size:13px">'
      + dues.map(x => '<tr style="border-top:1px solid #F3E4EC"><td style="padding:6px 0">' + (x.kind === 'debt' ? '💳 ' : '🧾 ') + esc_(x.name)
        + '<br><span style="color:' + (x.overdue ? '#D64B6A' : '#9A90A0') + ';font-size:11px">' + (x.overdue ? 'เลยกำหนด ' : '') + fmtDateTh_(x.due) + '</span></td>'
        + '<td style="text-align:right;font-weight:600">' + fmtMoney_(x.amount) + '</td></tr>').join('') + '</table>';
  }
  if (b.copay && b.copay.enabled && b.copay.active) {
    const u = b.copay.usage;
    html += '<p style="font-size:13px;margin-top:14px">🟦 ' + esc_(b.copay.name) + ': รัฐยังช่วยได้วันนี้ ' + fmtMoney_(Math.min(u.dayLeft, u.monthLeft, u.totalLeft))
      + ' · เดือนนี้ใช้ไป ' + fmtMoney_(u.month) + '</p>';
  }
  return { to: to, subject: '[断捨ISM] สรุปการเงิน ' + fmtDateTh_(today), html: mailFrame_('สรุปการเงินประจำวัน', html) };
}

export function mailFrame_(title, inner) {
  return '<div style="background:#FFF9FB;padding:18px;font-family:\'Noto Sans Thai\',Tahoma,sans-serif;color:#3A3440">'
    + '<div style="max-width:560px;margin:0 auto;background:#fff;border:1px solid #F3E4EC;border-radius:20px;padding:20px">'
    + '<div style="font-size:12px;letter-spacing:.2em;color:#9A90A0">断捨ISM</div><h2 style="margin:4px 0 14px;font-size:19px">' + title + '</h2>'
    + inner + '<p style="color:#B8AFBD;font-size:11px;margin-top:18px">อีเมลอัตโนมัติจาก Dansha-ISM · อ่านอย่างเดียว</p></div></div>';
}

export function esc_(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
