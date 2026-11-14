import java.util.Properties

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
        versionCode = 5
        versionName = "0.5"
    }

    // release 서명: 저장소 밖의 설정 파일(~/.boneul/motionforecast_signing.properties)이 있을 때만.
    // 파일이 없으면 release는 서명 없이 만들어지고, debug 빌드는 그대로 된다.
    val signingFile = File(System.getProperty("user.home"), ".boneul/motionforecast_signing.properties")
    val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
    signingConfigs {
        if (signingFile.exists()) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
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
