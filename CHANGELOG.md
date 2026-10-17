# Changelog

All notable changes to the Brudcast Android SDK are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the SDK uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0, a minor release may change
the public API.

## [Unreleased]

### Added

- `sdk-core`: configuration, a no-backup device id, sessions, `identify` and `reset` with
  server-computed user hashes, `track` and `screen`, a persistent batched event queue, opt-out,
  `disableTracking` for consent frameworks, and the action handler.
- `sdk-inapp`: message sync, the live stream, event, screen and session triggers with property
  filters, display rules and the frequency cap, Compose modal, banner and full screen rendering,
  the host message handler, and the inbox with its default activity.
- `sdk-push`: permission, FCM token registration for the signed-in user, foreground display, and
  open and dismiss tracking.
- `sdk-push-firebase`: a `FirebaseMessagingService` and helpers, with Firebase compile-only.
- `sdk-bom` to keep the artifact versions aligned.
- `BrudcastAction.verified`, false for a push tap the SDK cannot prove it created.
- Apache License 2.0, a security policy and this changelog.
- A sample app in the `sample` module that uses the SDK the way a customer's app would, from
  sign-in through a shop to sign-out, built and linted in CI.
- Publishing to Maven Central: signed artifacts with sources and API docs, a release workflow, and
  a CI check that every artifact is complete.

### Changed

- Public model types (`BrudcastOptions`, `BrudcastTraits`, `BrudcastEvent`, `BrudcastAction`,
  `BrudcastSession`, `BrudcastApiError`, `BrudcastInAppMessage`, `BrudcastInboxPage`,
  `BrudcastPushNotification`) are regular classes instead of data classes, so fields can be added
  later without breaking binary compatibility. They have no `copy` or `componentN` functions.
- The consumer ProGuard rules no longer keep every SDK class, so apps can shrink the SDK.

### Security

- A link from a push tap the SDK cannot verify, such as one on a notification FCM drew itself,
  only opens web links and exported activities. Another app can no longer send a forged launch
  intent that makes the app open one of its private activities. Notifications the SDK posts carry
  a per-install token and keep full access.
- Events are sent with the session of the user who produced them. Events of a user who signed out
  go with that user's last session while it lasts and are dropped after that, instead of being
  credited to whoever signs in next.

### Fixed

- A NaN or infinite event property no longer crashes the app. It is dropped with a warning, and no
  exception thrown on the SDK's thread can kill it any more.
- Notifications the SDK shows are posted before `onMessageReceived` returns, so FCM can no longer
  end the process first and lose them. A notification handler that does not answer within two
  seconds no longer holds the notification back.
- Notifications use a monochrome small icon: `PushModule.smallIcon`, then FCM's
  `default_notification_icon` meta-data, then a bundled bell, never the launcher icon.
- Notification images are capped at 5 MB and scaled down before decoding.
- In-app images are scaled to the screen so they always fit the memory cache, and are kept in a
  50 MB disk cache. Large images no longer fail to show.
- One invalid event no longer drops the whole batch. A rejected batch is halved until the event the
  service refuses is sent alone and dropped.
- A refused publishable key is told apart from a refused user hash by the service's error code when
  it sends one, and a refused key is tried again on the next foreground instead of never.
- The live stream no longer stays connected in the background when it is stopped while connecting.
- Messages with a trigger this SDK cannot read are reported as `render_unsupported` instead of
  being dropped silently.
- `initialize` logs an error instead of throwing when the context's application context is not the
  `Application`.
- The event queue is written to disk at most once a second, and when the app goes to the
  background, instead of on every event.
