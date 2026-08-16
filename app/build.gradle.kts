import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("com.google.protobuf") version "0.10.0"
}

android {
    namespace = "com.lsd.wififrankenstein"
    compileSdk = 37

    packaging {
        resources {
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    defaultConfig {
        applicationId = "com.lsd.wififrankenstein"
        minSdk = 21
        targetSdk = 37
        versionCode = 4
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        ndkVersion = "30.0.15729638"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
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
            isShrinkResources = true
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
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildToolsVersion = "37.0.0"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("25"))
        languageVersion.set(KotlinVersion.fromVersion("2.4"))
        apiVersion.set(KotlinVersion.fromVersion("2.4"))
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
    }
}

dependencies {
    implementation(libs.ipaddress)
    implementation(libs.commons.net)
    implementation(libs.jcifs)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.nio)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
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
