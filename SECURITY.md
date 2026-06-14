# Security Policy

Tezeract is a camera-equipped gaming console. Security matters more
here than in most app repos — please report vulnerabilities responsibly.

## Reporting a vulnerability

**Do not file a public GitHub issue.** Email:

  **security@tezeract.com**

PGP key fingerprint: *(will be published before v1.0; until then, plain
email is fine — we don't have anything in production worth attacking
yet)*.

Include:

- A clear description of the issue + affected component (SDK,
  service-motion, sample game, docs)
- Steps to reproduce — minimal repro is best
- Your assessment of impact (information disclosure, RCE, privacy,
  motion-stream interception, etc.)
- Whether you'd like credit in the eventual advisory (and what name to
  use); we default to credit unless you opt out

## Our commitment

- We acknowledge receipt within **3 business days**
- We aim to provide a remediation plan or "no-fix" rationale within
  **14 days**
- For confirmed valid vulnerabilities we publish a coordinated
  disclosure: notify, ship a fix, publish a GitHub Security Advisory
  + CVE if applicable. Default disclosure window is **90 days** from
  acknowledgement.
- We won't sue or pursue legal action against good-faith researchers
  who follow this policy

## Scope

In scope:

- Code in this repository (`sdk-motion/`, `service-motion/`, the four
  sample games)
- Public documentation in `docs/`

Out of scope (please report to the appropriate channel):

- Issues in the **private** Tezeract Store, developer portal, or
  storefront — those have their own disclosure address at
  `security@tezeract.com` (same address, just a heads-up that the
  triage path is different)
- Physical security of the device
- Third-party dependencies — please report upstream first

## What we consider serious

For a camera product, anything in this list is high-priority:

- Frame capture, exfiltration, or interception over the AIDL boundary
- Bypass of the privacy cover state
- A path for one sideloaded app to read another app's motion stream
  without user consent
- Service-process escalation that gives an app raw camera access
  without the system camera permission gate

For non-camera surfaces (game logic, sample games, build infra), the
usual web-app severity rubric applies.

## Out-of-scope reports

The following don't merit a security report — they're either bugs or
not vulnerabilities:

- "Game X crashes when I do Y" → please file a regular GitHub issue
- Theoretical attacks requiring physical access to an unlocked Tezeract
- "The MediaPipe model could be more accurate" → that's a roadmap item

Thanks for keeping the camera in the living room something worth
trusting.
