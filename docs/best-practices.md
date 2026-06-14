# Best Practices

Building a great motion game is mostly about respecting the player's
body. They're standing in a living room, possibly tired, and they
expect the game to feel responsive without being exhausting.

## Gesture choice

### ✅ Prefer

- **Single, deliberate poses held briefly** (raise an arm, clap,
  triangle)
- **Continuous-position controls for "analog" things** (lean for
  steering, wrist Y for paddle position)
- **Big, low-precision motions** — easy to perform, easy to detect

### ❌ Avoid

- **Tiny finger-precision controls** — the platform tracks 21
  hand landmarks but accuracy degrades quickly when the hand is
  far from the camera.
- **Sustained difficult poses** — Flappy Arms originally required
  arms above shoulders and was exhausting in 30 seconds. We rewrote
  to "any rapid arm motion."
- **Conflicting gestures** — clap and X-cross had overlapping wrist
  trajectories; we removed X-cross. Watch for similar collisions.
- **Gestures the user can't see** — if the gesture happens behind
  the body or out of frame, no one knows what they're doing wrong.
  Show a **skeleton HUD** so the player sees themselves (every
  shipped Tezeract game does).

## Input: pick the right level of abstraction

| Game type | Use |
| --- | --- |
| Buttons + simple commands (jump, dodge, fire) | `InputListener` (`TezeractInput` enum) |
| Named gestures (clap to play, wave to start) | `GestureListener` (`Gesture.NAME` constants) |
| Continuous analog control (paddle, steering, wing flap velocity) | `MotionListener` (raw `MotionFrame` keypoints) |

Mix freely. **Flappy Arms** uses raw motion for the analog flap
velocity; **Shadow Boxing** uses `MotionListener` + a custom
`PunchDetector`; **the launcher** uses `InputListener` for gamepad-style
nav.

## Always ship a HUD

Every shipped Tezeract game shows a small (~150×220 dp) panel
top-right with:

1. A **skeleton mirror** of the player so they confirm "the camera
   sees me"
2. A **legend of the active controls** — icons + labels

Without this, a stranger picking up your game can't figure out what
to do. Copy `BoxHUD` from `app-shadow-boxing` and restyle to match
your game's palette.

## Mirroring is selfie-style

The motion service mirrors X coordinates **at the source** — so when
your game says "paddle at LEFT_WRIST.x", that paddle naturally
follows the user's left hand on the left of the screen. **You do not
need to flip anything.**

If you find yourself writing `1f - x`, you're double-flipping.

## Performance budgets

The motion service runs at ~30fps. Your game gets a frame callback
for each. If you do > ~25ms of work in `onMotionFrame`, you start
dropping frames.

| Work | Where |
| --- | --- |
| Update game state | OK in `onMotionFrame` |
| Heavy rendering | Push to a separate game thread (see flappy / sample-game) |
| Async API calls / disk IO | Use coroutines, don't block the binder thread |

`MotionListener.onMotionFrame` runs on a binder thread. Marshal to
the UI thread with `Activity.runOnUiThread` or `mutableStateOf` if
you need to update Compose state from it.

## First-run UX

The very first time a player opens your game they don't know:

- Where to stand
- Which gestures are mapped
- That the camera is even working

Give them a **30-second onboarding** with:

- A target silhouette in the HUD viewport so they know where to
  position themselves
- One on-screen prompt per control as you go ("now raise your right
  arm")
- Skip on any input

`app-flappy` ships a faded T-pose silhouette in its HUD as the
positioning guide. Steal the idea.

## Accessibility

- **Don't require dual-arm motion if it's avoidable.** A player with
  one arm can still raise an arm or lean. Where possible, make
  single-arm versions of multi-arm gestures (e.g. wave instead of
  arms-up).
- **Don't penalize standing still.** Idle pose should never trigger
  an action. The `ARMS_DOWN` gesture is deliberately
  transition-based for this reason.
- **Audio cues, not just visual.** Some players have low vision; a
  beep on a successful punch lands even if they can't see the +100
  popup.

## Power and thermals

The motion service runs continuously and the camera draws steady
power. If your game is "open for hours" (a fitness routine, a
party-mode rotation) consider:

- Lower frame rate when nothing's happening on screen
- A "rest" mode that pauses tracking after N seconds of no motion
- Honest descriptions in the store ("intense — designed for 5–10
  minute sessions")

## What makes a game feel "Tezeract"

- **Clean motion-first UI.** Buttons sized for 10-foot viewing,
  high contrast, big type.
- **Motion always has visible feedback.** A flash, a particle, a
  HUD row that lights up. Players need to know their gesture
  registered.
- **Triangle to exit always works.** Don't trap the player. The SDK
  handles this for you, but don't accidentally consume it.
- **Calibration-tolerant.** Don't hard-code magic numbers like
  "shoulders at y=0.45". Pull from `CalibrationProfile` when you can,
  or compute baselines on first detected body.

We want the platform to feel coherent. Players should sense that any
Tezeract game speaks the same body language as the launcher.
