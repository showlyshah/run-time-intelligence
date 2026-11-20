plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.logan.runtimeintelligence"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.logan.runtimeintelligence"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("prebuilt/linux/**", "prebuilt/macosx/**", "prebuilt/windows/**")
    }
    androidResources { noCompress += "apk" }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    debugImplementation(project(":debug-sdk"))
    implementation(files("libs/apktool-android-1.0.0.aar"))
    implementation(fileTree(mapOf("dir" to "libs/apktool-runtime", "include" to listOf("*.jar"))))
    implementation("com.android.tools.build:apksig:8.7.3")
    testImplementation("junit:junit:4.13.2")
}
