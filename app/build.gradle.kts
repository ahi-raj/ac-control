plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ahiraj.accontrol"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ahiraj.accontrol"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.work:work-runtime:2.9.0")
    implementation("com.google.android.gms:play-services-location:21.0.1")
}
