plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace="com.xtremex.tv"
  compileSdk=35
  defaultConfig {
    applicationId="com.xtremex.tv"
    minSdk=23
    targetSdk=35
    versionCode=1
    versionName="1.0.0"
  }
  buildTypes {
    debug {
      applicationIdSuffix=".debug"
      versionNameSuffix="-debug"
    }
    release { isMinifyEnabled=false }
  }
  compileOptions {
    sourceCompatibility=JavaVersion.VERSION_17
    targetCompatibility=JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget="17" }
}

dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.recyclerview:recyclerview:1.4.0")
  implementation("androidx.media3:media3-exoplayer:1.6.1")
  implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
  implementation("androidx.media3:media3-ui:1.6.1")
}
