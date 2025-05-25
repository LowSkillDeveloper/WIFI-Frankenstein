plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("com.google.protobuf") version "0.9.5"
}

android {
    namespace = "com.lsd.wififrankenstein"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lsd.wififrankenstein"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    kotlinOptions {
        jvmTarget = "24"
        languageVersion = "2.3"
        apiVersion = "2.3"
    }
    buildFeatures {
        viewBinding = true
    }
    buildToolsVersion = "36.0.0"

}

dependencies {

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
    implementation(libs.opencsv){
        exclude(group = "commons-logging", module = "commons-logging")
    }
    implementation(libs.okhttp)
    implementation(libs.osmdroid.android)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.glide)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}