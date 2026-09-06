# BTConnect — Bluetooth Chat, Calls & File Transfer

A native Android app (Kotlin + Jetpack Compose) that lets you discover nearby
Bluetooth devices, send a connection request, and once accepted: chat, send
files/photos, and make voice calls — all over classic Bluetooth (RFCOMM),
with **no internet or mobile network involved**.

## Why native Android, not a web app

A browser can only use the Web Bluetooth API, which lets a page *connect to*
a BLE peripheral — it cannot make the phone *discoverable* to other browser
tabs, so two web pages can never find each other over Bluetooth. Native
Android's classic Bluetooth APIs (`BluetoothAdapter`, RFCOMM sockets) support
mutual discovery, bidirectional streaming, and enough bandwidth for chat,
files, and voice — which is what this project uses.

## How it works

- **Discovery**: `BluetoothAdapter.startDiscovery()` finds nearby devices;
  paired devices are listed separately.
- **Request / Accept**: tapping a device opens an RFCOMM socket
  (`app/.../bluetooth/BluetoothProtocol.kt` defines a fixed service UUID both
  sides use). The connecting side sends a `CONNECT_REQUEST` frame; the other
  side sees an Accept/Decline dialog and replies `CONNECT_ACCEPT` or
  `CONNECT_REJECT`.
- **One socket, many message types**: every frame on the wire is
  `[1-byte type][4-byte length][payload]`. This carries chat text, file
  metadata/chunks, call signaling, and live call audio, all multiplexed on
  the same connection (see `BluetoothProtocol.kt`).
- **Chat**: plain text frames, rendered as bubbles in `ChatScreen.kt`.
- **File / photo transfer**: files are streamed in 8KB chunks with a
  progress bar; images are decoded and shown inline, other files are saved
  under the app's external files directory.
- **Calls**: `AudioCallManager.kt` captures mic audio with `AudioRecord`,
  streams raw PCM chunks over the same socket, and plays incoming audio with
  `AudioTrack`. Call setup/teardown is signaled with `CALL_REQUEST` /
  `CALL_ACCEPT` / `CALL_REJECT` / `CALL_END` frames.

## Project structure

```
app/src/main/java/com/example/btconnect/
  bluetooth/
    BluetoothProtocol.kt   - wire format (frame types, read/write helpers)
    BluetoothService.kt    - singleton: discovery, connection, chat, files, call signaling
    AudioCallManager.kt    - mic capture + playback for calls
  model/
    DeviceInfo.kt, ChatMessage.kt
  ui/
    theme/                 - Material3 color palette & typography
    screens/
      DeviceListScreen.kt  - discovery + request/accept
      ChatScreen.kt        - message bubbles, attach, send
      CallScreen.kt        - incoming/outgoing/in-call UI
  MainActivity.kt          - permissions, navigation, wiring
  BTApplication.kt         - starts BluetoothService on app launch
```

## Getting an APK without installing Android Studio

This project includes a GitHub Actions workflow (`.github/workflows/build-apk.yml`)
that builds a debug APK automatically in the cloud - GitHub does the compiling,
you just download the result. Steps:

1. Create a new repository on [github.com](https://github.com) (public or
   private, doesn't matter) and push this whole `BTConnect` folder to it.
   Easiest way if you don't use git locally: on github.com, click
   **Add file > Upload files** and drag the whole folder in, then commit.
2. Go to the **Actions** tab of your repo. A workflow run called
   "Build debug APK" should start automatically (or click **Run workflow**
   if it doesn't).
3. Wait for it to finish (a few minutes) - a green checkmark means success.
4. Open the finished run, scroll to **Artifacts**, and download
   `BTConnect-debug-apk`. Unzip it to get `app-debug.apk`.
5. Transfer that APK to your Android phone (e.g. via email to yourself,
   Google Drive, or a USB cable) and tap it to install. You'll need to allow
   "install from unknown sources" the first time - Android will prompt you.
6. Repeat on a second phone to actually test the chat/call features.

No Android Studio, no local SDK setup required for this route.

## Opening the project in Android Studio (alternative)

1. Open this folder in Android Studio (Koala/Jellyfish or newer) and let it
   sync Gradle — it needs internet access to download dependencies the
   first time.
2. Run it on **two physical Android devices** (Bluetooth doesn't work in the
   emulator). Install the app on both.
3. Grant the Bluetooth/microphone permissions and turn Bluetooth on when
   prompted.
4. On device A, tap **Scan for devices**, find device B, tap it.
5. On device B, accept the connection request dialog.
6. Chat, tap the call icon to start a voice call, or use the attach button
   to send a photo or file.

## Known limitations / good next steps

- One active connection at a time (matches "find a nearby device → chat with
  that person" — extending to multiple simultaneous chats means keeping a
  map of `address -> ConnectedThread` instead of one global one).
- Voice call quality is basic (uncompressed 16kHz PCM). For better quality
  over the limited bandwidth of classic Bluetooth, add an audio codec
  (e.g. Opus via a small native/JNI library) before sending chunks.
- No foreground `Service` yet, so a long call/transfer will stop if the app
  is killed in the background — worth adding for a production version.
- Large files are read into memory chunk-by-chunk (not all at once), but very
  large files will still be slow — classic Bluetooth SPP tops out around
  1–3 Mbps in practice.
- Video calls are intentionally not included — Bluetooth's bandwidth can't
  support them.
