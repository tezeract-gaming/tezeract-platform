# Tezeract Platform

Open-source SDK, motion engine, and reference games for Tezeract — a
motion-controlled gaming console for the living room.

If you're a game developer who wants to ship to Tezeract devices, you're
in the right place. The store is private (security review gate stays at
Tezeract), but **everything you need to build is here, under permissive
licenses, with no API keys required.**

## What's in this repo

| Module | Purpose | License |
|---|---|---|
| **[`sdk-motion/`](./sdk-motion/)** | Kotlin SDK every game depends on. `MotionListener`, `GestureListener`, `InputListener`, and a polled `getLatestFrame()` API | Apache-2.0 |
| **[`service-motion/`](./service-motion/)** | The on-device motion engine — CameraX + MediaPipe + AIDL. Publishing it is the audit-the-camera trust story | Apache-2.0 |
| **[`app-flappy/`](./app-flappy/)** | Reference game — Flappy Bird with arm-flap motion control | MIT |
| **[`app-sample-game/`](./app-sample-game/)** | Reference game — catch falling objects with two-wrist paddles | MIT |
| **[`app-shadow-boxing/`](./app-shadow-boxing/)** | Reference game — boxing tutorial + arcade fight, custom motion classifier | MIT |
| **[`app-input-tester/`](./app-input-tester/)** | SDK debug / validation tool. Read motion frames, visualize keypoints, log gestures | MIT |
| **[`docs/`](./docs/)** | Developer documentation. Renders at [docs.tezeract.dev](https://docs.tezeract.dev) | CC-BY-4.0 |

## Quickstart for game developers

```bash
git clone https://github.com/tezeract-gaming/tezeract-platform.git
cd tezeract-platform
./gradlew :app-sample-game:assembleDebug
```

That produces a debug APK in `app-sample-game/build/outputs/apk/debug/`.

To target a real Tezeract device, sign and submit via the developer
portal at [dev.tezeract.dev](https://dev.tezeract.dev) — full submission
guide in [`docs/submitting.md`](./docs/submitting.md).

To target development hardware (Orange Pi 5 Plus + a USB webcam) without
a finished Tezeract device, see
[`docs/quickstart.md`](./docs/quickstart.md).

## Buying a Tezeract

[**tezeract.com**](https://tezeract.com) — the device that runs everything
in this repo. Pre-orders open now; first batch ships Q1 2027.

## How the store fits in

This repo is the platform. The store is separate (and private), and
remains the security review gate — apps are reviewed before they're
listed. Publishing to the open repo lets you build, fork, learn from,
and modify the SDK and games. Publishing to the store still requires
sign-up + review at [dev.tezeract.dev](https://dev.tezeract.dev). The
two layers are independent.

You can build and run your own games on your own devices today without
involving the store at all — sideload via `adb install`.

## Contributing

We accept PRs. Read [`CONTRIBUTING.md`](./CONTRIBUTING.md) first — short
version: sign your commits (`git commit -s`), one feature per PR,
follow the existing patterns.

For security issues, see [`SECURITY.md`](./SECURITY.md) — please don't
file public issues for vulnerabilities.

## License

Per-module licenses are in the [`NOTICE`](./NOTICE) file. The root SDK
and motion engine are Apache-2.0; sample games are MIT; documentation
is CC-BY-4.0. All three let you build commercial paid games and ship
them on Tezeract or anywhere else.

"Tezeract" is a trademark of Tezeract, Inc. The licenses above grant
you the right to fork, modify, and redistribute the code — they do
**not** grant the right to use the Tezeract name, brand, or logos. If
you ship a fork, please give it a different name.
