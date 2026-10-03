/**
 * ตัวรันสถานการณ์สำหรับเทียบผลกับแอพ Kotlin (core/src/test/.../ReferenceTest.kt)
 *
 *   node run.mjs <data.json> <scenario.json>   → พิมพ์ JSON: ผลของแต่ละขั้น
 *
 * scenario = { steps: [
 *   { now: '2026-10-03T12:00' },                    // ตั้งเวลา (เวลาไทย)
 *   { get: { action: 'bootstrap' } },               // อ่าน → { ok: ผลลัพธ์ } | { error }
 *   { post: { action: 'create', table, data } },    // แก้ข้อมูล → { ok: true, db } | { error, db }
 * ] }
 * update: รวมแถวเดิม + data ก่อนส่ง (แบบเดียวกับที่แอพส่งทั้งแถว)
 * id ที่สร้างใหม่เป็นเลขเรียง (000000000001, ...) เพื่อให้เทียบกันได้
 */
import { readFileSync } from 'node:fs';
import * as E from './engine/index.js';
import { useDb, readConfig_ } from './engine/store.js';
import { upcomingDues_, buildReminders_ } from './engine/mail.js';
import { resetDebtMemo_ } from './engine/debt.js';

let counter = 0;
Object.defineProperty(globalThis, 'crypto', {
  value: { randomUUID: () => (++counter).toString(16).padStart(12, '0') + '-0000-4000-8000-000000000000' },
  configurable: true,
});

const [dataFile, scenarioFile] = process.argv.slice(2);
if (!dataFile || !scenarioFile) { console.error('usage: node run.mjs <data.json> <scenario.json>'); process.exit(1); }
const raw = JSON.parse(readFileSync(dataFile, 'utf8'));
const db = E.normalizeDb(raw.tables ? Object.assign({ config: raw.config }, raw.tables) : raw);
const scenario = JSON.parse(readFileSync(scenarioFile, 'utf8'));

const dump = () => {
  const tables = {};
  E.ROW_TABLES.forEach(t => { tables[t] = db[t]; });
  return JSON.parse(JSON.stringify({ config: db.config, tables }));
};

const out = [];
for (const step of scenario.steps) {
  if (step.now) {
    E.setNow(step.now.length === 16 ? `${step.now}:00+07:00` : `${step.now}+07:00`);
    out.push({ now: E.nowIso_() });
  } else if (step.get && step.get.action === 'upcomingDues') {
    // ตรรกะแจ้งเตือนจาก mail.js (แอพใหม่ใช้แจ้งเตือนในเครื่องแทนอีเมล)
    useDb(db); resetDebtMemo_();
    out.push({ ok: JSON.parse(JSON.stringify(upcomingDues_(step.get.within))) });
  } else if (step.get && step.get.action === 'reminders') {
    useDb(db); resetDebtMemo_();
    const m = buildReminders_(Object.assign({}, readConfig_(), { summary_emails: 'test@example.com' }));
    out.push({ ok: m ? { count: m.count, subject: m.subject } : null });
  } else if (step.get) {
    try { out.push({ ok: E.get(db, step.get) }); } catch (e) { out.push({ error: e.message }); }
  } else if (step.post) {
    const body = JSON.parse(JSON.stringify(step.post));
    if (body.action === 'update' && body.table) {
      const key = E.keyOf(body.table);
      const cur = (db[body.table] || []).find(r => String(r[key]) === String(body.id));
      body.data = Object.assign({}, cur || {}, body.data || {});
    }
    try { E.post(db, body); out.push({ ok: true, db: dump() }); } catch (e) { out.push({ error: e.message, db: dump() }); }
  }
}
console.log(JSON.stringify(out));
