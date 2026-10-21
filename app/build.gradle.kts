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
    (leaderboardProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(name)?.trim().orEmpty())
        .replace("\\", "\\\\").replace("\"", "\\\"")

// Подпись: один постоянный ключ для всех сборок (локальных, CI, релизов) —
// иначе Android не ставит новую версию поверх старой. Путь и пароль берутся из
// keystore.properties в корне (локально, в .gitignore) или из переменных
// окружения CI (секреты NUTRILENS_KEYSTORE_*). Нет ни того ни другого —
// сборка подписывается стандартным debug-ключом.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    signingProps.getProperty(prop)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }

val keystorePath = signingValue("storeFile", "NUTRILENS_KEYSTORE_PATH")
val keystorePassword = signingValue("storePassword", "NUTRILENS_KEYSTORE_PASSWORD")
val hasStableSigning = keystorePath != null && keystorePassword != null &&
    rootProject.file(keystorePath).exists()

android {
    namespace = "com.nutrilens.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nutrilens.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 58
        versionName = "3.1"
        buildConfigField("String", "LEADERBOARD_REPO", "\"${leaderboardProp("LEADERBOARD_REPO")}\"")
        buildConfigField("String", "LEADERBOARD_TOKEN", "\"${leaderboardProp("LEADERBOARD_TOKEN")}\"")
    }

    signingConfigs {
        if (hasStableSigning) {
            create("nutrilens") {
                storeFile = rootProject.file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = signingValue("keyAlias", "NUTRILENS_KEY_ALIAS") ?: "nutrilens"
                keyPassword = signingValue("keyPassword", "NUTRILENS_KEY_PASSWORD") ?: keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasStableSigning) signingConfig = signingConfigs.getByName("nutrilens")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasStableSigning) {
                signingConfigs.getByName("nutrilens")
            } else {
                signingConfigs.getByName("debug")
            }
        }
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

    testImplementation(libs.junit)
}
