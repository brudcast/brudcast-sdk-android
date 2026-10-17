# Brudcast Android sample

A small Compose app built the way a customer's app would be, with each SDK call where a real app
puts it: identify at sign-in, reset at sign-out, a screen view as each screen appears, events as
the user shops, and message buttons that open the app's own screens. It builds the SDK modules
from this repository, so changes to them show up on the next run.

## Run it

1. Add the publishable key of an in-app application in the dashboard to `local.properties` at the
   repository root (it is ignored by git):

   ```properties
   brudcast.publishableKey=bpk_...
   brudcast.environment=production
   ```

2. Run the `sample` configuration in Android Studio, or `./gradlew :sample:installDebug`.

`brudcast.environment` picks `production`, `staging`, `uat`, `development` or `local`. `local`
talks to `brudcast-in-app-delivery-service` on the host at `brudcast.localHost` (`10.0.2.2`, the
emulator's address for the host) port 8191; the sample allows cleartext HTTP to it.

## Sign in like a real app

Add a backend of your own to `local.properties`, and the sign-in screen signs users in through it:
the backend computes the user hash with the identity secret, the app passes it to
`Brudcast.identify`, and **Send me a welcome** on Home has the backend send the user an in-app
message through the Brudcast API. The identity secret and the API key stay on the backend. Without
it, sign-in takes a pasted user hash instead.

```properties
brudcast.sampleBackendUrl=http://10.0.2.2:4000
```

The backend needs two endpoints, both taking and returning JSON:

| Endpoint | Body | Response |
| --- | --- | --- |
| `POST /login` | `{ "userId", "email" }` | `200` with `{ "externalId", "userHash", "email" }`, where `userHash` is `hex(HMAC-SHA256(identitySecret, externalId))` |
| `POST /welcome` | `{ "externalId" }` | `2xx` once it has sent the user an in-app message with `POST /in-app/messages` on the Brudcast API |

On an error, the app shows the response's `message`.

## The app

```
Sign in ──▶ Home ┬ Shop ──▶ Product ──▶ Checkout
                 └ Account ──▶ Sign out ──▶ Sign in
```

The sample keeps its own session in `SharedPreferences`, as an app keeps its login, so a relaunch
lands on Home while the SDK restores its identity.

| Where | What the SDK does |
| --- | --- |
| Sign in | `Brudcast.identify` with the hash from the backend (`SampleSession.signIn`) |
| Every screen | `Brudcast.screen`: `SignIn`, `Home`, `Shop`, `Product`, `Checkout`, `Account` |
| Home | The inbox bell and its unread badge, and **Send me a welcome** through the backend |
| Product | Tracks `product_viewed` with `product` and `price` |
| Checkout | Tracks `checkout_started` with `product` and `total` on arrival, `order_completed` on **Place order** |
| Account | Notification permission, pausing and syncing messages, the activity log, developer tools for any event or screen, and sign-out with `Brudcast.reset` |

To try a campaign, trigger it with `checkout_started` or the `Checkout` screen and go to checkout.
Set a button's action to `brudcast-sample://checkout` or `brudcast-sample://shop` and tapping it
opens that screen: the action handler routes the sample's own links and records every action in
the activity log, with `unverified` on taps the SDK cannot prove it created, and anything else uses
the SDK's default behaviour. The same links open from outside the app:

```sh
adb shell am start -a android.intent.action.VIEW -d brudcast-sample://checkout com.brudcast.sample
```

## Push

Push sends through FCM, so the sample needs your Firebase project to receive notifications. Add an
Android app with the package `com.brudcast.sample` to it and put its `google-services.json` in
`sample/` (ignored by git). The build applies the Google Services plugin only when the file is
there, and the sample registers its token at launch. Then tap **Allow notifications** on Account
on Android 13 and later.

Without the file the sample still runs; it logs that push tokens are not registered.
