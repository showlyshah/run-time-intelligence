plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.sha.mymoney"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sha.mymoney"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.3.4"
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("LOGANTECH_KEYSTORE_PATH") ?: "logantech-release.jks")
            storePassword = System.getenv("LOGANTECH_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("LOGANTECH_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("LOGANTECH_KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
