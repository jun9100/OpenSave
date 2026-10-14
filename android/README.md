# OpenSave Android

This is the native Android client foundation for OpenSave. The first milestone
uses Jetpack Compose and the Storage Access Framework (SAF). It does not scan
other applications' private directories and it does not require root.

## Local build

The project requires JDK 17, Android SDK Platform 35, and Build Tools 35.0.0.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## Odin2 / Android 13 smoke test

The baseline device is Odin2 running Android 13. The device must grant the
computer ADB access before installation. Verify the platform level with:

```bash
adb shell getprop ro.build.version.sdk
```

It should return `33`. On Android 11 and later, Wireless debugging has two
ports: a pairing port and a connection port. On Odin2, enable **Developer
options -> Wireless debugging**, then run the commands shown by the device:

```bash
adb pair 192.168.12.40:<pairing-port> <pairing-code>
adb connect 192.168.12.40:<connection-port>
adb devices -l
```

If ADB is already enabled over USB, the legacy TCP flow is also available:

```bash
adb devices
adb -s <usb-serial> tcpip 5555
adb connect 192.168.12.40:5555
```

The app's first smoke test is:

1. Install and launch the debug APK.
2. Choose a disposable test directory with **选择并授权存档目录**.
3. Restart the app and confirm the persisted read/write permission remains.
4. Use **复制到工作区** and record the file count, byte count, and manifest hash.
5. Use **写回授权目录** and verify the source files with an external hash.

Do not use a real game-save directory for the first write-back test. The
current implementation writes files back but does not delete files that are
not present in the private workspace; destructive restore will be added with
the snapshot safety layer.
