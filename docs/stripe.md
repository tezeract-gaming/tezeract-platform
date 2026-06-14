# Pricing & Stripe

TezeractOS supports both **free** and **paid** apps. Payments flow
through **Stripe Connect Express**: each developer onboards their own
Stripe account, players pay Stripe directly, the funds land in your
Stripe balance, and we keep an application fee.

!!! info "Free apps work today"
    Set `price_cents = 0` and skip the rest of this page. Free apps
    don't need a Stripe account.

## Revenue share

| Tier | Platform fee | Developer keeps |
| --- | --- | --- |
| Standard | **15%** | 85% |
| Volume (after $250k lifetime gross) | **10%** | 90% |
| Free | 0% | n/a |

Plus Stripe's standard processing fees (2.9% + 30¢ in the US).

## Setting a price

In the dev portal, set **Price** during submission (USD only at
launch). On the row that's:

```sql
UPDATE apps SET price_cents = 499 WHERE id = '<your-app-id>';
-- $4.99
```

Pricing is per-app, not per-version. Once published, you can change
the price but Stripe takes ~24 hours to reflect changes globally.

## Connecting your Stripe account

Onboarding is a one-time Stripe-hosted flow. From the dev portal:

1. Click **Connect Stripe** on your developer profile
2. Stripe walks you through identity, bank details, tax info
3. Stripe redirects back; we update your row:

```sql
developers.stripe_account_id        = 'acct_...'
developers.stripe_payouts_enabled   = true
developers.stripe_charges_enabled   = true
developers.stripe_onboarded_at      = NOW()
```

Your paid apps can now be approved.

## How a purchase flows

1. Player taps **BUY $4.99** on the Tezeract launcher's app detail
   screen.
2. Launcher hits our backend `POST /v1/checkout` endpoint with
   `app_id` + the player's Tezeract user id.
3. Backend calls Stripe to create a `PaymentIntent` with:
   - `amount` = `app.price_cents`
   - `application_fee_amount` = 15% of amount
   - `transfer_data.destination` = developer's `stripe_account_id`
4. Backend returns a Stripe Checkout URL. Launcher displays it via
   a Compose WebView (or QR code for the player to scan with their
   phone — the Pi has no touch input).
5. Player completes checkout. Stripe sends a `payment_intent.succeeded`
   webhook to our backend.
6. Backend writes a `purchases` row:
   ```sql
   INSERT INTO purchases (user_id, app_id, stripe_payment_intent_id,
                          amount_cents, status, paid_at)
   VALUES (...)
   ```
7. Launcher polls `GET /v1/entitlements?user_id=...`, sees the new
   purchase, flips the button from BUY to INSTALL, and the
   normal install flow takes over.

## Sample server endpoint

A complete reference implementation lives in `web-dev-portal/api/`.
The core is short:

```ts
// pages/api/v1/checkout.ts
import Stripe from 'stripe'
const stripe = new Stripe(process.env.STRIPE_SECRET_KEY!)

export default async function handler(req, res) {
  const { app_id, user_id } = req.body
  const app = await supabase.from('apps').select(
    'price_cents, name, developer_id, developers!inner(stripe_account_id)'
  ).eq('id', app_id).single()

  if (app.data.price_cents === 0) {
    // Free — record entitlement directly.
    await supabase.from('purchases').insert({
      user_id, app_id, amount_cents: 0, status: 'free', paid_at: new Date()
    })
    return res.json({ status: 'free' })
  }

  const session = await stripe.checkout.sessions.create({
    mode: 'payment',
    line_items: [{
      price_data: {
        currency: 'usd',
        product_data: { name: app.data.name },
        unit_amount: app.data.price_cents,
      },
      quantity: 1,
    }],
    payment_intent_data: {
      application_fee_amount: Math.round(app.data.price_cents * 0.15),
      transfer_data: {
        destination: app.data.developers.stripe_account_id,
      },
      metadata: { app_id, user_id },
    },
    success_url: `${process.env.APP_URL}/checkout/success`,
    cancel_url: `${process.env.APP_URL}/checkout/cancel`,
  })

  res.json({ url: session.url })
}
```

And the webhook:

```ts
// pages/api/v1/stripe-webhook.ts
const event = stripe.webhooks.constructEvent(
  rawBody, req.headers['stripe-signature']!, process.env.STRIPE_WEBHOOK_SECRET!
)

if (event.type === 'payment_intent.succeeded') {
  const pi = event.data.object as Stripe.PaymentIntent
  await supabase.from('purchases').insert({
    user_id: pi.metadata.user_id,
    app_id: pi.metadata.app_id,
    stripe_payment_intent_id: pi.id,
    amount_cents: pi.amount,
    status: 'paid',
    paid_at: new Date(),
  })
}
```

## Payouts

Stripe handles them. Connect Express defaults to a **2-day rolling
payout** to the bank account on file. You see all of this on your
own Stripe dashboard, not ours.

## Refunds

For now, refunds are handled out-of-band. Email
`support@tezeract.dev` with the `payment_intent_id` and reason. We'll
issue the refund through Stripe and mark the `purchases` row
`status = 'refunded'` — the launcher will then prompt the player to
uninstall the game on their next visit.

## Testing

- Use Stripe **test mode** during development. Test cards:
  `4242 4242 4242 4242`, any future expiry, any CVC.
- Set `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` to your test
  keys in the dev portal.
- Test purchases will populate `purchases` rows with `amount_cents`
  matching test card amounts; switch to live keys when you're ready.

## What's deferred

Real-money paid apps **require the platform-side server endpoints to
be live**, and those need our Stripe account in production mode +
webhook URLs configured. **For the current MVP, ship free** —
schema and developer-side onboarding are ready, but the paid
download flow lights up once the platform's Stripe webhook
infrastructure is deployed.

We'll announce live paid availability in the dev forum.
