plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "ai.openclaw.mobile"
  compileSdk = 34

  defaultConfig {
    applicationId = "ai.openclaw.mobile"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0-alpha"
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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
  buildFeatures {
    compose = true
  }
  composeOptions {
    kotlinCompilerExtensionVersion = "1.5.14"
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.compose.ui:ui:1.7.2")
  implementation("androidx.compose.ui:ui-tooling-preview:1.7.2")
  implementation("androidx.compose.material3:material3:1.3.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("com.google.android.material:material:1.12.0")
  debugImplementation("androidx.compose.ui:ui-tooling:1.7.2")
}
