plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val releaseSigningValues = mapOf(
    "RELEASE_KEYSTORE_PATH" to System.getenv("RELEASE_KEYSTORE_PATH"),
    "KEYSTORE_PASSWORD" to System.getenv("KEYSTORE_PASSWORD"),
    "KEY_ALIAS" to System.getenv("KEY_ALIAS"),
    "KEY_PASSWORD" to System.getenv("KEY_PASSWORD"),
)
val hasAnyReleaseSigningValue = releaseSigningValues.values.any { !it.isNullOrBlank() }
val hasReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }

if (hasAnyReleaseSigningValue && !hasReleaseSigning) {
    val missingValues = releaseSigningValues
        .filterValues { it.isNullOrBlank() }
        .keys
        .joinToString()
    throw GradleException("Incomplete release signing configuration. Missing: $missingValues")
}

android {
    namespace = "org.archuser.onmyway"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "org.archuser.onmyway"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "v0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("RELEASE_KEYSTORE_PATH")!!)
                storePassword = releaseSigningValues.getValue("KEYSTORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
}
