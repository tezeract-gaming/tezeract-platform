# Contributing to tezeract-platform

PRs welcome. Before opening one, read this whole file — it's short.

## Sign-off (DCO)

Every commit must carry a `Signed-off-by:` line. This is the Linux
kernel's Developer Certificate of Origin — by signing, you're stating
that you have the right to submit the contribution under the project's
license. Read the certificate at [developercertificate.org](https://developercertificate.org).

The `git commit -s` flag adds the line automatically:

```bash
git commit -s -m "Concise subject line"
```

PRs without sign-off get auto-rejected by CI. If you forgot, amend with
`git commit --amend -s --no-edit` and force-push to your PR branch.

## What we want PRs for

- Bug fixes — yes, always
- Performance improvements — yes, with a benchmark or before/after
- New SDK affordances that unblock real games — yes, with a use-case
- Sample game improvements — yes
- New sample games — open an issue first to discuss fit
- Tests, docs, type annotations — yes
- Style-only changes — open an issue first

## What we don't want PRs for

- Sweeping refactors without a target outcome
- New cross-cutting abstractions ("everything should be a Strategy now")
- Reformat-the-whole-codebase changes — Kotlin formatting follows the
  default IntelliJ settings; please don't run a different formatter
- Adding new dependencies without discussing tradeoffs
- Changes to AIDL contracts in `sdk-motion/` without a deprecation
  story — every game in the store links these interfaces, and breaking
  them strands published apps

## Process

1. Open an issue if your change is non-trivial. Aligning before you
   write code saves both of us time.
2. Branch from `main`. One concern per branch.
3. Push your branch + open a PR. CI runs `./gradlew assembleDebug` on
   every PR.
4. A maintainer will review within a few days. Expect comments.
5. After approval, the maintainer squash-merges. Your commit-message
   subject becomes the merge commit subject — make it good.

## Coding conventions

- Kotlin idioms over Java idioms; use the standard library
- `val` over `var` unless mutation is unavoidable
- Coroutines on `Dispatchers.Default` or a single-threaded executor
  for inference work; never on `Main`
- No new dependencies without justification
- Add a short comment when *why* isn't obvious from *what*; skip the
  comment when *what* tells you *why*

## Where to ask

- Public discussion: GitHub Discussions on this repo
- Security: see [`SECURITY.md`](./SECURITY.md) — do not file public
  issues for vulnerabilities

## Licensing of your contribution

By submitting a PR, you agree your contribution is licensed under the
same license as the module you're modifying (Apache-2.0 for `sdk-motion/`,
`service-motion/`; MIT for the four sample games; CC-BY-4.0 for `docs/`).
The DCO sign-off is the legal hook for this — please don't open a PR if
you can't honestly sign it.
