# Reverb - Android Notification Mirroring App

## Project Overview

**Reverb** is an Android application that mirrors device notifications to a web client (e.g., browser or Vision Pro) over a local network WebSocket connection. It runs an embedded **Ktor HTTP/WebSocket server** on port 8765, enabling real-time notification viewing, SMS reply forwarding, and device-finding functionality.

### Key Features

- **Real-time notification mirroring** via WebSocket to connected web clients
- **SMS/Message reply forwarding** from web client back to Android (via RemoteInput or direct SMS)
- **Device finder** - triggers ringtone remotely to locate the device
- **Notification filtering** - whitelist/blacklist mode for installed apps
- **Heartbeat** - broadcasts battery status every 30 seconds
- **Auth token** - 6-character alphanumeric code for WebSocket connections

## Tech Stack

- **Language**: Kotlin 2.0.21
- **Build System**: Gradle 8.7.2 (Kotlin DSL)
- **Min SDK**: 26 (Android 8.0) / **Target SDK**: 35 (Android 15)
- **UI**: Android Views with ViewBinding
- **Server**: Ktor 3.0.1 (CIO engine, WebSocket, CORS, ContentNegotiation)
- **Serialization**: Kotlinx Serialization JSON 1.7.3
- **Coroutines**: Kotlinx Coroutines 1.9.0
- **QR Code**: ZXing 3.5.3

## Project Structure

```
Reverb/
├── app/
│   ├── src/main/
│   │   ├── java/com/reverb/
│   │   │   ├── model/          # Data models (NotificationPayload, ReplyCommand, ServerMessage)
│   │   │   ├── server/         # Ktor server, WebSocket manager, notification store, filter engine
│   │   │   ├── service/        # NotificationListenerService, SMS reply manager
│   │   │   ├── ui/             # MainActivity, FilterActivity
│   │   │   └── util/           # IP helper, token manager
│   │   ├── res/                # Android resources
│   │   ├── assets/             # App assets
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
│   └── libs.versions.toml      # Version catalog
├── build.gradle.kts            # Root build configuration
├── settings.gradle.kts         # Project settings
└── gradle.properties           # Gradle properties
```

### Package Breakdown

| Package | Responsibility |
|---------|----------------|
| `com.reverb.ui` | MainActivity (server info, permissions) and FilterActivity (app filter management) |
| `com.reverb.server` | Embedded Ktor server, WebSocket session management, notification storage, filtering logic |
| `com.reverb.service` | NotificationListenerService (foreground) and SMS reply handling |
| `com.reverb.model` | Serializable data models for WebSocket/REST communication |
| `com.reverb.util` | WiFi IP resolution and auth token generation/storage |

## Architecture

### Notification Flow

1. **MainActivity** launches → checks notification listener permission → prompts user if needed
2. **NotificationService** connects → starts foreground service → launches Ktor server on port 8765
3. Android notifications are intercepted by `NotificationListenerService` → filtered by `FilterEngine` → stored in `NotificationStore` (max 200) → broadcast via `WebSocketManager` to all connected clients
4. Web clients connect at `ws://<device-ip>:8765/ws` and receive a snapshot of all stored notifications on connection

### REST API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/reply` | Send SMS reply via cached RemoteInput or direct SMS |
| `POST` | `/api/ring` | Play ringtone for 5 seconds (device finder) |
| `GET/POST` | `/api/filters` | Get/update notification filter config |
| `GET` | `/api/status` | Get device name, battery level, charging state |
| `POST` | `/api/test-notification` | Send a test notification from the web UI |

### WebSocket Authentication

- 6-character alphanumeric token stored via `TokenManager`
- Local network IPs (192.168.x.x, 10.x.x.x, 172.16-31.x.x) bypass token auth

## Building and Running

### Prerequisites

- Android Studio (latest stable recommended)
- JDK 11+
- Android SDK with API level 35

### Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug

# Run all tests
./gradlew test

# Clean build
./gradlew clean

# Build and install in one step
./gradlew clean assembleDebug installDebug
```

### Running the App

1. Connect an Android device via USB (enable USB debugging) or use an emulator
2. Run `./gradlew installDebug` or use Android Studio's Run button
3. Launch the app on the device
4. Grant notification listener permission when prompted
5. Note the server URL displayed in the app (e.g., `ws://192.168.1.100:8765/ws`)
6. Connect from a web client using the displayed auth token

## Required Permissions

- **Network**: INTERNET, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE
- **SMS**: RECEIVE_SMS, READ_SMS, SEND_SMS, READ_PHONE_STATE
- **Foreground Service**: FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, POST_NOTIFICATIONS
- **Power**: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, WAKE_LOCK

## Development Conventions

- **Kotlin coding style**: Official Kotlin style guide (`kotlin.code.style=official`)
- **ViewBinding**: Enabled in build config
- **Version catalog**: Uses Gradle version catalog (`gradle/libs.versions.toml`) for dependency management
- **ProGuard**: Configured for release builds (`app/proguard-rules.pro`)
- **No third-party repositories**: Only Google and Maven Central are used

## Key Technical Notes

- Notification store is thread-safe using `ArrayDeque` with `Mutex`, capped at 200 entries with deduplication by ID
- Filter engine uses SharedPreferences for persistence, supports both whitelist and blacklist modes
- Foreground service type is `connectedDevice`
- Uses cleartext traffic (`android:usesCleartextTraffic="true"`) for local network communication
- SMS reply caching uses `RemoteInput` to replay through original app (SMS, WhatsApp, RCS, etc.)
