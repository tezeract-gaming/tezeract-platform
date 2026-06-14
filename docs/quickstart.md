# Quick Start

Build a "raise your arms to score" game in ten minutes.

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17**
- A **TezeractOS device** (Orange Pi 5 Plus with the system image flashed)
  *or* the local emulator+EmulatorMode setup if you don't have hardware

## 1. Create a new Android app module

`File → New → New Project → Empty Compose Activity` (or build a plain
View-based app — the SDK is UI-framework-agnostic).

In `app/build.gradle.kts`:

```kotlin
android {
    compileSdk = 34
    defaultConfig {
        targetSdk = 31              // Android 12 — the TezeractOS image
        minSdk = 31
    }
}

dependencies {
    implementation("com.tezeract:sdk-motion:1.0.0")  // see Packaging for actual coordinate
}
```

## 2. Declare yourself as a Tezeract game

Add the `MOTION_GAME` intent filter so the launcher discovers you:

```xml
<!-- AndroidManifest.xml -->
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:screenOrientation="landscape">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
    <intent-filter>
        <action android:name="com.tezeract.intent.action.MOTION_GAME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>

    <meta-data android:name="com.tezeract.category" android:value="party" />
    <meta-data android:name="com.tezeract.min_players" android:value="1" />
    <meta-data android:name="com.tezeract.max_players" android:value="1" />
    <meta-data android:name="com.tezeract.description"
        android:value="Raise your arms to score points!" />
</activity>
```

See [Game Manifest](manifest.md) for every metadata field.

## 3. Subscribe to motion

```kotlin
class MainActivity : ComponentActivity() {

    private var score by mutableStateOf(0)

    private val inputListener = InputListener { event ->
        if (event.action == InputAction.PRESS && event.input == TezeractInput.BUTTON_Y) {
            // BUTTON_Y maps to ARMS_UP by default — both arms above the head.
            score += 10
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TezeractMotion.initialize(this)
        TezeractMotion.addInputListener(inputListener)

        setContent {
            Text("Score: $score", fontSize = 48.sp)
        }
    }

    override fun onDestroy() {
        TezeractMotion.removeInputListener(inputListener)
        TezeractMotion.release()
        super.onDestroy()
    }
}
```

That's the whole game. Three lines of motion code:

1. `TezeractMotion.initialize(this)` — bind to the system motion service
2. `TezeractMotion.addInputListener { ... }` — register your callback
3. `TezeractMotion.release()` in `onDestroy`

## 4. Run it

Build, install, launch. Stand in front of the camera, raise both arms
above your head — score increments.

To exit, **make a triangle above your head** (arms peaked together).
The SDK fires `Intent.CATEGORY_HOME` automatically — your game closes,
the Tezeract launcher appears. **You did not have to write exit code.**

## What's next

- **[SDK Reference](sdk-reference.md)** — all the inputs, gestures,
  and raw keypoints you can subscribe to.
- **[Best Practices](best-practices.md)** — choose gestures users
  can actually do.
- **[Submitting to the Store](submitting.md)** — when you're ready
  to ship.
