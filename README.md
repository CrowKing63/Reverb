# Reverb

**Mirror Android notifications into a browser-ready control surface on your local network.**

Reverb is an Android app with an embedded HTTP/WebSocket server. It turns your phone into a local notification hub so a browser can receive alerts in real time, inspect message details, send supported replies, trigger a ringtone to find the phone, and review forwarding rules.

## Product Shape

- **Android app**: connection hub, permission/status dashboard, QR onboarding, forwarding rule management
- **Web UI**: live notification center with search, app/category filtering, detail view, reply panel, and connection feedback

## Features

- Real-time notification mirroring over WebSocket
- Browser reply flow for notifications that expose a valid Android `RemoteInput`
- Token-based browser connection with QR-assisted onboarding
- Local forwarding rules with blacklist or whitelist mode
- Device finder endpoint to ring the phone from the web UI
- Live battery status and connection health feedback

## Connection Flow

1. Install and open the Android app.
2. Grant **Notification Listener** permission.
3. From the dashboard, copy the URL or scan the QR code.
4. Open the served web UI on the same local network.
5. If needed, enter the token shown in the Android app.

The QR entrypoint uses the local web URL plus the current token so the browser can connect with fewer manual steps.

## Reply Support

Replies only work for notifications that actually provide a reply action through Android notification APIs. In practice this usually means SMS or chat notifications that expose a `RemoteInput`. Read-only notifications still appear in the web UI, but the reply panel stays hidden.

## Forwarding Rules

Reverb supports two forwarding modes:

- **Blacklist**: send everything except the selected apps
- **Whitelist**: send only the selected apps

The Android app is the primary place to add or search installed apps. The web UI can inspect the current rule set, change mode, and remove packages that are already registered.

## Web UI Capabilities

- View incoming notifications in reverse chronological order
- Search notifications by app, title, body, or package name
- Filter the current feed by app or category
- Inspect a dedicated detail panel for the selected notification
- Send replies when supported
- Trigger a test notification
- Ring the phone

## API Surface

### REST

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/api/reply` | Sends a reply through cached `RemoteInput` or SMS fallback |
| `POST` | `/api/ring` | Plays the default ringtone briefly |
| `GET` / `POST` | `/api/filters` | Reads or updates forwarding rules |
| `GET` | `/api/status` | Returns device name and battery state |
| `POST` | `/api/test-notification` | Emits a local test notification and broadcasts it |

### WebSocket

Connect to `ws://<device-ip>:8765/ws` with the current token.

Message types currently used by the client:

- `snapshot`
- `notification`
- `status`

## Architecture

```text
Android NotificationListenerService
  -> FilterEngine
  -> NotificationStore
  -> Ktor HTTP/WebSocket server
  -> Browser-based notification center
```

## Tech Stack

- Kotlin 2.0.21
- Android Views + ViewBinding
- Ktor 3.0.1
- Kotlinx Serialization
- Kotlinx Coroutines
- ZXing for QR code generation

## Project Structure

```text
app/src/main/java/com/reverb/
  model/     data contracts
  server/    embedded Ktor server, store, filter engine
  service/   notification listener and reply cache
  ui/        Android dashboard and filter management
  util/      token and network helpers

app/src/main/assets/web/
  index.html
  style.css
  app.js
```

## Development Notes

- The Android app is intended to be run from Android Studio.
- The embedded web UI is served from app assets.
- The current implementation targets local-network usage and keeps authentication lightweight.

## License

MIT. See [LICENSE](LICENSE).
