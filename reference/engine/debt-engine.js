/**
 * ตัวจำลองสินเชื่อ Ascend PayNext / PayNext Extra (ฟังก์ชันล้วน — ทดสอบด้วย node ได้)
 *
 * กติกา (ตรวจกับ statement จริง 23 ฉบับ):
 *  - รอบบิล 16–15, ออกบิลวันที่ 15, ครบกำหนดวันที่ 1 ของเดือนถัดไป
 *  - ค่างวด = P·r / (1 − (1+r)^−n), r = อัตราต่อปี / 12, ปัด 2 ตำแหน่ง (คงที่ทุกงวด งวดสุดท้าย = เงินต้นที่เหลือ)
 *  - ดอกเบี้ยนับวันจริง: เงินต้นคงเหลือ × อัตรา × วัน / 365 นับจากวันที่ชำระครั้งล่าสุด (หรือวันกู้)
 *  - บิล: ดอกของงวด = ดอกคาดการณ์ถึงวันครบกำหนด − ดอกที่จ่ายล่วงหน้าไปแล้วในงวด; เงินต้นของงวด = ค่างวด − ดอกทั้งงวด
 *  - จ่ายก่อนกำหนด: ดอกคิดถึงวันที่จ่ายจริง ส่วนที่เหลือของยอดบิลก้อนนั้นตัดเงินต้นของก้อนนั้น (= ประหยัดดอก)
 *  - ลำดับตัดชำระ: ค่าธรรมเนียม → ดอกเบี้ย (ก้อนเก่าก่อน) → เงินต้นตามบิล (ก้อนเก่าก่อน) → เงินต้นส่วนเกิน (ก้อนเก่าก่อน)
 *  - ยอดเต็มจำนวน (ร้านค้า) ไม่มีดอกถ้าจ่ายครบภายในวันครบกำหนด; ค้าง ≥ 300 ณ สิ้นวันครบกำหนด → แปลงเป็นผ่อน 5 งวดอัตโนมัติ
 */

export const DebtEngine = (function () {
  const DAY = 86400000;
  const r2 = n => Math.round((n + Number.EPSILON) * 100) / 100;
  const toDay = s => { const p = String(s).slice(0, 10).split('-').map(Number); return Date.UTC(p[0], p[1] - 1, p[2]) / DAY; };
  const toStr = d => new Date(d * DAY).toISOString().slice(0, 10);
  const EPS = 0.004;

  function installmentOf(principal, tenor, rate) {
    const r = rate / 12;
    return r2(principal * r / (1 - Math.pow(1 + r, -tenor)));
  }

  /** วันครบกำหนดของบิลที่ออกวันที่ 15 ของเดือนของ d (d = day number) */
  function dueAfterStatement(stmtDay) {
    const dt = new Date(stmtDay * DAY);
    return Date.UTC(dt.getUTCFullYear(), dt.getUTCMonth() + 1, 1) / DAY;
  }
  /** วันออกบิลถัดไปที่ >= d */
  function nextStatement(d) {
    const dt = new Date(d * DAY);
    let s = Date.UTC(dt.getUTCFullYear(), dt.getUTCMonth(), 15) / DAY;
    if (s < d) s = Date.UTC(dt.getUTCFullYear(), dt.getUTCMonth() + 1, 15) / DAY;
    return s;
  }
  /** วันครบกำหนดถัดไปที่ > d (ใช้กับการแปลงผ่อนระหว่าง 16–1) */
  function nextDue(d) {
    const dt = new Date(d * DAY);
    if (dt.getUTCDate() === 1) return d; // วันที่ 1 = วันครบกำหนดของบิลปัจจุบัน (ยังเลือกผ่อนได้)
    return Date.UTC(dt.getUTCFullYear(), dt.getUTCMonth() + 1, 1) / DAY;
  }

  /**
   * input = {
   *   rate: 0.25, autoConvertTenor: 5, autoConvertMin: 300,
   *   loans: [{ id, date, principal, tenor, rate?, billNow?, desc?,
   *             snapshot?: { date, remaining, paidPeriods, lastInterestDate } }],
   *   purchases: [{ id, date, amount, desc }],     // ยอดเต็มจำนวน
   *   payments:  [{ id, date, amount }],
   *   until: 'yyyy-mm-dd'
   * }
   */
  function simulate(input) {
    const rate = input.rate != null ? input.rate : 0.25;
    const until = toDay(input.until);
    const autoTenor = input.autoConvertTenor || 5;
    const autoMin = input.autoConvertMin != null ? input.autoConvertMin : 300;
    const autopayFrom = input.autopayFrom ? toDay(input.autopayFrom) : null;

    const loans = [];
    const statements = [];
    const log = [];
    const fulls = []; // {id,date,amount,remaining,billedDue:null}
    let fees = 0;
    let overduePeriods = 0;

    const events = [];
    (input.loans || []).forEach(l => {
      const start = l.snapshot ? toDay(l.snapshot.date) : toDay(l.date);
      events.push({ day: start, order: 1, type: 'loan', data: l });
    });
    (input.purchases || []).forEach(p => events.push({ day: toDay(p.date), order: 1, type: 'purchase', data: p }));
    (input.payments || []).forEach(p => events.push({ day: toDay(p.date), order: 2, type: 'payment', data: p }));
    const first = events.length ? Math.min.apply(null, events.map(e => e.day)) : until;
    for (let s = nextStatement(first); s <= until; s = nextStatement(s + 1)) {
      events.push({ day: s, order: 3, type: 'statement' });
      const d = dueAfterStatement(s);
      if (d <= until) events.push({ day: d, order: 4, type: 'due', stmt: s });
    }
    events.sort((a, b) => a.day - b.day || a.order - b.order);

    const mode = input.roundMode || 'exact';
    const seg = v => mode === 'round' ? r2(v) : mode === 'floor' ? Math.floor(v * 100 + 1e-9) / 100 : mode === 'ceil' ? Math.ceil(v * 100 - 1e-9) / 100 : v;
    function accrue(l, day) {
      if (day > l.L) { l.A += seg(l.R * l.rate * (day - l.L) / 365); l.L = day; }
    }
    function addLoan(l, day) {
      const snap = l.snapshot;
      const loan = {
        id: l.id, desc: l.desc || '', kind: l.kind || 'cash', start: toDay(l.date), principal: Number(l.principal), tenor: Number(l.tenor),
        rate: l.rate != null ? Number(l.rate) : rate,
        R: snap ? Number(snap.remaining) : Number(l.principal),
        L: snap ? toDay(snap.lastInterestDate || snap.date) : toDay(l.date),
        A: 0, Ip: 0, k: snap ? Number(snap.paidPeriods) : 0, bill: null, history: [],
      };
      loan.installment = l.installment ? Number(l.installment) : installmentOf(loan.principal, loan.tenor, loan.rate);
      if (l.fromPurchases && !snap) {
        // แปลงยอดเต็มจำนวนในบิลปัจจุบันเป็นผ่อน: ตัดยอดเต็มจำนวน (เก่าก่อน) และคิดดอกตั้งแต่วันถัดจากวันออกบิล
        const due = nextDue(day);
        let left = loan.principal;
        fulls.forEach(u => { if (left > EPS && u.billedDue === due && u.remaining > EPS) { const x = Math.min(left, u.remaining); u.remaining = r2(u.remaining - x); left = r2(left - x); } });
        const stmtDay = Date.UTC(new Date(due * DAY).getUTCFullYear(), new Date(due * DAY).getUTCMonth() - 1, 16) / DAY;
        loan.L = l.interestFrom ? toDay(l.interestFrom) : Math.min(stmtDay, day);
        l.billNow = true;
      } else if (l.interestFrom && !snap) loan.L = toDay(l.interestFrom);
      loans.push(loan);
      if (l.billNow) billLoan(loan, nextDue(day));
      return loan;
    }
    function billLoan(l, due) {
      if (l.R <= EPS || l.k >= l.tenor) return null;
      const projected = l.A + seg(l.R * l.rate * (due - l.L) / 365);
      const interestDue = r2(projected);
      l.k += 1;
      let principalDue = r2(l.installment - interestDue - l.Ip);
      if (l.k >= l.tenor || principalDue >= l.R - EPS) principalDue = r2(l.R);
      if (principalDue < 0) principalDue = 0;
      l.bill = { k: l.k, due: due, R: r2(l.R), principalDue: principalDue, interestDue: interestDue, O: r2(principalDue + interestDue) };
      l.Ip = 0;
      return l.bill;
    }

    function pay(p, day) {
      let X = Number(p.amount);
      const alloc = { id: p.id, date: toStr(day), amount: X, fee: 0, interest: 0, principal: 0, parts: [] };
      loans.forEach(l => accrue(l, day));
      // 1. ค่าธรรมเนียม
      const f = Math.min(X, fees); fees = r2(fees - f); X = r2(X - f); alloc.fee = f;
      const payLoan = (l, cap, extra) => {
        // ดอกเบี้ยของก้อนนี้ก่อน แล้วค่อยเงินต้นของก้อนนี้
        let limit = cap;
        const i = Math.min(X, r2(l.A), limit);
        if (i > 0) {
          l.A = r2(r2(l.A) - i); X = r2(X - i); limit = r2(limit - i); alloc.interest = r2(alloc.interest + i);
          if (l.bill && l.bill.O > 0) l.bill.O = r2(Math.max(0, l.bill.O - i));
          else if (!l.bill) l.Ip = r2(l.Ip + i); // ดอกที่จ่ายหลังวันครบกำหนด → ไปลดบิลถัดไป
          alloc.parts.push({ loan: l.id, interest: i });
        }
        const x = Math.min(X, l.R, limit);
        if (x > 0) {
          l.R = r2(l.R - x); X = r2(X - x); alloc.principal = r2(alloc.principal + x);
          if (l.bill && l.bill.O > 0) l.bill.O = r2(Math.max(0, l.bill.O - x));
          alloc.parts.push({ loan: l.id, principal: x, extra: !!extra });
        }
      };
      const payFull = u => {
        const x = Math.min(X, u.remaining); if (x <= 0) return;
        u.remaining = r2(u.remaining - x); X = r2(X - x);
        alloc.principal = r2(alloc.principal + x); alloc.parts.push({ purchase: u.id, principal: x });
      };
      // 2. รายการที่ออกบิลแล้ว (ก้อนผ่อน + ยอดเต็มจำนวน) เรียงตามวันที่ — แต่ละก้อน: ดอก → ต้น ไม่เกินยอดบิลของก้อน
      const billed = [];
      fulls.forEach(u => { if (u.billedDue != null && u.remaining > EPS) billed.push({ t: 'full', o: u, day: u.day }); });
      loans.forEach(l => { if (l.bill && l.bill.O > EPS) billed.push({ t: 'loan', o: l, day: l.start }); });
      billed.sort((a, b) => a.day - b.day);
      billed.forEach(b => { if (X > 0) b.t === 'full' ? payFull(b.o) : payLoan(b.o, b.o.bill.O); });
      // 3. ยอดเต็มจำนวนที่ยังไม่ออกบิล
      fulls.forEach(u => { if (X > 0 && u.remaining > EPS) payFull(u); });
      // 4. ส่วนเกิน → ก้อนเก่าสุดก่อน (ดอกของก้อน → ต้นของก้อน)
      loans.forEach(l => { if (X > 0 && (l.R > EPS || l.A > EPS)) payLoan(l, Infinity, true); });
      alloc.unapplied = X;
      log.push(Object.assign({ type: 'payment' }, alloc));
    }

    events.forEach(e => {
      if (e.day > until) return;
      if (e.type === 'loan') addLoan(e.data, e.day);
      else if (e.type === 'purchase') fulls.push({ id: e.data.id, day: e.day, date: toStr(e.day), desc: e.data.desc || '', amount: Number(e.data.amount), remaining: Number(e.data.amount), billedDue: null });
      else if (e.type === 'payment') pay(e.data, e.day);
      else if (e.type === 'statement') {
        const due = dueAfterStatement(e.day);
        const rows = [];
        loans.forEach(l => {
          if (l.start > e.day || (l.bill && l.bill.due === due)) {
            if (l.bill && l.bill.due === due) rows.push(Object.assign({ loan: l.id }, l.bill, { installment: l.installment, tenor: l.tenor }));
            return;
          }
          const b = billLoan(l, due);
          if (b) rows.push(Object.assign({ loan: l.id }, b, { installment: l.installment, tenor: l.tenor }));
        });
        let fullDue = 0;
        fulls.forEach(u => { if (u.day <= e.day && u.remaining > EPS && u.billedDue == null) { u.billedDue = due; } if (u.billedDue === due) fullDue = r2(fullDue + u.remaining); });
        const outstanding = r2(loans.reduce((s, l) => s + l.R, 0) + fulls.reduce((s, u) => s + u.remaining, 0));
        const totalDue = r2(rows.reduce((s, b) => s + b.O, 0) + fullDue + fees);
        statements.push({ date: toStr(e.day), due: toStr(due), rows: rows, fullDue: fullDue, fees: fees, totalDue: totalDue, outstanding: outstanding });
      } else if (e.type === 'due') {
        // โหมดประมาณการ: สมมติว่าจ่ายเต็มบิลตรงวันครบกำหนดทุกงวด
        if (autopayFrom != null && e.day >= autopayFrom) {
          const amt = r2(loans.reduce((s, l) => s + (l.bill && l.bill.due === e.day ? l.bill.O : 0), 0)
            + fulls.reduce((s, u) => s + (u.billedDue === e.day ? u.remaining : 0), 0) + fees);
          if (amt > EPS) pay({ id: 'auto_' + toStr(e.day), amount: amt, auto: true }, e.day);
        }
        // สิ้นวันครบกำหนด: ยอดเต็มจำนวนค้าง ≥ 300 → ผ่อน 5 งวด; บิลผ่อนค้าง = ผิดนัด
        const unpaidFull = r2(fulls.filter(u => u.billedDue === e.day).reduce((s, u) => s + u.remaining, 0));
        const unpaidLoans = loans.filter(l => l.bill && l.bill.due === e.day && l.bill.O > EPS);
        if (unpaidFull >= autoMin) {
          fulls.forEach(u => { if (u.billedDue === e.day) u.remaining = 0; });
          addLoan({ id: 'auto_' + toStr(e.day), kind: 'auto', desc: 'แปลงยอดเต็มจำนวนเป็นผ่อนอัตโนมัติ', date: toStr(e.day), principal: unpaidFull, tenor: autoTenor }, e.day);
          log.push({ type: 'autoConvert', date: toStr(e.day), amount: unpaidFull });
        }
        const unpaidTotal = r2(unpaidLoans.reduce((s, l) => s + l.bill.O, 0) + (unpaidFull < autoMin ? unpaidFull : 0));
        if (unpaidTotal > EPS) {
          overduePeriods += 1;
          const debt = loans.reduce((s, l) => s + l.R, 0);
          if (debt >= 1000) fees = r2(fees + (overduePeriods > 1 ? 100 : 50));
          log.push({ type: 'overdue', date: toStr(e.day), amount: unpaidTotal });
        } else overduePeriods = 0;
        loans.forEach(l => { if (l.bill && l.bill.due === e.day && l.bill.O <= EPS) l.bill = null; });
      }
    });

    loans.forEach(l => accrue(l, until));
    return {
      asOf: toStr(until),
      loans: loans.map(l => ({
        id: l.id, desc: l.desc, kind: l.kind, start: toStr(l.start), principal: l.principal, tenor: l.tenor, rate: l.rate,
        installment: l.installment, remaining: r2(l.R), paidPeriods: l.k, periodsLeft: Math.max(0, l.tenor - l.k),
        accruedInterest: r2(l.A), lastInterestDate: toStr(l.L), openBill: l.bill ? Object.assign({}, l.bill, { due: toStr(l.bill.due) }) : null,
        status: l.R <= EPS ? 'closed' : 'active',
      })),
      purchases: fulls.map(u => ({ id: u.id, date: u.date, desc: u.desc, amount: u.amount, remaining: u.remaining, billedDue: u.billedDue != null ? toStr(u.billedDue) : null })),
      statements: statements.map(s => Object.assign({}, s, { rows: s.rows.map(b => Object.assign({}, b, { due: toStr(b.due) })) })),
      log: log,
      fees: fees,
      outstanding: r2(loans.reduce((s, l) => s + l.R, 0) + fulls.reduce((s, u) => s + u.remaining, 0)),
      accruedInterest: r2(loans.reduce((s, l) => s + l.A, 0)),
    };
  }

  /** ประมาณการจ่ายโปะวันนี้ให้หมด: เงินต้น + ดอกถึงวันที่จ่าย */
  function payoffQuote(sim) {
    return r2(sim.outstanding + sim.accruedInterest + (sim.fees || 0));
  }

  /** ตารางผ่อนล่วงหน้า (สมมติจ่ายตรงบิลทุกงวด) → { loanId: [{k, due, R, principalDue, interestDue, O}] } */
  function schedule(input, fromDate) {
    const u = new Date(toDay(fromDate) * DAY); const until = toStr(Date.UTC(u.getUTCFullYear() + 2, u.getUTCMonth() + 2, 2) / DAY);
    const sim = simulate(Object.assign({}, input, { autopayFrom: fromDate, until: until }));
    const out = {};
    sim.statements.filter(st => st.date >= fromDate).forEach(st => st.rows.forEach(b => { (out[b.loan] = out[b.loan] || []).push(b); }));
    const bills = sim.statements.filter(st => st.date >= fromDate && st.totalDue > 0).map(st => ({ date: st.date, due: st.due, totalDue: st.totalDue, interest: r2(st.rows.reduce((a, b) => a + b.interestDue, 0)) }));
    return { byLoan: out, bills: bills };
  }
  /** วันออกบิล / ครบกำหนดถัดไป (สำหรับ UI) */
  function nextDates(dateStr) {
    const d = toDay(dateStr); const s = nextStatement(d);
    return { statement: toStr(s), due: toStr(dueAfterStatement(s)) };
  }

  return { simulate: simulate, schedule: schedule, nextDates: nextDates, installmentOf: installmentOf, payoffQuote: payoffQuote, r2: r2, toDay: toDay, toStr: toStr };
})();

