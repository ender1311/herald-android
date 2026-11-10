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
    implementation("com.github.ender1311.herald-android:herald-core:0.1.0")
    implementation("com.github.ender1311.herald-android:herald-ui:0.1.0")
    implementation("com.github.ender1311.herald-android:herald-fcm:0.1.0")
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

The full guide, including push setup, is at https://beaconfrontier.com/herald/docs.

## License

Apache License 2.0. See [LICENSE](LICENSE).
