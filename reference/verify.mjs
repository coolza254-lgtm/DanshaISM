/**
 * ตัวคำนวณอ้างอิง (ตรรกะเดิมทุกบรรทัด ย้ายจาก Apps Script และตรวจกับระบบเดิมแล้ว)
 * ใช้สร้าง "ตัวเลขที่ถูกต้อง" ไว้เทียบกับแอพใหม่
 *
 *   node verify.mjs <data.json> [yyyy-mm-dd] [hh:mm]   → พิมพ์ JSON: { asOf, bootstrap, debts, outlook }
 *   node verify.mjs dansha-data-2026-10-03.json 2026-10-03 12:00 > expected.json
 *
 * ต้องใช้ Node 20+ (ไม่ต้องติดตั้งอะไรเพิ่ม)
 */
import { readFileSync } from 'node:fs';
import * as E from './engine/index.js';

const [file, date, time] = process.argv.slice(2);
if (!file) { console.error('usage: node verify.mjs <data.json> [yyyy-mm-dd] [hh:mm]'); process.exit(1); }
if (date) E.setNow(`${date}T${time || '12:00'}:00+07:00`);
const raw = JSON.parse(readFileSync(file, 'utf8'));
const db = E.normalizeDb(raw.tables ? Object.assign({ config: raw.config }, raw.tables) : raw);
const out = {
  asOf: E.nowIso_(),
  bootstrap: E.get(db, { action: 'bootstrap' }),
  debts: E.get(db, { action: 'debts' }),
  outlook: E.get(db, { action: 'outlook', count: 6 }),
};
console.log(JSON.stringify(out, null, 2));
