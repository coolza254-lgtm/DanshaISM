/**
 * โครงสร้างข้อมูลทุกตาราง (เดิมคือแท็บใน Google Sheets)
 * ข้อมูลจริงเก็บเป็นไฟล์ data/<ชื่อตาราง>.json บน branch "data" ของ GitHub
 */
export const SHEETS = {
  ACCOUNT: 'accounts',
  TXN: 'transactions',
  BILL: 'bills',
  DEBT: 'debts',
  SHOPEE: 'shopee',
  PORT: 'port',
  CONFIG: 'config',
  CATEGORY: 'categories',
  BILL_TEMPLATE: 'billTemplates',
  FX: 'fx',
  PRICES: 'prices',
};

// คอลัมน์ของแต่ละตาราง (ลำดับคงที่ — ใช้ตอนบันทึกเป็น JSON)
export const SCHEMA = {
  [SHEETS.ACCOUNT]: ['id', 'name', 'type', 'currency', 'opening_balance', 'credit_limit', 'color', 'icon', 'sort', 'active', 'note', 'created_at',
    'debt_engine', 'debt_since', 'tenor_min', 'tenor_max', 'annual_rate'],
  [SHEETS.TXN]: ['id', 'date', 'pay_cycle', 'type', 'account_id', 'to_account_id', 'amount', 'currency', 'category_id', 'subcategory_id', 'note', 'source', 'ref_id', 'created_at', 'full_price', 'gov_subsidy'],
  [SHEETS.BILL]: ['id', 'pay_cycle', 'group', 'name', 'category_id', 'account_id', 'est_amount', 'actual_amount', 'status', 'due_date', 'paid_date', 'txn_id', 'template_id', 'note', 'to_account_id', 'sort'],
  [SHEETS.BILL_TEMPLATE]: ['id', 'group', 'name', 'category_id', 'account_id', 'est_amount', 'due_day', 'active', 'sort', 'note', 'to_account_id'],
  [SHEETS.DEBT]: ['id', 'account_id', 'txn_date', 'kind', 'description', 'principal', 'tenor', 'annual_rate', 'installment', 'first_due_date', 'status', 'note', 'created_at',
    'snap_date', 'snap_remaining', 'snap_paid_periods', 'snap_last_interest_date', 'txn_id'],
  [SHEETS.SHOPEE]: ['id', 'order_date', 'item', 'shop', 'price', 'tenor', 'annual_rate', 'account_id', 'status', 'note', 'created_at',
    'pay_method', 'installment', 'category_id', 'txn_id', 'order_no'],
  [SHEETS.PORT]: ['id', 'date', 'symbol', 'name', 'asset_type', 'side', 'qty', 'price', 'currency', 'fx_rate', 'fee', 'tax', 'broker', 'note', 'created_at'],
  [SHEETS.CATEGORY]: ['id', 'name', 'type', 'parent_id', 'color', 'icon', 'sort', 'active'],
  [SHEETS.FX]: ['currency', 'auto_rate', 'manual_rate', 'rate_to_thb', 'updated_note'],
  [SHEETS.PRICES]: ['symbol', 'price', 'currency', 'updated_at'],
};

// ตารางที่ API อนุญาตให้ CRUD (key ที่ frontend ใช้ → ชื่อแท็บ)
export const TABLES = {
  accounts: SHEETS.ACCOUNT,
  transactions: SHEETS.TXN,
  bills: SHEETS.BILL,
  billTemplates: SHEETS.BILL_TEMPLATE,
  debts: SHEETS.DEBT,
  shopee: SHEETS.SHOPEE,
  port: SHEETS.PORT,
  categories: SHEETS.CATEGORY,
};

// คอลัมน์ตัวเลข / boolean — แปลงชนิดให้ตอนอ่าน
export const NUMERIC = ['opening_balance', 'credit_limit', 'sort', 'amount', 'est_amount', 'actual_amount', 'principal', 'tenor',
  'annual_rate', 'installment', 'price', 'qty', 'fx_rate', 'fee', 'tax', 'due_day', 'auto_rate', 'manual_rate', 'rate_to_thb', 'full_price', 'gov_subsidy', 'tenor_min', 'tenor_max', 'snap_remaining', 'snap_paid_periods'];
export const BOOLEAN = ['active'];
export const DATE_COLS = ['date', 'txn_date', 'first_due_date', 'order_date', 'due_date', 'paid_date', 'debt_since', 'snap_date', 'snap_last_interest_date'];

export const CURRENCIES = ['THB', 'USD', 'JPY', 'CNY'];
export const ACCOUNT_TYPES = ['cash', 'bank', 'revolving_credit'];

// ค่าตั้งต้นของ Config
export const DEFAULT_CONFIG = [
  ['base_currency', 'THB', 'สกุลเงินหลัก'],
  ['pay_cycle_start_day', '15', 'วันเริ่มรอบเงินเดือน'],
  ['theme', 'pastel', 'pastel | mono'],
  ['summary_emails', '', 'อีเมลผู้รับสรุป คั่นด้วย ,'],
  ['reminder_emails', '', 'อีเมลรับแจ้งเตือนครบกำหนด (ว่าง = คนแรกของ summary_emails)'],
  ['daily_summary_enabled', 'false', 'ส่งสรุปรายวันหรือไม่'],
  ['daily_summary_hour', '21', 'ชั่วโมงที่ส่งสรุปรายวัน (0-23)'],
  ['debt_reminder_enabled', 'true', 'แจ้งเตือนครบกำหนดหนี้'],
  ['debt_reminder_days', '3,1', 'แจ้งล่วงหน้ากี่วัน คั่นด้วย ,'],
  ['bill_reminder_enabled', 'false', 'แจ้งเตือนบิลใน Bill Plan'],
  ['bill_reminder_days', '1', 'แจ้งบิลล่วงหน้ากี่วัน'],
  ['shop_warn_pct', '30', 'เช็คก่อนซื้อ: เตือนเมื่อราคาเกินกี่ % ของเงินที่เหลือรอบนี้'],
  ['shop_buffer', '0', 'เช็คก่อนซื้อ: เงินกันไว้ขั้นต่ำต่อรอบ (บาท)'],
  ['copay_enabled', 'true', 'โครงการร่วมจ่าย (ไทยช่วยไทยพลัส 60/40)'],
  ['copay_name', 'ไทยช่วยไทยพลัส 60/40 เฟส 2', 'ชื่อโครงการ'],
  ['copay_account_id', '', 'บัญชี G-Wallet (id ของบัญชี)'],
  ['copay_gov_rate', '60', 'รัฐช่วยจ่ายกี่ %'],
  ['copay_daily_cap', '200', 'รัฐช่วยสูงสุดต่อวัน (บาท)'],
  ['copay_monthly_cap', '1000', 'รัฐช่วยสูงสุดต่อเดือน — ไม่สะสมข้ามเดือน (เฟส 2 ไม่มีเพดานรายเดือน: ใส่เท่าเพดานทั้งโครงการ)'],
  ['copay_total_cap', '1000', 'รัฐช่วยสูงสุดทั้งโครงการ (เฟส 2: 1,000 บาทตลอด 2 เดือน)'],
  ['copay_start', '2026-10-01', 'วันเริ่มใช้สิทธิ'],
  ['copay_end', '2026-11-30', 'วันสุดท้าย'],
  ['copay_hours', '06:00-23:00', 'ช่วงเวลาสแกนจ่ายได้'],
];

// หมวดหมู่ตั้งต้นแบบคร่าวๆ: [ชื่อ, type, สี, ไอคอน, [หมวดย่อย]]
export const DEFAULT_CATEGORIES = [
  ['เงินเดือน', 'income', '#B8E0D2', '💼', []],
  ['รายได้เสริม', 'income', '#C7E9F1', '✨', ['ฟรีแลนซ์', 'ขายของ']],
  ['ดอกเบี้ย/ปันผล', 'income', '#D6EADF', '🌱', []],
  ['รายรับอื่นๆ', 'income', '#E2F0CB', '➕', []],
  ['อาหาร', 'expense', '#FFD6E0', '🍜', ['อาหารหลัก', 'ขนม/เครื่องดื่ม', 'ร้านสะดวกซื้อ', 'เดลิเวอรี่']],
  ['เดินทาง', 'expense', '#C9E4FF', '🚆', ['รถสาธารณะ', 'แท็กซี่/Grab', 'น้ำมัน']],
  ['ที่พัก/บ้าน', 'expense', '#E7D8FF', '🏠', ['ค่าเช่า', 'ค่าไฟ', 'ค่าน้ำ', 'อินเทอร์เน็ต']],
  ['โทรศัพท์/สมาชิก', 'expense', '#D4F1F4', '📱', ['ค่าโทรศัพท์', 'Subscription']],
  ['ช้อปปิ้ง', 'expense', '#FFE5EC', '🛍️', ['Shopee', 'เสื้อผ้า', 'ของใช้']],
  ['สุขภาพ', 'expense', '#D8F3DC', '💊', ['ยา', 'โรงพยาบาล']],
  ['บันเทิง', 'expense', '#FDE2E4', '🎮', ['เกม', 'หนัง/เพลง', 'เที่ยว']],
  ['ครอบครัว/แฟน', 'expense', '#FAD2E1', '💗', []],
  ['ชำระหนี้', 'expense', '#E2ECF9', '💳', ['ดอกเบี้ย', 'ค่าธรรมเนียม']],
  ['ลงทุน', 'expense', '#DDF3F5', '📈', []],
  ['อื่นๆ', 'expense', '#EEEEEE', '📦', []],
];
