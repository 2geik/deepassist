import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// .env stays outside Git. Its values are embedded in the APK at build time.
val envProperties = Properties().apply {
    val file = rootProject.file(".env")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun envBuildConfigString(name: String): String {
    val value = envProperties.getProperty(name, "").trim()
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.deepassist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deepassist"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String", "DEEPSEEK_API_KEY",
            envBuildConfigString("DEEPSEEK_API_KEY")
        )
        buildConfigField(
            "String", "OPENAI_API_KEY",
            envBuildConfigString("OPENAI_API_KEY")
        )
        buildConfigField(
            "String", "TAVILY_API_KEY",
            envBuildConfigString("TAVILY_API_KEY")
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
