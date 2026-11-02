<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="340" />

  <h3>The Modern Open-Source Media Hub & Video Player</h3>

  <p>
    A free, unified, open-source streaming and media application for Android, iOS, and Desktop.
    <br />
    <strong>Bring Your Own Sources.</strong> Nuvio transforms your streams and catalogs into an elegant library with metadata, multi-source ratings, synchronized subtitles, offline downloads, and seamless cross-device progress.
  </p>

  <p>
    <a href="https://github.com/MMUU6699/nuvio/releases"><img src="https://img.shields.io/github/v/release/MMUU6699/nuvio?style=flat-square&color=blue" alt="Release" /></a>
    <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-Multiplatform-purple?style=flat-square&logo=kotlin" alt="Kotlin Multiplatform" /></a>
    <a href="https://www.jetbrains.com/compose-multiplatform/"><img src="https://img.shields.io/badge/Compose-Multiplatform-4285F4?style=flat-square" alt="Compose Multiplatform" /></a>
    <a href="https://github.com/MMUU6699/nuvio/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-green?style=flat-square" alt="License GPLv3" /></a>
    <a href="https://github.com/MMUU6699/nuvio/stargazers"><img src="https://img.shields.io/github/stars/MMUU6699/nuvio?style=flat-square" alt="Stars" /></a>
  </p>

  <p>
    <a href="#key-features">Key Features</a> •
    <a href="#whats-new-in-this-release">What's New</a> •
    <a href="#architecture">Architecture</a> •
    <a href="#getting-started">Installation</a> •
    <a href="#building-from-source">Build from Source</a> •
    <a href="#contributing">Contributing</a>
  </p>

</div>

---

## Overview

**Nuvio** is an all-in-one entertainment powerhouse built from the ground up using **Kotlin Multiplatform (KMP)** and **Compose Multiplatform**. It brings a desktop-class and TV-grade media experience directly to your mobile and personal devices.

Instead of locking you into a walled garden, Nuvio is designed around the **Bring Your Own Sources (BYOS)** philosophy:
- Seamless compatibility with the entire **Stremio Addon Protocol** (Catalogs, Streams, Subtitles, and Meta).
- Direct integration with high-speed **Debrid providers** (*Real-Debrid, Premiumize, Torbox*) for instant cloud streaming.
- Optional built-in **P2P Torrent streaming engine** (via TorrServer).
- Automated metadata enrichment via **TMDB**, critical ratings aggregation via **MDBList**, and scrobbling with **Trakt.tv** & **Simkl**.

---

## What's New in This Release

This milestone marks a major evolution in player UX, reliability, and extensibility:

### 1. Re-engineered Video Player Controls
- **Clean Ergonomic Layout:** Removed clutter from the bottom bar (including the redundant speed button) to achieve a balanced, comfortable touch surface.
- **Harmonized Spacing:** Optimized button distribution and padding (`6.dp` spacing) for easy one-handed navigation on both small screens and tablets.
- **Dynamic Action Bar:** Quick access to audio tracks, subtitle selector, chapters panel, stream sources, and playback speed modal.

### 2. Intelligent Buffering & Network Indicator
- **Center Slot Buffering Spinner:** High-contrast circular loading indicator appears automatically in the central play/pause slot during network rebuffering, packet loss, or connectivity drops.
- **Auto-Hide Overlay Retention:** If controls auto-hide during buffering, the central spinner persists gracefully without obscuring the canvas.
- **Touch-Desync Protection:** Play/pause clicks are safely disabled while buffering to eliminate race conditions and keep player state synchronized.

### 3. Smart Skip Intro System
- **Decoupled Fast Resolution:** Intro intervals from `IntroDb` and `AniSkip` resolve immediately without waiting on secondary ID lookups, slashing detection latency.
- **Persistent Visibility:** Intro skip action stays clearly visible throughout the detected intro segment without premature auto-dismissal.
- **Smooth Seek-to-End:** Tapping the Skip Intro button seeks accurately to the exact intro end timestamp and immediately resumes playback.

### 4. Comprehensive Integrations Hub
- **TMDB Metadata Enrichment:** Posters, backdrops, episode thumbnails, trailers, and cast filmographies.
- **MDBList Multi-Score Aggregation:** Displays live ratings from IMDb, TMDb, Trakt, Metacritic, and Rotten Tomatoes directly on media headers.
- **Debrid Cloud Acceleration:** Native high-performance streaming from Real-Debrid, Premiumize, and Torbox cached magnets.

### 5. Foldable & Dual-Screen Experience
- **Adaptive Split Layout:** On foldable phones and dual-screen hardware (such as Galaxy Z Fold or Surface Duo), video playback renders uncompromised on the upper fold while interactive episode selectors and player controls occupy the lower fold.

---

## Key Features

### 🎬 High-Performance Video Player
- **Dual-Engine Playback:**
  - **Android:** Google ExoPlayer / Media3 with hardware-accelerated decoding, dynamic buffer sizing, and audio track switching.
  - **Cross-Platform / iOS:** `MPVKit` / `libmpv` with full support for Advanced SubStation Alpha (`.ass` / `.ssa`) styling, custom fonts, and complex soft subtitles.
- **Gesture Controls:** Horizontal swipe for seeking, vertical swipe on left for brightness, vertical swipe on right for volume, and double-tap to jump.
- **Picture-in-Picture (PiP):** Keep watching while browsing other apps or navigating within Nuvio.
- **Chapters & Next Episode:** Chapter markers directly on the timeline, interactive chapters sheet, and smart auto-play countdown for next episodes.

### 🧩 Stremio Addon Compatibility
- Complete implementation of the Stremio Addon Protocol (V1 & V2).
- Install any community or self-hosted add-on using a standard manifest URL.
- Support for add-on catalogs, dynamic search, multi-source stream resolvers, and external subtitle providers.

### ⚡ Debrid & Torrent Cloud Streaming
- Direct integration with **Real-Debrid**, **Torbox**, and **Premiumize**.
- Unrestricted bandwidth: stream 4K HDR, Dolby Vision, and Remux files instantly from cached cloud storage.
- Integrated P2P TorrServer fallback for non-Debrid torrent playback with live peer and download diagnostics.

### 📊 Comprehensive Tracking & Cloud Sync
- **Trakt.tv Integration:** Two-way history synchronization, real-time scrobbling, and watch progress restoration.
- **Simkl Scrobbling:** Automated scrobbling and anime episode tracking.
- **Supabase Cloud Backend:** Multi-profile support with instant cloud sync for continue-watching carousels, saved libraries, and add-on configurations across all your devices.

### 💾 Offline Downloads Manager
- Download movies, episodes, and external subtitles directly to local storage.
- Reliable background download worker with pause, resume, and completion notifications.

### 👥 Watch Party / SyncPlay
- Create or join synchronized watch rooms with friends.
- Zero-latency playback state sync combined with built-in real-time party chat.

### 🎨 Material 3 & Customization
- Expressive Material 3 design with dynamic Monet color theming.
- Deep subtitle styling: customize fonts, sizes, colors, outlines, shadows, background opacity, and SDH filtering.
- Multi-language interface with support for English, Arabic, Spanish, French, German, Italian, Bulgarian, Vietnamese, Russian, Portuguese, and more.

---

## Architecture

Nuvio is organized as a modular Kotlin Multiplatform repository:

```
nuvio/
├── composeApp/                     # Shared KMP & Compose Multiplatform codebase
│   └── src/
│       ├── commonMain/kotlin/com/nuvio/app/
│       │   ├── core/               # Auth, network, theme, database, build configs
│       │   ├── features/
│       │   │   ├── addons/         # Stremio Addon HTTP client & manifest parser
│       │   │   ├── catalog/        # Media discovery, genre filters, feeds
│       │   │   ├── debrid/         # Real-Debrid, Premiumize, Torbox clients
│       │   │   ├── details/        # Media details, seasons, cast, recommendations
│       │   │   ├── downloads/      # Offline storage downloader
│       │   │   ├── library/        # User watch history, bookmarks, favorites
│       │   │   ├── party/          # Synchronized watch party & live chat
│       │   │   ├── player/         # Video player engine, controls, gestures, skip intro
│       │   │   ├── settings/       # App preferences, theme editor, integrations hub
│       │   │   ├── trakt/          # Trakt.tv two-way scrobbling & history
│       │   │   └── simkl/          # Simkl anime & media tracking
│       │   └── navigation/         # Type-safe navigation & screen routes
│       ├── androidMain/            # Android platform specifics (Media3, PiP, notifications)
│       └── iosMain/                # iOS platform specifics (AVPlayer / MPVKit bridge)
├── androidApp/                     # Android native entry point & application manifest
├── iosApp/                         # iOS Xcode project, xcframework bindings & assets
├── assets/                         # Vector assets, rating icons, animations
└── store.json                      # AltStore / SideStore repository manifest
```

---

## Getting Started

### Android
- Download the latest APK from the [Releases](https://github.com/MMUU6699/nuvio/releases) page.
- Install the APK on any Android phone, tablet, or Android TV running Android 7.0 (API 24) or newer.

### iOS
- Install via **AltStore** or **SideStore**:
  1. Open AltStore or SideStore on your iOS device.
  2. Navigate to **Sources** → **Add Source (+)**.
  3. Enter the Nuvio store URL:
     ```
     https://raw.githubusercontent.com/MMUU6699/nuvio/main/store.json
     ```
  4. Search for **Nuvio** and tap **Install**.

---

## Building from Source

### Prerequisites
- **JDK 17** or higher
- **Android Studio Ladybug (or newer)** with Android SDK 35
- **Xcode 15+** (required only for building the iOS client on macOS)

### 1. Clone the repository
```bash
git clone https://github.com/MMUU6699/nuvio.git
cd nuvio
```

### 2. Build Android
To compile and assemble the full debug APK:
```bash
./gradlew :androidApp:assembleFullDebug
```
The output APK will be generated at:
`androidApp/build/outputs/apk/full/debug/androidApp-full-debug.apk`

To run unit and platform tests:
```bash
./gradlew :composeApp:testDebugUnitTest
```

### 3. Build iOS (macOS required)
```bash
env NUVIO_IOS_DISTRIBUTION=full xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -derivedDataPath build/ios-derived-full-simulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

---

## Configuration & Integrations

Nuvio works out-of-the-box with standard Stremio add-on URLs. To unlock advanced capabilities, navigate to **Settings → Integrations**:

| Service | Purpose | Setup |
|---|---|---|
| **TMDB** | Rich synopsis, backdrops, cast filmography, and trailers | Add your TMDB API v3 key or use the built-in resolver |
| **MDBList** | Aggregated IMDb, Trakt, TMDb, Metacritic, Rotten Tomatoes scores | Enter your MDBList API key |
| **Real-Debrid** | High-speed cached torrent cloud streaming | Connect via OAuth device token or API key |
| **Torbox** | Debrid and cloud seedbox streaming | Enter your Torbox API token |
| **Premiumize** | Cloud storage and torrent cache streaming | Enter your Premiumize API pin/key |
| **Trakt.tv** | Real-time scrobbling and watchlist synchronization | One-tap OAuth device authentication |
| **Simkl** | Anime episode and drama scrobbling | One-tap OAuth login |

---

## Contributing

We welcome contributions from the open-source community! Whether you are fixing a bug, adding translations, or improving documentation, please read our [Contributing Guidelines](CONTRIBUTING.md) and review the [Pull Request Template](.github/PULL_REQUEST_TEMPLATE.md).

1. Fork the repository.
2. Create your feature branch (`git checkout -b fix/playback-buffer-timeout`).
3. Commit your changes with clear, descriptive messages.
4. Push to your branch and open a Pull Request.

---

## License

This project is licensed under the **GNU General Public License v3.0** (GPLv3). See the [LICENSE](LICENSE) file for complete details.

---

<div align="center">
  <sub>Built with ❤️ by the open-source community. Nuvio does not host, store, or distribute any media content. Users provide their own sources.</sub>
</div>
