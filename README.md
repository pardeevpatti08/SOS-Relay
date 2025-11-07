# P2P SOS Relay

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

P2P SOS Relay is an Android application that forms a decentralized, peer-to-peer mesh network using Wi‑Fi Direct to broadcast and relay SOS alerts between nearby devices. It is designed for emergency scenarios where Internet connectivity may be unavailable (natural disasters, remote areas). Devices forward alerts to other peers until a device with Internet access relays the alert to a backend server.

Table of contents
- [Overview](#overview)
- [How it works](#how-it-works)
  - [Roles](#roles)
  - [Core workflow](#core-workflow)
- [Key features](#key-features)
- [Technologies used](#technologies-used)
- [Setup & installation](#setup--installation)
- [Testing](#testing)
  - [Permissions & device settings](#permissions--device-settings)
  - [Test flow](#test-flow)
  - [Notes for custom Android skins](#notes-for-custom-android-skins)
- [Message schema (example)](#message-schema-example)
- [Development notes](#development-notes)
- [Contributing](#contributing)
- [License](#license)
- [Contact](#contact)

## Overview
P2P SOS Relay uses Android Wi‑Fi Direct (P2P) to discover nearby devices running the app and create ad-hoc groups. When a user sends an SOS, the message propagates through the local P2P network until it reaches a device with Internet access that forwards it to a backend server. When the backend confirms receipt, the confirmation is broadcast back through the mesh to stop further relays for that alert.

## How it works

### Roles
- Standalone
  - Default state when not connected to any P2P group. Uses Wi‑Fi Direct service discovery to find peers and attempts connections to known peers when sending messages.
- Group Owner (GO)
  - Acts as the host/server in a formed P2P group. Listens for client connections and relays messages to clients in the group.
- Client
  - Joins a group created by a GO and sends messages to the GO for broadcast to the group.

### Core workflow
1. Discovery: Devices discover each other via Wi‑Fi Direct Service Discovery.
2. Connection: Devices form P2P groups. Group Owner is selected automatically (based on groupOwnerIntent).
3. Communication: Devices exchange messages over TCP Sockets. The GO runs a ServerSocket for incoming client connections.
4. Alert propagation:
   - If sender has Internet: send directly to backend.
   - If no Internet: broadcast SOS to P2P peers.
   - A peer receiving the SOS attempts to deliver to the backend; if it also lacks Internet, it increments a hops counter and re‑broadcasts to its peers.
5. Confirmation: When backend acknowledges an alert, a confirmation is broadcast back through the network so peers stop relaying that alert.

## Key features
- Peer-to-peer communication using Android Wi‑Fi Direct (no AP required).
- Automatic service discovery of nearby devices running the app.
- Dynamic role switching between Standalone, Group Owner, and Client.
- Internet-aware relaying: peers decide whether to forward to backend or re-broadcast.
- Hop-based propagation to extend alert reach in offline environments.
- Real-time logging in-app for P2P events, connections, and message statuses.
- Graceful lifecycle management (cleanup of services and sockets).

## Technologies used
- Kotlin
- Kotlin Coroutines (concurrency & background tasks)
- Android Wi‑Fi Direct (P2P) API
- Sockets (TCP) for data transfer
- Google Play Services (Location) for device location in SOS alerts
- Gson for JSON serialization/deserialization
- Android ViewBinding for UI interaction

## Setup & installation

1. Clone the repo
```bash
git clone https://github.com/pardeevpatti08/SOS-Relay.git
cd SOS-Relay
```

2. Open in Android Studio
- Open Android Studio.
- Select "Open an Existing Project" and choose the cloned folder.
- Let Gradle sync and build the project.

3. Build & run on device(s)
- Enable Developer Options and USB debugging on your Android devices.
- Connect device(s) to your development machine via USB.
- Select a target device in Android Studio and run the app.
- Install and run the app on at least two devices to test peer discovery and relaying.

## Testing

### Permissions & device settings
- Grant Location and Nearby Devices permissions on first launch (required for Wi‑Fi Direct discovery and location).
- Ensure Wi‑Fi is turned on (no need to be connected to an access point).
- Allow background activity / disable battery optimizations on devices with aggressive power management (see notes below).

### Test flow
1. On each test device, open the app and grant required permissions.
2. Tap "Discover Peers" (or equivalent) on one or more devices to start service discovery.
3. On one device, tap "Send SOS":
   - The app will attempt to find and connect to a peer and send the SOS.
   - If a connection is required, accept the connection dialog on the other device.
4. Observe logs on both devices:
   - Connected devices will exchange the SOS message.
   - A receiving device with no Internet will re‑broadcast; its hops counter will increment with each re-broadcast.
5. When a device with Internet forwards the SOS to the backend and receives confirmation, that confirmation gets broadcast through the mesh and relaying for that alert stops.

### Notes for custom Android skins (Vivo, Xiaomi, OnePlus, etc.)
Certain OEMs implement aggressive battery optimizations which can kill background services:
- Go to Settings → Battery → App Battery Management.
- Find this app and set "Allow background activity" or disable optimization for it.
- Lock the app in Recent Apps if supported to prevent the system from killing it.

## Message schema (example)
```json
{
  "id": "uuid-or-token",
  "from": "+15555550100",
  "message": "Emergency at 123 Main St — need immediate assistance",
  "location": {
    "lat": 37.422,
    "lng": -122.084
  },
  "priority": "high",
  "timestamp": "2025-11-07T13:00:00Z",
  "hops": 0
}
```

## Development notes
- Use Kotlin Coroutines for networking and discovery tasks to avoid blocking the main thread.
- Keep socket lifecycle tied to group lifecycle (open ServerSocket when GO, close when GO stops).
- Validate incoming messages and deduplicate using the alert id.
- Consider adding a persistent queue (e.g., local DB) to guarantee delivery if immediate forwarding fails.

## Contributing
Contributions are welcome. Suggested workflow:
1. Open an issue to discuss features or bugs.
2. Fork the repo and create a feature branch.
3. Write tests for new behavior.
4. Submit a pull request with a clear description of changes.

Add a CODE_OF_CONDUCT.md and CONTRIBUTING.md to document contribution expectations and workflow.

## License
This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

## Contact
Maintainer: pardeevpatti08
- GitHub: https://github.com/pardeevpatti08
