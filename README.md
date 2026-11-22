# Herald Android SDK

The Herald Android SDK connects an Android app to Herald. It identifies users, records events and attributes, registers the device for push through Firebase Cloud Messaging, and shows content cards and in-app messages. `herald-core` holds the core calls, `herald-ui` adds the default Jetpack Compose views, and `herald-fcm` adds the FCM messaging service, token sync and notification display.

## Requirements

- Android 8.0 (API level 26) or later
- Android Gradle Plugin 8 and compileSdk 35; the SDK is built with Kotlin 2.0.21 and Java 17 bytecode
- Jetpack Compose for `herald-ui`, and a Firebase project for `herald-fcm`

## Install

The modules are published through JitPack. Add the repository:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then the modules:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.ender1311.herald-android:herald-core:0.6.0")
    implementation("com.github.ender1311.herald-android:herald-ui:0.6.0")
    implementation("com.github.ender1311.herald-android:herald-fcm:0.6.0")
}
```

## Quickstart

```kotlin
// In Application.onCreate
Herald.configure(this, appKey = "YOUR_APP_KEY", endpoint = "https://YOUR_HERALD_HOST")
HeraldInAppPresenter.install()

// After sign-in
Herald.identify("user-123", mapOf("plan" to "free"))
Herald.setUserToken(userTokenFromYourBackend)
Herald.track("workout_completed", mapOf("minutes" to 32))

// In a composable: ContentCardFeed()
```

The full guide, including push setup, is at https://heraldsdk.com/docs.

## Pushes your app sends

Herald limits how many pushes a person gets in 24 hours, counting pushes your app sends itself. Report each one from
your backend with the `$external_push` event (`POST /api/v1/events`), or from the app:

```kotlin
Herald.trackExternalPush(messageId = "order-42-shipped", category = ExternalPushCategory.TRANSACTIONAL, source = "orders")
```

With `trackExternalPushes` on (the default, in `HeraldOptions`), herald-fcm's `HeraldMessagingService` also records an
FCM message that isn't from Herald and carries a notification when it arrives. The SDKs also record some of these pushes on their own: on iOS when someone taps one, and on Android when one arrives. To count every push, report them from your backend. Data-only messages are never
recorded, and taps on your own notifications aren't. Put the id your backend reports in the data key
`herald_external_id` (else `RemoteMessage.messageId` is used) and `transactional`, `marketing` or `other` in
`herald_category`, so the backend's report and the SDK's count once. In your own FirebaseMessagingService, call
`Herald.handleExternalPush(message.data, message.notification != null, message.messageId)`. Guide:
https://heraldsdk.com/docs/push-notifications/pushes-your-app-sends

## License

Apache License 2.0. See [LICENSE](LICENSE).
