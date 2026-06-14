# SDK Reference

The Tezeract Motion SDK is a thin Android library (`sdk-motion`) that
binds to the system Motion Engine service and exposes three subscription
surfaces. Pick the one that fits your game.

## TezeractMotion (singleton)

```kotlin
object TezeractMotion {
    fun initialize(context: Context)
    fun release()

    fun isAvailable(): Boolean
    fun isCameraConnected(): Boolean
    fun getCameraFps(): Int
    fun getAverageLatency(): Float

    fun setTrackingMode(mode: TrackingMode)

    // Raw frames — every camera tick (≈30 fps).
    fun addMotionListener(listener: MotionListener)
    fun removeMotionListener(listener: MotionListener)

    // Named gestures — fire on detection.
    fun addGestureListener(listener: GestureListener)
    fun removeGestureListener(listener: GestureListener)

    // Semantic gamepad-style inputs — recommended for most games.
    fun addInputListener(listener: InputListener)
    fun removeInputListener(listener: InputListener)

    // Per-user calibration (default profile is fine for MVP).
    fun getCalibrationProfile(userId: String = "default"): CalibrationProfile?
    fun setCalibrationProfile(profile: CalibrationProfile, userId: String = "default")
}
```

**Lifecycle:** call `initialize()` once in `Activity.onCreate()`; call
`release()` in `onDestroy()`. The singleton is shared across the
process — multiple activities/composables can subscribe.

## InputListener — the recommended path

`InputEvent` looks like a gamepad. Use this if your game maps to
"buttons" rather than continuous motion.

```kotlin
data class InputEvent(
    val input: TezeractInput,    // DPAD_UP/DOWN/LEFT/RIGHT, BUTTON_A/B/X/Y, TRIGGER_L/R
    val action: InputAction,     // PRESS, HOLD, RELEASE
    val confidence: Float,       // 0..1
    val bodyId: Int,             // which tracked body
    val timestamp: Long,         // System.nanoTime()
)
```

### Default mapping

| TezeractInput | Source motion |
| --- | --- |
| `DPAD_LEFT`  | Raise left arm above shoulder |
| `DPAD_RIGHT` | Raise right arm above shoulder |
| `DPAD_UP`    | Jump |
| `DPAD_DOWN`  | Squat |
| `BUTTON_A`   | Clap (hands meet at chest) |
| `BUTTON_Y`   | Both arms above head (ARMS_UP) |
| `BUTTON_B`   | Both wrists drop to sides from elevated (ARMS_DOWN) |
| `BUTTON_X`   | Lean to the user's left |
| `TRIGGER_L`  | Make a fist with the left hand |
| `TRIGGER_R`  | Make a fist with the right hand |

The mapping comes from `InputBinding.default()`. Users can rebind via
the calibration wizard (forthcoming).

### Press / Hold / Release

- **One-shot gestures** (jump, squat, clap, etc.) emit `PRESS` then
  an automatic `RELEASE` on the next frame.
- **Continuous detectors** (lean, hand raise, hand grab) emit
  `PRESS` on entering the active region, `HOLD` while active,
  `RELEASE` on exit.

## GestureListener — named raw gestures

Use this when you want the gesture name without the gamepad
abstraction.

```kotlin
data class Gesture(
    val name: String,            // see Gesture.kt for constants
    val bodyId: Int,
    val confidence: Float,
    val timestamp: Long,
)
```

Built-in gesture names:

```
JUMP, SQUAT, WAVE_LEFT, WAVE_RIGHT, ARMS_UP, ARMS_DOWN,
CROSS_ARMS, HOME_TRIANGLE, CLAP, STEP_LEFT, STEP_RIGHT
```

## MotionListener — raw 33-keypoint frames

Use this when you need analog motion — paddle position, swipe arcs,
custom punch classifiers, etc.

```kotlin
data class MotionFrame(
    val timestamp: Long,
    val frameNumber: Long,
    val latencyMs: Float,
    val bodyCount: Int,
    val bodies: List<BodyPose>,
    val cameraWidth: Int,
    val cameraHeight: Int,
)

data class BodyPose(
    val id: Int,
    val keypoints: List<Keypoint>,    // 33 — MediaPipe Pose topology
    val hands: List<HandPose>?,       // null unless tracking mode includes hands
    val face: FaceMesh?,
)

data class Keypoint(
    val index: Int,                   // see Keypoint.kt for constants
    val name: String,
    val x: Float,                     // normalized 0..1, MIRRORED selfie-style
    val y: Float,                     // normalized 0..1, increases downward
    val z: Float,                     // relative depth, scale ≈ x
    val confidence: Float,            // 0..1
)
```

### Coordinate system

X and Y are **normalized to 0..1** within the camera frame. **X is
mirrored** at the SDK level so your game's left-side paddle naturally
follows the user's left wrist — no flipping required.

Y increases **downward** (image-coordinate convention).

Z is a relative depth estimate (positive = farther from camera) and
should be treated as low-confidence.

### Keypoint indices

The 33 indices follow MediaPipe Pose topology. Constants on
`Keypoint`:

```
NOSE = 0
LEFT_EYE_INNER = 1, LEFT_EYE = 2, LEFT_EYE_OUTER = 3
RIGHT_EYE_INNER = 4, RIGHT_EYE = 5, RIGHT_EYE_OUTER = 6
LEFT_EAR = 7, RIGHT_EAR = 8
MOUTH_LEFT = 9, MOUTH_RIGHT = 10
LEFT_SHOULDER = 11, RIGHT_SHOULDER = 12
LEFT_ELBOW = 13, RIGHT_ELBOW = 14
LEFT_WRIST = 15, RIGHT_WRIST = 16
LEFT_PINKY = 17, RIGHT_PINKY = 18
LEFT_INDEX = 19, RIGHT_INDEX = 20
LEFT_THUMB = 21, RIGHT_THUMB = 22
LEFT_HIP = 23, RIGHT_HIP = 24
LEFT_KNEE = 25, RIGHT_KNEE = 26
LEFT_ANKLE = 27, RIGHT_ANKLE = 28
LEFT_HEEL = 29, RIGHT_HEEL = 30
LEFT_FOOT_INDEX = 31, RIGHT_FOOT_INDEX = 32
```

## Tracking modes

```kotlin
enum class TrackingMode { BODY_ONLY, BODY_AND_HANDS, FULL }
```

- `BODY_ONLY` — fastest (~10ms / frame on RK3588). Default. No
  finger landmarks, no `TRIGGER_L/R`.
- `BODY_AND_HANDS` — adds 21 landmarks per hand and enables grab
  detection. ~+10ms.
- `FULL` — also adds face mesh (typically not needed for games).

Switch via `TezeractMotion.setTrackingMode(...)`. The motion service
hot-swaps without losing the camera connection.

## Universal HOME_TRIANGLE escape

Every app that calls `TezeractMotion.initialize()` automatically
gets a "go home" gesture: arms forming a triangle peak above the
head, held ~300ms. The SDK fires `Intent.CATEGORY_HOME` and the
user lands on the Tezeract launcher.

**You don't have to do anything.** Just call `initialize()`. The
gesture is reliable enough that fullscreen / kiosk-style games never
need a system back button.
