# Examples

Every game shipped on TezeractOS is open source. Read the source —
they're each small enough to skim in 10 minutes.

## Catch Craze (`app-sample-game`)

The "hello world" of Tezeract motion. Two paddles track your wrists;
falling colored objects must be caught. Demonstrates the simplest
useful pattern: subscribe to `MotionListener`, read a wrist keypoint,
position a UI element.

| | |
| --- | --- |
| **Repo path** | `app-sample-game/` |
| **Genre** | Kids / arcade |
| **What to learn** | Raw `MotionFrame` keypoints, mirroring (auto-handled), simple Canvas rendering |

Key file: `app-sample-game/src/main/java/com/tezeract/samplegame/MainActivity.kt`

## Flappy Arms (`app-flappy`)

Flap your arms like wings to keep a bird airborne. Demonstrates
**analog motion control** (wing-flap velocity), a custom **HUD with
positioning guide**, and tuning thresholds for accessibility.

| | |
| --- | --- |
| **Repo path** | `app-flappy/` |
| **Genre** | Kids |
| **What to learn** | Velocity-based gesture detection, motion-themed HUD, in-game exit gesture |

Key file: `app-flappy/src/main/java/com/tezeract/flappy/MainActivity.kt`

## Input Tester (`app-input-tester`)

A diagnostic tool, not really a game — but the source is the cleanest
example of using **`InputListener`** + the gamepad-style
`TezeractInput` enum. Renders a virtual gamepad that lights up as
each input fires.

| | |
| --- | --- |
| **Repo path** | `app-input-tester/` |
| **Genre** | Utility |
| **What to learn** | `InputListener`, `TezeractInput`, drawing a live skeleton |

Key file: `app-input-tester/src/main/java/com/tezeract/inputtester/MainActivity.kt`

## Shadow Boxing (`app-shadow-boxing`)

Three boxing modes (Reaction, Training, Fight). Demonstrates a
**custom motion classifier on top of `MotionListener`** (the
`PunchDetector` class), an **animated opponent** drawn entirely in
Compose Canvas, **mode picker driven by motion-bridge** dispatching
KeyEvents into the Compose focus engine, and a **reusable HUD
component** (`BoxHUD`).

| | |
| --- | --- |
| **Repo path** | `app-shadow-boxing/` |
| **Genre** | Fitness |
| **What to learn** | Building a domain-specific punch/block classifier, opponent animation, motion-driven menus, reusable HUD pattern |

Key files:

- `app-shadow-boxing/src/main/java/com/tezeract/shadowbox/motion/PunchDetector.kt`
  — wrist trajectory analysis, classifies LEFT/RIGHT × JAB / HOOK /
  UPPERCUT plus BLOCK
- `app-shadow-boxing/src/main/java/com/tezeract/shadowbox/ui/FightModeScreen.kt`
  — animated opponent + telegraph + block window + HP bars
- `app-shadow-boxing/src/main/java/com/tezeract/shadowbox/ui/BoxHUD.kt`
  — reusable per-game HUD pattern

## Patterns to copy across games

| Pattern | Lives in | Reuse it for |
| --- | --- | --- |
| MotionInputBridge → KeyEvents → TV focus | `app-shadow-boxing` | Any Compose-driven menu |
| Skeleton viewport HUD | `app-shadow-boxing`, `app-flappy` | Every game's first-run UX |
| Custom motion classifier on raw `MotionFrame` | `app-shadow-boxing` PunchDetector | Sport games, sword-fighting, dance scoring |

## Want your game listed here?

Once your game is published in the Tezeract Store and stable, we'll
link to its source (or to whatever public reference you want) on
this page. Email `docs@tezeract.dev` with the link.
