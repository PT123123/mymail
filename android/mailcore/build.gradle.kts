plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // JavaMail 1.6.x 是 Android 上久经考验的 IMAP/SMTP 实现(K-9 血统)
    // api:app 模块直接使用 MimeMessage 等类型
    // 注意:1.6.7 的包名仍是 javax.*;排除其传递的 jakarta.activation,用 Android 专用的 android-activation
    api("com.sun.mail:jakarta.mail:1.6.7") {
        exclude(group = "com.sun.activation", module = "jakarta.activation")
    }
    api("com.sun.mail:android-activation:1.6.7")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
