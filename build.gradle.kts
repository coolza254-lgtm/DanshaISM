// ปลั๊กอินทั้งหมดอยู่ใน classloader เดียวกัน (AGP ต้องมองเห็นจาก Kotlin plugin)
// AGP โหลดเฉพาะเมื่อสร้างแอพ: ./gradlew -Pdansha.android=false :core:test ใช้ได้โดยไม่ต้องมี Android SDK
buildscript {
    val kotlinVersion = "2.1.20"
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        if (providers.gradleProperty("dansha.android").orNull != "false") {
            classpath("com.android.tools.build:gradle:8.9.1")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
            classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.1.20-1.0.32")
        }
    }
}
