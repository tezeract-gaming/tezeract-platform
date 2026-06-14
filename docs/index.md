# TezeractOS Developer Docs

TezeractOS is a motion-controlled gaming platform built on Android 12.
Players control games **with their body** via a USB camera and the
Tezeract Motion SDK — no controllers, no touchscreen.

This site shows you how to build games for it.

## What you can build

- **Body-controlled games** — pose tracking, gesture recognition,
  hand grab detection, all delivered as either raw 33-point keypoints
  or as semantic gamepad-style input events (DPAD/ABXY/triggers
  mapped from body motion).
- **Games of any genre** — the SDK doesn't dictate gameplay, just
  input. Action, fitness, party, education, kids — all welcome in
  the Tezeract Store.
- **Solo or 1–4 player local** — the motion service can track up to
  two bodies; declare your player count in the manifest and the
  Store surfaces it.

## What's in this site

- **[Quick Start](quickstart.md)** — get a "Hello, motion!" game
  running on a Tezeract device in under ten minutes.
- **[SDK Reference](sdk-reference.md)** — every public API on
  `TezeractMotion`, `MotionListener`, `GestureListener`,
  `InputListener`, and the data model.
- **[Game Manifest](manifest.md)** — the intent filter and metadata
  the launcher uses to discover your game.
- **[Building & Packaging](packaging.md)** — Gradle setup, signing,
  target-SDK constraints, dependency rules.
- **[Submitting to the Store](submitting.md)** — developer account
  setup, app submission, review workflow.
- **[Pricing & Stripe](stripe.md)** — set a price, connect a Stripe
  account for payouts, how revenue flows.
- **[Best Practices](best-practices.md)** — gesture choice,
  accessibility, performance budgets, what makes a great motion
  game.
- **[Examples](examples.md)** — full source links for every game
  shipped on the platform.

## The motion model in one paragraph

Your game runs on Android. It binds to a system-level **Motion Engine
service** that owns the camera, runs MediaPipe pose + hand tracking,
classifies built-in gestures, and broadcasts everything to subscribed
clients. You wire up one to three listeners (raw frames / named
gestures / gamepad-style input events) and react. The SDK auto-handles
"go home" via a triangle gesture so users can always escape your game
without you writing exit code.

Ready? **[Start with the Quick Start →](quickstart.md)**
