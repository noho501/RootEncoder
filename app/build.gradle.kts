plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.pedro.streamer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pedro.streamer"
        minSdk = 23
        targetSdk = 36
        versionCode = project.version.toString().replace(".", "").toInt()
        versionName = project.version.toString()
        multiDexEnabled = true
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
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":library"))
    implementation(project(":extra-sources"))
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.multidex)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    implementation("io.insert-koin:koin-core:4.2.1")

    implementation("io.insert-koin:koin-android:4.2.1")

    implementation("io.insert-koin:koin-androidx-compose:4.2.1")
    implementation("com.google.android.material:material:1.14.0")
}
