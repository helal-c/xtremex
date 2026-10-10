plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStore = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "com.xtremex.tv"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.xtremex.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "6").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.1.4"
        val authBase = System.getenv("AUTH_API_BASE") ?: "https://xtremex-tv-admin.vercel.app"
        require(authBase.matches(Regex("https://[a-zA-Z0-9.-]+(:443)?/?"))) { "AUTH_API_BASE must be an HTTPS origin" }
        val admobApp = System.getenv("ADMOB_APP_ID") ?: "ca-app-pub-3940256099942544~3347511713"
        require(admobApp.matches(Regex("ca-app-pub-[0-9]{16}~[0-9]{10}"))) { "Invalid ADMOB_APP_ID" }
        manifestPlaceholders["admobAppId"] = admobApp
        buildConfigField("String", "ADMOB_APP_ID", "\"$admobApp\"")
        buildConfigField("String", "AUTH_API_BASE", "\"$authBase\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (!releaseStore.isNullOrBlank()) {
                storeFile = file(releaseStore)
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (!releaseStore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // 25.5 raises minSdk to 24; 25.4 preserves installed Android 6 devices.
    implementation("com.google.android.gms:play-services-ads:25.4.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
}
