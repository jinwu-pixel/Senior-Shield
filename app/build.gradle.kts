
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.seniorshield"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.seniorshield"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "com.example.seniorshield.test.M3TestRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        val stabilityConfiguration = rootProject.file("compose-stability.conf")
            .absolutePath
            .replace('\\', '/')
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=$stabilityConfiguration",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

providers.gradleProperty("composeCompilerReportsDir").orNull?.let { reportsDir ->
    val absoluteReportsDir = file(reportsDir).absolutePath.replace('\\', '/')

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (name == "compileDebugKotlin") {
            kotlinOptions.freeCompilerArgs += listOf(
                "-P",
                "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$absoluteReportsDir",
                "-P",
                "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$absoluteReportsDir",
            )
        }
    }
}

dependencies {
    implementation(project(":data"))
    implementation(project(":domain:risk"))
    implementation(project(":domain:contracts"))

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.google.android.material:material:1.12.0")

    implementation("com.google.dagger:hilt-android:2.52")
    kapt("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("javax.inject:javax.inject:1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.13")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.52")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.52")
}
