plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.logan.runtimedebug"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug { buildConfigField("boolean", "DIAGNOSTICS_ENABLED", "true") }
        release { buildConfigField("boolean", "DIAGNOSTICS_ENABLED", "false") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

