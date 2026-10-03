/**
 * ที่เก็บข้อมูลในหน่วยความจำ (แทน Google Sheets) + ฟังก์ชันวันที่/รอบเงินเดือน
 * ใช้ได้ทั้งในแอพ (WebView/เบราว์เซอร์), Cloudflare Worker และ Node (GitHub Actions)
 *
 * db = { config: { key: 'value' }, accounts: [row], transactions: [row], ... }
 * แต่ละแถวเก็บครบทุกคอลัมน์ตาม SCHEMA เรียงตามลำดับ และแปลงชนิดแล้ว (ตัวเลข/boolean/วันที่ yyyy-MM-dd)
 */
import { SHEETS, SCHEMA, NUMERIC, BOOLEAN, DATE_COLS, CURRENCIES, DEFAULT_CONFIG, DEFAULT_CATEGORIES } from './schema.js';

export const TZ = 'Asia/Bangkok';
export const MONTHS_EN = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
/** ตารางที่เก็บเป็นแถว (ทุกตารางยกเว้น config) */
export const ROW_TABLES = Object.keys(SCHEMA);
/** คอลัมน์ที่ใช้เป็นคีย์ของแต่ละตาราง */
export const KEY_OF = { [SHEETS.FX]: 'currency', [SHEETS.PRICES]: 'symbol' };
export const keyOf = table => KEY_OF[table] || 'id';

let DB = null;

/** ตั้ง db ที่ฟังก์ชันทั้งหมดจะอ่าน/เขียน (แก้ object นี้โดยตรง) */
export function useDb(db) {
  DB = normalizeDb(db);
  return DB;
}

export function currentDb() {
  if (!DB) throw new Error('ยังไม่ได้โหลดข้อมูล');
  return DB;
}

export function emptyDb() {
  const db = { config: {} };
  ROW_TABLES.forEach(t => { db[t] = []; });
  return db;
}

/** ข้อมูลตั้งต้นของผู้ใช้ใหม่: Config + หมวดหมู่ + สกุลเงิน */
export function seedDb() {
  const db = emptyDb();
  DEFAULT_CONFIG.forEach(([k, v]) => { db.config[k] = v; });
  let sort = 0;
  DEFAULT_CATEGORIES.forEach(([name, type, color, icon, subs]) => {
    const id = newId_('cat');
    db.categories.push(normRow_(SHEETS.CATEGORY, { id, name, type, parent_id: '', color, icon, sort: sort++, active: true }));
    subs.forEach(s => db.categories.push(normRow_(SHEETS.CATEGORY, { id: newId_('cat'), name: s, type, parent_id: id, color, icon, sort: sort++, active: true })));
  });
  db.fx = CURRENCIES.map(c => normRow_(SHEETS.FX, { currency: c, auto_rate: c === 'THB' ? 1 : '', manual_rate: '', rate_to_thb: c === 'THB' ? 1 : '', updated_note: '' }));
  return db;
}

/** เติมตาราง/คอลัมน์ที่ขาด และแปลงชนิดข้อมูลให้ตรง SCHEMA (ใช้กับข้อมูลที่อ่านจากไฟล์) */
export function normalizeDb(db) {
  const out = db || {};
  out.config = out.config && typeof out.config === 'object' ? out.config : {};
  ROW_TABLES.forEach(t => {
    out[t] = Array.isArray(out[t]) ? out[t].filter(r => r && r[keyOf(t)] !== '' && r[keyOf(t)] != null).map(r => normRow_(t, r)) : [];
  });
  return out;
}

// ---------- id / วันที่ ----------

export function newId_(prefix) {
  const uuid = (globalThis.crypto && globalThis.crypto.randomUUID) ? globalThis.crypto.randomUUID()
    : 'xxxxxxxxxxxx4xxxyxxxxxxxxxxxxxxx'.replace(/[xy]/g, c => ((Math.random() * 16) | 0).toString(16));
  return prefix + '_' + uuid.replace(/-/g, '').slice(0, 12);
}

const pad2 = n => String(n).padStart(2, '0');
let bkkFmt = null;
let nowOverride = null;

/** ตั้งเวลาปัจจุบันเอง (ใช้ทดสอบ) — null = เวลาจริง */
export function setNow(date) { nowOverride = date ? new Date(date) : null; }

/**
 * เวลาปัจจุบันของไทย เป็น Date ที่ getFullYear/getMonth/getDate/getHours = เวลาไทย
 * (Apps Script ตั้ง timezone เป็นไทยไว้ — ที่นี่ต้องแปลงเองเพราะเครื่องอาจอยู่ timezone อื่น เช่น Worker = UTC)
 */
export function now_() {
  const real = nowOverride || new Date();
  if (!bkkFmt) bkkFmt = new Intl.DateTimeFormat('en-US', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
  const p = {};
  bkkFmt.formatToParts(real).forEach(x => { p[x.type] = x.value; });
  return new Date(Number(p.year), Number(p.month) - 1, Number(p.day), Number(p.hour) % 24, Number(p.minute), Number(p.second));
}

/** Date (เวลาท้องถิ่นแบบเดียวกับ now_/parseDate_) → 'yyyy-MM-dd' */
export function fmtDate_(d) {
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

function fmtDateTime_(d) {
  return fmtDate_(d) + 'T' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
}

export function nowIso_() {
  return fmtDateTime_(now_());
}

export function today_() {
  return fmtDate_(now_());
}

/** 'yyyy-MM-dd' → Date เวลาเที่ยงคืน (เวลาท้องถิ่น) */
export function parseDate_(s) {
  if (s instanceof Date) return s;
  const m = String(s).match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (!m) throw new Error('รูปแบบวันที่ต้องเป็น yyyy-MM-dd: ' + s);
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
}

/**
 * รอบเงินเดือนเริ่มวันที่ 15 ถึง 14 ของเดือนถัดไป
 * คืนค่าเช่น "2026-09 Sep-Oct" (เรียงตามเวลาได้ + อ่านง่าย)
 */
export function payCycleOf_(date, startDay) {
  const d = parseDate_(date);
  startDay = startDay || 15;
  let y = d.getFullYear();
  let m = d.getMonth();
  if (d.getDate() < startDay) {
    m -= 1;
    if (m < 0) { m = 11; y -= 1; }
  }
  const next = (m + 1) % 12;
  return y + '-' + String(m + 1).padStart(2, '0') + ' ' + MONTHS_EN[m] + '-' + MONTHS_EN[next];
}

/** ช่วงวันที่ของรอบ "yyyy-MM ..." → {start, end} เป็น yyyy-MM-dd */
export function payCycleRange_(cycle, startDay) {
  startDay = startDay || 15;
  const y = Number(cycle.slice(0, 4));
  const m = Number(cycle.slice(5, 7)) - 1;
  const start = new Date(y, m, startDay);
  const end = new Date(y, m + 1, startDay - 1);
  return { start: fmtDate_(start), end: fmtDate_(end) };
}

export function round2_(n) {
  return Math.round(n * 100) / 100;
}

// ---------- อ่าน/เขียนตาราง ----------

/** แปลงค่าตามชนิดคอลัมน์ (เหมือนตอนอ่านจาก Sheet เดิม) */
export function normCell_(key, v) {
  if (v === '' || v === null || v === undefined) return BOOLEAN.includes(key) ? false : (NUMERIC.includes(key) ? null : '');
  if (v instanceof Date) return DATE_COLS.includes(key) ? fmtDate_(v) : fmtDateTime_(v);
  if (NUMERIC.includes(key)) return Number(v);
  if (BOOLEAN.includes(key)) return v === true || String(v).toLowerCase() === 'true';
  if (DATE_COLS.includes(key)) return fmtDate_(parseDate_(v));
  return v;
}

export function normRow_(name, data) {
  const o = {};
  SCHEMA[name].forEach(c => { o[c] = normCell_(c, data[c]); });
  return o;
}

function table_(name) {
  const db = currentDb();
  if (!SCHEMA[name]) throw new Error('unknown table: ' + name);
  return db[name] || (db[name] = []);
}

/** คืนสำเนาของแถว (แก้ผลลัพธ์ได้โดยไม่กระทบข้อมูล) */
export function readTable_(name) {
  return table_(name).map(r => Object.assign({}, r));
}

export function hasRow_(name, id) {
  const k = keyOf(name);
  return table_(name).some(r => String(r[k]) === String(id));
}

export function insertRow_(name, data) {
  table_(name).push(normRow_(name, data));
  return data;
}

export function updateRow_(name, id, patch) {
  const rows = table_(name);
  const k = keyOf(name);
  const i = rows.findIndex(r => String(r[k]) === String(id));
  if (i < 0) throw new Error('ไม่พบรายการ id=' + id);
  const next = Object.assign({}, rows[i]);
  SCHEMA[name].forEach(c => { if (c !== k && c in patch) next[c] = normCell_(c, patch[c]); });
  rows[i] = next;
  return Object.assign({}, next);
}

export function deleteRow_(name, id) {
  const rows = table_(name);
  const k = keyOf(name);
  const i = rows.findIndex(r => String(r[k]) === String(id));
  if (i < 0) throw new Error('ไม่พบรายการ id=' + id);
  rows.splice(i, 1);
}

// ---------- Config ----------

export function readConfig_() {
  const cfg = {};
  DEFAULT_CONFIG.forEach(([k, v]) => { cfg[k] = v; }); // key ใหม่ที่ยังไม่มีใน Config ใช้ค่าตั้งต้น
  const stored = currentDb().config;
  Object.keys(stored).forEach(k => { cfg[k] = String(stored[k]); });
  return cfg;
}

export function setConfig_(key, value) {
  currentDb().config[key] = String(value);
}

// ---------- อัตราแลกเปลี่ยน ----------

/** อัตราที่ใช้ = manual_rate (ถ้ากรอก) ไม่งั้น auto_rate (อัปเดตอัตโนมัติวันละครั้ง) */
export function fxRateOf_(r) {
  const manual = Number(r.manual_rate);
  if (manual > 0) return manual;
  const auto = Number(r.auto_rate);
  return auto > 0 ? auto : Number(r.rate_to_thb);
}

export function readFx_() {
  const rates = { THB: 1 };
  readTable_(SHEETS.FX).forEach(r => {
    const rate = fxRateOf_(r);
    if (r.currency && rate > 0) rates[r.currency] = rate;
  });
  return rates;
}

/** อัปเดตอัตราอัตโนมัติ: rates = { USD: บาทต่อ 1 USD, ... } */
export function setFxAuto_(rates, date) {
  const rows = table_(SHEETS.FX);
  Object.keys(rates || {}).forEach(c => {
    const rate = Number(rates[c]);
    if (!(rate > 0) || CURRENCIES.indexOf(c) < 0) return;
    let r = rows.find(x => x.currency === c);
    if (!r) { r = normRow_(SHEETS.FX, { currency: c }); rows.push(r); }
    r.auto_rate = rate;
    r.rate_to_thb = fxRateOf_(r);
    r.updated_note = 'auto ' + (date || today_());
  });
  return readFx_();
}
