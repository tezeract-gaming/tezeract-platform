# Submitting to the Store

The Tezeract Store is a Supabase-backed catalog. Submissions move
through a four-state review workflow. Free apps publish on approval;
paid apps publish on approval **and** Stripe payouts being enabled
on your developer account (see [Pricing & Stripe](stripe.md)).

## 1. Create a developer account

The dev portal lives at `https://dev.tezeract.dev` (or run
`web-dev-portal/` locally for now — it's a Next.js scaffold).

Sign up with email. You'll be issued a row in the `developers` table:

```sql
INSERT INTO developers (auth_uid, email, studio_name, display_name, bio)
VALUES (auth.uid(), 'you@studio.com', 'Your Studio', 'Your Name',
        'We make body-controlled puzzle games.');
```

`verified` defaults to false. Verification is a manual one-time
process — once verified, your apps display a small ✓ badge in the
store.

## 2. Submit an app

Two options:

### Option A — dev portal (recommended)

1. **New App** → fill out the form (name, package_name, category,
   tagline, long description, optional price)
2. **Upload APK** → we extract `versionName`, `versionCode`,
   declared metadata
3. **Upload screenshots** (1–4 PNGs, 1280×720)
4. **Submit for review**

The portal sets `review_status = 'pending_review'` and your row
appears in our admin queue.

### Option B — direct REST

You can also POST to the Supabase REST API. Useful for CI:

```bash
curl -X POST "$SUPABASE_URL/rest/v1/apps" \
     -H "apikey: $YOUR_DEV_KEY" \
     -H "Content-Type: application/json" \
     -d '{
       "developer_id": "<your-dev-id>",
       "name": "My Game",
       "package_name": "com.you.mygame",
       "version": "1.0.0",
       "version_code": 1,
       "category": "party",
       "tagline": "Wave to win",
       "description": "Short tile description",
       "long_description": "**Markdown** is supported.",
       "min_players": 1,
       "max_players": 2,
       "requires_hands": false,
       "price_cents": 0,
       "review_status": "pending_review"
     }'
```

Then upload your APK to the storage bucket:

```bash
curl -X POST \
     "$SUPABASE_URL/storage/v1/object/app-assets/apks/$PACKAGE-v$VERSION.apk" \
     -H "apikey: $YOUR_DEV_KEY" \
     -H "Content-Type: application/vnd.android.package-archive" \
     --data-binary @app-release.apk
```

Set `apk_url` on the row to the resulting public URL.

## 3. Review workflow

We aim to review within **3 business days**.

We check:

| What we check | Why |
| --- | --- |
| APK installs on a real Tezeract device | Catches signing/manifest bugs |
| Game responds to motion within 30s of launch | No-op submissions |
| HOME_TRIANGLE returns to launcher | Required UX |
| No background services beyond what the SDK requires | Battery, thermal |
| Description matches gameplay (no rugpulls) | Catalog integrity |
| No GMS/Play services dependencies | TezeractOS has no GMS |
| Permissions match what the game actually uses | Privacy hygiene |

We **do not** check:

- Game balance or difficulty
- Art quality (subjective)
- Whether your app is "fun" — that's the player's call

### What approval looks like

```sql
review_status = 'published'
status        = 'published'
reviewed_at   = '2026-...'
reviewed_by   = 'admin@tezeract.com'
```

The launcher's bundled catalog refresh + per-device package broadcasts
mean your app appears in the Store within minutes.

### What rejection looks like

```sql
review_status = 'rejected'
review_notes  = 'Crashes on launch — see logcat. NPE in HandPose mapping.'
```

You'll see this on your dev portal dashboard. Fix, push a new
`version_code`, set `review_status = 'pending_review'` again. We
re-review.

## 4. Updates

To ship a new version:

1. Bump `versionCode` in your APK's manifest (must be strictly higher).
2. Re-submit via the portal — same flow, new APK upload.
3. We re-review.
4. On approval, the launcher's Store shows "UPDATE" instead of "PLAY"
   for users who already have an older version installed.

## 5. Removing your app

Set `status = 'archived'` via the portal. It vanishes from the store
within minutes; existing installs continue to work.

To re-publish, set `status = 'published'` (no fresh review needed if
the row was previously approved).

## What's coming

- Beta channels (per-developer "early access" track)
- Crash reporting hooks (we'll surface a per-app dashboard)
- A/B price testing
- Bundles (multi-game packages at a discount)

These are post-MVP. Ask for ETAs in the Tezeract dev forum.
