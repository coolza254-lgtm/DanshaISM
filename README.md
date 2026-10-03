# 断捨ISM

แอพบันทึกรายรับรายจ่าย หนี้ และแผนบิลส่วนตัว — Android native (Kotlin + Jetpack Compose)

- ข้อมูลอยู่ในเครื่องเท่านั้น (Room/SQLite) ใช้ได้แม้ออฟไลน์ ไม่มีเซิร์ฟเวอร์ ไม่มี token
- สำรองข้อมูล: Android Auto Backup + ส่งออก/นำเข้าไฟล์ `dansha-data/1` (JSON)
- อัปเดตแอพจากในแอพ: อ่าน GitHub Releases ของ repo นี้ → ดาวน์โหลด APK → เปิดตัวติดตั้งของ Android

**repo นี้มีแต่โค้ด** ห้าม commit ข้อมูลส่วนตัว (`dansha-data-*.json`), keystore หรือ token (`.gitignore` กันไว้แล้ว)

## โครงสร้าง

| โมดูล | คืออะไร |
|---|---|
| `core/` | Kotlin ล้วน (ไม่พึ่ง Android): แถวข้อมูลทุกตาราง, อ่าน/เขียนไฟล์ `dansha-data/1`, วันที่/รอบเงินเดือน (เวลาไทยเสมอ) และตัวคำนวณ (Phase 2) |
| `app/` | แอพ Android: Room, หน้าจอ Compose, ตัวอัปเดต |

## คำสั่ง

```bash
# ทดสอบ core (ไม่ต้องมี Android SDK)
./gradlew -Pdansha.android=false :core:test

# ทดสอบกับไฟล์ข้อมูลจริงที่อยู่นอก repo
./gradlew -Pdansha.android=false :core:test -Ddansha.data=/path/to/dansha-data.json

# สร้าง APK (ต้องมี Android SDK)
./gradlew :app:assembleDebug
```

## Release

GitHub Actions (`.github/workflows/build.yml`) ทดสอบ + สร้าง APK ทุก push
และสร้าง Release ที่เซ็นแล้ว (`v1.0.<เลข build>`) เมื่อ push เข้า branch หลัก หรือกด **Run workflow**
ต้องมี secrets `ANDROID_KEYSTORE_BASE64` และ `ANDROID_KEYSTORE_PASSWORD` (alias `dansha`)
