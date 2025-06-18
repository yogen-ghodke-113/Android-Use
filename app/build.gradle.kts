plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
    id("kotlin-kapt")
}

android {
    namespace = "com.yogen.Android_Use"
    compileSdk = 35
    
    defaultConfig {
        applicationId = "com.yogen.Android_Use"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    packaging {
        resources.excludes.add("**/adk-files/**")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Google ADK
    // implementation("com.google.adk:provider-dev:0.2.0") // REMOVED - Artifact not found, will define needed classes manually.

    // Import the Firebase BoM - use a version compatible with Kotlin 1.9.0
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))

    // Firebase Analytics
    implementation("com.google.firebase:firebase-analytics")
    
    // Firebase Firestore
    implementation("com.google.firebase:firebase-firestore")
    
    // Firebase Storage
    implementation("com.google.firebase:firebase-storage")
    
    // Firebase Authentication
    implementation("com.google.firebase:firebase-auth")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Gson for JSON parsing (used in AccessibilityService)
    implementation("com.google.code.gson:gson:2.10.1")

    // LocalBroadcastManager for intra-app communication
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Networking - Retrofit for Calibration API
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    // Moshi for JSON parsing (needed by converter-moshi)
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1") // Or latest version
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.15.1") // If using code gen

    // OkHttp (Retrofit uses this underneath, ensure consistent version if already present)
    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.squareup.okhttp3:logging-interceptor") // Optional for debugging

    // Jetpack DataStore for saving calibration matrix
    implementation("androidx.datastore:datastore-preferences:1.1.1") // Or latest stable version
} 