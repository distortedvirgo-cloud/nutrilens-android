plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties

// Лидерборд: репозиторий и токен читаются из корневого leaderboard.properties.
// Файл в .gitignore и не коммитится; отсутствует — пустые строки, фича выключена.
// Внутри build.gradle.kts ссылку java.util.Properties нужно через import:
// иначе компилятор Kotlin DSL разрешает java не как пакет, и util не находится.
val leaderboardProps = Properties().apply {
    val f = rootProject.file("leaderboard.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Значение для buildConfigField: экранируем слеши и кавычки, чтобы строковый
// литерал собрался при любом содержимом properties.
fun leaderboardProp(name: String): String =
    leaderboardProps.getProperty(name, "").trim()
        .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.nutrilens.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nutrilens.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 54
        versionName = "1.53"
        buildConfigField("String", "LEADERBOARD_REPO", "\"${leaderboardProp("LEADERBOARD_REPO")}\"")
        buildConfigField("String", "LEADERBOARD_TOKEN", "\"${leaderboardProp("LEADERBOARD_TOKEN")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
}
