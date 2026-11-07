import java.util.Properties

plugins { id("com.android.application") }

// T14-1: the Play upload key lives OUTSIDE the repo (owner-backed-up); its path and
// passwords come from the GITIGNORED android/keystore.properties. Without that file the
// release build stays unsigned (gates still pass on machines without the key).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android { namespace = "dev.unifieddesktop.app"; compileSdk = 36
    // T13-10 item 5 (B-127 decision): BuildConfig.DEBUG guards the debug-only thermal
    // injection - the release build must not even compile the flag in.
    buildFeatures { buildConfig = true }
    defaultConfig { applicationId = "dev.unifieddesktop.app"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "1.0.0" }
    // libbox64.so must be extracted to nativeLibraryDir so it can be executed (C1: only
    // files from nativeLibraryDir run; everything else is data).
    packaging { jniLibs { useLegacyPackaging = true } }
    // Phase 12 A5: a phone never needs the other ABI's translator/Node binaries (82 MB of
    // libnode alone). Keep a universal APK for adb testing and produce per-ABI APKs for
    // real installs and Play delivery.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                println("PopToPC: android/keystore.properties not found - assembleRelease produces an UNSIGNED APK")
            }
            isMinifyEnabled = false
        }
    }
}
dependencies { implementation(project(":engine")); implementation("org.tukaani:xz:1.10"); testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303") }
