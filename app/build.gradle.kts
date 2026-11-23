import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)  // @Serializable models for JSON answers
}

android {
    namespace = "com.farimarwat.saferequestexample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.farimarwat.saferequestexample"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: only from ~/.gradle/gradle.properties (srqSampleStoreFile, srqSampleStorePassword,
    // srqSampleKeyAlias, srqSampleKeyPassword), never from this repo. Without them the release APK is
    // built unsigned.
    val storeFilePath = providers.gradleProperty("srqSampleStoreFile").orNull
    if (storeFilePath != null) {
        signingConfigs.create("sampleRelease") {
            storeFile = file(storeFilePath)
            storePassword = providers.gradleProperty("srqSampleStorePassword").orNull
            keyAlias = providers.gradleProperty("srqSampleKeyAlias").orNull
            keyPassword = providers.gradleProperty("srqSampleKeyPassword").orNull
        }
    }

    buildTypes {
        // SafeRequest's debugSignatures(...): the SHA-256 of THIS machine's debug key, read when
        // building, so Android Studio's own tools (Run's fast deploy, debugger, profilers) don't
        // block requests in debug builds. Release builds get "" and stay fully strict.
        debug {
            buildConfigField("String", "RELEASE_KEY_SHA256", "\"\"")  // debug builds are signed with the debug key
            buildConfigField("String", "DEBUG_KEY_SHA256", "\"${debugKeySha256(signingConfigs.getByName("debug"))}\"")
        }
        release {
            signingConfigs.findByName("sampleRelease")?.let { signingConfig = it }
            buildConfigField("String", "DEBUG_KEY_SHA256", "\"\"")
            // SafeRequest's expectSignatures(...): the SHA-256 of the key you sign the release APK
            // with (keytool -list -v, or Play Console > App integrity > App signing key). Set
            // srqSampleReleaseKey in ~/.gradle/gradle.properties or pass -PsrqSampleReleaseKey=...
            // Then a re-signed (edited) copy of the app is blocked at every level.
            buildConfigField("String", "RELEASE_KEY_SHA256", "\"${releaseKeySha256()}\"")
            // Full R8, as most apps ship. Not packageScope: optimizing only some packages moved a
            // Kotlin standard-library class out of its package and crashed the app at start (seen
            // 2026-11-16, IllegalAccessError in kotlin.collections, before any SafeRequest code).
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // The library this sample shows (and the manual test screen in MainActivity uses).
    implementation(libs.saferequest)  // from Maven Central
    implementation(libs.kotlinx.serialization.json)  // @Serializable models
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
/**
 * SHA-256 (hex) of the debug signing certificate, or "" (with a warning) if it can't be read. On a
 * new machine Android Studio makes the debug keystore during the first build: build once more.
 */
fun debugKeySha256(config: com.android.build.api.dsl.ApkSigningConfig): String {
    val file = config.storeFile
    if (file == null || !file.isFile) {
        logger.warn("Debug keystore not found yet: build again so debugSignatures(...) gets your debug key")
        return ""
    }
    return try {
        val password = (config.storePassword ?: return "").toCharArray()
        val certificate = KeyStore.getInstance(file, password).getCertificate(config.keyAlias ?: return "") ?: return ""
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
    } catch (e: java.security.GeneralSecurityException) {
        logger.warn("Debug keystore can't be read (${e.javaClass.simpleName}): debugSignatures(...) not set")
        ""
    } catch (e: java.io.IOException) {
        logger.warn("Debug keystore can't be read (${e.javaClass.simpleName}): debugSignatures(...) not set")
        ""
    }
}

/**
 * The release key's SHA-256 from -PsrqSampleReleaseKey (hex, colons allowed). Missing or malformed
 * only matters when a release build was asked for (warning / failure then); debug builds ignore it.
 */
fun releaseKeySha256(): String {
    val buildingRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
    val value = providers.gradleProperty("srqSampleReleaseKey").orNull?.replace(":", "")?.trim()?.lowercase()
    if (value.isNullOrEmpty()) {
        if (buildingRelease) {
            logger.warn("srqSampleReleaseKey not set: this release APK does not check its own signature (a re-signed copy is not blocked)")
        }
        return ""
    }
    if (!Regex("[0-9a-f]{64}").matches(value)) {
        require(!buildingRelease) { "srqSampleReleaseKey must be a SHA-256 in hex" }
        return ""
    }
    return value
}
