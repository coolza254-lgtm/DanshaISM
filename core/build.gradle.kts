import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // ไฟล์ข้อมูลจริง (ไม่อยู่ใน repo) สำหรับทดสอบในเครื่อง: -Ddansha.data=/path/dansha-data.json
    System.getProperty("dansha.data")?.let { systemProperty("dansha.data", it) }
    // ตัวคำนวณอ้างอิง (JavaScript ของระบบเดิม) สำหรับเทียบผล — ต้องมี node
    systemProperty("dansha.reference", rootProject.file("reference").absolutePath)
    System.getProperty("dansha.dates")?.let { systemProperty("dansha.dates", it) }
}
