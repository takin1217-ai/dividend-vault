import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("../secrets/keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKey = keystorePropertiesFile.exists()
if (hasReleaseKey) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.takin1217.dividendvault"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.takin1217.dividendvault"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file("../secrets/${keystoreProperties.getProperty("storeFile")}")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

tasks.register<Copy>("copySchema") {
    from(rootProject.file("../shared/schema.sql"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild").configure { dependsOn("copySchema") }
