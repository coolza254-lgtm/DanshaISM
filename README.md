# 断捨ISM

แอพบันทึกรายรับรายจ่าย หนี้ และแผนบิลส่วนตัว — Android native (Kotlin + Jetpack Compose)

- ข้อมูลอยู่ในเครื่องเท่านั้น (Room/SQLite) ใช้ได้แม้ออฟไลน์ ไม่มีเซิร์ฟเวอร์ ไม่มี token
- สำรองข้อมูล: Android Auto Backup + ส่งออก/นำเข้าไฟล์ `dansha-data/1` (JSON)
- อัปเดตแอพจากในแอพ: อ่าน GitHub Releases ของ repo นี้ → ดาวน์โหลด APK → เปิดตัวติดตั้งของ Android

**repo นี้มีแต่โค้ด** ห้าม commit ข้อมูลส่วนตัว (`dansha-data-*.json`), keystore หรือ token (`.gitignore` กันไว้แล้ว)

## โครงสร้าง

| โมดูล | คืออะไร |
|---|---|
| `core/` | Kotlin ล้วน (ไม่พึ่ง Android): แถวข้อมูลทุกตาราง, อ่าน/เขียนไฟล์ `dansha-data/1`, วันที่/รอบเงินเดือน (เวลาไทยเสมอ) |
| `core/.../engine/` | ตัวคำนวณทั้งหมด port จากระบบเดิม: หนี้ Ascend (`DebtEngine`), SPayLater, 60/40, แผนบิล, ภาพรวมรายรอบ, เช็คก่อนซื้อ, พอร์ต, แจ้งเตือน, การแก้ไขข้อมูล (`Store`) |
| `app/` | แอพ Android: Room, หน้าจอ Compose, ตัวอัปเดต |
| `reference/` | ระบบเดิม (JavaScript) ใช้เป็นตัวเทียบผลในชุดทดสอบ + ข้อมูลสมมติ `sample-data.json` |

## หลักการคำนวณ

ตัวคำนวณใน `core/.../engine/` ต้องให้ผล **เท่ากับระบบเดิมทุกตัวเลข** (ใช้ Double + ลำดับการปัดเศษเดิม)
`ReferenceTest` รัน `reference/run.mjs` (ระบบเดิม) กับตัวคำนวณ Kotlin ด้วยข้อมูลและเวลาเดียวกัน
~100 วัน (ทุก 2 วัน + วันออกบิล/ครบกำหนด) และขั้นตอนแก้ไขข้อมูลกว่า 100 ขั้น แล้วเทียบทุกช่อง
ถ้าแก้สูตร ต้องแก้ทั้งสองฝั่งให้ตรงกันและให้ Id ยืนยันก่อน

## คำสั่ง

```bash
# ทดสอบ core (ไม่ต้องมี Android SDK)
./gradlew -Pdansha.android=false :core:test

# ทดสอบกับไฟล์ข้อมูลจริงที่อยู่นอก repo (ต้องมี node สำหรับเทียบกับระบบเดิม)
./gradlew -Pdansha.android=false :core:test -Ddansha.data=/path/to/dansha-data.json

# เลือกวัน/เวลาที่จะเทียบเอง
./gradlew -Pdansha.android=false :core:test -Ddansha.data=... -Ddansha.dates=2026-10-03T12:00,2026-11-02T09:00

# สร้าง APK (ต้องมี Android SDK)
./gradlew :app:assembleDebug
```

## Release

GitHub Actions (`.github/workflows/build.yml`) ทดสอบ + สร้าง APK ทุก push
และสร้าง Release ที่เซ็นแล้ว (`v1.0.<เลข build>`) เมื่อ push เข้า branch หลัก หรือกด **Run workflow**
ต้องมี secrets `ANDROID_KEYSTORE_BASE64` และ `ANDROID_KEYSTORE_PASSWORD` (alias `dansha`)
