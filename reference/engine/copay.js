/**
 * โครงการร่วมจ่ายของรัฐ (ไทยช่วยไทยพลัส 60/40) ผ่าน G-Wallet
 * - ผู้ใช้กรอก "ราคาเต็ม" → รัฐจ่าย = min(ราคา × %, เหลือวันนี้, เหลือเดือนนี้, เหลือทั้งโครงการ)
 * - ส่วนที่เหลือคือเงินของเราเองใน G-Wallet (บันทึกเป็น amount ของ Transaction)
 * - เพดานรายวัน/รายเดือนตัดตามวันปฏิทิน ไม่สะสม
 */
import { SHEETS } from './schema.js';
import { readConfig_, readTable_, round2_, today_ } from './store.js';
export function copayConfig_(cfg) {
  cfg = cfg || readConfig_();
  return {
    enabled: String(cfg.copay_enabled) === 'true',
    name: cfg.copay_name || 'โครงการร่วมจ่าย',
    accountId: cfg.copay_account_id || '',
    rate: (Number(cfg.copay_gov_rate) || 0) / 100,
    dailyCap: Number(cfg.copay_daily_cap) || 0,
    monthlyCap: Number(cfg.copay_monthly_cap) || 0,
    totalCap: Number(cfg.copay_total_cap) || 0,
    start: cfg.copay_start || '',
    end: cfg.copay_end || '',
    hours: cfg.copay_hours || '',
  };
}

/** ยอดที่รัฐช่วยไปแล้ว ณ วันที่ date (ไม่นับรายการ excludeId — ใช้ตอนแก้ไข) */
export function copayUsage_(txns, cc, date, excludeId) {
  const inProgram = t => Number(t.gov_subsidy) > 0 && t.id !== excludeId && t.date >= cc.start && t.date <= cc.end;
  const used = txns.filter(inProgram);
  const sum = rows => round2_(rows.reduce((s, t) => s + Number(t.gov_subsidy), 0));
  const day = sum(used.filter(t => t.date === date));
  const month = sum(used.filter(t => t.date.slice(0, 7) === date.slice(0, 7)));
  const total = sum(used);
  return {
    date: date, day: day, month: month, total: total,
    dayLeft: round2_(Math.max(0, cc.dailyCap - day)),
    monthLeft: round2_(Math.max(0, cc.monthlyCap - month)),
    totalLeft: round2_(Math.max(0, cc.totalCap - total)),
  };
}

/** คำนวณส่วนแบ่ง: คืน {gov, self} */
export function copaySplit_(price, cc, usage) {
  const active = cc.enabled && usage.date >= cc.start && usage.date <= cc.end;
  if (!active) return { gov: 0, self: round2_(price), reason: 'นอกช่วงโครงการ' };
  const gov = round2_(Math.min(price * cc.rate, usage.dayLeft, usage.monthLeft, usage.totalLeft));
  return { gov: Math.max(0, gov), self: round2_(price - Math.max(0, gov)) };
}

/** เรียกจาก validate_ ของ transactions เมื่อ d.copay = true */
export function applyCopay_(d) {
  const cc = copayConfig_();
  if (d.type !== 'expense') throw new Error('สิทธิร่วมจ่ายใช้ได้กับรายจ่ายเท่านั้น');
  if (cc.accountId && d.account_id !== cc.accountId) throw new Error('ต้องจ่ายจากบัญชี G-Wallet ที่ตั้งไว้');
  const price = Number(d.full_price);
  if (!(price > 0)) throw new Error('กรุณากรอกราคาเต็ม');
  const usage = copayUsage_(readTable_(SHEETS.TXN), cc, d.date, d.id);
  const s = copaySplit_(price, cc, usage);
  d.full_price = round2_(price);
  d.gov_subsidy = s.gov;
  d.amount = s.self;
}

export function copayStatus_(txns, cfg) {
  const cc = copayConfig_(cfg);
  if (!cc.enabled) return { enabled: false };
  const today = today_();
  return Object.assign({}, cc, {
    today: today,
    active: today >= cc.start && today <= cc.end,
    usage: copayUsage_(txns, cc, today),
    // รายการที่ใช้สิทธิแล้ว (ไว้คำนวณตัวอย่างในหน้าแอพ)
    used: txns.filter(t => Number(t.gov_subsidy) > 0 && t.date >= cc.start && t.date <= cc.end)
      .map(t => ({ id: t.id, date: t.date, gov_subsidy: t.gov_subsidy })),
  });
}
