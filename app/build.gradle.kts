plugins {
    id("com.android.application")
}

android {
    namespace = "br.radar.alerta"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.radar.alerta"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
