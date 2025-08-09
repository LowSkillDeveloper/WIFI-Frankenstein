import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

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
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
