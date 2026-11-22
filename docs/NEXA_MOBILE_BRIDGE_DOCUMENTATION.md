# N.E.X.A Mobile Bridge - Complete Architecture, Screen & System Documentation

| Atribut Proyek | Keterangan |
|---|---|
| **Versi Dokumen** | 3.1 (Complete Self-Contained Architecture, Screen Guide & Protocol Reference - 22 Nov 2026) |
| **Perangkat Target** | Samsung Galaxy A33 5G (`SM-A336E`), Android 16 (API 36), One UI 8.0 (`arm64-v8a`, `PAGE_SIZE = 4096`) |
| **Package ID & App** | `com.nexa.mobilebridge` (`compileSdk 36`, `minSdk 36`, `targetSdk 36`, JVM 17) |
| **Workspace Klien** | `c:\workspace\nexa-mobile-bridge` |
| **Pasangan Server** | `c:\workspace\nexa-server` (`src/interfaces/bridge/mobile_bridge/*`, Node.js) + `nexa-io` (Go Sidecar) |
| **Dokumen Terkait** | [RAM_USAGE_AUDIT_REPORT.md](./RAM_USAGE_AUDIT_REPORT.md), [JSON_PROTOCOL_SPECIFICATION.md](./JSON_PROTOCOL_SPECIFICATION.md), [MOBILE_BRIDGE_TESTING_REPORT.md](./MOBILE_BRIDGE_TESTING_REPORT.md) |

---

## Daftar Isi

1. [Ringkasan Eksekutif & Gambaran Besar Ekosistem](#1-ringkasan-eksekutif--gambaran-besar-ekosistem)
2. [Diagram Arsitektur End-to-End (Android <-> VPS <-> AI Core)](#2-diagram-arsitektur-end-to-end)
3. [Struktur Lengkap Kode Sumber & Tanggung Jawab Tiap Paket](#3-struktur-lengkap-kode-sumber--tanggung-jawab-tiap-paket)
4. [Sistem Navigasi, Tema OLED & Desain UI](#4-sistem-navigasi-tema-oled--desain-ui)
5. [Panduan Lengkap Seluruh Halaman & Layar Aplikasi (8 Layar/Modal)](#5-panduan-lengkap-seluruh-halaman--layar-aplikasi)
6. [Ekosistem Chat AI Native, Voice Note, Slash Commands & 12 Kartu Interaktif](#6-ekosistem-chat-ai-native-voice-note-slash-commands--12-kartu-interaktif)
7. [Sistem Notifikasi Percakapan, Inline Reply & Supresi Cerdas](#7-sistem-notifikasi-percakapan-inline-reply--supresi-cerdas)
8. [Arsitektur Keamanan, Kriptografi Keystore & Biometrik](#8-arsitektur-keamanan-kriptografi-keystore--biometrik)
9. [Protokol Komunikasi Dual-WebSocket (Koneksi A `/ws` vs Koneksi B `/io/*`)](#9-protokol-komunikasi-dual-websocket)
10. [Layer Data & Persistensi (Room Database, Paging 3 & Encrypted DataStore)](#10-layer-data--persistensi)
11. [Mesin Latar Belakang, Hardware Dispatcher, Live Voice & Screen Share](#11-mesin-latar-belakang-hardware-dispatcher-live-voice--screen-share)
12. [Manajemen Daya Baterai, Reconnect 4-Fase & Standby UI Hibernation (RAM ~50 MB)](#12-manajemen-daya-baterai-reconnect-4-fase--standby-ui-hibernation)
13. [Integrasi Sisi Server (`nexa-server` & Go Sidecar `nexa-io`)](#13-integrasi-sisi-server)
14. [Panduan Build, Unit Test, ADB Deploy & Verifikasi](#14-panduan-build-unit-test-adb-deploy--verifikasi)

---

## 1. Ringkasan Eksekutif & Gambaran Besar Ekosistem

**N.E.X.A Mobile Bridge** adalah **Pusat Kontrol AI Mandiri (Native AI Control Center)** untuk pemilik tunggal di smartphone Android yang terhubung langsung 24/7 ke server pribadi **N.E.X.A Server**.

Jika sebelumnya aplikasi ini hanya berfungsi sebagai jembatan hardware (*thin-client* untuk perintah senter/lokasi/TTS sementara obrolan utama lewat Telegram), arsitektur versi 3.x menggabungkan **empat peran dalam satu aplikasi native Android**:

1. **Native AI Chat & Financial Control Center:** Percakapan teks dan pesan suara (*voice note*) dua arah langsung ke otak N.E.X.A, lengkap dengan riwayat offline Room DB, 12 jenis kartu interaktif native (terutama kartu struk transaksi `FINANCE_TX` dengan picker & edit dialog tanpa mengetik ulang), palette perintah `/`, serta konfirmasi biometrik (*step-up*) untuk aksi keuangan sensitif.
2. **Executive Hardware & Sensor Bridge:** Mengeksekusi perintah fisik dari AI server secara real-time di HP (senter, kamera hening, GPS/geofencing, TTS suara Indonesia, volume/DND, pemutar alarm darurat, overlay dialog, hingga Live Voice full-duplex dan MediaProjection screen sharing).
3. **On-Demand VPS Observability Console:** Memantau log journald/server secara live (`LogViewerScreen`) dan mengakses shell Linux interaktif (`TerminalScreen` VT100/xterm-256color) langsung dari HP dengan keamanan biometrik dan jejak memori nol di server saat layar ditutup.
4. **Banking-Safe & Zero-WebView Architecture:**
   - **Tanpa `AccessibilityService` (`FR-CORE-03`):** Seluruh modul aksesibilitas telah dihapus dan digantikan oleh `UsageStatsManager` (`AppUsageTracker.kt`) dan `MediaProjection` (`ScreenCaptureService.kt` yang patuh `FLAG_SECURE`), agar aplikasi M-Banking (BCA, Mandiri, BNI, dll.) tetap aman berjalan di perangkat yang sama.
   - **Tanpa WebView/Chromium:** Seluruh UI Chat, Kartu, Logs, dan Terminal menggunakan Jetpack Compose + Canvas native agar dingin di Exynos 1280 dan ringan di RAM.

---

## 2. Diagram Arsitektur End-to-End

```text
+----------------------------------------------------------------------------------------+
|                       SAMSUNG GALAXY A33 5G (com.nexa.mobilebridge)                    |
|                                                                                        |
|  [LAPIS 2: UI LAYER - Hibernasi saat Idle di Home/Layar Mati -> Views=0, GPU ~1 MB]   |
|   ├── MainActivity.kt (Single Activity Compose Host + Standby Hibernation Controller)   |
|   └── Navigation.kt (MainShell[Chat] -> Settings / BridgeStatus[HUD] / Logs / Shell)   |
|                                                                                        |
|  [LAPIS 1: CORE ENGINE 24/7 - Tetap Hidup di Background ~50,0 MB RAM Fisik]           |
|   ├── NexaBridgeService.kt (FGS: connectedDevice|specialUse|microphone, START_STICKY)  |
|   ├── @Singleton NexaWebSocketClient (Koneksi A /ws: JSON + Binary Live Voice PCM)     |
|   ├── @Singleton DeviceCommandDispatcher (25+ Hardware, Sensor, Geofence & Call Action)|
|   ├── @Singleton ChatRepository + NexaDatabase (Room SQLite Offline-First)             |
|   ├── @Singleton ChatNotificationManager + ActionReceiver (Direct Reply & Alert)       |
|   ├── @Singleton TicketAuthRepository + DeviceKeystoreManager (ECDSA P-256 Biometrik)  |
|   └── Layar/Service Mandiri: FakeCallActivity, TransparentCamera, Overlay, ScreenShare |
+------------------------------------+-------------------------------+-------------------+
                                     | WSS /ws (Selalu Aktif)        | WSS /io/logs & /io/term
                                     | JSON Text + Live PCM          | (On-Demand + Tiket Ed25519)
                                     v                               v
+----------------------------------------------------------------------------------------+
|                        AZURE UBUNTU VPS (Caddy TLS 1.3 Reverse Proxy)                  |
|                                                                                        |
|  Caddy (:443) --+-- /ws       --> nexa-server (Node.js CommonJS, port 3000)            |
|                 |                  ├── mobile_bridge/client.js    (WS lifecycle)       |
|                 |                  ├── mobile_bridge/chat_handler.js (Chat/Voice/Auth) |
|                 |                  ├── mobile_bridge/card_handler.js (12 Cards RPC)  |
|                 |                  ├── mobile_bridge/adapter.js   (Device RPC)         |
|                 |                  └── AI_Router + Domain Engines (Finance/Cal/Task)   |
|                 |                                                                      |
|                 +-- /io/logs  --> /run/nexa-log.sock  (Go Sidecar: nexa-io --mode=logs)|
|                 +-- /io/term  --> /run/nexa-term.sock (Go Sidecar: nexa-io --mode=term)|
+----------------------------------------------------------------------------------------+
```

---

## 3. Struktur Lengkap Kode Sumber & Tanggung Jawab Tiap Paket

Seluruh kode Kotlin berada di bawah `app/src/main/java/com/nexa/mobilebridge/` dengan Hilt sebagai Dependency Injection:

### 3.1. Root Components
- `NexaBridgeApp.kt`: Kelas `Application` beranotasi `@HiltAndroidApp` sebagai akar DI graph singleton.
- `MainActivity.kt`: Host tunggal Compose (`FragmentActivity` agar kompatibel dengan `BiometricPrompt`), edge-to-edge UI, auto-start `NexaBridgeService`, penangan deep-link notifikasi (`EXTRA_OPEN_TAB`), dan pengontrol **Standby UI Hibernation**.
- `Navigation.kt` & `NavigationKeys.kt`: Router `NavDisplay` (AndroidX Navigation 3) untuk 5 rute utama (`MainShell`, `BridgeStatus`, `Settings`, `ServerLogs`, `RemoteTerminal`).
- `NexaBridgeService.kt`: `ForegroundService` (`START_STICKY`) yang memegang siklus hidup WS-A, `launchCommandConsumer`, `launchTelemetryReporter`, `launchPhaseFailureListener`, `SensorObserverManager`, `ContextEngine`, `GeofenceManager`, `NetworkWatcher`, dan `alertTts`.
- `TransparentCameraActivity.kt`: Activity transparan tanpa UI yang dipanggil oleh `CameraHandler` untuk memotret kamera depan/belakang via CameraX secara hening dan menutup diri.

### 3.2. `core/` (Mesin Infrastruktur, Jaringan, Daya & Keamanan)
| Sub-paket | File & Peran Teknis |
|---|---|
| `core/auth/` | `TicketAuthRepository.kt`: Mengelola alur `TICKET_REQ -> CHALLENGE -> Biometric Keystore Sign -> CHALLENGE_RESP -> TICKET` (Ed25519 scope `logs`/`term`, mendukung `resumeSid`). |
| `core/context/` | `ContextEngine.kt`: Mengolah event sensor & transisi geofence menjadi `ContextReport` terstruktur untuk server. |
| `core/di/` | `AppModule.kt` (menyediakan `DataStore<Preferences>`, `NexaPreferencesDataStore`, Room `NexaDatabase`, `ChatMessageDao`) dan `NetworkModule.kt` (menyediakan 2 `OkHttpClient`: client `/ws` dengan ping 60s, dan client `@Named("sidecar")` `/io/*` tanpa ping otomatis agar tidak membangunkan modem seluler saat pause). |
| `core/network/` | `NexaWebSocketClient.kt` (antarmuka tunggal WS-A dengan `Channel` FIFO terpisah untuk `chat`, `auth`, `command`, serta listener Live Voice) & `ReconnectOrchestrator.kt` (state machine retry 4 fase). |
| `core/power/` | `BridgePowerLockManager.kt`: Pengelola `PARTIAL_WAKE_LOCK` (`NexaBridge::ActiveTransferWakeLock`) & `WIFI_MODE_FULL_LOW_LATENCY` berhitung referensi (on-demand maks 15 detik/command dan 30 menit/panggilan). |
| `core/receiver/` | `BootCompletedReceiver.kt`: Menangkap `BOOT_COMPLETED` / `QUICKBOOT_POWERON` untuk menyalakan `NexaBridgeService` pasca-reboot. |
| `core/security/` | `DeviceKeystoreManager.kt` (ECDSA P-256 `secp256r1`), `BiometricAuthHelper.kt` (`BIOMETRIC_STRONG`), `SecretKeyStoreCipher.kt` (enkripsi AES-GCM token/HMAC di DataStore), `HmacVerifier.kt` (validasi perintah GodMode), `WindowSecureEffect.kt` (pengendali `FLAG_SECURE` berhitung referensi). |
| `core/sensor/` | `GeofenceManager.kt`, `GeofenceModel.kt`, `GeofenceBroadcastReceiver.kt`, `SensorObserverManager.kt`. |
| `core/usage/` | `AppUsageTracker.kt`: Pemeriksa sesi aplikasi aktif lewat `UsageStatsManager.queryEvents()` (aman M-Banking) untuk deteksi fokus/distraksi. |
| `core/util/` | `ConnectionState.kt`: Sealed hierarchy (`Connected`, `Connecting`, `Reconnecting(phase, attempt)`, `AuthFailed`, `Disconnected`). |

### 3.3. `data/` (Repositori Offline-First, Database & Protokol Biner)
- `data/chat/`:
  - `ChatRepository.kt`: Otak sisi klien untuk Chat (kirim teks `CHAT_SEND`, voice `CHAT_VOICE_SEND`, `CARD_ACTION`, `CHAT_STOP`, `CHAT_RESUME`, `PROACTIVE_CATCHUP`, `CHAT_READ_ACK`, otorisasi `ACTION_AUTH`/`ACTION_CANCEL`, antrean offline `SENDING`, dan notifikasi proaktif).
  - `ChatStreamConsumer.kt`: Konsumer token per-stream dengan *batching* ~33 ms (~30 fps UI), checkpoint ke Room tiap 5 detik, dan finalisasi di blok `NonCancellable`.
  - `ChatVisibilityTracker.kt`: Pelacak apakah aplikasi di foreground (`_isAppForeground`) dan tab mana yang sedang terlihat (`_visibleTab`), penghitung `unreadCount`, serta pemicu deep-link tab.
  - `InteractiveCardModels.kt`: Codec (`InteractiveCardCodec`) dan model data lengkap untuk 12 tipe kartu interaktif.
  - `VoiceNoteRecorder.kt`, `VoiceNotePlayer.kt`, `VoiceNoteMetadata.kt`, `ChatCopyText.kt`.
- `data/local/` & `data/local/chat/`:
  - `NexaPreferencesDataStore.kt`, `NexaDatabase.kt`, `ChatMessageEntity.kt`, `ChatMessageDao.kt`.
- `data/logs/`:
  - `CircularLogBuffer.kt`, `LogBinaryProtocol.kt`, `SidecarLogsClient.kt`.
- `data/terminal/`:
  - `Vt100TerminalEmulator.kt`, `TerminalBinaryProtocol.kt`, `SidecarTerminalClient.kt`, `ModifierState.kt`.

### 3.4. `dispatcher/`, `notification/`, `protocol/`, `service/`, `theme/`, & `ui/`
- `dispatcher/`: `DeviceCommandDispatcher.kt` beserta 16+ handler modular di `dispatcher/handler/`.
- `notification/`: `ChatNotificationManager.kt` & `ChatNotificationActionReceiver.kt`.
- `protocol/`: Model Kotlin Serializable untuk `NexaCommand`, `CommandResult`, `NexaActions`, `TelemetryReport`, `ContextReport`, `CallEvent`.
- `service/`: `NexaNotifListenerService.kt`, `ScreenCaptureService.kt`, `FloatingOverlayManager.kt`, `FloatingLifecycleOwner.kt`.
- `theme/`: `Color.kt`, `Theme.kt`, `Type.kt`.
- `ui/`: `call/`, `chat/`, `components/`, `hud/`, `logs/`, `overlay/`, `settings/`, `terminal/`.

---

## 4. Sistem Navigasi, Tema OLED & Desain UI

### 4.1. Peta Rute Navigasi (`Navigation.kt` & `NavigationKeys.kt`)
Aplikasi menggunakan **Chat-First Single Top Bar** tanpa Bottom Navigation Bar agar area obrolan bersih dan maksimal untuk konten:

```text
[MainShell: ChatScreen]  <-- Root Abadi (hanya halaman ini yang selalu ada di dasar stack)
   ├── Tap ikon Activity/HUD atau dari Settings  --> [BridgeStatus: HudScreen]
   ├── Tap ikon Gear / Settings                   --> [Settings: SettingsScreen]
   │      ├── Diagnostics -> Bridge Status        --> [BridgeStatus] (Stack: [MainShell, BridgeStatus])
   │      ├── Diagnostics -> Server Logs          --> [ServerLogs: LogViewerScreen]
   │      └── Diagnostics -> Remote Terminal      --> [RemoteTerminal: TerminalScreen]
   └── Dari ServerLogs / RemoteTerminal, tap Gear --> [Settings] ditumpuk sementara di atasnya
       (menekan Back dari Settings langsung kembali ke Terminal/Logs tanpa memutus sesi PTY)
```

### 4.2. Disiplin Desain & Tema (`theme/Color.kt`, `Theme.kt`, `Type.kt`)
- **Mode Gelap OLED (Default):** Latar utama `#0A0E14`, bubble asisten `#111720`, sub-card gelap `#0B111A`, elevasi `#1A2232`, garis tepi tegas `1 dp` (`cardBorder`) menggantikan shadow berat agar tidak mengalokasikan *offscreen blur buffer* di GPU.
- **Mode Terang & Sistem:** Mendukung `ThemeMode.DARK`, `ThemeMode.LIGHT`, dan `ThemeMode.SYSTEM`.
- **Pilihan Aksen Warna:** 4 preset (`Cyan`, `Emerald`, `Amber`, `Violet`) yang diterapkan secara dinamis dan konsisten pada tombol utama, ikon aktif, dan indikator status.
- **Empat Warna Semantik:**
  - `Primary Accent` (default Cyan): Aksi utama / Transfer / Identitas aktif.
  - `Emerald`: Sukses / Pemasukan / Terhubung TLS / Recorded / On Track.
  - `Amber`: Menunggu input wajib (e.g. akun belum dipilih) / Warning / Paused / Step-Up Biometrik.
  - `Crimson`: Error / Pengeluaran / Over Limit / Destructive Action (Cancel/Stop/Delete).
- **Tipografi Monospace (`NexaMonoTypography`):** Khusus dipakai untuk nominal uang, countdown auto-save, log konsol, terminal PTY, fingerprint SHA-256, dan metadata kode.
- **Bahasa Antarmuka (Full English UI Chrome):** Seluruh label kontrol UI Android (tombol, judul kartu, status badge, pengaturan) memakai Bahasa Inggris ringkas agar modern dan konsisten, sementara percakapan N.E.X.A dan narasi balasan menggunakan Bahasa Indonesia yang natural.

---

## 5. Panduan Lengkap Seluruh Halaman & Layar Aplikasi

Bagian ini menjelaskan secara rinci setiap halaman/layar yang dilihat pengguna ketika memakai aplikasi.

### 5.1. Halaman 1: Chat AI Native (`MainShell` - `ChatScreen.kt` + `NexaChrome.kt`)
Merupakan layar utama aplikasi saat dibuka.
- **Top AppBar (`NexaTopBar`):**
  - Kiri: Kapsul mata ekspresif OLED **`NexaExpressiveEyes`** (bereaksi terhadap state `IDLE`, `LISTENING`, `THINKING`, `STREAMING`, `STEP_UP`, `ALERT`, serta memantulkan level suara live). Animasi mata otomatis berhenti saat UI di background (`Lifecycle < STARTED`).
  - Tengah: Judul **"N.E.X.A"** dan status koneksi live (titik warna + teks *Connected*, *Listening...*, *Connecting*, *Offline*). Tap area ini membuka **Bridge Status (HUD)**.
  - Kanan: Tombol cepat **Live Voice Call** (ikon gagang telepon) dan tombol **Settings** (ikon roda gigi).
  - Saat mode seleksi pesan aktif, Top AppBar otomatis berganti menjadi **`ChatSelectionTopBar`** dengan jumlah pesan terpilih serta tombol **Reply**, **Copy**, dan **Delete**.
- **Daftar Pesan (`LazyColumn` dengan `reverseLayout = true`):**
  - Balon kanan (User): mendukung teks, preview kutipan reply, dan **Voice Note Player** (tombol play/pause, waveform 48-bar interaktif seekable, durasi, jam, dan tanda centang status `SENDING`/`SENT`/`FAILED` dengan tombol retry bila gagal).
  - Balon kiri (Asisten N.E.X.A): mendukung `IncrementalMarkdownText` (hanya me-render ulang blok markdown terakhir saat streaming berlangsung agar bebas jank), highlight angka Rupiah, status `"Response interrupted"` jika terputus, dan tombol **Read Aloud (TTS)** / menu kontekstual (Reply, Copy, Delete, Speak).
  - Jika pesan membawa kartu interaktif (`CARD`, `SUMMARY_CARD`, `CHIPS`, `INLINE_CARD`), UI kartu native dirender langsung menyatu di dalam bubble tanpa kotak bingkai ganda.
- **Area Input Bawah (`ChatInputBar`):**
  - Kolom teks adaptif (*multiline*) yang otomatis memunculkan floating **`SlashCommandPalette`** ketika mengetik `/`, ditambah banner **Reply Quote** jika sedang membalas pesan tertentu.
  - Kanan input: tombol rekam **Voice Note** (tekan-tahan atau kunci rekam, dengan bar timer live & tombol batal/kirim) atau tombol **Kirim**, serta tombol **Stop Streaming** saat N.E.X.A sedang menjawab.
- **Modal & Sheet Di Dalam Chat:**
  - `FinanceAccountPickerSheet`, `FinanceMethodPickerSheet`, `FinanceCategoryPickerSheet`, `FinanceEditTransactionDialog`, `VaultMetadataEditDialog`, `IdentityRejectReasonDialog`, dan **`FinanceStepUpBottomSheet`** (dilindungi `FLAG_SECURE`).

### 5.2. Halaman 2: Bridge Status / HUD (`BridgeStatus` - `HudScreen.kt` + `HudViewModel.kt`)
Layar diagnostik kesehatan jembatan perangkat keras & layanan background, diakses dengan mengetuk header status di Chat atau melalui *Settings -> Diagnostics -> Bridge Status*.
- **Dual Connection Cards (Berdampingan):**
  - **Core Link (`/ws`):** Menampilkan status koneksi (`Connected · TLS`, `Connecting`, `Retry #n`, `Disconnected`, `Auth failed`), host domain server yang disamarkan rapi, dan jenis jaringan (`Via Wi-Fi` / `Cellular` / `Offline`). Jika terputus saat service menyala, muncul tombol `Reconnect`.
  - **Inspection Link (`/io`):** Menampilkan status live apakah sidecar Logs/Terminal sedang aktif atau dalam mode hemat `Idle (on-demand)`.
- **Grid Telemetri Hardware & Daya 2x2:** Menampilkan angka riil Galaxy A33 tanpa metrik fiktif: level & status charging baterai, status interaktif layar (`Screen ON/OFF`), status `PowerLock` on-demand (`Idle` vs aktif saat transfer/call), dan status Geofence / tipe jaringan.
- **Permissions & Banking-Safe Hygiene Strip:** Menampilkan status izin `Usage Access` (`UsageStatsManager`), `Notification Access` (`NexaNotifListenerService`), serta shortcut cepat ke Pengaturan Sistem Android bila ada izin penting yang belum diberikan.
- **Device Activity (`CommandHistoryCard`):** Daftar 20 eksekusi perintah terbaru dari server beserta durasi (`ms`) dan hasil status. Perintah sensitif (`FINANCE`, `AUTH`, `OTP`, `CLIPBOARD`, `BANK`) otomatis disamarkan menjadi `[Redacted]`.
- **Kontrol Service (`BridgeServiceControlButton`):** Tombol untuk menyalakan `NexaBridgeService` atau menghentikannya dengan pengaman **Hold-to-Confirm 2 detik** (+ dialog konfirmasi) agar tidak pernah mati karena salah sentuh 1-tap.

### 5.3. Halaman 3: Settings (`Settings` - `SettingsScreen.kt` + `SettingsComponents.kt` + `SettingsSheetsAndDialogs.kt`)
Dibangun dengan `LazyColumn` (per grup seksi agar hemat memori GPU `GL mtrack`) dan dilindungi `FLAG_SECURE` pada build release. Tersusun dari atas ke bawah berdasarkan frekuensi penggunaan:
1. **Top Summary Card:** Status koneksi TLS saat ini (tap untuk ke HUD) & status kunci perangkat Keras (`Device key active · P-256` / `Not paired` / `Invalidated` -> tap untuk membuka `DeviceSecurityDetailSheet`). Bila kunci gugur (`INVALIDATED`), tampil banner merah untuk *Regenerate Hardware Key*.
2. **Appearance:** Pemilih tema (`System` / `Dark` / `Light`) dan 4 preset warna aksen (`Cyan`, `Emerald`, `Amber`, `Violet`), berlaku instan tanpa tombol Save.
3. **Notifications & Feedback:** Toggle `Haptic feedback`, pemilih **Chat notification tone** via Storage Access Framework (`OpenDocument` persistable URI + preview Play/Stop & Reset ke default), serta shortcut ke `System notification settings`.
4. **Server Connection (View-First, Terkunci Biometrik):** Menampilkan domain server (`WSS`), `Access token` bermasker (`Reveal 10s` wajib sidik jari), status `HMAC secret`, tombol **`Test connection`** (mengukur latency ping WS/reconnect langsung), dan tombol **`Edit configuration`** yang hanya terbuka setelah verifikasi sidik jari. Perubahan baru dapat disimpan melalui sticky bottom bar **`Apply (Fingerprint)`**.
5. **Device Security:** Ringkasan kunci Keystore & preview fingerprint, info autentikasi per aksi, serta **`Pair device via CLI` / `Re-pair device`** (`EnrollmentCliDialog`) untuk memasukkan kode enrollment dari `scripts/nexa-enroll.js` di server. Di dalam `DeviceSecurityDetailSheet`, tersedia fingerprint SHA-256 **32 oktet penuh** (4 baris x 8 oktet) beserta tombol salin.
6. **Diagnostics:** Pintu akses ke **Bridge Status (HUD)**, **Server Logs**, dan **Remote Terminal** (menampilkan status server kill switch Terminal aktif/nonaktif).
7. **Advanced:** Kontrol terpisah interval heartbeat saat layar menyala (`30s / 45s / 60s / 90s`) dan layar mati (`90s / 120s / 180s`), serta slider **Terminal font size (`8 sp - 16 sp`)** lengkap dengan preview konsol live.

### 5.4. Halaman 4: Server Log Monitor (`ServerLogs` - `LogViewerScreen.kt` + `LogViewerViewModel.kt`)
Konsol pemantau log VPS realtime *zero-footprint*:
- **Lifecycle Otomatis (`LifecycleStartEffect`):** Begitu layar muncul, meminta tiket biometrik `scope=logs` (bila belum ada tiket aktif) dan membuka koneksi B (`/io/logs`). Begitu keluar layar atau layar dimatikan (`ON_STOP`), stream seketika ditutup dan buffer dibersihkan.
- **Header & Kontrol:** Menampilkan badge status (`LIVE` / `PAUSED` / `AUTH`), indikator proteksi `FLAG_SECURE`, tombol Search (buka bar filter teks lokal secara instan tanpa ke server), tombol **Pause / Play** (bekerja lewat mekanisme tahan `CREDIT` protokol biner), dan tombol **Clear Local Buffer**. Tidak ada tombol Export demi keamanan.
- **Filter Chip Strip (Kirim Filter ke Server):** `ALL`, `FINANCE`, `BRIDGE`, `ERRORS ONLY` (mengirim ulang parameter `unit_mask`, `min_prio`, `cat_mask` ke `nexa-log`).
- **Tampilan Konsol Flat Monospace:** Daftar baris log dari `CircularLogBuffer` (maks 2.000 entri stabil per `stableId`) dengan pemisahan badge prioritas (`ERROR`, `WARN`, `INFO`, `DEBUG`), badge kategori (`FINANCE`, `BRIDGE`, `AUTH`, `SYNTHETIC`), deteksi user scroll-up untuk jeda auto-scroll dengan tombol floating *Jump to Bottom*, dan footer informatif (`Buffer: x / 2.000 baris · Dilewati: n · Berhenti otomatis saat layar mati`).

### 5.5. Halaman 5: Remote Interactive Terminal (`RemoteTerminal` - `TerminalScreen.kt` + `TerminalAccessoryBar.kt` + `TerminalInputSink.kt`)
Shell inspeksi SSH-less interaktif ke VPS sebagai user terbatas `nexa-term`:
- **Header Tunggal Ringkas 40 dp:** Menampilkan `nexa-term@vps`, status koneksi, tombol reconnect / disconnect (`⋮` dengan dialog konfirmasi), dan tombol Settings. Saat orientasi **Landscape**, masuk mode imersif ~100+ kolom.
- **Kanvas Terminal Native (`Vt100TerminalEmulator`):** Merender grid sel monospace berwarna ANSI 256 / TrueColor di atas Jetpack Compose `Canvas` dengan kursor aktif dan dukungan *vertical drag scrollback* hingga 1.000 baris.
- **`TerminalInputSink` Khusus Samsung Keyboard:** Input field tak terlihat (`1 dp`, alpha `0.01f`) menggunakan `KeyboardType.Password` + `autoCorrectEnabled = false` + sentinel `\u200B`, memastikan prediksi teks/swipe mati dan karakter kontrol (`Enter` -> `0x0D`, `Backspace` -> `0x7F`, `Tab` -> `0x09`, `CTRL+c` -> `0x03`) akurat.
- **Accessory Bar 2 Baris (`44x48 dp` per tombol):**
  - *Baris 1 (Tetap):* `[ESC]`, `[TAB]`, `[CTRL]`, `[ALT]`, `[←]`, `[↓]`, `[↑]`, `[→]` (mendukung *long-press key-repeat*), `[⋯]`.
  - *Baris 2 (Expandable `LazyRow`):* `[^C]`, `[^D]`, `[^Z]`, `[^L]`, `[-]`, `[/]`, `[|]`, `[~]`, `[$]`, `[*]`, `[Home]`, `[End]`, `[PgUp]`, `[PgDn]`.
  - Tombol `CTRL` & `ALT` berbagi `ModifierState` tunggal dengan 3 state visual: `IDLE`, `ONCE` (highlight border), dan `LOCKED` (solid fill + ikon gembok).
- **Pause/Resume Aman:** Saat layar dimatikan, `CREDIT` dihentikan agar PTY tidak membuang byte sementara bash ditahan di kernel; kembali dalam 120 detik otomatis mengirim frame `RESUME (0x09)` dengan `received_offset` dan `sid`.

### 5.6. Layar 6, 7 & 8: Layar Interupsi & Sistem Mandiri (`FakeCallActivity`, `OverlayActivity`, `TransparentCameraActivity`)
1. **`FakeCallActivity.kt` (Panggilan AI Full-Duplex & Lockscreen Wake):**
   Berjalan di task stack terisolasi (`taskAffinity="com.nexa.mobilebridge.call"`, `launchMode="singleInstance"`, `showWhenLocked="true"`, `turnScreenOn="true"`, `supportsPictureInPicture="true"`). Dapat membangunkan HP yang terkunci untuk panggilan intervensi proaktif atau ditekan manual oleh pengguna untuk mode *Live Voice Call* real-time ke Gemini Live API. Selesai panggilan langsung memanggil `finishAndRemoveTask()` agar pengguna kembali ke aplikasi sebelumnya tanpa meninggalkan jejak stack.
2. **`OverlayActivity.kt` (Popup Keputusan Dinamis):**
   Muncul di atas aplikasi apa pun dengan tema gelap transparan ketika server mengirim `SHOW_OVERLAY_MSG`. Menerima array JSON `options` dinamis (label, action, warna hex) dengan fallback 3 tombol default (`NO`, `VOICE`, `YES`), mengirim pilihan lewat `COMMAND_RESULT` lalu `finish()`.
3. **`TransparentCameraActivity.kt` (Pemotret Hening):**
   Mengambil foto kamera depan atau belakang lewat CameraX secara cepat dan hening saat diperintah server (`TAKE_PHOTO`), mengompresi ke Base64 JPEG (max 1280 px), mengirim `COMMAND_RESULT`, lalu langsung menutup diri.

---

## 6. Ekosistem Chat AI Native, Voice Note, Slash Commands & 12 Kartu Interaktif

### 6.1. Daur Hidup Transaksi Keuangan (`FINANCE_TX`) dengan Paritas Telegram Penuh
Salah satu pencapaian terbesar di v3.0 adalah menyamakan kecerdasan pencatatan keuangan Telegram ke dalam UI Kartu Native tanpa mengurangi fleksibilitas suara/teks:

1. **Input Fleksibel (Teks atau Voice Note):**
   Misalnya pengguna mengirim teks atau VN: *"Beli nasi padang seharga 13500"* atau *"Beli es teh dua 9rb tadi malam"*.
2. **Parsing Waktu Alami Indonesia (`Finance_Helpers.js` `_parseDateTimeExpression`):**
   - Mendukung konteks jam WIB saat ini: *"tadi pagi"*, *"tadi siang"*, *"tadi sore"*, *"tadi malam"*, *"kemarin [pagi/siang/sore/malam]"*, *"jam 12 malam"*, dll. Jika waktu tidak disebut, memakai waktu sekarang WIB saat pesan masuk.
3. **Hard Minimum Account Gate (`Finance_Confirmations.js` & `card_handler.js`):**
   - Syarat minimal transaksi boleh disimpan ke database Supabase adalah **Nominal + Akun + Waktu**.
   - Jika pengguna belum menyebutkan bank/dompet (`Account`), kartu tetap muncul dalam status **"Waiting for account · Auto-save paused"** (`expiresAtMs = 0`): timer 5 menit **tidak** berjalan, dan baik tombol **Confirm** maupun balasan suara *"ya"* akan ditahan sampai akun dipilih.
   - Jika `Account = Tunai` dipilih sedangkan `Method` kosong, `Method` otomatis terisi `Tunai`.
   - Begitu akun valid terisi (via 1-tap `Account` di kartu, teks, atau VN), countdown auto-save 5 menit otomatis aktif.
4. **Pembaruan Live Single-Card ("Opsi A"):**
   - Setiap transaksi punya **1 kartu tunggal** di chat. Pengguna bisa mengetuk baris **Account / Method / Category**, mengetuk ikon pensil untuk edit lengkap, **atau** langsung membalas lewat suara/teks (misal *"pake Bank Jago direct debit"*, *"ubah nominal jadi 15 ribu"*, *"ya"*, *"batal"*).
   - Ketika diubah via teks/VN, server mengirim `CARD_UPDATE` yang langsung mengisi kartu di atas secara live, dan balasan teks di bawah VN diringkas oleh `formatMobileFinanceMutationReply()` menjadi **1 kalimat ringkas bebas emoji** (contoh: *"Siap Tuan, kartu diperbarui ke Bank Jago (Direct Debit). Saldo Bank Jago saat ini Rp1.006.592. Otomatis tersimpan dalam 5 menit, atau balas 'ya' / tap Confirm di atas."*).
5. **Status Akhir Bersih:**
   - Saat selesai (`Recorded`, `Auto-saved`, `Cancelled`, atau `Expired`), footer kartu hanya menampilkan ikon Material + statusnya, sementara baris kosong tidak lagi bertuliskan *Select*/*Optional* melainkan `-`.

### 6.2. Daftar Lengkap 12 Kartu Interaktif (`card_handler.js` <-> `InteractiveCardView.kt`)

| Domain | Tipe Kartu | Presentasi | Interaksi Utama |
|---|---|---|---|
| **Finance** | `FINANCE_TX` | `CARD` | Account/Method/Category Pickers, Full Edit Dialog, Confirm, Cancel, Text/Voice Sync |
| **Finance** | `FINANCE_DELETE` | `CHIPS` | Konfirmasi cepat hapus transaksi (`Delete` / `Keep`) |
| **Finance** | `RECURRING_BILL` | `CARD` | Tagihan rutin otomatis (`Approve` / `Reject`) |
| **Finance** | `BUDGET_RECAP` | `SUMMARY_CARD` | Progress bar warna semantik per anggaran + action prompt |
| **Calendar/Task** | `BRIEFING_SUMMARY` | `SUMMARY_CARD` | Morning/Evening Executive Brief + Safe-to-Spend hero + 3 tombol cepat |
| **Calendar/Task** | `CALENDAR_CONFLICT` | `CHIPS` | Bentrok jadwal Google Calendar (`Force Create` / `Cancel`) |
| **Calendar/Task** | `TASK_CONFIRM` | `CHIPS` | Konfirmasi sinkronisasi kalender, durasi, atau list Google Tasks |
| **Calendar/Task** | `PROACTIVE_TASKS` | `INLINE_CARD` | Checklist tugas persiapan yang diusulkan dari agenda kalender |
| **Vault** | `VAULT_METADATA` | `CARD` | Editor metadata key-value dokumen arsip, Re-extract vision, Confirm & Save |
| **Memory/Self** | `IDENTITY_PROPOSAL` | `CARD` | Approval pembaruan profil mingguan atau Reject dengan alasan |
| **Memory/Self** | `MEMORY_HYGIENE` | `CARD` | Checklist seleksi arsip memori lama agar tetap aktif atau diarsipkan |
| **Discipline** | `DISCIPLINE_CHECK` | `CARD` | Intervensi pemakaian aplikasi (`Valid Research`, `+10 Min`, atau blokir) |

---

## 7. Sistem Notifikasi Percakapan, Inline Reply & Supresi Cerdas

Dikelola oleh `@Singleton ChatNotificationManager.kt` dan `ChatNotificationActionReceiver.kt`:
- **3 Channel Terdaftar:**
  - `nexa_chat_urgent` (`IMPORTANCE_HIGH`): Peringatan proaktif `P0` dan Step-Up Biometrik Keuangan 30 detik.
  - `nexa_chat_messages` (`IMPORTANCE_DEFAULT`): Pesan chat reguler & proaktif `P1` (mendukung nada kustom pilihan pengguna lewat SAF).
  - `nexa_chat_silent` (`IMPORTANCE_LOW`): Pembaruan sunyi `P2`.
- **Supresi Otomatis:** Menggunakan `ChatVisibilityTracker.isChatVisible()` agar notifikasi tidak pernah berbunyi saat pengguna sedang menatap tab Chat, dan dipaksa `silent = true` apabila panggilan Live Voice sedang berjalan.
- **MessagingStyle & Coalescing:** Menumpuk hingga 8 pesan terakhir dalam satu percakapan dengan *coalesce window* 3 detik agar burst chunk/proaktif tidak melakukan spam bunyi berturut-turut.
- **Privacy Lockscreen:** `setVisibility(VISIBILITY_PRIVATE)` dengan `publicVersion` aman ("New messages" / "Approval required") saat layar terkunci.
- **Direct Reply Tanpa Membuka Aplikasi:** `RemoteInput` mengirim teks balasan ke `ChatNotificationActionReceiver`, yang langsung meneruskannya ke `ChatRepository.sendMessage()` secara asinkron dan memperbarui isi notifikasi tanpa membuka layar utama.
- **Read Synchronization:** Begitu tab Chat dibuka (`visibilityTracker.chatVisible`), seluruh notifikasi conversation & badge dibersihkan dan `CHAT_READ_ACK` dikirim ke server.

---

## 8. Arsitektur Keamanan, Kriptografi Keystore & Biometrik

1. **Perlindungan Rahasia Lokal (`SecretKeyStoreCipher.kt`):** Token Bearer dan HMAC secret yang tersimpan di DataStore (`nexa_config`) dienkripsi terlebih dahulu memakai AES-GCM di Android Keystore.
2. **Otorisasi Perintah Level Tinggi (`HmacVerifier.kt`):** Perintah hardware ber-privilege tinggi diverifikasi menggunakan `HMAC-SHA256(timestamp + "." + level, hmacSecret)` sebelum dieksekusi oleh `DeviceCommandDispatcher`.
3. **Identitas Perangkat Asimetris (`DeviceKeystoreManager.kt`):** Kunci privat ECDSA `secp256r1` tidak pernah bisa diekspor dari hardware Keystore HP, mewajibkan `AUTH_BIOMETRIC_STRONG` (sidik jari) tiap kali penandatanganan (`CryptoObject`), dan otomatis tidak valid (`KeyPermanentlyInvalidatedException`) jika ada penambahan sidik jari baru di pengaturan Android.
4. **Enrollment Aman Out-of-Band (`scripts/nexa-enroll.js`):** Kunci publik perangkat hanya didaftarkan lewat CLI server (`enrolled_devices.json`), bukan dikirim bebas dari endpoint publik.
5. **Penegakan Step-Up Keuangan di Lapisan Tool Server (`Finance Step-Up`):** Keputusan otorisasi tidak pernah mengandalkan klasifikasi LLM maupun `intent_hint` dari klien. Server membekukan aksi di `pendingActions`, mengirim detail kanonis + `nonce` ke HP (`ACTION_PENDING`), dan baru mengeksekusi setelah tanda tangan biometrik ECDSA cocok atas `"nexa-action-v1\n$nonce\n$actionId\n$canonicalHash"`.
6. **Proteksi Layar Berhitung Referensi (`WindowSecureEffect.kt`):** Mengatur `FLAG_SECURE` menggunakan reference counter dan `findActivity()` (membuka `ContextWrapper`) sehingga transisi antar layar sensitif (`Settings`, `ServerLogs`, `RemoteTerminal`, `FinanceStepUpBottomSheet`) tidak pernah saling menghapus `FLAG_SECURE` secara tidak sengaja. *(Saat debug testing, parameter `allowInDebugBuild = true` membolehkan screenshot dan otomatis mengunci penuh pada APK release).*

---

## 9. Protokol Komunikasi Dual-WebSocket

### 9.1. Koneksi A (`/ws` -> Node.js Core)
- **Transport:** `wss://<domain>/ws`, header `Authorization: Bearer <token>`.
- **Pemisahan Frame Aman:** Seluruh **JSON Text Frame** digunakan untuk kontrol, chat, kartu, dan step-up, sedangkan **Binary Frame** didedikasikan eksklusif untuk audio PCM Live Voice sehingga tidak pernah bertabrakan.

| Arah | Frame `type` | Fungsi Utama |
|---|---|---|
| **HP -> Server** | `TELEMETRY`, `PING`, `CONTEXT_UPDATE`, `COMMAND_RESULT`, `NOTIFICATION_EVENT`, `ALARM_DISMISSED` | Telemetri adaptif, hasil eksekusi perintah hardware, event sensor/alarm |
| **HP -> Server** | `CHAT_SEND`, `CHAT_VOICE_SEND`, `CHAT_RESUME`, `CHAT_STOP`, `CHAT_READ_ACK`, `PROACTIVE_CATCHUP` | Kirim teks/VN (`audio_b64` + waveform), resume chunk dari `last_seq`, stop stream, sinkronisasi pesan tertunda |
| **HP -> Server** | `CARD_ACTION` | Eksekusi tombol kartu (`confirm`, `cancel`, `update_account`, `update_payment_method`, `update_category`, `update_fields`, `approve`, `reject`, `archive_selected`, `force_create`, dsb.) |
| **HP -> Server** | `TICKET_REQ`, `CHALLENGE_RESP`, `ENROLL_COMPLETE`, `ACTION_AUTH`, `ACTION_CANCEL` | Challenge-response tiket Ed25519 & otorisasi biometrik Step-Up Keuangan |
| **HP <-> Server** | `CALL_EVENT`, `CALL_AUDIO_STREAM`, `CALL_VIDEO_FRAME`, `CALL_AUDIO_PLAY`, `CALL_LIVE_READY`, `CALL_STATUS_UPDATE` | Sesi Live Voice full-duplex (16kHz uplink / 24kHz downlink) & screen share 1 FPS JPEG |
| **Server -> HP** | `EXECUTE_COMMAND` | Perintah hardware ke `DeviceCommandDispatcher` (+ opsional `signature` HMAC) |
| **Server -> HP** | `CHAT_ACK`, `CHAT_CHUNK`, `CHAT_RESUME_MISS`, `CHAT_PROACTIVE`, `CARD_UPDATE` | Status `THINKING`/`TRANSCRIBED`, stream token berurutan `seq 1..N`, `STREAM_FINAL` (+ optional `card`), dan update kartu live |
| **Server -> HP** | `CHALLENGE`, `TICKET`, `AUTH_ERR`, `ACTION_PENDING`, `ACTION_RESULT` | Pemberian nonce/tiket sidecar dan permintaan Step-Up Biometrik |

### 9.2. Koneksi B (`/io/logs` & `/io/term` -> Go Sidecar `nexa-io`)
- **Transport:** Binary WebSocket frame `[ch: u8][op: u8][payload: N bytes]` (Big-Endian), terotentikasi tiket Ed25519 (`scope=logs` atau `scope=term`).
- **Kanal (`ch`):** `0x00 CONTROL`, `0x03 LOGS`, `0x10..0xFF TERMINAL`.
- **Opcode (`op`):** `0x01 OPEN`, `0x02 DATA`, `0x03 CREDIT`, `0x04 CLOSE`, `0x05 RESIZE`, `0x06 ERR`, `0x07 PING`, `0x08 PONG`, `0x09 RESUME`.
- **Header Record Log (`ch=0x03 DATA`):** Header 13 byte `[prio: u8][unit_id: u8][cat_flags: u8][timestamp_ms: u64][msg_len: u16][UTF-8 message]` (maks 2.048 byte per pesan, flag `0x01 FINANCE`, `0x02 BRIDGE`, `0x04 AUTH`, `0x80 SYNTHETIC`).

---

## 10. Layer Data & Persistensi

1. **Room SQLite Database (`nexa_control_center.db` via `NexaDatabase`):**
   - Menyimpan entitas `ChatMessageEntity`: `id` (UUID PK), `clientMsgId`, `role` (`user`/`assistant`/`system`), `content`, `timestamp`, `status` (`SENDING`/`STREAMING`/`SENT`/`INTERRUPTED`/`FAILED`), `intentTag`, `executionMetadata` (JSON ter-enkode untuk `InteractiveCardData`, `VoiceNoteMetadata`, atau `ChatReplyMetadata`).
   - Indeks unik `[clientMsgId, role]` mencegah pesan duplikat saat retry offline atau `CHAT_RESUME`.
   - `ChatMessageDao` menggunakan `PagingSource<Int, ChatMessageEntity>` (`ORDER BY timestamp DESC`) dengan `maxSize = 100` dan query `findExactCardHostMessage()` menggunakan `LIKE '%"card_id":"' || :cardId || '"%'` agar pembaruan `CARD_UPDATE` presisi ke kartu targetnya.
2. **Preferences DataStore (`nexa_config` via `NexaPreferencesDataStore`):**
   - Menyimpan konfigurasi non-rahasia (URL server, tema, aksen, haptic, interval heartbeat layar nyala/mati, ukuran font terminal, status enrollment, URI nada notifikasi) serta `auth_token` & `hmac_secret` yang terenkripsi `SecretKeyStoreCipher`.
3. **Memori Non-Persisten Aman:**
   - Buffer Log (`CircularLogBuffer`, maks 2.000 entri) dan layar Terminal (`Vt100TerminalEmulator`, 1.000 baris scrollback) hanya hidup di RAM selama layar aktif dan langsung dibersihkan ketika ditutup.

---

## 11. Mesin Latar Belakang, Hardware Dispatcher, Live Voice & Screen Share

1. **9 Kapabilitas Background yang Tetap Hidup 24/7 di `NexaBridgeService`:**
   - WebSocket-A (`/ws`) + `NetworkWatcher` (debounce 800 ms saat berpindah Wi-Fi <-> Seluler).
   - `DeviceCommandDispatcher` beserta 25+ aksi aktif hardware, layar, intent, dan clipboard.
   - `TtsHandler` & `alertTts` (`id-ID`) + `AudioPlayerHandler` (ringtone/media darurat).
   - `SensorObserverManager`, `LocationHandler` & `GeofenceManager` (`GeofenceBroadcastReceiver`).
   - `AppUsageTracker` (`UsageStatsManager.queryEvents()`).
   - `@Singleton ChatRepository` & Room sync untuk pesan proaktif (`CHAT_PROACTIVE`) & update kartu saat layar tertutup.
   - `@Singleton ChatNotificationManager` & `ChatNotificationActionReceiver` (Direct Reply & Mark Read).
   - Notifikasi darurat Finance Step-Up (`showStepUpRequired`).
   - Receiver boot otomatis (`BootCompletedReceiver`) & `NexaNotifListenerService` (alarm dismiss pagi + event notifikasi).
2. **Screen Share MediaProjection (`ScreenCaptureService.kt`):**
   Diaktifkan on-demand selama panggilan Gemini Live bila pengguna membagikan layar; merekam 1 FPS pada resolusi `540x1200` (tepat 50% skala Galaxy A33) dalam JPEG 50 -> frame `CALL_VIDEO_FRAME`, dilengkapi dock kontrol melayang (`FloatingOverlayManager`). OS Android secara bawaan menjaga aplikasi M-Banking yang dilindungi `FLAG_SECURE` tampil hitam pada capture `MediaProjection`.

---

## 12. Manajemen Daya Baterai, Reconnect 4-Fase & Standby UI Hibernation

1. **Zero Persistent WakeLock (`BridgePowerLockManager.kt`):**
   Tidak ada `WakeLock`/`WifiLock` yang ditahan permanen saat servis idle. Kunci hanya di-acquire secara ref-counted ketika ada eksekusi perintah aktif (timeout 15 detik) atau selama Live Call berjalan (batas aman 30 menit).
2. **Strict On-Demand Sensing & Adaptive Telemetry:**
   - Sensor angkat HP dan lux cahaya terus-menerus dinonaktifkan; `GET_LIGHT_STATUS` hanya menyalakan sensor <1 detik lalu langsung `unregisterListener()`, dan `GET_SCREEN_STATUS` membaca `PowerManager.isInteractive` secara instan.
   - Telemetri hanya dikirim bila persentase baterai/charging berubah atau setiap detak 3 menit; saat antrean WebSocket >= 16 KiB, telemetri non-kritis dibuang (`NFR-BAT-03`).
   - Interval heartbeat ringan (`{"type":"PING"}`) dapat diatur: default **60 detik** (layar menyala) dan **120 detik** (layar mati).
3. **4-Phase Reconnect Orchestrator:**
   Fase 1 (20x backoff cepat 1s->30s) -> tunggu 1 jam -> Fase 2 (20x) -> notifikasi + TTS -> tunggu 6 jam -> Fase 3 (20x) -> notifikasi + TTS -> tunggu 24 jam -> Fase 4 (20x) -> notifikasi final + berhenti, memakai volume media saat ini.
4. **Standby UI Hibernation (`MainActivity.kt` -> RAM Standby ~`50,0 MB` Fisik):**
   - Ketika `MainActivity` masuk `onStop()` atau menerima `ACTION_SCREEN_OFF`, aplikasi langsung memangkas cache grafis GPU via `WindowManagerGlobal.trimMemory(TRIM_MEMORY_UI_HIDDEN)`.
   - Jika tetap di Home Screen selama **20 detik** (atau **5 detik** saat layar mati), `MainActivity` otomatis memanggil `finish()` untuk melepaskan seluruh pohon Compose & RenderThread (`ViewRootImpl: 0`), menurunkan `Private Dirty` >50% menjadi **`19,2 MB`** (`50,0 MB` RAM fisik aktif) sambil menjaga 100% layanan di `NexaBridgeService`.
   - **Pengaman Anti-Ganggu:** Hibernasi UI otomatis ditahan selama **130 detik** jika sesi Terminal ada di stack, dan dibatalkan bila sedang ada Live Call, konfirmasi Step-Up Biometrik (`pendingStepUp`), streaming chat aktif, atau pemilih file sistem/pengaturan notifikasi (grace 90 detik).

---

## 13. Integrasi Sisi Server (`nexa-server` & Go Sidecar `nexa-io`)

Untuk memahami keseluruhan proyek tanpa membuka repo server, berikut peran tiap modul pasangan di VPS:

1. **Node.js Core Bridge (`c:\workspace\nexa-server\src\interfaces\bridge\mobile_bridge/`):**
   - `client.js`: Menangani koneksi `/ws`, autentikasi Bearer saat HTTP upgrade, penyimpanan `latestTelemetry`, pengiriman `CHAT_PROACTIVE`, dan *catch-up* antrean proaktif saat klien reconnect.
   - `chat_handler.js`: Menangani `CHAT_SEND`, `CHAT_VOICE_SEND` (transkripsi Whisper/Voice_Engine -> alur NLU yang sama), 7 perintah slash langsung, 8 lapis *conversational interceptor* (Morning Check-in, Pending Calendar Duration, Pending Finance Delete, Pending Split Remainder, Pending Finance Confirmation, Pending Task, Pending Calendar Conflict, Pending Proactive Tasks), pemanggilan `AI_Router` + `domain_dispatcher.js`, replay buffer `CHAT_RESUME` (retensi 10 menit), serta `ACTION_AUTH`/`ACTION_CANCEL`.
   - `card_handler.js`: Membangun payload schema versi 1 untuk ke-12 kartu interaktif dan memproses aksi `CARD_ACTION` -> menerbitkan `CARD_UPDATE`, termasuk `formatMobileFinanceMutationReply()` (Opsi A).
   - `adapter.js`: Menjembatani modul domain server (seperti cron Morning Briefing, Midnight Check-in 01:00 WIB berbasis `GET_SCREEN_STATUS`, dan Reminder) untuk mengirim `EXECUTE_COMMAND` ke HP.
2. **Go Sidecar Binaries (`nexa-io`):**
   Satu binary Go terisolasi systemd yang dijalankan on-demand via Unix socket Caddy + systemd socket activation (`0 MB` saat idle):
   - `nexa-log.service`: User `nexa-log` (grup `systemd-journal`), memfilter `journalctl` menjadi frame biner 13 byte ber-`cat_flags` (`MemoryMax=48M`).
   - `nexa-term.service`: User `nexa-term` (sandboxed ketat `InaccessiblePaths`, `NoNewPrivileges=yes`), menjalankan bash PTY via `creack/pty` dengan 64 KB ring replay buffer (`MemoryMax=160M`).

---

## 14. Panduan Build, Unit Test, ADB Deploy & Verifikasi

### 14.1. Build & Jalankan Unit Test Android (PowerShell Windows)
```powershell
cd c:\workspace\nexa-mobile-bridge
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest assembleDebug
```
- **Output APK Debug:** `app\build\outputs\apk\debug\app-debug.apk`
- **Build Production (dengan R8 Shrinking):** `.\gradlew.bat assembleRelease` (`isMinifyEnabled = true`, `isShrinkResources = true` di `app/build.gradle.kts`).

### 14.2. Install & Inspeksi Live di Galaxy A33 (`RRCTC0085DH`)
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb -s RRCTC0085DH install -r 'c:\workspace\nexa-mobile-bridge\app\build\outputs\apk\debug\app-debug.apk'
& $adb -s RRCTC0085DH shell am start -n com.nexa.mobilebridge/.MainActivity

# Audit penggunaan RAM & WakeLock nyata:
& $adb -s RRCTC0085DH shell dumpsys meminfo com.nexa.mobilebridge
& $adb -s RRCTC0085DH shell "dumpsys power | grep -i nexa"
```

### 14.3. Catatan Git Hygiene
- File `.idea/` (seperti `.idea/gradle.xml` atau `.idea/deviceManager.xml`) dapat berubah otomatis oleh Android Studio dan **jangan** ikut di-commit bersama perubahan kode produksi.
- Gunakan pemeriksaan karakter non-ASCII / Unicode escape (`\u00B7`) pada source Kotlin agar bebas dari *mojibake* Windows-1252.

---
*Dokumentasi Resmi N.E.X.A Mobile Bridge v3.1 (Lengkap, Self-Contained, & Code-Verified - 22 November 2026).*
