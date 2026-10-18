# Brudcast Android SDK

![Brudcast: Reach Everyone, Every time](.github/assets/banner.svg)

In-app messaging and push notifications for Android apps on Brudcast. Android 6.0 (API 23) and later.

| Artifact                            | What it does                                                                                                                  |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `com.brudcast:sdk-core`             | Core: configuration, device id, sessions, identity, the event queue, the action handler. Always needed                        |
| `com.brudcast:sdk-inapp`            | In-app messages: sync, the live stream, triggers, display rules, Compose modal, banner and full screen rendering, the inbox   |
| `com.brudcast:sdk-push`             | Push: permission, FCM token registration for the signed-in user, foreground display, open and dismiss tracking                |
| `com.brudcast:sdk-push-firebase`    | A `FirebaseMessagingService` and helpers. Firebase is compile-only, so your app's Firebase version is the one that runs       |
| `com.brudcast:sdk-bom`              | Keeps the artifact versions aligned                                                                                           |

A push-only app depends on `sdk-push` (and `sdk-push-firebase`) and never ships the renderer.

## Install

The artifacts are on Maven Central, so `mavenCentral()` in your repositories is all they need. The
BOM keeps them on one version:

```kotlin
dependencies {
    implementation(platform("com.brudcast:sdk-bom:0.2.0"))
    implementation("com.brudcast:sdk-inapp")
    implementation("com.brudcast:sdk-push")
    implementation("com.brudcast:sdk-push-firebase")
}
```

## Start

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Brudcast.initialize(
            this,
            "bpk_...",
            BrudcastOptions(modules = listOf(InAppModule, PushModule)),
        )
        BrudcastPushFirebase.register()
    }
}
```

The publishable key is public by design. Never put the application's identity secret in the app.
In-app messages show over any activity, including ones that are not a `ComponentActivity`, such as
Flutter's `FlutterActivity`.

## Identify the user

Compute the user hash on your server and hand it to the app with the user's session:

```txt
userHash = hex(HMAC-SHA256(identitySecret, externalId))
```

```kotlin
Brudcast.identify(user.id, user.brudcastHash, BrudcastTraits(email = user.email))
```

Call `Brudcast.reset()` on logout. The device keeps its id, and its push token stops reaching the
previous user.

## Events and screens

```kotlin
Brudcast.track("checkout_started", mapOf("total" to 120, "product" to "sneakers"))
Brudcast.screen("Checkout")
```

## In-app messages

```kotlin
Brudcast.inApp.pauseDisplay()
Brudcast.inApp.resumeDisplay()

Brudcast.inApp.setMessageHandler { message ->
    if (message.format == InAppFormat.FULLSCREEN && onboarding) BrudcastMessageDecision.DEFER else BrudcastMessageDecision.SHOW
}

Brudcast.setActionHandler { action ->
    if (action.kind == BrudcastAction.Kind.DEEPLINK && router.handle(action.value)) BrudcastActionResult.HANDLED
    else BrudcastActionResult.USE_DEFAULT
}

BrudcastOptions(presentationFilter = { activity -> activity !is LoginActivity })
```

Inbox:

```kotlin
Brudcast.inApp.inbox.present(context)
Brudcast.inApp.inbox.unreadCount.collect { badge = it }
Brudcast.inApp.inbox.list { result -> ... }
```

## Push

`sdk-push-firebase` compiles against Firebase without bundling it, so add Firebase Messaging to the
app yourself (with the `google-services` plugin and your `google-services.json`):

```kotlin
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
```

Register the service in your manifest:

```xml
<service android:name="com.brudcast.sdk.push.firebase.BrudcastFirebaseMessagingService" android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

If you already have a `FirebaseMessagingService`, call `BrudcastPushFirebase.onNewToken(token)` and
`BrudcastPushFirebase.onMessageReceived(this, message)` from it instead; the second returns false
for messages that are not from Brudcast.

Ask for permission (Android 13 and later show a prompt):

```kotlin
Brudcast.push.requestPermission(activity) { granted -> ... }
```

Taps are tracked for every activity the notification opens. A `singleTop` activity should also
call `Brudcast.push.handleIntent(intent)` from `onNewIntent`.

Set a monochrome small icon for the notifications the SDK posts in the foreground. Without one the
SDK uses the icon in FCM's `com.google.firebase.messaging.default_notification_icon` meta-data, then a
plain bell:

```kotlin
Brudcast.push.smallIcon = R.drawable.ic_notification
```

A tap on a notification FCM drew itself arrives as an ordinary launch intent, which another app
could forge, so its action has `verified = false`. The default behaviour then opens only web links
and exported activities. Check `action.verified` before routing a deep link to a screen that is not
exported.

Push needs the in-app application linked to a push application in the dashboard. Until the
environment accepts device tokens, the SDK logs a warning once and keeps the token for later.

## Behaviour

- Calls made before `initialize` are queued. Nothing throws or blocks the main thread.
- Events are stored on disk and sent in batches of up to 100 every 10 seconds, when 20 are waiting,
  and when the app goes to the background. Failed batches back off up to 5 minutes; events older than
  7 days are dropped.
- The device id is a random UUID in no-backup storage, never `ANDROID_ID` or the advertising id.
- `Brudcast.disableTracking()` stops all event collection, for consent frameworks.
- Play data safety: the SDK collects a device id, the user id you pass, any email address, phone
  number and name you pass in `BrudcastTraits`, and app interactions with messages, for app
  functionality and analytics. Nothing is shared with third parties.

## Sample app

The `sample` module is a small Compose app that uses the SDK the way a customer's app would:
sign-in, a shop with checkout, and an account screen. It builds against this checkout. See its [README](sample/README.md).

## Develop

```sh
./gradlew testDebugUnitTest assembleRelease
```

[RELEASING.md](RELEASING.md) covers publishing to Maven Central.

`BrudcastEnvironment.Local()` points the emulator at `brudcast-in-app-delivery-service` on the host
(`10.0.2.2:8191`). Cleartext HTTP to it needs a debug network security config in the sample app.

## Security

Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md), never in a public issue.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
