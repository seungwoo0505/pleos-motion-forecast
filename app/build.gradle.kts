plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.recovlab.motionforecast"
    compileSdk = 35

    // AAOS standard Car API (speed, gear)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.recovlab.motionforecast"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")        // seats, doors, steering, turn signal, brake
    implementation("ai.pleos.playground:NaviHelper:3.0.5")     // route, TBT, congestion, remaining time
    implementation("ai.pleos.playground:FusedLocation:1.1.3")  // position for the trip log
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")   // on-device Korean voice
}
