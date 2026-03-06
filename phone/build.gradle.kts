plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.calendarcomplication.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.calendarcomplication"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.play.services.wearable)
    implementation(libs.work.runtime.ktx)
    implementation(libs.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.compose.material)
    implementation(libs.compose.foundation)
}
