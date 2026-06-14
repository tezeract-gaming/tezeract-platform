# Game Manifest

The Tezeract launcher discovers your game via the standard Android
package manager. Two intent filters and a handful of `<meta-data>`
entries are all you need.

## Required intent filters

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:screenOrientation="landscape">

    <!-- Standard launcher intent — needed for Android to consider your
         activity launchable. -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

    <!-- Tezeract-specific. The launcher's AppRepository scans installed
         packages for this action and presents matching activities in the
         Library. The Store also calls into this activity when the user
         taps PLAY. -->
    <intent-filter>
        <action android:name="com.tezeract.intent.action.MOTION_GAME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

`screenOrientation="landscape"` is required — the Tezeract device is
fixed in landscape and games must follow.

## Required metadata

Place these as `<meta-data>` children of `<activity>`:

| Key | Type | Example | Purpose |
| --- | --- | --- | --- |
| `com.tezeract.category` | string | `kids`, `fitness`, `party`, `education`, `utility` | Groups your game in the launcher Library. |
| `com.tezeract.min_players` | int | `1` | Minimum simultaneous players. |
| `com.tezeract.max_players` | int | `1`–`2` (engine tracks up to 2 bodies today) | Maximum simultaneous players. |
| `com.tezeract.description` | string | `"Flap your arms to fly..."` | Short tagline shown on the tile. |

Optional:

| Key | Type | Example | Purpose |
| --- | --- | --- | --- |
| `com.tezeract.requires_hands` | bool | `true` | Game needs hand-tracking mode (TRIGGER_L/R). The launcher will switch the engine to `BODY_AND_HANDS` before launching you. |
| `com.tezeract.requires_face` | bool | `true` | Game needs face-mesh tracking. Rare. |

Example, complete:

```xml
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

    <meta-data android:name="com.tezeract.category" android:value="fitness" />
    <meta-data android:name="com.tezeract.min_players" android:value="1" />
    <meta-data android:name="com.tezeract.max_players" android:value="1" />
    <meta-data android:name="com.tezeract.requires_hands" android:value="true" />
    <meta-data android:name="com.tezeract.description"
        android:value="Throw real punches, dodge incoming attacks." />
</activity>
```

## Permissions

You typically don't need any — the camera and microphone are owned
by the Motion Engine service, not by your game. If your game uses
network calls, audio playback, etc., declare those normally.

## Package visibility (Android 11+)

`sdk-motion`'s manifest already declares the `<queries>` block your
app needs to bind to the Motion Engine service and to be visible
from the launcher. **Don't override or strip it** — Android's
manifest merger pulls it in automatically when you depend on
`sdk-motion`.

## Validating

After install, run on the device:

```
adb shell dumpsys package com.your.game | grep -A 5 "MOTION_GAME"
```

You should see your activity listed under `Resolver Table`. If the
launcher isn't showing your game in Library, this is the first thing
to check.
