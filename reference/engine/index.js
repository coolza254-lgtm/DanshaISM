/**
 * Engine ของ 断捨ISM — ตรรกะทั้งหมดของแอพ (ย้ายมาจาก Apps Script)
 * ไม่ยุ่งกับเครือข่าย: รับ db (object) → คำนวณ/แก้ไข → คืนผลลัพธ์
 *
 *   const db = loadSomehow();
 *   const data = get(db, { action: 'bootstrap' });
 *   const row = post(db, { action: 'create', table: 'transactions', data: {...} });  // แก้ db ตรงๆ
 */
import { useDb, readConfig_ } from './store.js';
import { doGet, doPost } from './api.js';
import { buildDailySummary_, buildReminders_, dueMailJobs_, testReminderConfig_, recipients_ } from './mail.js';

export { emptyDb, seedDb, normalizeDb, setNow, now_, today_, nowIso_, payCycleOf_, ROW_TABLES, keyOf } from './store.js';
export { SHEETS, SCHEMA, TABLES, CURRENCIES } from './schema.js';
export { DebtEngine } from './debt-engine.js';

/** อ่านข้อมูล (ไม่แก้ db) */
export function get(db, params) {
  useDb(db);
  return clone(doGet(params));
}

/** แก้ข้อมูล: แก้ db ที่ส่งเข้ามาโดยตรง ถ้า error จะไม่มีอะไรเปลี่ยน */
export function post(db, body) {
  const work = clone(db);
  useDb(work);
  const res = clone(doPost(clone(body)));
  Object.keys(db).forEach(k => { delete db[k]; });
  Object.assign(db, work);
  return res;
}

/** อีเมลที่ถึงเวลาส่ง: sent = { summary, remind } ที่ส่งไปแล้ววันนี้ → [{ kind, to, subject, html }] */
export function dueMails(db, sent) {
  useDb(db);
  const cfg = readConfig_();
  return dueMailJobs_(cfg, sent).map(kind => {
    const m = kind === 'summary' ? buildDailySummary_(cfg) : buildReminders_(cfg);
    return m ? Object.assign({ kind: kind }, m) : { kind: kind, skip: true };
  });
}

/** อีเมลทดสอบ (สั่งจากหน้าตั้งค่า) */
export function testMail(db, kind) {
  useDb(db);
  const cfg = readConfig_();
  const m = kind === 'remind' ? buildReminders_(testReminderConfig_(cfg)) : buildDailySummary_(cfg);
  return m ? Object.assign({ kind: kind }, m) : { kind: kind, skip: true, to: recipients_(cfg, kind) };
}

const clone = v => (v === undefined ? v : JSON.parse(JSON.stringify(v)));
