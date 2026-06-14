# Building & Packaging

## Toolchain

| Tool | Version |
| --- | --- |
| **Android Gradle Plugin** | 8.2.x |
| **Kotlin** | 1.9.22+ |
| **JDK** | 17 (required by AGP 8) |
| **compileSdk** | 34 (or higher) |
| **targetSdk** | **31** — TezeractOS runs Android 12 |
| **minSdk** | **31** |

`targetSdk = 31` is enforced. Higher targets opt in to API 32+
behaviors (background-restrictions, BLUETOOTH_SCAN runtime perms,
etc.) that don't apply on the locked-down Tezeract image and can
cause your app to misbehave.

## SDK dependency

Until we publish to a public Maven, the `sdk-motion` AAR ships with
the platform SDK distribution. Drop it in `app/libs/` and:

```kotlin
dependencies {
    implementation(files("libs/sdk-motion-1.0.0.aar"))
    // OR via composite build during development:
    // implementation(project(":sdk-motion"))
}
```

The AAR's manifest contributes the `<queries>` block needed for
package visibility and the AIDL stubs needed to bind to the Motion
Engine service. Manifest merger handles both transparently.

## Recommended Compose stack (optional)

The shipped Tezeract launcher uses **TV Material 3** for focus-aware
UI. If you want the same auto-focus / scale-on-focus behavior:

```kotlin
implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
implementation("androidx.tv:tv-material:1.0.0-alpha10")
```

Pure `androidx.compose.foundation` works too — focus just isn't free.

## Architectures

The Pi is **arm64-v8a**. If you ship native libraries, include at
least `arm64-v8a`. `armeabi-v7a` is unnecessary for production but
fine to ship for emulator dev.

In `build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}
```

## Signing

Submissions to the Tezeract Store **must be signed** with a release
keystore. We accept the standard Android `apksigner` v2/v3 scheme.

Keep your keystore safe: it's how the platform recognizes app updates
as legitimate. We can't reset it for you.

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("TEZERACT_KEYSTORE") ?: "release.jks")
            storePassword = System.getenv("TEZERACT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("TEZERACT_KEY_ALIAS")
            keyPassword = System.getenv("TEZERACT_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

## ProGuard / R8

The SDK's public surface is small (a handful of classes in
`com.tezeract.motion`, `com.tezeract.input`, and `com.tezeract.sdk`)
and is annotated with `-keep` rules in the AAR's `consumer-rules.pro`,
so you don't need to add anything for the SDK itself.

Anything you reflect over (e.g. JSON model classes) — keep yourself
as usual.

## Building a release APK

```bash
./gradlew :app:assembleRelease
ls app/build/outputs/apk/release/
# app-release.apk  ← submit this
```

Verify the signing:

```bash
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

If you see `Verified using v2 scheme: true` you're good.

## Size budget (soft)

| Component | Recommended cap |
| --- | --- |
| Total APK | < 50 MB |
| Native libs | < 10 MB per ABI |
| Bundled assets (audio/textures) | < 30 MB |

These aren't enforced today, but the Tezeract device's storage is
modest and players are sensitive to install times — assume your game
is one of dozens on the device.
