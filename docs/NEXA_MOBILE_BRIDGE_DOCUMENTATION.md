# N.E.X.A Mobile Bridge — Architecture & Technical Documentation

**Document Version:** 1.0 (Super Complete / Production Edition)  
**Target Device Hardware:** Samsung Galaxy A33 5G (Android 16 / One UI 8)  
**Package Name:** `com.nexa.mobilebridge`  
**Project Path:** `d:\nexa-mobile-bridge`

---

## 1. Executive Summary & Architectural Philosophy

**N.E.X.A Mobile Bridge** adalah jembatan eksekutif seluler Android (*Thin-Client Executive Bridge*) yang menghubungkan smartphone pribadi pengguna dengan **N.E.X.A (Cloud / Local Server)** secara real-time via bidirectional WebSocket protocol.

### 🌟 Tiga Pilar Utama Arsitektur:
1. **Thin-Client Logic (Server-Centric Processing):**
   Aplikasi Android tidak memproses logika AI, pengambil keputusan, atau pembentukan teks percakapan. Seluruh otak N.E.X.A berada di server. Perangkat Android berfungsi sebagai **Interface & Executor Level Hardware** (kamera, lokasi, notifikasi, kontrol volume, interupsi audio, UI overlay, dan sensor).
2. **Resiliensi Immortality Tanpa Root (Knox & Warranty Safe):**
   Aplikasi mengeksploitasi izin akses tertinggi resmi Android (*Special Access / Privilege APIs*) seperti `NotificationListenerService`, `AccessibilityService`, `SYSTEM_ALERT_WINDOW`, dan `Notification Policy Access (DND)` tanpa perlu melakukan root pada Samsung Galaxy A33 5G (One UI 8).
3. **Ketahanan 24/7 & Efisiensi Daya:**
   Menjalankan `Foreground Service` bertanda `START_NOT_STICKY` dikombinasikan dengan `Partial WakeLock` dan sistem *smart exponential backoff* agar dapat bertahan hidup 24/7 di latar belakang tanpa dibunuh oleh manajemen memori OS dan tanpa menguras daya baterai.

---

## 2. Peta Lengkap Struktur Proyek & Modul (`com.nexa.mobilebridge`)

```
d:\nexa-mobile-bridge\app\src\main\java\com\nexa\mobilebridge\
 ├── NexaBridgeApp.kt                   --> Root Application Class (Hilt @HiltAndroidApp)
 ├── MainActivity.kt                    --> UI Window Host Activity & Edge-to-Edge Container
 ├── Navigation.kt & NavigationKeys.kt  --> Jetpack Compose Navigation Graph
 ├── NexaBridgeService.kt               --> Main Foreground Service & Core Controller Loop
 ├── NexaAccessibilityService.kt        --> System Accessibility Service (Screenshot & Global Actions)
 ├── TransparentCameraActivity.kt       --> Background CameraX Capture Activity (Transparent UI)
 │
 ├── core/                              --> Core Engine Components
 │    ├── context/
 │    │    └── ContextEngine.kt         --> Multi-Sensor Synthesis Engine & Rule Assessor
 │    ├── sensor/
 │    │    ├── SensorObserverManager.kt --> Hardware Sensor Listener (Light, Motion, Pickup, Step Counter)
 │    │    ├── GeofenceManager.kt       --> Google Play Services Geofencing Orchestrator
 │    │    └── GeofenceBroadcastReceiver.kt --> Background Geofence Transition Receiver
 │    ├── di/
 │    │    ├── AppModule.kt             --> Hilt Dependency Injection (Context, DataStore)
 │    │    └── NetworkModule.kt          --> Hilt OkHttpClient & Json Serialization Config
 │    ├── network/
 │    │    ├── NexaWebSocketClient.kt   --> OkHttp WebSocket Client & HMAC verification router
 │    │    ├── NexaHttpClient.kt        --> HTTP Client Helper
 │    │    └── ReconnectOrchestrator.kt  --> 4-Phase Tiered Smart Reconnect State Engine
 │    ├── security/
 │    │    └── HmacVerifier.kt          --> HMAC-SHA256 Signature Validation Engine (GodMode)
 │    └── util/
 │         └── ConnectionState.kt      --> Sealed Class for UI Connection Status
 │
 ├── data/                              --> Data Persistence Layer
 │    ├── DataRepository.kt             --> DataStore Preferences Accessor
 │    └── local/                        --> Preferences keys definition
 │
 ├── dispatcher/                        --> Command Execution Layer
 │    ├── DeviceCommandDispatcher.kt   --> Main Router for incoming WebSocket commands
 │    └── handler/                      --> 11 Modular Hardware Command Handlers
 │         ├── AppLauncherHandler.kt   --> App Launching & Intent Resolver
 │         ├── BatteryHandler.kt       --> Battery Level & Charging Status Sensor
 │         ├── CameraHandler.kt        --> Front/Back Transparent CameraX Trigger
 │         ├── FlashlightHandler.kt    --> Torch Light Switch via CameraManager
 │         ├── LocationHandler.kt      --> GPS/Network Location & Geocoder Address Resolver
 │         ├── NetworkHandler.kt       --> Wi-Fi State & Network Telemetry Sensor
 │         ├── OverlayHandler.kt       --> System Alert Window Launcher
 │         ├── ScreenHandler.kt        --> Home, Back, Recents Navigation Actions
 │         ├── ScreenshotHandler.kt    --> Base64 Screenshot via Accessibility API
 │         ├── TtsHandler.kt           --> Text-to-Speech Engine with Indonesian Locale
 │         └── VolumeHandler.kt        --> Multi-Stream Volume & DND Interruption Filter
 │
 ├── protocol/                          --> Data Exchange Protocols (Models)
 │    ├── NexaCommand.kt               --> Incoming Command JSON Structure
 │    ├── CommandResult.kt             --> Execution Result JSON Structure
 │    ├── NexaActions.kt               --> Command Action String Constants (16 Actions)
 │    ├── TelemetryReport.kt           --> Periodic Telemetry JSON Payload Model
 │    └── ContextReport.kt             --> High-Level Real-Time Context Update Event Payload
 │
 ├── service/                           --> Background Listening Services
 │    └── NexaNotifListenerService.kt  --> Intercept System Notification Events (Alarm Dismiss for Morning Briefing)
 │
 └── ui/                                --> Modern Jetpack Compose UI
      ├── hud/
      │    ├── HudScreen.kt             --> Main Executive Dashboard UI
      │    └── HudViewModel.kt          --> HUD State Holder & Connection Toggle
      ├── overlay/
      │    └── OverlayActivity.kt       --> Dynamic System Overlay Dialog with Dynamic Server Options
      └── settings/
           ├── SettingsScreen.kt        --> Configuration Screen (WS URL, Token, HMAC Secret)
           └── SettingsViewModel.kt     --> Settings DataStore ViewModel
```

---

## 3. Subsystem & Analisis Alur Kerja (Workflow Systems)

### 3.1. Smart Reconnect Subsystem (4-Phase Tiered Retry)

Sistem ini mencegah pengurasan baterai dan kelelahan koneksi saat N.E.X.A Server luring/down, sekaligus memberikan transparansi lengkap kepada pengguna melalui **Bilah Notifikasi Sistem** dan **Narasi Suara (TTS)**.

```
[KONEKSI TERPUTUS]
       │
       ▼
FASE 1 — Immediate (20x Attempt | Exponential Backoff 1s ➔ 30s)
       │
       └── Semua 20x Gagal ➔ Tunggu 1 Jam (Silence)
                                  │
                                  ▼
                     FASE 2 — +1 Jam (20x Attempt)
                                  │
                                  └── Gagal ➔ 🔔 Heads-up Notif + 🎙️ TTS ➔ Tunggu 6 Jam
                                                                                │
                                                                                ▼
                                                                  FASE 3 — +6 Jam (20x Attempt)
                                                                                │
                                                                                └── Gagal ➔ 🔔 Heads-up Notif + 🎙️ TTS ➔ Tunggu 24 Jam
                                                                                                                              │
                                                                                                                              ▼
                                                                                                                FASE 4 — +24 Jam (20x Attempt)
                                                                                                                              │
                                                                                                                              └── Gagal ➔ ❌ Notif Final + 🎙️ TTS ➔ BERHENTI TOTAL
```

#### Klasifikasi Exception Otomatis:
| Exceptions Class | Identifikasi Tipe Error | Narasi Suara (TTS) |
|---|---|---|
| `UnknownHostException` | Tidak Ada Internet / DNS Gagal | *"H P tidak dapat menemukan server N E X A. Pastikan koneksi internet aktif"* |
| `ConnectException` | Server Offline / Koneksi Ditolak | *"Server N E X A tidak dapat dihubungi. Server kemungkinan sedang offline atau dalam pemeliharaan"* |
| `SocketTimeoutException` | Request Timeout | *"Koneksi ke server N E X A habis waktu tunggu. Server mungkin sedang kelebihan beban"* |
| `SSLException` | SSL / Security Certificate Error | *"Koneksi gagal karena masalah keamanan S S L. Periksa konfigurasi sertifikat server"* |
| *Lainnya* | Unclassified Error | *"Koneksi N E X A gagal dengan error: [tech message]"* |

> 💡 **Aturan Volume TTS Alert:**  
> Peringatan suara kegagalan koneksi menggunakan **volume media yang sedang aktif saat itu** tanpa memaksanya naik ke 100%, sehingga tidak mengejutkan pengguna jika HP sedang di-mute atau volume rendah.

---

### 3.2. Tasker Replacement & Notification Intercept Subsystem

Menggantikan aplikasi pihak ketiga seperti Tasker dengan menangkap event sistem secara real-time via `NexaNotifListenerService`.

1. **Morning Briefing Interception (`onNotificationRemoved`) [FITUR AKTIF]:**
   - Mengawasi package jam Samsung (`com.sec.android.app.clockpackage`) dan Google Clock (`com.google.android.deskclock`).
   - Ketika alarm selesai berbunyi dan pengguna memencet tombol **Dismiss** (notifikasi hilang), service memancarkan event `ALARM_DISMISSED` via WebSocket.
   - N.E.X.A Server menangkap event ini dan langsung membalas dengan perintah `SPEAK_TEXT` berisi *Morning Briefing* (cuaca, berita, agenda) dengan volume 100%.

2. **Catatan Arsitektur Finansial (Finance Interception - UNUSED / DEPRECATED):**
   - *Penting:* Fitur penangkapan notifikasi transaksi bank (`onNotificationPosted` untuk BCA, GoPay, OVO, ShopeePay, Mandiri, BNI) pada aplikasi Android **tidak digunakan** di ekosistem N.E.X.A.
   - **Alasan Arsitektur:** Seluruh logika transaksi keuangan dikendalikan secara terpusat oleh **Server-Side N.E.X.A (`src/domain/Finance_Engine.js`)** menggunakan:
     1. **Mandiri Email Auto-Sync (`Gmail_Client.js`):** Memantau e-statement / notifikasi transaksi resmi via polling Gmail dengan *Zero-Duplication Engine*.
     2. **Manual & Voice Entry (`TELEGRAM_MANUAL`):** AI Router & Whisper mendeteksi input transaksi dari percakapan suara/teks Telegram pengguna dan langsung mencatatnya ke database Supabase.

---

### 3.3. Command Dispatcher & Handlers (28 Perintah Hardware & Aksesibilitas Terimplementasi)

`DeviceCommandDispatcher` menerima `NexaCommand` dari WebSocket dan merutekannya secara otomatis ke modul handler eksekusi yang sesuai:

| No | Action Name | Handler / Service | Deskripsi & API Android | Return Data |
|:---:|---|---|---|---|
| 1 | `TOGGLE_FLASHLIGHT` | `FlashlightHandler` | Mengontrol lampu kilat (LED Kamera) via `CameraManager.setTorchMode()`. | `enabled: Boolean` |
| 2 | `SET_VOLUME` | `VolumeHandler` | Mengubah volume audio (`STREAM_MUSIC`, `STREAM_RING`, `STREAM_ALARM`, `STREAM_NOTIFICATION`, `STREAM_SYSTEM`). | `targetVolume`, `maxVolume` |
| 3 | `FORCE_DND` | `VolumeHandler` | Mengubah mode Do Not Disturb via `NotificationManager.setInterruptionFilter()`. | Status `ENABLED` / `DISABLED` |
| 4 | `GET_BATTERY_STATUS` | `BatteryHandler` | Membaca status baterai, persentase, dan mode pengisian via `ACTION_BATTERY_CHANGED`. | `battery_level`, `charging` |
| 5 | `GO_HOME_SCREEN` | `ScreenHandler` | Mengirim Intent `ACTION_MAIN` dengan kategori `CATEGORY_HOME`. | Success Message |
| 6 | `GO_BACK` | `ScreenHandler` | Menavigasi kembali via `AccessibilityService.GLOBAL_ACTION_BACK`. | Success Message |
| 7 | `SHOW_RECENTS` | `ScreenHandler` | Membuka app switcher via `AccessibilityService.GLOBAL_ACTION_RECENTS`. | Success Message |
| 8 | `LOCK_SCREEN` | `ScreenHandler` | Perintah kunci layar (Device Admin). | Status Info |
| 9 | `LAUNCH_APP` | `AppLauncherHandler` | Membuka aplikasi Android berdasarkan `package_name` via `PackageManager.getLaunchIntentForPackage()`. | Success Message |
| 10 | `TOGGLE_WIFI` | `NetworkHandler` | Mengontrol/membaca status Wi-Fi via `WifiManager`. | Status Info |
| 11 | `GET_NETWORK_INFO` | `NetworkHandler` | Membaca status koneksi data/Wi-Fi aktif via `ConnectivityManager` (SSID, RSSI dBm). | `type`, `connected`, `ssid`, `rssi` |
| 12 | `GET_LOCATION` | `LocationHandler` | Membaca koordinat GPS/Network via `LocationManager` + Resolusi alamat via `Geocoder`. | `latitude`, `longitude`, `accuracy`, `provider`, `address` |
| 13 | `SPEAK_TEXT` | `TtsHandler` | Membaca suara via Android `TextToSpeech` (Locale `id-ID` / Fallback `en-US`), otomatis memastikan volume terdengar. | Status `SUCCESS` / `FAILURE` |
| 14 | `TAKE_PHOTO` | `CameraHandler` | Membuka `TransparentCameraActivity` (CameraX) tanpa animasi UI, mengambil foto kamera depan/belakang, resize max 1280px. | `image_base64` |
| 15 | `TAKE_SCREENSHOT` | `ScreenshotHandler` | Mengambil tangkapan layar penuh via `NexaAccessibilityService.takeScreenshot()`, resize max 1280px, kompres JPEG 60%. | `image_base64` |
| 16 | `SHOW_OVERLAY_MSG` | `OverlayHandler` | Meluncurkan `OverlayActivity` di atas semua aplikasi dengan dialog Compose bermaterial gelap dan tombol pilihan dinamis server. | Selected Action (`action`) |
| 17 | `GET_CLIPBOARD` | `ClipboardHandler` | Membaca teks papan klip (*clipboard*) sistem HP. | `text: String` |
| 18 | `SET_CLIPBOARD` | `ClipboardHandler` | Menuliskan teks ke papan klip (*clipboard*) sistem HP. | Success Message |
| 19 | `OPEN_INTENT` | `IntentHandler` | Membuka URL di browser, koordinat Google Maps (`geo:`), atau bagikan teks. | Success Message |
| 20 | `DUMP_UI_HIERARCHY` | `NexaAccessibilityService` | **Mata (UI Inspector):** Memindai seluruh struktur elemen UI layar HP menjadi hierarki JSON (Bounds, ID, Text, Class). | `data.nodes: Array<UiNode>` |
| 21 | `ACCESSIBILITY_CLICK` | `NexaAccessibilityService` | **Tangan (Click Controller):** Mengeklik elemen berdasarkan ID/Text atau koordinat piksel `(x, y)` via `dispatchGesture`. | Success Message |
| 22 | `ACCESSIBILITY_INPUT_TEXT` | `NexaAccessibilityService` | **Input Teks Automasi:** Memasukkan teks otomatis ke form input yang sedang aktif atau difokuskan. | Success Message |
| 23 | `ACCESSIBILITY_SCROLL` | `NexaAccessibilityService` | **Usap Layar (Scroll Controller):** Memutar/menggeser layar ke atas atau bawah (`FORWARD` / `BACKWARD`). | Success Message |
| 24 | `PLAY_RINGTONE` | `AudioPlayerHandler` | Memutar ringtone darurat alarm sistem dengan volume max 100%. | Status `SUCCESS` |
| 25 | `PLAY_MEDIA` | `AudioPlayerHandler` | Memutar file audio media via MediaPlayer. | Status `SUCCESS` |
| 26 | `STOP_MEDIA` | `AudioPlayerHandler` | Menghentikan pemutaran audio/ringtone darurat. | Status `SUCCESS` |
| 27 | `SIMULATE_INCOMING_CALL` | `FakeCallHandler` / `FakeCallActivity` | Memunculkan layar panggilan interaktif 6-state (Ringing, Speaking, 10s Voice Recording, Waiting, Reply). | Event `CALL_ACCEPTED` / `REJECTED` / `AUDIO_REPLY` |
| 28 | `PLAY_AUDIO_STREAM` | `AudioPlayerHandler` | Memutar stream balasan audio PCM dari server atau sinyal penutupan panggilan interaktif. | Status `SUCCESS` / Event `CALL_REPLY_COMPLETE` |

---

### 3.4. Dynamic System Overlay (`OverlayActivity.kt`)

Dialog pop-up interaktif yang muncul melayang di atas aplikasi apa pun (*System Alert Window*):
- **Opsi Dinamis:** Menerima JSON `options` dari server. Server dapat menentukan jumlah tombol, label teks, kode warna hex (misal `#00E676` hijau), dan `action` yang dihasilkan saat diklik.
- **Fallback Default:** Jika server tidak mengirimi `options`, ia otomatis merender 3 tombol standar: `TIDAK` (`NO`), `BICARA` (`VOICE`), dan `YA` (`YES`).
- **Resiliensi Format Warna:** Dilengkapi `try-catch` saat mem-parse warna Hex untuk mencegah crash jika format warna dari server tidak valid.

---

### 3.5. Security Engine (GodMode HMAC SHA-256)

Mencegah perintah berbahaya dijalankan dari sumber tak dikenal jika koneksi terintersepsi.
- **`HmacVerifier.kt`:** Memverifikasi payload perintah ber-Privilege tinggi (level ≥ 3).
- **Formula Signature:** `HMAC-SHA256(timestamp + "." + level, hmacSecret)`.
- Jika signature tidak cocok dengan rahasia yang tersimpan di `DataStore` aplikasi, perintah **ditolak mentah-mentah** oleh `NexaWebSocketClient`.

---

### 3.6. System Lifecycle & Safety Mechanisms (WebSocket Immortality Protocol)

- **Single Scope Lifecycle:** `serviceScope` di dalam `NexaBridgeService` diciptakan ulang pada `onCreate()` dan dibatalkan penuh pada `onDestroy()` menggunakan `serviceScope.cancel()`.
- **Anti-Duplikasi Loop (`isServiceStarted`):** Bendera boolean memastikan bahwa panggilan `onStartCommand` yang berulang (misal dari spamming switch UI) tidak mencetuskan *consumer loop* ganda.
- **State Reset:** Method `orchestrator.reset()` dipanggil setiap kali koneksi baru dipicu dari UI agar state percobaan lama benar-benar bersih.
- **Pencegahan Out-Of-Memory (OOM):** `resizeBitmap(bitmap, 1280)` membatasi ukuran gambar kamera & screenshot sebelum diubah menjadi string Base64.
- **Boot Completed Auto-Start (`BootCompletedReceiver.kt`):** Mendaftarkan `ACTION_BOOT_COMPLETED` & `ACTION_QUICKBOOT_POWERON` agar service Bridge otomatis menyala di latar belakang saat HP di-restart tanpa perlu membuka aplikasi secara manual.
- **Bi-Directional Keepalive (Anti-NAT / Caddy Timeout):**
  - **Android Client:** `OkHttpClient.pingInterval(20, TimeUnit.SECONDS)` secara proaktif mengirim 2-byte ping frame setiap 20 detik, mencegah router Wi-Fi rumah dan Caddy Reverse Proxy memutus jalur NAT yang sedang diam/idle.
  - **Node.js Server:** `heartbeatInterval` (25s) secara berkala memeriksa kesehatan soket klien. Jika tidak ada respons `pong`, server langsung mengeksekusi pembersihan.
- **Instant Zombie Socket Termination (`ws.terminate()`):**
  - Menggantikan `ws.close()` yang lambat/menggantung saat koneksi baru masuk (*client replacement*).
  - Server langsung memutus koneksi lama di level kernel TCP (`socket.destroy()`), mengeliminasi kode error `1006` (Abnormal Closure) dan mencegah kebocoran memori di PM2.
- **Client-Side Stale Socket Abort & Listener Guarding:**
  - `NexaWebSocketClient.kt` memanggil `webSocket?.cancel()` sebelum membuka soket baru untuk membunuh thread pool usang.
  - `WebSocketListener` dilengkapi *instance guarding* (`ws !== this@NexaWebSocketClient.webSocket`) agar event `onFailure`/`onClosed` dari soket lama yang mati tidak merusak status koneksi soket baru yang sehat.
- **Samsung One UI `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`):**
  - `NexaBridgeService` mengakuisisi `WifiManager.WifiLock` bersamaan dengan `PARTIAL_WAKE_LOCK` untuk mencegah OS Samsung menidurkan chip radio Wi-Fi saat layar HP terkunci di meja.
- **Network Watcher Debounce (1500ms):**
  - `ConnectivityManager.NetworkCallback.onAvailable()` dilengkapi timer debounce 1.5 detik untuk meredam osilasi sinyal cepat saat berpindah antara Wi-Fi ↔ 4G, mencegah *reconnect storm*.

---

### 3.7. Context Synthesis Engine & Sensor Observer Subsystem (Top 5 Sensors)

Subsistem ini mengubah data mentah dari 5 sensor prioritas utama menjadi konteks situasional cerdas (*High-Level Context Reports*) yang dikirimkan secara real-time ke N.E.X.A Server via WebSocket (`ContextReport`).

```
[HARDWARE SENSORS]
 ├── Light Sensor ──────────► [Dual-Threshold Hysteresis + 600ms Debounce] ──┐
 ├── Step Counter ──────────► [Cumulative Step Tracking] ──────────────────┤
 ├── Pickup Gesture ────────► [Vendor Hardware ID Fallback (ID 25)] ───────┼──► [ContextEngine.kt] ──► WebSocket
 ├── Motion Detector ───────► [Significant Motion Filter] ─────────────────┤      (Rule Synthesis)      (ContextReport)
 └── GPS Geofencing ────────► [GeofenceBroadcastReceiver (Foreground Svc)] ┘
```

#### Detail Arsitektur & Robustness 5 Sensor Utama:

1. **Light Sensor (Cahaya Ambient):**
   - **Problem:** Threshold tunggal menyebabkan *jitter/spamming* ratusan log saat nilai cahaya berada di batas ambang (misal 9.9 lux ↔ 10.1 lux).
   - **Solusi Rekayasa:** Menggunakan **Dual-Threshold Hysteresis** (Batas Gelap: `< 10 lux`, Batas Terang: `> 15 lux`) dikombinasikan dengan **600ms Debounce Timer**. Menghilangkan event spam secara total dan menjamin stabilitas.
   - **Event Output:** `LIGHT_CHANGED` (`DARK`, `NORMAL`, `BRIGHT`).

2. **Pickup Gesture (Perangkat Diangkat):**
   - **Problem:** `Sensor.TYPE_PICK_UP_GESTURE` sering menyebabkan `NullPointerException` pada SDK vendor tertentu (misal Samsung Knox/One UI).
   - **Solusi Rekayasa:** Menggunakan `@Suppress("DEPRECATION")` dengan pembacaan langsung Hardware Sensor ID `25` jika ID konstanta standar tidak mengembalikan instance.
   - **Event Output:** `PHONE_PICKUP`.

3. **Step Counter (Penghitung Langkah):**
   - **Mekanisme:** Mendengarkan `Sensor.TYPE_STEP_COUNTER` untuk mengukur aktivitas fisik pengguna secara kumulatif tanpa menyedot daya CPU.
   - **Event Output:** `STEP_COUNTER`.

4. **Activity & Significant Motion (Deteksi Pergerakan):**
   - **Mekanisme:** Menggunakan `Sensor.TYPE_SIGNIFICANT_MOTION` untuk mendeteksi kapan pengguna mulai bergerak (berjalan/berkendara) atau diam.
   - **Event Output:** `USER_MOVING`, `USER_STATIONARY`.

5. **GPS Geofencing (Presensi Area):**
   - **Mekanisme:** Menggunakan Google Play Services `GeofencingClient` dengan radius 100m (Rumah/Kantor).
   - **Ketahanan Android 8+ (Anti-Crash):** `GeofenceBroadcastReceiver` memanggil `ContextCompat.startForegroundService()` alih-alih `startService()` biasa. Ini mencegah `IllegalStateException` saat OS membangunkan receiver di latar belakang saat HP tertidur/terkunci.
   - **Event Output:** `USER_ARRIVED_HOME`, `USER_LEFT_HOME`, `USER_ARRIVED_WORK`, `USER_LEFT_WORK`.

#### Sintesis Konteks Majemuk (`ContextEngine.kt` Rules):
ContextEngine melakukan kombinasi multi-signal secara real-time:
- **`ROOM_DARK_NIGHT`**: Terpemicu jika Light Sensor = `DARK` **DAN** Waktu Lokal = Malam Hari (18:00 - 06:00).
- **`PHONE_PICKUP_MORNING`**: Terpemicu jika `PHONE_PICKUP` terdeteksi pada pagi hari (05:00 - 09:00).
- **`WALKING_AT_NIGHT`**: Terpemicu jika `USER_MOVING` terdeteksi pada malam hari.

---

## 4. Matriks Akses Khusus Android (Privilege Matrix)

| Nama Izin / Special Access | Fungsi Fitur | Lokasi Aktivasi di Samsung One UI 8 |
|---|---|---|
| `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` | Intersep Alarm Dismiss (Samsung/Google Clock) | Settings > Special Access > Notification Access > Nexa Mobile Bridge |
| `android.permission.BIND_ACCESSIBILITY_SERVICE` | Screenshot Base64 & Navigasi Back/Recents | Settings > Accessibility > Installed Apps > Nexa Mobile Bridge |
| `android.permission.SYSTEM_ALERT_WINDOW` | Tampilan Overlay Interaktif | Settings > Special Access > Appear on top > Nexa Mobile Bridge |
| `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Mencegah OS Membunuh Service 24/7 | Settings > Apps > Nexa Mobile Bridge > Battery > Unrestricted |
| `android.permission.ACCESS_NOTIFICATION_POLICY` | Mengontrol Mode Do Not Disturb (DND) | Settings > Special Access > Do Not Disturb Permission |
| `android.permission.CAMERA` | Mengambil foto latar belakang (CameraX) | Prompt Izin Aplikasi (Standard Permission) |
| `android.permission.ACCESS_FINE_LOCATION` | Pelacakan Lokasi GPS & Alamat | Prompt Izin Aplikasi (Standard Permission) |
| `android.permission.ACCESS_BACKGROUND_LOCATION` | Pelacakan Geofence di Latar Belakang | Settings > Apps > Nexa Mobile Bridge > Permissions > Location > "Allow all the time" |
| `android.permission.ACTIVITY_RECOGNITION` | Membaca Step Counter & Significant Motion | Prompt Izin Aplikasi (Standard Permission) |

---

## 5. Model Schema Payload Protocol (JSON Exchange)

### A. Format Perintah dari Server (`NexaCommand`)
```json
{
  "type": "COMMAND",
  "command_id": "cmd_10023",
  "action": "SHOW_OVERLAY_MSG",
  "timestamp": 1785970000000,
  "signature": "a8f9c... (opsional untuk GodMode)",
  "params": {
    "message": "Konfirmasi tindakan keamanan",
    "options": [
      { "label": "BATAL", "action": "CANCEL", "color": "#FF5252" },
      { "label": "PROSES", "action": "CONFIRM", "color": "#00E676" }
    ]
  }
}
```

### B. Format Respon dari Client (`CommandResult`)
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_10023",
  "status": "SUCCESS",
  "message": "User selected: CONFIRM",
  "data": {
    "action": "CONFIRM"
  },
  "timestamp": 1785970005000
}
```

### C. Format Telemetri Periodik (`TelemetryReport`)
```json
{
  "type": "TELEMETRY",
  "battery_level": 85,
  "battery_charging": true,
  "network_type": "WIFI",
  "network_strength": -55,
  "motion_state": "STATIONARY",
  "screen_on": true,
  "timestamp": 1785970060000
}
```

### D. Format Context Report Real-Time (`ContextReport`)
```json
{
  "type": "CONTEXT_UPDATE",
  "event": "USER_ARRIVED_HOME",
  "summary": "Pengguna telah tiba di rumah",
  "details": {
    "geofence_id": "HOME",
    "transition": "ENTER"
  },
  "timestamp": 1785993934365
}
```

---

## 6. Riwayat Audit & Verifikasi Kinerja

- **Audit Concurrency & Thread-Safety:** LULUS. Seluruh mutable state (`isStopped`, `currentPhase`, `attemptInPhase`) terisolasi di coroutine scope atau ber-annotation `@Volatile`.
- **Audit Coroutine Leakage:** LULUS. `serviceScope.cancel()` dipanggil pada `onDestroy()`.
- **Audit UI Resiliency:** LULUS. Overlay parser dilengkapi fallback aman untuk semua tipe data string/JSON yang rusak.
- **Pengujian Terbukti (Live Tested & Verified):**
  1. *Morning Briefing Intercept* terbukti sukses 100% saat alarm Samsung di-dismiss.
  2. *Smart Reconnect* terbukti sukses melakukan backoff bertingkat dan menyuarakan error spesifik.
  3. *CameraX Transparent Activity* terbukti mengambil foto secara hening dan mengembalikan Base64 JPEG.
  4. *Sensor Hysteresis & Debounce:* Teruji via `test_context_engine_server.js` — fluktuasi cahaya sekitar 4-10 lux terisolasi tanpa event spam/jitter.
  5. *Pickup Gesture Samsung One UI:* Teruji mendeteksi gerakan angkat HP (`PHONE_PICKUP`) dengan fallback Hardware Sensor ID 25 secara presisi.
  6. *GPS Geofencing Integration:* Teruji via `GeofenceManager` & `GeofenceBroadcastReceiver` terkompilasi bersih dan aman dari background service crash di Android 8+ (One UI 8).

---

## 7. Ekstensi "Hands & Eyes" (UI Inspector, Accessibility Controller & Agentic Loop)

### 7.1. Konsep Utama: "Bridge adalah Tangan & Mata Nexa"
Sesuai filosofi arsitektur *Thin-Client Executive Bridge*, Android Bridge diperluas dengan kemampuan persepsi UI (*Mata*) dan eksekusi aksi manusia (*Tangan*) tanpa membengkakkan aplikasi Android dengan AI.

```
[ SERVER N.E.X.A (Agentic LLM Loop) ]
       │                               ▲
       │ 1. EXECUTE_COMMAND            │ 2. COMMAND_RESULT / UI JSON
       ▼                               │
┌─────────────────────────────────────────────┐
│ NEXA MOBILE BRIDGE (Android Accessibility)  │
│  ├── 👁️ Mata: DUMP_UI_HIERARCHY             │
│  ├── 🖐️ Tangan: ACCESSIBILITY_CLICK (x, y)  │
│  ├── ⌨️ Ketik: ACCESSIBILITY_INPUT_TEXT      │
│  ├── 📋 Klip: GET / SET_CLIPBOARD           │
│  └── 🚀 Intent: OPEN_INTENT (URL/Geo/Share) │
└─────────────────────────────────────────────┘
```

### 7.2. Perintah Baru "Hands & Eyes"
| Action Name | Handler / Service | Deskripsi & Kemampuan Hardware | Parameter Input / Output |
|---|---|---|---|
| `DUMP_UI_HIERARCHY` | `NexaAccessibilityService` | **Mata (UI Inspector):** Memindai seluruh struktur elemen UI di layar aktif HP menjadi hierarki JSON (Node, Bounds, ID, Text, Class, Clickable, Editable). | `data.nodes: Array<UiNode>` |
| `ACCESSIBILITY_CLICK` | `NexaAccessibilityService` | **Tangan (Click Controller):** Mengeklik elemen layar berdasarkan target ID/Text atau koordinat piksel presisi `(x, y)` via `dispatchGesture`. | `type: "coordinate"\|"node"`, `x`, `y`, `target` |
| `ACCESSIBILITY_INPUT_TEXT` | `NexaAccessibilityService` | **Ketukan Teks (Input Engine):** Memasukkan teks secara otomatis ke form input yang sedang aktif atau berdasarkan target Node (`ACTION_SET_TEXT`). | `text: String`, `target: String?` |
| `ACCESSIBILITY_SCROLL` | `NexaAccessibilityService` | **Usap Layar (Scroll Controller):** Melakukan gulir layar ke atas atau ke bawah (`ACTION_SCROLL_FORWARD` / `BACKWARD`). | `direction: "FORWARD"\|"BACKWARD"` |
| `GET_CLIPBOARD` / `SET_CLIPBOARD` | `DeviceCommandDispatcher` | **Clipboard Engine:** Membaca atau menulis teks papan klip (*clipboard*) sistem Android. | `text: String` |
| `OPEN_INTENT` | `AppLauncherHandler` | **Intent Engine:** Membuka URL web (e.g. `google.com`), Google Maps (`geo:lat,lng`), atau Share Text. | `type: "OPEN_URL"\|"OPEN_MAPS"\|"SHARE_TEXT"`, `url`, `text` |

### 7.3. Audit Kinerja & Hardening Keamanan Aksesibilitas
1. **Pencegahan Memory Leak Native (`AccessibilityNodeInfo`):**
   Pada traversal rekursif UI tree (`traverseNode`), setiap child `AccessibilityNodeInfo` dipastikan dipanggil `.recycle()` untuk mencegah kebocoran memori native OS.
2. **Gesture Stroke Fixing (0-Length Path Protection):**
   `clickCoordinates(x, y)` mematenkan path stroke minimal dengan `lineTo(x + 1f, y + 1f)` agar gesture click tidak pernah ditolak Android OS akibat koordinat awal dan akhir identik (length = 0).
3. **Penyelarasan Izin XML Manifest:**
   Konfigurasi `accessibility_service_config.xml` dilengkapi dengan `android:canPerformGestures="true"` agar izin gestur tingkat sistem diaktifkan penuh.
4. **Resiliensi CountDownLatch Callback:**
   Panggilan `dispatchGesture` menjamin penanganan status gagal (*resultCallback.onCancelled*) agar `CountDownLatch` tidak pernah dead-lock atau memicu timeout service.

### 7.4. Pengujian Real-Time Agentic Loop (Cerebras Gemma 4 31B & Key Rotation)
- **Integrasi LLM Terbaru:** Teruji menggunakan model ultra-cepat terbaru **Cerebras Gemma 4 31B (`gemma-4-31b`)** dalam arsitektur *ReAct Loop* (Reasoning + Acting).
- **Mekanisme API Key Rotation (1-2-3-4):** Mengatasi pembatasan ketat Rate Limit (RPM / Requests Per Minute) pada API Cerebras gratis dengan memutar 4 buah API Key secara berurutan (`CEREBRAS_API_KEY_1` s/d `4`) pada setiap giliran berpikir LLM (`currentKeyIndex = (currentKeyIndex + 1) % 4`).
- **Auto-Retry & Resiliensi Server Load:** Ditambahkan penanganan otomatis fallback saat server mengalami *high traffic / queue_exceeded* dengan jeda 2 detik tanpa memutuskan siklus agent.
- **Analisis Kebutuhan Vision LLM:** Pengujian membuktikan bahwa LLM Teks mencoba menebak atribut ID HTML (seperti `q`, `google-search-input`) saat berada di dalam WebView. Untuk navigasi UI presisi 100%, sistem disiapkan untuk dipasangkan dengan Vision LLM untuk mengekstrak koordinat spasial `(x, y)`.

---

## 8. Fitur & Arsitektur Call Interaction v2.0 (Bidirectional Voice Interruption & VoiceRecorder Engine)

### 8.1. Filosofi Penciptaan & Interupsi Keabaikan ("Good Distraction Interruption")
Fitur **Panggilan Masuk Interaktif N.E.X.A (Jarvis-Level Call Flow)** diciptakan berdasarkan motivasi utama pengguna: menghadirkan asisten pribadi yang memiliki otoritas untuk memecah distraksi pengguna demi kebaikan (misalnya: memutus doom-scrolling, mengingatkan waktu fokus/ibadah, atau menegur secara lisan melalui antarmuka telepon penuh).

### 8.2. Arsitektur 6-State Machine (`FakeCallActivity.kt`)
Antarmuka panggilan dibangun menggunakan Jetpack Compose dengan arsitektur State Machine 6 tahap yang sangat tangguh:

```
┌───────────┐     Swipe Hijau     ┌───────────────────┐               ┌─────────────────┐
│  RINGING  │ ──────────────────► │ ACCEPTED_SPEAKING │ ────────────► │    RECORDING    │
└───────────┘                     └───────────────────┘  TTS Selesai  └─────────────────┘
      │                                                                        │
      │ Swipe Merah                                                            │ 10 Detik
      ▼                                                                        ▼
┌───────────────────┐                                                 ┌─────────────────┐
│ REJECTED_SPEAKING │                                                 │  WAITING_REPLY  │
└───────────────────┘                                                 └─────────────────┘
      │                                                                        │
      │ TTS Selesai                                                            │ PLAY_AUDIO_STREAM
      └───────────────────────────► ┌───────────┐ ◄────────────────────────────┘
                                    │ FINISHED  │ (Activity Finish)
                                    └───────────┘
```

1. **`RINGING`:** Layar panggilan melayang bertema *Dark Navy / Neon Cyan* (`#0A0F24`) dengan getaran (*haptic feedback*) dan ringtone suara. Tombol swipe cyan (Angkat) & merah (Tolak).
2. **`ACCEPTED_SPEAKING`:** Mengirim `CALL_ACCEPTED` ke server. Tombol hilang, N.E.X.A membacakan pesan pembuka lisan via `TtsHandler`.
3. **`RECORDING`:** Mikrofon aktif merekam suara pengguna 10 detik (`VoiceRecorderHandler`) dengan timer `00:10` -> `00:00`.
4. **`WAITING_REPLY`:** Tampilan memunculkan spinner "MENUNGGU BALASAN N.E.X.A" sembari mengkripsi suara Base64 (128.000 Bytes) lalu mengirimkannya via WebSocket (`CALL_AUDIO_REPLY`).
5. **`REJECTED_SPEAKING`:** Mengirim `CALL_REJECTED` beserta `rejection_count`. N.E.X.A membaca kalimat perpisahan singkat. Server dapat menilai tingkat urgensi untuk memutuskan apakah akan menelpon ulang atau mentolerir penolakan.
6. **`FINISHED`:** Layar ditutup secara bersih via `BroadcastReceiver` yang menerima sinyal internal `CALL_REPLY_COMPLETE` atau timeout.

### 8.3. Komponen General-Purpose `VoiceRecorderHandler.kt`
Modul perekam suara dirancang terpisah dan **100% Reusable** untuk seluruh kebutuhan perekaman audio N.E.X.A (Panggilan, Voice Command, Voice Memo, Ambient Capture):
- **Spesifikasi Format:** Mono 16-bit PCM pada sampel rate 16kHz (`AudioFormat.ENCODING_PCM_16BIT`, `AudioFormat.CHANNEL_IN_MONO`). Ini adalah standar universal API STT (Whisper, Google STT, Azure).
- **Parameter Fleksibel:** `record(durationMs = 10_000L, onTickMs = { elapsed -> ... })`.

### 8.4. Pengerasan Sistem & Perbaikan Bug (Hardening)
1. **Pencegahan Echo Gema TTS (700ms Silence Gap):**
   - *Problem:* Saat N.E.X.A selesai bicara via TTS dan mikrofon langsung dibuka, sisa gema speaker HP terrekam oleh `AudioRecord` dan salah ditranskripsikan oleh Whisper menjadi kata "So".
   - *Solusi:* Menambahkan `delay(700L)` antara akhir TTS dan pembukaan mikrofon di `FakeCallActivity.kt` agar sesi audio bersih dari gema.
2. **Perbaikan Serialisasi JSON (`CallEvent.kt`):**
   - *Problem:* Default value `val type: String = "CALL_EVENT"` membuat `kotlinx.serialization` menghilangkan field `type` dalam string JSON.
   - *Solusi:* Menghapus default value agar field `"type": "CALL_EVENT"` dipaksa terkirim eksplisit dalam setiap payload WebSocket.

### 8.5. Spesifikasi Lengkap Protokol JSON Call Interaction v2.0

#### A. Inbound (Server ➔ Nexa Bridge HP)
- **`SIMULATE_INCOMING_CALL`:**
  ```json
  {
    "type": "EXECUTE_COMMAND",
    "command_id": "cmd_call_1786003061219",
    "action": "SIMULATE_INCOMING_CALL",
    "params": {
      "caller_name": "N.E.X.A Assistant",
      "message": "Tuan Faqih, waktu fokus Anda telah tiba. Apakah ada tanggapan?",
      "play_ringtone": true
    }
  }
  ```
- **`PLAY_AUDIO_STREAM`:**
  ```json
  {
    "type": "EXECUTE_COMMAND",
    "command_id": "cmd_reply_1786003065000",
    "action": "PLAY_AUDIO_STREAM",
    "params": {
      "audio_base64": "",
      "audio_format": "PCM_16BIT_16KHZ_MONO"
    }
  }
  ```

#### B. Outbound (Nexa Bridge HP ➔ Server)
- **`CALL_ACCEPTED`:**
  ```json
  {
    "type": "CALL_EVENT",
    "event": "CALL_ACCEPTED",
    "command_id": "cmd_call_1786003061219",
    "caller_name": "N.E.X.A Assistant",
    "device_name": "Samsung_A33_5G",
    "timestamp": 1786003062000
  }
  ```
- **`CALL_REJECTED`:**
  ```json
  {
    "type": "CALL_EVENT",
    "event": "CALL_REJECTED",
    "command_id": "cmd_call_1786003061219",
    "caller_name": "N.E.X.A Assistant",
    "rejection_count": 1,
    "device_name": "Samsung_A33_5G",
    "timestamp": 1786003062500
  }
  ```
- **`CALL_AUDIO_REPLY`:**
  ```json
  {
    "type": "CALL_EVENT",
    "event": "CALL_AUDIO_REPLY",
    "command_id": "cmd_call_1786003061219",
    "caller_name": "N.E.X.A Assistant",
    "audio_base64": "UklGRi... (String Base64 PCM 16kHz 16-bit Mono 128.000 Bytes)",
    "audio_format": "PCM_16BIT_16KHZ_MONO",
    "device_name": "Samsung_A33_5G",
    "timestamp": 1786003073000
  }
  ```

---
*Dokumentasi Lengkap Ekosistem N.E.X.A Assistant (Update Terakhir: 2026).*
