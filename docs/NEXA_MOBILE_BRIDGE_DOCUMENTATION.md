# N.E.X.A Mobile Bridge - Architecture & Technical Documentation

| | |
|---|---|
| **Document Version** | 3.0 (AI Control Center & Native Interactive Cards Edition - 22 November 2026) |
| **Target Device** | Samsung Galaxy A33 5G (`SM-A336E`), Android 16 (API 36), One UI 8.0 (`arm64-v8a`, `PAGE_SIZE = 4096`) |
| **Package Name** | `com.nexa.mobilebridge` |
| **Workspace Path** | `c:\workspace\nexa-mobile-bridge` |
| **Related Docs** | [RAM_USAGE_AUDIT_REPORT.md](./RAM_USAGE_AUDIT_REPORT.md), [JSON_PROTOCOL_SPECIFICATION.md](./JSON_PROTOCOL_SPECIFICATION.md), [MOBILE_BRIDGE_TESTING_REPORT.md](./MOBILE_BRIDGE_TESTING_REPORT.md) |

---

## 1. Executive Summary & Architectural Philosophy

**N.E.X.A Mobile Bridge** telah berevolusi dari jembatan perangkat keras (*Thin-Client Executive Bridge*) menjadi **AI Control Center Native** yang menghubungkan smartphone pemilik langsung ke **N.E.X.A Server** melalui protokol Dual-WebSocket aman tanpa ketergantungan pada aplikasi chat pihak ketiga maupun WebView.

### Empat Pilar Arsitektur Utama:
1. **100% Native Jetpack Compose (Zero WebView):**
   Seluruh antarmuka Chat AI, 12 Kartu Interaktif, Palette Perintah Slash (`/`), Log Monitor, Terminal PTY, hingga Pengaturan dirender native dengan Compose + Canvas OLED. Tidak memakai Chromium/WebView sehingga hemat RAM dan bebas *thermal throttling* pada Exynos 1280.
2. **Dual-WebSocket Terisolasi (`/ws` JSON + Binary PCM vs `/io/*` Sidecar Biner):**
   - **Koneksi A (`/ws` -> Node.js Core):** Tetap hidup 24 jam di `NexaBridgeService` untuk eksekusi perintah hardware, telemetri adaptif, challenge/tiket autentikasi, AI Chat streaming, Voice Note, Kartu Interaktif, Step-Up Keuangan, serta audio PCM Live Voice.
   - **Koneksi B (`/io/logs` & `/io/term` -> Go Sidecar `nexa-io`):** Bersifat **on-demand** (hanya terbuka saat tab Logs atau Terminal tampil di layar dan dihentikan otomatis saat keluar layar atau layar mati), memakai frame biner berkinerja tinggi `[ch:u8][op:u8][payload]` serta tiket Ed25519 terikat biometrik.
3. **M-Banking Safe & Hardware-Backed Security (Tanpa Root & Tanpa AccessibilityService):**
   Seluruh kode dan manifest `NexaAccessibilityService` telah dihapus (`FR-CORE-03`) agar aplikasi mobile banking tidak memblokir perangkat. Deteksi aplikasi aktif bermigrasi ke `UsageStatsManager` (`AppUsageTracker.kt`), sementara perintah sensitif keuangan, akses log, dan shell VPS dilindungi kunci perangkat keras **Android Keystore ECDSA P-256** + `BiometricPrompt` (`BIOMETRIC_STRONG`).
4. **Arsitektur Dua Lapis untuk Efisiensi RAM & Baterai 24/7:**
   - **Lapis 1 (Mesin Background Abadi):** `NexaBridgeService` (`START_STICKY`) beserta komponen `@Singleton` Hilt (WebSocket-A, `DeviceCommandDispatcher`, `ChatRepository`, `ChatNotificationManager`, TTS, Geofence, `BridgePowerLockManager` on-demand) bertahan di RAM fisik **~50,0 MB** saat standby.
   - **Lapis 2 (Kanvas Visual & GPU On-Demand):** `MainActivity` melepaskan cache GPU (`TRIM_MEMORY_UI_HIDDEN`) secara instan saat ke Home/layar mati dan melakukan *Standby UI Hibernation* (`finish()`) setelah masa tenggang aman, mengembalikan jumlah `ViewRootImpl` menjadi `0` tanpa mengganggu panggilan suara, step-up biometrik, atau masa resume terminal 120 detik.

---

## 2. Peta Lengkap Struktur Proyek & Modul (`com.nexa.mobilebridge`)

```text
c:\workspace\nexa-mobile-bridge\app\src\main\java\com\nexa\mobilebridge\
 ├── NexaBridgeApp.kt                     --> Root Application (@HiltAndroidApp)
 ├── MainActivity.kt                      --> Single-Activity Compose Host, Deep-Link, & Standby UI Hibernation
 ├── Navigation.kt & NavigationKeys.kt    --> Navigation 3 NavDisplay (MainShell, BridgeStatus, Settings, ServerLogs, RemoteTerminal)
 ├── NexaBridgeService.kt                 --> Main Foreground Service (connectedDevice|specialUse|microphone, START_STICKY)
 ├── TransparentCameraActivity.kt         --> Background CameraX Capture Activity (Translucent UI)
 │
 ├── core/
 │    ├── auth/
 │    │    └── TicketAuthRepository.kt    --> Challenge-Response Nonce -> Keystore ECDSA -> Ed25519 Ticket (/io/logs & /io/term)
 │    ├── context/
 │    │    └── ContextEngine.kt           --> Multi-Sensor & Geofence Context Synthesis Engine
 │    ├── di/
 │    │    ├── AppModule.kt               --> Hilt DI (Context, DataStore, Room NexaDatabase & DAO)
 │    │    └── NetworkModule.kt           --> Hilt DI (OkHttpClient ping 20s, kotlinx.serialization Json)
 │    ├── network/
 │    │    ├── NexaWebSocketClient.kt     --> WS-A Router (Chat, Cards, Voice Notes, Step-Up, Commands, Live Voice PCM)
 │    │    └── ReconnectOrchestrator.kt   --> 4-Phase Tiered Smart Reconnect State Engine
 │    ├── power/
 │    │    └── BridgePowerLockManager.kt  --> Reference-Counted On-Demand WakeLock & WifiLock (15s cmd / 30m call cap)
 │    ├── receiver/
 │    │    └── BootCompletedReceiver.kt   --> Auto-Start NexaBridgeService on BOOT_COMPLETED / QUICKBOOT_POWERON
 │    ├── security/
 │    │    ├── BiometricAuthHelper.kt     --> BiometricPrompt STRONG wrapper & CryptoObject signing
 │    │    ├── DeviceKeystoreManager.kt   --> Hardware ECDSA secp256r1 Keypair, SPKI DER & SHA-256 fingerprint
 │    │    ├── HmacVerifier.kt            --> HMAC-SHA256 validation for privileged server commands
 │    │    ├── SecretKeyStoreCipher.kt    --> AES-GCM Keystore encryption for locally stored tokens/secrets
 │    │    └── WindowSecureEffect.kt      --> Reference-counted FLAG_SECURE controller via findActivity()
 │    ├── sensor/
 │    │    ├── GeofenceManager.kt         --> Play Services GeofencingClient orchestrator
 │    │    ├── GeofenceModel.kt           --> Dynamic geofence data models
 │    │    ├── GeofenceBroadcastReceiver.kt --> Background geofence transition handler
 │    │    └── SensorObserverManager.kt   --> Controlled on-demand sensor lifecycle manager
 │    ├── usage/
 │    │    └── AppUsageTracker.kt         --> M-Banking safe active app discipline monitor via UsageStatsManager
 │    └── util/
 │         └── ConnectionState.kt         --> Sealed connection & phase state models
 │
 ├── data/
 │    ├── chat/
 │    │    ├── ChatCopyText.kt            --> Clean text/markdown copy sanitizer
 │    │    ├── ChatRepository.kt          --> Offline-first Room + Paging 3, stream router, card updates & step-up handler
 │    │    ├── ChatStreamConsumer.kt      --> Per-stream Channel (~33 ms UI batching + 5s checkpoint + NonCancellable)
 │    │    ├── ChatVisibilityTracker.kt   --> Visible tab state, unread counter & notification deep-link router
 │    │    ├── InteractiveCardModels.kt   --> InteractiveCardCodec & 12 card models (CardField, Metric, Action, etc.)
 │    │    ├── VoiceNoteMetadata.kt       --> Voice note audio metadata & 48-bar waveform codec
 │    │    ├── VoiceNotePlayer.kt         --> MediaPlayer wrapper with progress polling & cleanup
 │    │    └── VoiceNoteRecorder.kt       --> OGG/M4A/WebM/WAV voice note recorder (max 3 min / 1.5 MB)
 │    ├── local/
 │    │    ├── NexaPreferencesDataStore.kt --> Theme, accent, heartbeat (60s/120s), TTS/haptic, & terminal font prefs
 │    │    └── chat/
 │    │         ├── ChatMessageEntity.kt  --> Room table (unique index on clientMsgId + role)
 │    │         ├── ChatMessageDao.kt     --> PagingSource, checkpoints, exact card_id lookup, & cleanup queries
 │    │         └── NexaDatabase.kt       --> Room database (`nexa_control_center.db`)
 │    ├── logs/
 │    │    ├── CircularLogBuffer.kt       --> Fixed 2.000-entry thread-safe ring buffer with monotonic stableId
 │    │    ├── LogBinaryProtocol.kt       --> 13-byte binary header parser/encoder (unit_id, prio, cat_flags)
 │    │    └── SidecarLogsClient.kt       --> On-demand WSS `/io/logs` client with credit window flow control
 │    └── terminal/
 │         ├── ModifierState.kt           --> Shared IDLE / ONCE / LOCKED state for CTRL & ALT
 │         ├── SidecarTerminalClient.kt   --> On-demand WSS `/io/term` client + 64 KiB credit + RESUME offset
 │         ├── TerminalBinaryProtocol.kt  --> Binary PTY codec (OPEN, DATA, CREDIT, RESIZE, RESUME, CLOSE, ERR)
 │         └── Vt100TerminalEmulator.kt   --> Native ANSI xterm-256color / UTF-8 / cursor / 1.000-line scrollback engine
 │
 ├── dispatcher/
 │    ├── DeviceCommandDispatcher.kt      --> Central router for hardware, sensor, audio, call & deprecated stubs
 │    └── handler/ (16 Modular Handlers)
 │         ├── AppLauncherHandler.kt, AudioPlayerHandler.kt, BatteryHandler.kt, CameraHandler.kt,
 │         ├── ClipboardHandler.kt, FakeCallHandler.kt, FlashlightHandler.kt, GeofenceHandler.kt,
 │         ├── IntentHandler.kt, LightSensorHandler.kt, LocationHandler.kt, NetworkHandler.kt,
 │         ├── OverlayHandler.kt, ScreenHandler.kt, ScreenshotHandler.kt, TtsHandler.kt,
 │         └── VolumeHandler.kt (+ VoiceRecorderHandler.kt, VoiceStreamHandler.kt)
 │
 ├── notification/
 │    ├── ChatNotificationManager.kt      --> MessagingStyle conversation notifications, 3 priority channels & Step-Up alert
 │    └── ChatNotificationActionReceiver.kt --> Background Inline Direct Reply & Mark-as-Read handler
 │
 ├── protocol/
 │    ├── CallEvent.kt, CommandResult.kt, ContextReport.kt, NexaActions.kt, NexaCommand.kt, TelemetryReport.kt
 │
 ├── service/
 │    ├── NexaNotifListenerService.kt     --> Clock Alarm Dismiss listener for Morning Briefing
 │    ├── ScreenCaptureService.kt         --> MediaProjection screen capture service for live inspection
 │    └── FloatingOverlayManager.kt & FloatingLifecycleOwner.kt --> System overlay lifecycle support
 │
 ├── theme/
 │    └── Color.kt, Theme.kt, Type.kt     --> NexaCyberDark OLED (#0A0E14) & Light theme + JetBrains Mono typography
 │
 └── ui/
      ├── call/
      │    ├── FakeCallActivity.kt        --> Lockscreen-capable Live Voice & 6-state call UI
      │    └── NexaExpressiveEyes.kt      --> OLED animated visor eyes (pauses when Lifecycle < STARTED)
      ├── chat/
      │    ├── ChatScreen.kt              --> Chat-First LazyColumn (reverseLayout), markdown, voice bar & TTS controls
      │    ├── ChatViewModel.kt           --> Paging 3, draft/voice recording, reply/selection, card & step-up flows
      │    ├── ChatMessageActions.kt      --> Selection top bar, reply preview, contextual action menu
      │    ├── InteractiveCardView.kt     --> Renderers for all 12 native interactive cards, Bottom Sheets & Edit Dialogs
      │    ├── FinanceStepUpBottomSheet.kt --> Biometric Finance Authorization Modal Bottom Sheet (30s countdown)
      │    └── SlashCommandPalette.kt     --> Floating `/` command autocomplete palette
      ├── components/
      │    └── NexaChrome.kt              --> Unified Top AppBar (NexaExpressiveEyes, connection status, Call & Settings)
      ├── hud/
      │    └── HudScreen.kt & HudViewModel.kt --> Bridge Status dashboard, live telemetry, redacted Command Log & service stop guard
      ├── logs/
      │    └── LogViewerScreen.kt & LogViewerViewModel.kt --> Realtime log viewer (ALL / FINANCE / BRIDGE / ERRORS ONLY)
      ├── overlay/
      │    └── OverlayActivity.kt         --> Server-driven floating decision dialog
      ├── settings/
      │    └── SettingsScreen.kt, SettingsComponents.kt, SettingsSheetsAndDialogs.kt, SettingsViewModel.kt
      └── terminal/
           └── TerminalScreen.kt, TerminalViewModel.kt, TerminalAccessoryBar.kt, TerminalInputSink.kt
```

---

## 3. Navigasi & Antarmuka Chat-First (`Navigation.kt` & `NexaChrome.kt`)

Aplikasi mengusung arsitektur **Pure Chat-First** (tanpa Bottom Navigation Bar permanen):
- **Root Screen (`MainShell`):** Langsung menampilkan **Chat AI Native** dengan `NexaTopBar` yang memuat kapsul mata OLED `NexaExpressiveEyes`, indikator status koneksi WS-A, tombol cepat `Live Voice Call`, dan tombol `Settings`.
- **Akses Diagnostik Terpusat lewat `Settings` (atau Notifikasi Deep-Link):**
  - `BridgeStatus` (HUD Telemetri & Kontrol Service)
  - `ServerLogs` (Log Monitor On-Demand)
  - `RemoteTerminal` (Shell PTY VPS)
- **Disiplin Tumpukan Memori (`backStack`):**
  - Berpindah antar layar diagnostik (`BridgeStatus`, `ServerLogs`, `RemoteTerminal`) selalu membersihkan layar sekunder sebelumnya sehingga stack tetap maksimal 2 lapis `[MainShell, LayarAktif]`.
  - Pengecualian aman: membuka `Settings` dari dalam `RemoteTerminal` (misal untuk mengubah ukuran font terminal) menumpuk `Settings` di atas `RemoteTerminal` sementara agar tombol `Back` langsung kembali ke sesi terminal yang masih aktif.
  - Transisi halaman memakai slide horizontal murni tanpa *offscreen alpha compositing layer* (`fadeIn`/`fadeOut` dihapus demi hemat GPU Exynos 1280).

---

## 4. Pilar 1: Chat AI Native, Voice Notes, Slash Commands & 12 Kartu Interaktif

### 4.1. Alur Pesan & Ketahanan Offline-First (`ChatRepository.kt` & `ChatStreamConsumer.kt`)
- **Penyimpanan Lokal (Room + Paging 3):** Tabel `chat_messages` di `nexa_control_center.db` memiliki indeks unik `(clientMsgId, role)` dan indeks `timestamp`. Paging 3 menggunakan `pageSize = 20`, `prefetchDistance = 5`, `maxSize = 100`.
- **Batching Stream (~33 ms) & Checkpoint (5 detik):** Token `STREAMING_CHUNK` dikumpulkan dalam `Channel<StreamToken>` khusus per stream (~30 pembaruan UI/detik agar bebas GC jank) dan di-checkpoint ke Room setiap ~5 detik. Finalisasi dijalankan di konteks `NonCancellable` (`SENT`, `INTERRUPTED`, atau `FAILED`).
- **Offline Queue & Resume (`CHAT_RESUME`):** Pesan `SENDING` otomatis terkirim ulang secara idempoten dengan `client_msg_id` yang sama saat koneksi pulih. Saat reconnect di tengah stream, klien mengirim `CHAT_RESUME { client_msg_id, last_seq }` untuk melanjutkan chunk yang tertinggal.
- **Voice Notes (`CHAT_VOICE_SEND`):** Merekam audio OGG/M4A/WebM/WAV (maksimal 3 menit / 1,5 MB) dengan visualisasi waveform 48 bar (`VoiceNoteMetadata.kt`). Di server, audio ditranskrip terlebih dahulu lalu masuk ke jalur NLU & interceptor yang **100% sama** dengan pesan teks (`executeAndStreamChat`), termasuk mampu memicu dan memperbarui kartu interaktif.
- **Reply Kontekstual, Text Selection, & TTS Lokal:** Setiap bubble N.E.X.A mendukung swipe-to-reply, tap menu aksi (Reply, Copy bersih, Delete, Read Aloud TTS `id-ID`), serta multi-select.

### 4.2. Palette Perintah Slash (`SlashCommandPalette.kt`)
Mengetik `/` di kolom input memunculkan palette perintah cepat di atas bar input tanpa perlu mengetik manual, dieksekusi langsung di server tanpa membebani LLM router:

| Perintah | Deskripsi | Output UI |
|---|---|---|
| `/briefing` *(atau `/morning`, `/evening`)* | Ringkasan pagi / refleksi malam, cuaca, agenda, tugas & safe-to-spend | Kartu `BRIEFING_SUMMARY` + narasi bersih |
| `/budget` *(atau `/anggaran`, `/rekap`)* | Rekap anggaran bulanan/mingguan lengkap per kategori | Kartu `BUDGET_RECAP` + progress bar |
| `/vault` | Meninjau & menyunting metadata arsip dokumen Vault terbaru | Kartu `VAULT_METADATA` |
| `/search <kata kunci>` | Mencari riwayat percakapan lampau dari memori jangka panjang | Hasil pencarian riwayat chat |
| `/playbook` | Memeriksa aturan playbook adaptif aktif atau `undo <INTENT>` | Status playbook |
| `/capabilities` | Menampilkan daftar kapabilitas resmi modul N.E.X.A & versi server | Daftar kapabilitas |
| `/trigger_weekly` | Menjalankan Weekly Cognitive Identity Inference secara manual | Konfirmasi + push kartu proaktif |

### 4.3. Sistem Kartu Interaktif Native (12 Jenis Kartu)
Kartu dikirim dari `card_handler.js` melalui field `card` pada `STREAM_FINAL` / `CHAT_PROACTIVE`, disimpan di kolom `executionMetadata` Room (`InteractiveCardCodec`), dan diperbarui secara live di chat tanpa bubble duplikat melalui frame **`CARD_UPDATE`**. Interaksi tombol dari HP dikirim lewat **`CARD_ACTION`**.

| No | `card_type` | Presentasi | Fungsi & Interaksi Native |
|:---:|---|---|---|
| 1 | `FINANCE_TX` | `CARD` | **Struk Keuangan Receipt-First:** Nominal monospace besar, pill tipe (`Expense`/`Income`/`Transfer`), baris tap langsung (`Account`, `Method`, `Category`), ikon pensil untuk dialog edit lengkap (nominal, waktu alami Indonesia seperti *"tadi malam jam 8"*, *"kemarin sore"*, catatan), 1-tap Picker Bottom Sheet (`Account`, `Method`, `Category`), banner pengingat info kurang, dan 2 tombol footer (`Cancel` \| `Select Account` / `Confirm`). Dilengkapi **Hard Account Gate**: auto-save 5 menit dan Confirm diblokir jika `Account` belum dipilih. Pembaruan lewat teks/voice menerapkan **Opsi A**: kartu di atas langsung ter-update live sementara balasan chat hanya 1 kalimat ringkas bebas emoji dan menampilkan saldo akun terkini. |
| 2 | `BRIEFING_SUMMARY` | `SUMMARY_CARD` | **Editorial Morning / Evening Brief:** Menampilkan tanggal di kanan atas, cuaca, hero metric berlabel (`SAFE TO SPEND TODAY`), strip statistik tanpa border bertumpuk (`Agenda Today`, `Tasks Due`, `Upcoming Bills`, `Active Goals`), divider ke narasi briefing bebas emoji, serta 1 baris tombol cepat simetris (`Detail`, `Tasks`, `Budget`). |
| 3 | `BUDGET_RECAP` | `SUMMARY_CARD` | **Rekap Anggaran:** Menampilkan badge status (`On Track` / `Over Limit`), hero net remaining/over, daftar kategori dengan `LinearProgressIndicator` berwarna semantik (Emerald <80%, Amber >=80%, Crimson >=100%), dan tombol cepat riwayat/analisa. |
| 4 | `VAULT_METADATA` | `CARD` | **Arsip Dokumen Vault:** Menampilkan kategori & key-value metadata dokumen dengan `VaultMetadataEditDialog` (ubah kategori, edit semua field, tambah field baru), tombol `Re-extract`, dan `Confirm & Save`. |
| 5 | `CALENDAR_CONFLICT` | `CHIPS` | Strip tombol cepat di bawah chat saat jadwal baru bentrok dengan agenda Google Calendar (`Force Create` / `Cancel`). |
| 6 | `FINANCE_DELETE` | `CHIPS` | Tombol konfirmasi cepat hapus transaksi (`Delete` / `Keep`). |
| 7 | `TASK_CONFIRM` | `CHIPS` | Tombol konfirmasi cepat pembuatan tugas (`Confirm Sync`, `Duration`, atau `List`). |
| 8 | `PROACTIVE_TASKS` | `INLINE_CARD` | Kartu checklist interaktif dengan `Checkbox` untuk memilih tugas persiapan agenda mana saja yang ingin dibuat. |
| 9 | `RECURRING_BILL` | `CARD` | Kartu approval tagihan rutin otomatis (`Approve` / `Reject`). |
| 10 | `IDENTITY_PROPOSAL` | `CARD` | Kartu persetujuan pembaruan profil/identitas mingguan dengan dialog penolakan opsional (`Reject` + alasan). |
| 11 | `MEMORY_HYGIENE` | `CARD` | Kartu checklist untuk memilih memori usang mana yang diarsipkan dan mana yang dipertahankan aktif. |
| 12 | `DISCIPLINE_CHECK` | `CARD` | Kartu intervensi distraksi dari `AppUsageTracker` (`Valid Research`, `+10 Min`, atau blokir). |

> **Kebersihan Tampilan Kartu (`stripCardEmojis` & Local Expiry):**
> Setiap kartu yang selesai (`Recorded`, `Auto-saved`, `Cancelled`, `Expired`) hanya menampilkan ikon native Material + label status, membersihkan emoji mentah dan titik korup (`\u00B7`), serta mengunci tombol secara lokal bila `expiresAtMs` telah terlewati.

---

## 5. Keamanan Perangkat, Biometrik & Engine Notifikasi

### 5.1. Otorisasi Hardware-Backed (`TicketAuthRepository` & `FinanceStepUpBottomSheet`)
1. **Enrollment & Kunci ECDSA P-256 (`DeviceKeystoreManager.kt`):**
   Membuat pasangan kunci `secp256r1` di Android Keystore (`AUTH_BIOMETRIC_STRONG`, wajib verifikasi biometrik per penggunaan, otomatis gugur bila sidik jari baru ditambahkan di OS). Kunci publik SPKI DER didaftarkan ke server melalui CLI `nexa-enroll` (ditampilkan SHA-256 32 oktet penuh di Settings).
2. **Tiket Akses Sidecar (`Logs` & `Terminal`):**
   `TICKET_REQ` -> server mengirim `CHALLENGE` (`nonce` 32 byte, `sid`, `exp`) -> HP menandatangani `"nexa-ticket-v1\n$nonce\n$scope\n$sid"` via `BiometricPrompt.CryptoObject` -> `CHALLENGE_RESP` -> server mengembalikan `TICKET` Ed25519 (TTL 45 detik, sekali pakai `jti`).
3. **Finance Step-Up Bottom Sheet (`ACTION_PENDING` -> `ACTION_AUTH` -> `ACTION_RESULT`):**
   Untuk eksekusi finansial sensitif dari tool server, `ChatScreen` memunculkan `FinanceStepUpBottomSheet` dengan **`FLAG_SECURE`**, menutup IME otomatis, menonaktifkan input chat, menampilkan detail penerima lengkap (nama utuh tanpa ellipsis, bank, 4-6 digit rekening terakhir, status whitelist, sisa batas harian, `action_id`, timer 30 detik), dan menandatangani payload kanonis dengan sidik jari.

### 5.2. Smart Conversation Notifications (`ChatNotificationManager.kt`)
- **3 Channel Prioritas:** `nexa_chat_urgent` (P0), `nexa_chat_messages` (P1, mendukung nada kustom SAF dari Settings), dan `nexa_chat_silent` (P2).
- **Supresi Cerdas (`ChatVisibilityTracker`):** Tidak membunyikan notifikasi bila tab `Chat` sedang aktif terlihat di layar atau saat panggilan Live Voice berlangsung.
- **Inline Direct Reply & Mark-as-Read (`ChatNotificationActionReceiver.kt`):** Tuan dapat membalas pesan N.E.X.A langsung dari bilah notifikasi atau menandai terbaca tanpa membuka aplikasi.
- **Notifikasi Step-Up Darurat:** Jika `ACTION_PENDING` masuk ketika layar `Chat` tertutup, notifikasi prioritas tinggi muncul dengan tombol `Verify` dan auto-expire 30 detik.

---

## 6. Pilar 2 & 3: Log Monitor On-Demand & Terminal PTY

### 6.1. Log Monitor Biner (`SidecarLogsClient.kt` & `LogViewerScreen.kt`)
- Membuka koneksi WSS `/io/logs` **hanya** saat `LogViewerScreen` berada di state `ON_START` (`LifecycleStartEffect`), memakai tiket scope `logs` + `FLAG_SECURE`.
- Saat layar mati atau pindah tab (`ON_STOP`), stream otomatis dihentikan, `CLOSE` dikirim, dan `CircularLogBuffer` (kapasitas tetap 2.000 entri) dikosongkan.
- Mendukung protokol biner header **13 byte** (`LogBinaryProtocol.kt`), filter sisi server (`ALL`, `FINANCE`, `BRIDGE`, `ERRORS ONLY`), badge kategori terpisah, Pause/Resume berbasis kredit (`CREDIT`), baris sintetis `SKIPPED`, Auto-Scroll, dan Clear Local Buffer.

### 6.2. Remote Interactive Terminal (`SidecarTerminalClient.kt` & `TerminalScreen.kt`)
- Membuka koneksi WSS `/io/term` dengan tiket scope `term` ber-`sid` otoritatif server + `FLAG_SECURE`.
- **Emulator Native (`Vt100TerminalEmulator.kt`):** Mendukung ANSI 256 warna / TrueColor, UTF-8, atribut tebal/garis bawah, posisi kursor, `SIGWINCH` `RESIZE`, dan scrollback 1.000 baris.
- **Input Samsung Keyboard (`TerminalInputSink.kt`):** Menggunakan `KeyboardType.Password` + `autoCorrectEnabled = false` dengan sentinel `\u200B` yang terbukti di Galaxy A33 One UI 8 mematikan saran kata & swipe typing, sekaligus meneruskan `Enter` (`0x0D`), `Backspace` (`0x7F`), dan `Tab` (`0x09`).
- **Accessory Bar 2 Baris (`TerminalAccessoryBar.kt`):** Baris 1 berisi 9 tombol ukuran `44x48 dp` (`ESC`, `TAB`, `CTRL`, `ALT`, panah `← ↓ ↑ →` dengan key-repeat, dan `⋯`), terhubung ke `ModifierState` tunggal (`IDLE` / `ONCE` / `LOCKED`).
- **Flow Control & Resume (`0x09 RESUME`):** Jendela kredit awal 64 KiB dan dukungan resume berbasis `received_offset` dalam batas masa tenggang server 120 detik.

---

## 7. Command Dispatcher, Live Voice Full-Duplex & Smart Power Management

### 7.1. Command Dispatcher Aktif (`DeviceCommandDispatcher.kt`)
Semua perintah dijalankan konkuren dengan batas penguncian daya on-demand 15 detik (`BridgePowerLockManager.withActiveTransferLock`):

| Kategori | Action Terimplementasi Aktif | Handler |
|---|---|---|
| **Hardware & Layar** | `TOGGLE_FLASHLIGHT`, `SET_VOLUME`, `FORCE_DND`, `TOGGLE_WIFI`, `LOCK_SCREEN`, `GO_HOME_SCREEN` | `FlashlightHandler`, `VolumeHandler`, `NetworkHandler`, `ScreenHandler` |
| **Sensor & Telemetri On-Demand** | `GET_BATTERY_STATUS`, `GET_SCREEN_STATUS`, `GET_LIGHT_STATUS`, `GET_NETWORK_INFO`, `GET_LOCATION` | `BatteryHandler`, `ScreenHandler`, `LightSensorHandler`, `NetworkHandler`, `LocationHandler` |
| **Media, TTS & Panggilan** | `SPEAK_TEXT`, `PLAY_RINGTONE`, `PLAY_MEDIA`, `STOP_MEDIA`, `SIMULATE_INCOMING_CALL`, `PLAY_AUDIO_STREAM`, `END_CALL`, `HANGUP` | `TtsHandler`, `AudioPlayerHandler`, `FakeCallHandler` |
| **Kamera, Overlay & Intent** | `TAKE_PHOTO` (`TransparentCameraActivity`), `SHOW_OVERLAY_MSG` (`OverlayActivity`), `LAUNCH_APP`, `OPEN_INTENT`, `GET_CLIPBOARD`, `SET_CLIPBOARD` | `CameraHandler`, `OverlayHandler`, `AppLauncherHandler`, `IntentHandler`, `ClipboardHandler` |
| **Dynamic Geofencing** | `SET_GEOFENCE`, `MARK_GEOFENCE_HERE`, `REMOVE_GEOFENCE`, `LIST_GEOFENCES` | `GeofenceHandler`, `GeofenceManager` |
| **Deprecated (Security Policy `FR-CORE-03`)** | `TAKE_SCREENSHOT` (Accessibility path), `DUMP_UI_HIERARCHY`, `ACCESSIBILITY_CLICK`, `ACCESSIBILITY_INPUT_TEXT`, `ACCESSIBILITY_SCROLL` | Mengembalikan kegagalan eksplisit karena `AccessibilityService` dihapus demi keamanan M-Banking. |

### 7.2. Real-Time Live Voice Call (`FakeCallActivity.kt` & `VoiceStreamHandler.kt`)
Mendukung panggilan AI interaktif full-duplex (`CALL_ACCEPTED` -> `CALL_LIVE_READY` -> `CALL_AUDIO_STREAM` uplink PCM 16 kHz / `CALL_AUDIO_PLAY` downlink PCM 24 kHz -> `CALL_FINISHED`), dibangun di atas `taskAffinity="com.nexa.mobilebridge.call"` dan `launchMode="singleInstance"` agar dapat membangunkan lockscreen dan kembali bersih ke aplikasi sebelumnya (`finishAndRemoveTask()`).

### 7.3. Smart Reconnect & Manajemen Daya/RAM Modern
1. **4-Phase Reconnect + Modern NetworkWatcher:**
   `ReconnectOrchestrator` tetap menjalankan retry 4 fase berjenjang (Immediate -> +1h -> +6h -> +24h) dengan alert TTS lokal menggunakan volume media aktif. `registerDefaultNetworkCallback` memicu reconnect cepat (800 ms debounce) saat berganti antara Wi-Fi dan Seluler sambil selalu memuat konfigurasi terbaru dari DataStore terenkripsi.
2. **On-Demand Power Locks (`BridgePowerLockManager.kt`):**
   Menggantikan `WakeLock`/`WifiLock` permanen lama. Kunci hanya diambil sementara saat mengeksekusi perintah/transfer aktif (maks 15 detik) atau selama panggilan suara berlangsung (batas aman 30 menit).
3. **Interval Heartbeat & Telemetri Adaptif:**
   Delta-driven + heartbeat yang dapat diatur di Settings (default **60 detik** saat layar menyala, **120 detik** saat layar mati), ditambah pembersihan antrean non-kritis bila antrean kirim OkHttp >= 16 KiB (`NFR-BAT-03`).
4. **Standby UI Hibernation (`MainActivity.kt`):**
   Saat aplikasi keluar ke Home atau layar mati (`ACTION_SCREEN_OFF`), cache GPU langsung di-trim (`TRIM_MEMORY_UI_HIDDEN`), lalu `MainActivity` dilepas (`finish()`) setelah **20 detik** di Home (**5 detik** saat layar mati, **130 detik** bila ada sesi Terminal di stack, dan dibatalkan bila sedang Voice Call, Step-Up Biometrik, streaming Chat, atau membuka file picker). Detail benchmark nyata tersedia di [RAM_USAGE_AUDIT_REPORT.md](./RAM_USAGE_AUDIT_REPORT.md).

---
*Dokumentasi Resmi Ekosistem N.E.X.A Mobile Bridge v3.0 (Diperbarui: 22 November 2026).*
