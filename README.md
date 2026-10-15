# Run with the Wind 🏃💨

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Wear OS](https://img.shields.io/badge/Wear%20OS-4.0%2B%20(API%2033%2B)-4285F4.svg?logo=google&logoColor=white)](https://developer.android.com/wear)
[![Android SDK](https://img.shields.io/badge/Target%20SDK-36-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Wear%20%26%20M3-4285F4.svg?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)

**Run with the Wind** is a standalone Wear OS workout tracker and companion Android app built with modern Android standards: **Jetpack Compose**, **Google Health Services API**, **Coroutines & Flow**, **Room**, and a **pure Kotlin multi-module architecture**.

It is engineered for high reliability during workouts—featuring **1 Hz streaming activity logging**, **atomic startup crash recovery**, and **offline-first data retention**—ensuring you never lose a workout even if your watch battery dies mid-run.

---

## 🌟 Features

- **⌚ Standalone Watch Tracking**: Record workouts directly on your Wear OS watch with real-time GPS, heart rate, speed, pace, and elevation tracking—no phone required.
- **🛡️ Crash-Proof & Resilient**: Unfinished sessions caused by sudden app force-close or watch power loss are automatically recovered, calculated, compressed, and finalized on the next launch.
- **📊 3-Page Metric Dashboard**: Swipe or use the physical watch rotary crown to navigate through:
  - **Overview**: Distance (km), Moving Time, Average Pace (min/km).
  - **Splits**: Distance, Current Pace, Split Pace, Moving Time.
  - **Health & Elevation**: Live Heart Rate (BPM), Elevation Gain (m), Elapsed Time.
- **⚡ 1 Hz Telemetry Engine**: Records high-frequency sensor telemetry to local disk using newline-delimited JSON (`.jsonl`) compressed into `.jsonl.gz` upon completion.
- **🧹 Auto Retention Policy**: Built-in 30-day lifecycle management that automatically prunes expired activity files and orphan records.
- **📱 Phone Companion & Export**: Syncs compressed activity logs via the Wearable Data Layer for history viewing and third-party upload destinations (Strava first).
- **🚴 Multi-Sport Ready**: Extensible architecture designed for Running, Cycling, and Swimming without hardcoded sport limits.

---

## 🏗️ Architecture & Stack

The project uses a clean **multi-module Gradle architecture** separating platform-specific UIs from pure Kotlin business logic. Both `:wear` and `:mobile` modules share **one Application ID** and **one signing key**.

```
RunwiththeWind/
├── :wear                 # Wear OS app (Compose for Wear OS, ExerciseService, Room, Health Services)
├── :mobile               # Companion phone app (Material 3, Navigation 3, Hilt DI)
├── :core:model           # Pure Kotlin/JVM domain models & SI unit primitives (No Android imports)
├── :core:sync-contract   # Pure Kotlin/JVM versioned IPC wire format for Wearable Data Layer
└── :core:export          # Pure Kotlin/JVM export generators (GPX, TCX, Strava payloads)
```

### Stack Comparison (.NET / Web vs. Android)

| Concept | Android / Wear OS Stack | Role in "Run with the Wind" |
| :--- | :--- | :--- |
| **Worker / Service** | `ForegroundService` (`ExerciseService`) | Runs independently of UI; maintains ongoing watch notification while gathering GPS & HR sensors. |
| **Sensor API** | Google `HealthServicesClient` | System API providing low-power hardware sensor callbacks (GPS, Heart Rate, Elevation). |
| **Database** | `Room` (`AppDatabase` / `ActivityDao`) | Embedded SQLite database acting as the single source of truth for workout metadata. |
| **Telemetry Writer** | `ActivityRecorder` (`JSONL` + `Gzip`) | Streams 1 Hz timestamped sample objects to disk as newline JSON, gzipping atomically on finish. |
| **UI & State** | `Jetpack Compose` + `StateFlow` | Declarative, reactive UI observing immutable state flows emitted by background services. |


---

## 📱 User Journey & Workout Lifecycle

1. **Activities Menu**: Choose sport type (`RUN`, `CYCLE`, `SWIM`).
2. **GPS & Permission Readiness**: Real-time permission validation and GPS fix status indicator (Green/Yellow/Red).
3. **Active Workout Tracking**: Foreground service begins streaming telemetry. Live metrics update at 1 Hz across 3 rotary-scrolling pages. Physical back or stem button toggles pause/resume.
4. **Finish & Summarize**: On finishing, the raw `.jsonl` stream is closed, gzipped to `.jsonl.gz`, marked as `FINISHED` in Room DB, and displayed on the summary screen.

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio**: Ladybug / Meerkat or newer
- **JDK**: 17 or higher
- **Android SDK**: `compileSdk 36`, `minSdk 33`
- **Device / Emulator**: Wear OS 4+ (API 33+) with Google Play Services and Health Services support

### Building the Project

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-username/Run-with-the-Wind.git
   cd Run-with-the-Wind
   ```

2. **Assemble Debug Builds**:
   ```bash
   ./gradlew assembleDebug
   ```

3. **Run Unit Tests**:
   ```bash
   ./gradlew test
   ```

4. **Deploy to Wear OS Device / Emulator**:
   - Open Android Studio, select the `:wear` run configuration, choose your Wear OS device or emulator, and click **Run**.

---

## 🧪 Technical Highlights & Quality Assurance

- **Concurrency Safety**: Coroutine scopes managed with `SupervisorJob()` and `Dispatchers.Main.immediate`, with file writing delegated off the UI thread via `Dispatchers.IO`.
- **Resource Discipline**: Strict stream and buffer management using Kotlin `.use {}` blocks and explicit Wearable API buffer release calls.
- **Unit Tested Core Logic**: Pure unit tests covering retention calculations, crash recovery stats derivation, and history formatting.

---

## 📜 License & Guidelines

Developed as a modern personal fitness companion demonstrating production-grade Wear OS architecture and Android best practices.

*Build with wind at your back! 🏃💨*
