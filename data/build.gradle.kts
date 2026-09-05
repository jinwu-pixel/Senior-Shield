plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.seniorshield.data"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

kapt {
    arguments {
        // Room 스키마 JSON을 data/schemas/<DB FQCN>/<version>.json 으로 export.
        // exportSchema=true 와 한 쌍 — 이 인자가 없으면 JSON이 생성되지 않는다(경고만).
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    api(project(":domain:contracts"))
    api(project(":domain:risk"))

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.google.dagger:hilt-android:2.52")
    kapt("com.google.dagger:hilt-android-compiler:2.52")
    implementation("javax.inject:javax.inject:1")
}
