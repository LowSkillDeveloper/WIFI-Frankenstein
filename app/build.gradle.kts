import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("com.google.protobuf") version "0.9.5"
}

android {
    namespace = "com.lsd.wififrankenstein"
    compileSdk = 36

    packaging {
        resources {
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    defaultConfig {
        applicationId = "com.lsd.wififrankenstein"
        minSdk = 21
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
    }

    val officialSignature = localProperties.getProperty("OFFICIAL_SIGNATURE_SHA256")
        ?: System.getenv("OFFICIAL_SIGNATURE_SHA256")
        ?: "no_official_signature_configured"

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "official_signature_sha256", officialSignature)
            resValue("string", "is_debug_build", "false")
        }

        debug {
            resValue("string", "official_signature_sha256", officialSignature)
            resValue("string", "is_debug_build", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }

    buildFeatures {
        viewBinding = true
    }

    buildToolsVersion = "36.0.0"

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("24"))
        languageVersion.set(KotlinVersion.fromVersion("2.3"))
        apiVersion.set(KotlinVersion.fromVersion("2.3"))
        // Проверки на будующее:
        // freeCompilerArgs.addAll(listOf("-Xprogressive"))
        // optIn.add("kotlin.RequiresOptIn")
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
    }
}

dependencies {
    implementation (libs.ipaddress)
    implementation (libs.commons.net)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.nio)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.jsoup)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.opencsv) {
        exclude(group = "commons-logging", module = "commons-logging")
    }
    implementation(libs.okhttp)
    implementation(libs.osmdroid.android)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.glide)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.flexbox)
    implementation(libs.core)
    implementation(libs.zstd.jni)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.play.services.location)
    implementation(libs.androidx.documentfile)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
