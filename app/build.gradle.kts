import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Debug-only convenience: never populated for release builds.
val devPrefill = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun environmentName(key: String): String = "WATCHDOG_" + key.removePrefix("watchdog.")
    .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
    .uppercase()

fun prefill(key: String): String = devPrefill.getProperty(key) ?: System.getenv(environmentName(key)).orEmpty()

fun String.asBuildConfigLiteral(): String = buildString {
    append('"')
    this@asBuildConfigLiteral.forEach { character ->
        append(
            when (character) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> character
            },
        )
    }
    append('"')
}

android {
    namespace = "com.patbaumgartner.roomwatchdog"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.patbaumgartner.roomwatchdog"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_DEVICE_URL", prefill("watchdog.deviceUrl").asBuildConfigLiteral())
            buildConfigField("String", "DEV_API_TOKEN", prefill("watchdog.apiToken").asBuildConfigLiteral())
            buildConfigField("String", "DEV_GOTIFY_URL", prefill("watchdog.gotifyUrl").asBuildConfigLiteral())
            buildConfigField(
                "String",
                "DEV_GOTIFY_CLIENT_TOKEN",
                prefill("watchdog.gotifyClientToken").asBuildConfigLiteral(),
            )
            buildConfigField("String", "DEV_ROOM_NAME", prefill("watchdog.roomName").asBuildConfigLiteral())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEV_DEVICE_URL", "\"\"")
            buildConfigField("String", "DEV_API_TOKEN", "\"\"")
            buildConfigField("String", "DEV_GOTIFY_URL", "\"\"")
            buildConfigField("String", "DEV_GOTIFY_CLIENT_TOKEN", "\"\"")
            buildConfigField("String", "DEV_ROOM_NAME", "\"\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
