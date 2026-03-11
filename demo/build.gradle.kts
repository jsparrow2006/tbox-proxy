plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dashing.tbox.proxy.demo"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "app"
    productFlavors {
        create("demo1") {
            dimension = "app"
            applicationId = "com.dashing.tbox.proxy.demo"
            resValue("string", "app_name", "TBox Demo")
        }
        create("demo2") {
            dimension = "app"
            applicationId = "com.dashing.tbox.proxy.demo2"
            resValue("string", "app_name", "TBox Demo2")
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
        compose = false
    }
    compileSdkExtension = 11
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.activity:activity:1.7.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation(project(":core"))
}