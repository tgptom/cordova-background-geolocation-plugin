# Android development (Cordova Android >= 12)

This plugin is tested with:

- JDK: **17**
- Android Gradle Plugin: **7.4.2**
- Gradle wrapper: **7.6.4**
- compileSdk/targetSdk: **34**
- Cordova Android: **12+**

## Prerequisites

1. Install Android Studio (or command-line SDK tools) with:
   - Android SDK Platform 34
   - Android SDK Build-Tools 34.0.0
2. Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and ensure platform-tools are on `PATH`.
3. Use JDK 17 for Gradle builds.

## Build and test from this repository

From repository root:

```bash
npm ci
npm run lint
npm run test:android
npm run pack:check
```

These commands execute Android lint, unit tests, and debug assembly for the `common` and `CDVBackgroundGeolocation` modules.

## Validate merged manifest

```bash
cd android
./gradlew :CDVBackgroundGeolocation:processDebugMainManifest --no-daemon
```

Merged manifest output:

`android/CDVBackgroundGeolocation/build/intermediates/merged_manifests/debug/AndroidManifest.xml`

## Cordova test app flow

Use a Cordova app that includes this plugin (local path or packed tarball). No copied `CordovaLib` sources or deprecated `compile` configuration are required in app projects.

Example flow:

```bash
npm pack
cordova plugin add ./cordova-background-geolocation-plugin-<version>.tgz
cordova platform add android@latest
cordova build android
```
