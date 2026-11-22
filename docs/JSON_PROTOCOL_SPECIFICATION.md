# 📑 N.E.X.A Mobile Bridge: Complete JSON & Binary Protocol Specification

**Document ID:** `NEXA-MB-PROTO-001`  
**Version:** 4.0.0 (Sovereign AI Control Center Edition, Control Center v3.1)  
**Target Hardware:** Samsung Galaxy A33 5G (`SM-A336E`), Android 16 (One UI 8 / API 36)  
**Transport:** Dual-WebSocket Architecture:
- **Connection A (`wss://<host>/ws`):** Bidirectional JSON Frames (Chat, 12 Interactive Cards, Voice Notes, Hardware Commands, Telemetry, Keystore ECDSA P-256 Auth, Finance Step-Up) & Live Voice PCM
- **Connection B (`wss://<host>/io/logs` & `wss://<host>/io/term`):** 13-Byte Big-Endian Binary Frames to Go 1.22 Sidecar (`nexa-io`) with Offline Ed25519 Ticket Auth  
**Package:** `com.nexa.mobilebridge`  

---

## 📌 1. Executive Summary & Protocol Overview

Dokumen ini merupakan **spesifikasi referensi teknis lengkap (Single Source of Truth)** untuk seluruh format payload JSON (Connection A `/ws`) maupun protokol bingkai biner 13-byte (Connection B `/io/logs` & `/io/term`) yang dipertukarkan antara **N.E.X.A Cloud Core v4.0.0** dan **N.E.X.A Mobile Bridge (Android Control Center)**.

### 🔄 Alur Komunikasi Multi-Jalur (Dual-WebSocket Flow):
1. **Inbound Hardware Commands (`Server ➔ Mobile Bridge`):**  
   Peladen mengirimkan instruksi perangkat keras dalam format amplop `NexaCommand` (`COMMAND` / `EXECUTE_COMMAND`).
2. **Outbound Command Results (`Mobile Bridge ➔ Server`):**  
   Setiap eksekusi command membalas secara sinkron atau asinkron dengan amplop `CommandResult`.
3. **Autonomous Outbound Events (`Mobile Bridge ➔ Server`):**  
   Peristiwa mandiri dari ponsel: Telemetri Periodik (`TELEMETRY_REPORT`), UsageStats App Discipline (`APP_USAGE_TELEMETRY`), Multi-Sensor Context Engine (`CONTEXT_UPDATE`), Interupsi Panggilan (`CALL_EVENT`), dan Intersepsi Alarm/Notifikasi (`ALARM_DISMISSED`, `NOTIFICATION_EVENT`).
4. **Zero-Trust Hardware Enrollment & Ed25519 Session Tickets (`/ws`):**  
   Pendaftaran kunci perangkat keras Android Keystore ECDSA P-256 (`ENROLL_SUBMIT` -> `ENROLL_OK`) dan penerbitan tiket sesi pendek Ed25519 (`TICKET_REQ` -> `CHALLENGE` -> `CHALLENGE_RESP` -> `TICKET`).
5. **Native AI Chat, Voice Notes & 10-Minute Replay (`/ws`):**  
   Percakapan streaming real-time (`CHAT_SEND`, `CHAT_VOICE_SEND`, `CHAT_ACK`, `CHAT_CHUNK`, `CHAT_RESUME`, `CHAT_STOP`, `CHAT_PROACTIVE`, `CHAT_READ`).
6. **12 Native Interactive Cards & Live Mutation (`/ws`):**  
   Kartu UI interaktif di dalam bubble chat (`FINANCE_TX` dengan informasi `Balance` real-time, `BRIEFING_SUMMARY`, `BUDGET_RECAP`, dll.) melalui `CARD_ACTION` dan `CARD_UPDATE`.
7. **Hardware Biometric Finance Step-Up Gate (`/ws`):**  
   Otorisasi transaksi finansial sensitif via tanda tangan perangkat keras ECDSA P-256 (`ACTION_PENDING` -> `ACTION_AUTH` / `ACTION_CANCEL` -> `ACTION_RESULT`).
8. **Connection B Binary Sidecars (`/io/logs` & `/io/term`):**  
   Streaming log `journald` tersedot-rahasia dan terminal PTY VT100 interaktif dengan buffer replay 64 KB.

---

## 🏛️ 2. Standar Format Amplop Utama (Envelope Schemas)

### 2.1. Amplop Perintah Peladen (`NexaCommand`)
Semua perintah dari server menuju HP **wajib** mematuhi struktur dasar berikut:

```json
{
  "type": "COMMAND",
  "command_id": "cmd_1786000000000",
  "action": "ACTION_NAME_HERE",
  "params": { ... },
  "signature": "a8f9c... (opsional untuk GodMode Level >= 3)",
  "timestamp": 1786000000000
}
```

| Field | Tipe | Wajib? | Deskripsi |
| :--- | :--- | :---: | :--- |
| `type` | String | Ya | Tetap bernilai `"COMMAND"` atau `"EXECUTE_COMMAND"`. |
| `command_id` | String | Ya | ID unik unik untuk penelusuran (misal: `cmd_1001` / `uuid`). |
| `action` | String | Ya | Konstanta nama aksi (terdaftar di `NexaActions.kt`). |
| `params` | Object | Opsional | Parameter input spesifik untuk aksi terkait. |
| `signature` | String | Opsional | Tanda tangan digital HMAC-SHA256 untuk perintah berhak akses tinggi (*GodMode*). |
| `timestamp` | Long | Opsional | Unix Epoch Milliseconds saat perintah dibuat peladen. |

---

### 2.2. Amplop Respon Klien (`CommandResult`)
Setiap kali HP selesai memproses perintah, amplop ini akan dikirimkan kembali ke peladen:

```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_1786000000000",
  "status": "SUCCESS",
  "data": { ... },
  "message": "Deskripsi hasil eksekusi manusiawi",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000002000
}
```

| Field | Tipe | Deskripsi |
| :--- | :--- | :--- |
| `type` | String | Selalu bernilai `"COMMAND_RESULT"`. |
| `command_id` | String | Merujuk pada `command_id` dari perintah asal. |
| `status` | String | `"SUCCESS"` \| `"FAILURE"` \| `"PARTIAL"`. |
| `data` | Object / null | Payload data kembalian (koordinat, base64 gambar, status sensor, node UI). |
| `message` | String | Ringkasan teks informatif atau alasan kegagalan. |
| `device_name` | String | Identitas model perangkat (`"Samsung_A33_5G"`). |
| `timestamp` | Long | Unix Epoch Milliseconds saat respon dihasilkan. |

---

## 📱 3. Spesifikasi Lengkap Per-Fitur / Command ID (32 Hardware Handlers)

---

### 🟢 GRUP A: Kontrol Hardware & Sensor Perangkat

#### 1. `TOGGLE_FLASHLIGHT`
*Mengontrol lampu kilat LED kamera HP dari jarak jauh.*
* **Handler:** `FlashlightHandler.kt`
* **Izin Terkait:** `android.permission.CAMERA`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_flash_01",
  "action": "TOGGLE_FLASHLIGHT",
  "params": {
    "enabled": true
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_flash_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Flashlight turned ON",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 2. `SET_VOLUME`
*Mengatur volume sistem suara berdasarkan nama stream dan persentase.*
* **Handler:** `VolumeHandler.kt`
* **Streams:** `"MUSIC"`, `"RING"` / `"RINGTONE"`, `"NOTIFICATION"`, `"SYSTEM"`, `"ALARM"`.

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_vol_01",
  "action": "SET_VOLUME",
  "params": {
    "stream": "MUSIC",
    "level": 75
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_vol_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Volume [MUSIC] set to 11 / 15 (75%)",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 3. `FORCE_DND`
*Memaksa HP masuk atau keluar dari mode Jangan Ganggu (Do Not Disturb / Hening Total).*
* **Handler:** `VolumeHandler.kt`
* **Izin Terkait:** `android.permission.ACCESS_NOTIFICATION_POLICY`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_dnd_01",
  "action": "FORCE_DND",
  "params": {
    "enabled": true
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_dnd_01",
  "status": "SUCCESS",
  "data": null,
  "message": "DND mode ENABLED",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 4. `GET_BATTERY_STATUS`
*Membaca status baterai real-time dan mode pengisian daya.*
* **Handler:** `BatteryHandler.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_bat_01",
  "action": "GET_BATTERY_STATUS"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_bat_01",
  "status": "SUCCESS",
  "data": {
    "battery_level": 78,
    "charging": true
  },
  "message": "Battery: 78%, Charging: true",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 5. `GET_LOCATION`
*Mengambil titik koordinat GPS presisi & mengonversinya menjadi alamat resmi via Geocoder.*
* **Handler:** `LocationHandler.kt`
* **Izin Terkait:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_loc_01",
  "action": "GET_LOCATION"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_loc_01",
  "status": "SUCCESS",
  "data": {
    "latitude": -7.762178,
    "longitude": 110.377636,
    "accuracy": 14.2,
    "provider": "fused",
    "address": "Gg. Siti Sonya, Pogung Kidul, Sinduadi, Kec. Mlati, Kabupaten Sleman, D.I. Yogyakarta"
  },
  "message": "Location retrieved successfully",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000002500
}
```

---

#### 6. `TOGGLE_WIFI`
*Mengaktifkan atau mematikan modul radio Wi-Fi HP.*
* **Handler:** `NetworkHandler.kt`
* **Izin Terkait:** `WRITE_SECURE_SETTINGS` / `CHANGE_WIFI_STATE`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_wifi_01",
  "action": "TOGGLE_WIFI",
  "params": {
    "enabled": false
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_wifi_01",
  "status": "SUCCESS",
  "data": null,
  "message": "WiFi has been disabled.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 7. `GET_NETWORK_INFO`
*Membaca status jaringan aktif, nama SSID Wi-Fi, dan kekuatan sinyal (dBm).*
* **Handler:** `NetworkHandler.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_net_01",
  "action": "GET_NETWORK_INFO"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_net_01",
  "status": "SUCCESS",
  "data": {
    "type": "WIFI",
    "ssid": "NURUL BAROKAH",
    "rssi": -48
  },
  "message": "WiFi SSID: NURUL BAROKAH, Signal: -48 dBm",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 8. `TAKE_PHOTO`
*Mengambil foto secara hening di latar belakang menggunakan CameraX (Depan / Belakang).*
* **Handler:** `CameraHandler.kt` / `TransparentCameraActivity.kt`
* **Izin Terkait:** `android.permission.CAMERA`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_cam_01",
  "action": "TAKE_PHOTO",
  "params": {
    "front_camera": false
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_cam_01",
  "status": "SUCCESS",
  "data": {
    "image_base64": "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDA..."
  },
  "message": "Photo captured successfully",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000003000
}
```

---

#### 9. `TAKE_SCREENSHOT`
*Mengambil tangkapan layar penuh secara on-demand via MediaProjection API (bebas AccessibilityService).*
* **Handler:** `ScreenshotHandler.kt` / `ScreenCaptureService.kt`
* **Izin Terkait:** `FOREGROUND_SERVICE_MEDIA_PROJECTION` (On-Demand MediaProjection Grant)

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_shot_01",
  "action": "TAKE_SCREENSHOT"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_shot_01",
  "status": "SUCCESS",
  "data": {
    "image_base64": "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDA..."
  },
  "message": "Screenshot captured successfully",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000002000
}
```

---

### 🔊 GRUP B: Sintesis Suara, Audio & Media

#### 10. `SPEAK_TEXT`
*Membacakan teks secara lisan menggunakan TTS Bahasa Indonesia Offline (Volume otomatis disesuaikan).*
* **Handler:** `TtsHandler.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_tts_01",
  "action": "SPEAK_TEXT",
  "params": {
    "text": "Selamat pagi Tuan Faqih. Semua sistem NEXA beroperasi normal."
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_tts_01",
  "status": "SUCCESS",
  "data": null,
  "message": "TTS spoken successfully",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000004500
}
```

---

#### 11. `PLAY_RINGTONE`
*Memutar suara ringtone alarm darurat sistem dengan volume maksimal 100%.*
* **Handler:** `AudioPlayerHandler.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_ring_01",
  "action": "PLAY_RINGTONE"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_ring_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Emergency Alarm Ringtone started playing at 100% volume.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 12. `PLAY_MEDIA`
*Memutar file audio dari URL web.*
* **Handler:** `AudioPlayerHandler.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_media_01",
  "action": "PLAY_MEDIA",
  "params": {
    "url": "https://example.com/audio/briefing.mp3"
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_media_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Media playback started: https://example.com/audio/briefing.mp3",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 13. `STOP_MEDIA`
*Menghentikan pemutaran audio/ringtone/media apa pun yang sedang berlangsung.*
* **Handler:** `AudioPlayerHandler.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_stop_01",
  "action": "STOP_MEDIA"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_stop_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Audio playback stopped successfully.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 14. `PLAY_AUDIO_STREAM`
*Memutar data stream audio Base64 (PCM/WAV) balasan peladen setelah panggilan interaktif selesai.*
* **Handler:** `AudioPlayerHandler.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_stream_01",
  "action": "PLAY_AUDIO_STREAM",
  "params": {
    "audio_base64": "UklGRi... (String Base64)",
    "audio_format": "PCM_16BIT_16KHZ_MONO"
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_stream_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Server audio reply playing.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

### 📞 GRUP C: Panggilan Interaktif Dua Arah v2.0 (Jarvis Call Flow)

#### 15. `SIMULATE_INCOMING_CALL`
*Membuka antarmuka panggilan layar penuh 6-state (Ringing ➔ TTS Speaking ➔ 10s Perekaman Suara ➔ Waiting ➔ Selesai).*
* **Handler:** `FakeCallHandler.kt` / `FakeCallActivity.kt`
* **Izin Terkait:** `SYSTEM_ALERT_WINDOW`, `RECORD_AUDIO`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_call_101",
  "action": "SIMULATE_INCOMING_CALL",
  "params": {
    "caller_name": "N.E.X.A Assistant",
    "message": "Tuan Faqih, waktu fokus belajar telah tiba. Ada tanggapan?",
    "play_ringtone": true
  }
}
```

**Outbound (HP ➔ Server - Initial Launch Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_call_101",
  "status": "SUCCESS",
  "data": null,
  "message": "Simulated incoming call displayed successfully.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

*(Catatan: Interaksi lanjutan dari panggilan ini dikirim melalui amplop khusus `CallEvent`, lihat Bagian 4.3).*

---

### 🪟 GRUP D: Tampilan UI, Navigasi & Intent Sistem

#### 16. `SHOW_OVERLAY_MSG`
*Menampilkan dialog Pop-Up melayang di atas semua aplikasi dengan tombol opsi dinamis dari peladen.*
* **Handler:** `OverlayHandler.kt` / `OverlayActivity.kt`
* **Izin Terkait:** `android.permission.SYSTEM_ALERT_WINDOW`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_over_01",
  "action": "SHOW_OVERLAY_MSG",
  "params": {
    "message": "Waktu istirahat Anda sudah selesai. Lanjutkan bekerja?",
    "options": [
      { "label": "NANTI (5M)", "action": "SNOOZE_5M", "color": "#FF9800" },
      { "label": "SIAP", "action": "CONTINUE", "color": "#00E676" }
    ]
  }
}
```

**Outbound Asinkron (Saat Tombol Diklik Pengguna):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_over_01",
  "status": "SUCCESS",
  "data": {
    "action": "CONTINUE"
  },
  "message": "User selected: CONTINUE",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000005000
}
```

---

#### 17. `GO_HOME_SCREEN`
*Mengembalikan HP ke Layar Utama (Home Screen).*
* **Handler:** `ScreenHandler.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_home_01",
  "action": "GO_HOME_SCREEN"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_home_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Go Home Screen executed.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 18. `LOCK_SCREEN`
*Mengunci layar HP secara instan.*
* **Handler:** `ScreenHandler.kt` / `NexaAccessibilityService.kt`
* **Izin Terkait:** `BIND_ACCESSIBILITY_SERVICE`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_lock_01",
  "action": "LOCK_SCREEN"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_lock_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Screen locked successfully.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 19. `GO_BACK`
*Menekan tombol kembali sistem (Global Back Navigation).*
* **Handler:** `ScreenHandler.kt` / `NexaAccessibilityService.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_back_01",
  "action": "GO_BACK"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_back_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Navigated BACK successfully.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 20. `SHOW_RECENTS`
*Membuka menu App Switcher (Recent Apps Overview).*
* **Handler:** `ScreenHandler.kt` / `NexaAccessibilityService.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_rec_01",
  "action": "SHOW_RECENTS"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_rec_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Opened RECENT APPS successfully.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 21. `LAUNCH_APP`
*Membuka aplikasi terpasang berdasarkan nama package Android.*
* **Handler:** `AppLauncherHandler.kt`
* **Izin Terkait:** `QUERY_ALL_PACKAGES`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_app_01",
  "action": "LAUNCH_APP",
  "params": {
    "package_name": "com.google.android.youtube"
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_app_01",
  "status": "SUCCESS",
  "data": null,
  "message": "App launched: com.google.android.youtube",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 22. `OPEN_INTENT`
*Membuka URL di browser, koordinat di Google Maps, atau membagikan teks.*
* **Handler:** `IntentHandler.kt`
* **Types:** `"OPEN_URL"`, `"OPEN_MAPS"`, `"SHARE_TEXT"`.

**Inbound A - Buka URL (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_int_01",
  "action": "OPEN_INTENT",
  "params": {
    "type": "OPEN_URL",
    "url": "https://github.com"
  }
}
```

**Inbound B - Buka Google Maps (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_int_02",
  "action": "OPEN_INTENT",
  "params": {
    "type": "OPEN_MAPS",
    "lat": -7.762178,
    "lng": 110.377636,
    "query": "UGM Yogyakarta"
  }
}
```

**Inbound C - Bagikan Teks (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_int_03",
  "action": "OPEN_INTENT",
  "params": {
    "type": "SHARE_TEXT",
    "text": "Catatan tugas dari NEXA AI"
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_int_01",
  "status": "SUCCESS",
  "data": null,
  "message": "URL opened successfully: https://github.com",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 23. `GET_CLIPBOARD`
*Membaca isi teks papan klip (clipboard) sistem HP.*
* **Handler:** `ClipboardHandler.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_clip_01",
  "action": "GET_CLIPBOARD"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_clip_01",
  "status": "SUCCESS",
  "data": {
    "text": "https://chatgpt.com"
  },
  "message": "Clipboard read successfully.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 24. `SET_CLIPBOARD`
*Menuliskan teks baru ke papan klip (clipboard) sistem HP.*
* **Handler:** `ClipboardHandler.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_clip_02",
  "action": "SET_CLIPBOARD",
  "params": {
    "text": "Kode OTP: 983210"
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_clip_02",
  "status": "SUCCESS",
  "data": {
    "text": "Kode OTP: 983210"
  },
  "message": "Clipboard updated successfully.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

### 👁️ GRUP E: Eks-Aksesibilitas (Deprecated di v4.0.0 Demi Keamanan M-Banking / `FR-CORE-03`)

> **Catatan Arsitektur v4.0.0 (`FR-CORE-03`):** Layanan `NexaAccessibilityService` telah **dihapus permanen** dari `AndroidManifest.xml` dan codebase agar aplikasi perbankan (*Livin' by Mandiri, Bank Jago, BCA mobile, BRImo*) tidak pernah mendeteksi layanan aksesibilitas aktif sebagai ancaman *overlay/screen-reader*. Pemantauan disiplin aplikasi kini beralih sepenuhnya ke `UsageStatsManager` (`AppUsageTracker.kt`). Keempat aksi di bawah ini tetap dikenali oleh `DeviceCommandDispatcher.kt` demi kompatibilitas protokol mundur, namun secara deterministik mengembalikan `status: "FAILURE"`.

#### 25. `DUMP_UI_HIERARCHY` *(Deprecated - Banking-Safe Guard)*
* **Handler:** `DeviceCommandDispatcher.kt` (`deprecatedAccessibilityCommand`)

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_dump_01",
  "action": "DUMP_UI_HIERARCHY"
}
```

**Outbound (HP ➔ Server - Deterministic Deprecation Response):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_dump_01",
  "status": "FAILURE",
  "data": null,
  "message": "Command DUMP_UI_HIERARCHY is deprecated: AccessibilityService removed for m-banking security (FR-CORE-03).",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001500
}
```

---

#### 26. `ACCESSIBILITY_CLICK` *(Deprecated - Banking-Safe Guard)*
* **Handler:** `DeviceCommandDispatcher.kt` (`deprecatedAccessibilityCommand`)

**Outbound (HP ➔ Server):**
Mengembalikan `status: "FAILURE"` dengan pesan `"Command ACCESSIBILITY_CLICK is deprecated: AccessibilityService removed for m-banking security (FR-CORE-03)."`

---

#### 27. `ACCESSIBILITY_INPUT_TEXT` *(Deprecated - Banking-Safe Guard)*
* **Handler:** `DeviceCommandDispatcher.kt` (`deprecatedAccessibilityCommand`)

**Outbound (HP ➔ Server):**
Mengembalikan `status: "FAILURE"` dengan pesan `"Command ACCESSIBILITY_INPUT_TEXT is deprecated: AccessibilityService removed for m-banking security (FR-CORE-03)."`

---

#### 28. `ACCESSIBILITY_SCROLL` *(Deprecated - Banking-Safe Guard)*
* **Handler:** `DeviceCommandDispatcher.kt` (`deprecatedAccessibilityCommand`)

**Outbound (HP ➔ Server):**
Mengembalikan `status: "FAILURE"` dengan pesan `"Command ACCESSIBILITY_SCROLL is deprecated: AccessibilityService removed for m-banking security (FR-CORE-03)."`

---

### 📍 GRUP F: Dynamic Geofencing & Pagar Virtual

#### 29. `SET_GEOFENCE`
*Mendaftarkan zona pagar virtual baru dengan koordinat dan radius tertentu.*
* **Handler:** `GeofenceHandler.kt` / `GeofenceManager.kt`
* **Izin Terkait:** `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_geo_01",
  "action": "SET_GEOFENCE",
  "params": {
    "id": "KANTOR",
    "name": "Kantor Pusat",
    "latitude": -7.762178,
    "longitude": 110.377636,
    "radius_meters": 150.0
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_geo_01",
  "status": "SUCCESS",
  "data": {
    "id": "KANTOR",
    "name": "Kantor Pusat",
    "latitude": -7.762178,
    "longitude": 110.377636,
    "radius_meters": 150.0
  },
  "message": "Geofence [KANTOR] (Kantor Pusat) registered successfully at (-7.762178, 110.377636) with radius 150.0m",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001500
}
```

---

#### 30. `MARK_GEOFENCE_HERE`
*Mengambil GPS saat ini dan langsung menjadikannya titik Geofence tersimpan.*
* **Handler:** `GeofenceHandler.kt` / `GeofenceManager.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_geo_02",
  "action": "MARK_GEOFENCE_HERE",
  "params": {
    "id": "HOME",
    "name": "Rumah Pribadi",
    "radius_meters": 100.0
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_geo_02",
  "status": "SUCCESS",
  "data": {
    "id": "HOME",
    "name": "Rumah Pribadi",
    "latitude": -7.762178,
    "longitude": 110.377636,
    "radius_meters": 100.0
  },
  "message": "📍 Lokasi saat ini berhasil ditandai sebagai Geofence [HOME] (Rumah Pribadi) pada (-7.762178, 110.377636)",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000002000
}
```

---

#### 31. `REMOVE_GEOFENCE`
*Menghapus titik geofence aktif berdasarkan ID.*
* **Handler:** `GeofenceHandler.kt` / `GeofenceManager.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_geo_03",
  "action": "REMOVE_GEOFENCE",
  "params": {
    "id": "KANTOR"
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_geo_03",
  "status": "SUCCESS",
  "data": null,
  "message": "Geofence [KANTOR] removed successfully.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 32. `LIST_GEOFENCES`
*Membaca seluruh daftar zona geofence yang tersimpan di DataStore HP.*
* **Handler:** `GeofenceHandler.kt` / `GeofenceManager.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_geo_04",
  "action": "LIST_GEOFENCES"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_geo_04",
  "status": "SUCCESS",
  "data": {
    "total": 2,
    "zones": [
      {
        "id": "HOME",
        "name": "Rumah Pribadi",
        "latitude": -7.762178,
        "longitude": 110.377636,
        "radius_meters": 100.0,
        "created_at": 1786000000000
      },
      {
        "id": "KANTOR",
        "name": "Kantor Pusat",
        "latitude": -7.755100,
        "longitude": 110.381200,
        "radius_meters": 150.0,
        "created_at": 1786000050000
      }
    ]
  },
  "message": "Found 2 active geofence zone(s).",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

## ⚡ 4. Spesifikasi Event Otonom (Autonomous Outbound Events)

Event-event berikut dipancarkan secara otomatis oleh **N.E.X.A Mobile Bridge** ke **N.E.X.A Server** tanpa perlu menunggu perintah (*Proactive Sensing*).

---

### 4.1. Laporan Telemetri Periodik (`TELEMETRY_REPORT`)
*Dipancarkan otomatis setiap 60 detik oleh `NexaBridgeService`.*

```json
{
  "type": "TELEMETRY_REPORT",
  "battery_level": 82,
  "battery_charging": true,
  "network_type": "WIFI",
  "network_strength": -45,
  "motion_state": "STATIONARY",
  "screen_on": true,
  "timestamp": 1786000060000
}
```

---

### 4.2. Sintesis Konteks Multi-Sensor (`CONTEXT_UPDATE`)
*Dipancarkan oleh `ContextEngine` saat aturan multi-sensor terpenuhi.*

#### A. Ruangan Gelap di Malam Hari (`ROOM_DARK_NIGHT`):
```json
{
  "type": "CONTEXT_UPDATE",
  "event": "ROOM_DARK_NIGHT",
  "summary": "Ruangan dalam kondisi gelap di malam hari",
  "details": {
    "lux": 1.2
  },
  "timestamp": 1786000070000
}
```

#### B. HP Diangkat di Pagi Hari (`PICKUP_MORNING`):
```json
{
  "type": "CONTEXT_UPDATE",
  "event": "PICKUP_MORNING",
  "summary": "HP diangkat pada pagi hari (Potensi Morning Briefing)",
  "details": {
    "timestamp": 1786000080000
  },
  "timestamp": 1786000080000
}
```

#### C. Milestone Langkah Kaki Kumulatif (`STEP_MILESTONE`):
```json
{
  "type": "CONTEXT_UPDATE",
  "event": "STEP_MILESTONE",
  "summary": "Pencapaian 5000 langkah kaki tercapai",
  "details": {
    "total_steps": 5120,
    "milestone": 5000
  },
  "timestamp": 1786000090000
}
```

#### D. Transisi Geofence Area (`USER_ARRIVED_HOME` / `USER_LEFT_HOME`):
```json
{
  "type": "CONTEXT_UPDATE",
  "event": "USER_ARRIVED_HOME",
  "summary": "Pengguna telah tiba di rumah",
  "details": {
    "geofence_id": "HOME",
    "transition": "ENTER"
  },
  "timestamp": 1786000100000
}
```

---

### 4.3. Event Panggilan Telepon Interaktif (`CALL_EVENT`)
*Dipancarkan oleh `FakeCallActivity` selama siklus interaksi suara 6-state.*

#### A. Panggilan Diterima (`CALL_ACCEPTED`):
```json
{
  "type": "CALL_EVENT",
  "event": "CALL_ACCEPTED",
  "command_id": "cmd_call_101",
  "caller_name": "N.E.X.A Assistant",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000002000
}
```

#### B. Panggilan Ditolak (`CALL_REJECTED`):
```json
{
  "type": "CALL_EVENT",
  "event": "CALL_REJECTED",
  "command_id": "cmd_call_101",
  "caller_name": "N.E.X.A Assistant",
  "rejection_count": 1,
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000002500
}
```

#### C. Rekaman Balasan Suara Pengguna (`CALL_AUDIO_REPLY`):
*Mengirimkan 10 detik rekaman audio PCM 16kHz 16-bit Mono (128.000 Bytes) untuk ditranskripsi oleh Whisper STT peladen.*
```json
{
  "type": "CALL_EVENT",
  "event": "CALL_AUDIO_REPLY",
  "command_id": "cmd_call_101",
  "caller_name": "N.E.X.A Assistant",
  "audio_base64": "UklGRi... (String Base64 PCM 16kHz 16-bit Mono)",
  "audio_format": "PCM_16BIT_16KHZ_MONO",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000013000
}
```

---

### 4.4. Event Intersepsi Notifikasi & Alarm Sistem

#### A. Pemadaman Alarm Jam (`ALARM_DISMISSED`):
*Dipancarkan oleh `NexaNotifListenerService` saat notifikasi alarm Samsung/Google Clock di-dismiss.*
```json
{
  "type": "ALARM_DISMISSED",
  "source_package": "com.sec.android.app.clockpackage",
  "timestamp": 1786000020000
}
```

#### B. Notifikasi Finansial Terdeteksi (`NOTIFICATION_EVENT`):
*Dipancarkan saat notifikasi transaksi bank/e-wallet masuk (BCA, GoPay, OVO, ShopeePay, Mandiri, BNI).*
```json
{
  "type": "NOTIFICATION_EVENT",
  "source_package": "com.bca",
  "title": "Transaksi Berhasil",
  "text": "Transfer keluar Rp 50.000 ke Rekening 1234567890 berhasil.",
  "timestamp": 1786000030000
}
```

---

## 🔐 5. Keamanan & Verifikasi GodMode HMAC-SHA256

Untuk perintah-perintah berhak akses tinggi (*GodMode Level ≥ 3* seperti `LOCK_SCREEN`, `FORCE_DND`, `DISABLE_WIFI`), peladen menyertakan field `signature` dalam payload `NexaCommand`:

$$\text{Signature} = \text{HMAC-SHA256}\left(\text{timestamp} + \text{"."} + \text{level}, \text{hmacSecret}\right)$$

If signature verification fails, the Android client rejects execution:
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_god_01",
  "status": "FAILURE",
  "data": null,
  "message": "HMAC signature verification failed. GodMode command rejected.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

## 🔑 6. Zero-Trust Hardware Enrollment & Ed25519 Session Ticket Protocol (`/ws`)

Diimplementasikan oleh `DeviceKeystoreManager.kt` & `TicketAuthRepository.kt` di Android serta `src/core/auth/ws_auth_handler.js` & `ticket_service.js` di Server.

### 6.1. Pendaftaran Kunci Publik Perangkat Keras (`ENROLL_SUBMIT` ➔ `ENROLL_OK` / `ENROLL_ERR`)
Mengikat kunci publik **Android Keystore ECDSA P-256** dengan kode sekali pakai dari CLI `node scripts/nexa-enroll.js`.

**Outbound (HP ➔ Server):**
```json
{
  "type": "ENROLL_SUBMIT",
  "req_id": "enroll_1786000100",
  "device_id": "samsung-a33-tuan",
  "enroll_code": "NX-8492-ABCD",
  "public_key_spki_der_b64": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE..."
}
```

**Inbound Sukses (Server ➔ HP):**
```json
{
  "type": "ENROLL_OK",
  "req_id": "enroll_1786000100",
  "device_id": "samsung-a33-tuan",
  "fingerprint_sha256": "49:19:7c:0a:54:a9:65:77:...",
  "enrolled_at": "2026-11-22T08:00:00.000Z",
  "status": "ACTIVE"
}
```

### 6.2. Permintaan Tiket Sesi Sidecar (`TICKET_REQ` ➔ `CHALLENGE` ➔ `CHALLENGE_RESP` ➔ `TICKET`)
Digunakan untuk mendapatkan tiket **Ed25519** berumur singkat (scope `"logs"` atau `"terminal"`) sebelum membuka Connection B (`/io/logs` atau `/io/term`).

**1. Outbound `TICKET_REQ` (HP ➔ Server):**
```json
{
  "type": "TICKET_REQ",
  "req_id": "tkt_01",
  "device_id": "samsung-a33-tuan",
  "scope": "logs",
  "resume_sid": ""
}
```

**2. Inbound `CHALLENGE` (Server ➔ HP):**
```json
{
  "type": "CHALLENGE",
  "req_id": "tkt_01",
  "nonce": "b7f4c2e981a04d56...",
  "scope": "logs",
  "sid": "sid_9a8b7c6d",
  "exp": 1786000130000
}
```

**3. Outbound `CHALLENGE_RESP` (HP ➔ Server):**
Klien menandatangani payload kanonikal tantangan menggunakan kunci privat ECDSA P-256 di dalam Hardware Keystore:
```json
{
  "type": "CHALLENGE_RESP",
  "req_id": "tkt_01",
  "device_id": "samsung-a33-tuan",
  "nonce": "b7f4c2e981a04d56...",
  "signature_der_b64": "MEUCIQDf..."
}
```

**4. Inbound `TICKET` (Server ➔ HP):**
```json
{
  "type": "TICKET",
  "req_id": "tkt_01",
  "ticket": "eyJ2IjoxLCJkZXZpY2VfaWQiOiJzYW1zdW5nLWEzMy10dWFuIiwic2NvcGUiOiJsb2dzIi...",
  "scope": "logs",
  "sid": "sid_9a8b7c6d",
  "exp": 1786000160000
}
```

---

## 💬 7. Native Mobile Chat, Voice Notes & 10-Minute Replay Protocol (`/ws`)

Diimplementasikan oleh `ChatRepository.kt` & `ChatStreamConsumer.kt` di Android serta `src/interfaces/bridge/mobile_bridge/chat_handler.js` di Server.

### 7.1. Kirim Pesan Teks & Slash Command (`CHAT_SEND`)
Mendukung idempotensi penuh berbasis `client_msg_id` (maks 128 karakter), batas konten 16 KB, konteks kutipan balasan (`reply_to`), dan perintah slash (`/briefing`, `/budget`, `/vault`, `/search`, `/playbook`, `/capabilities`).

**Outbound (HP ➔ Server):**
```json
{
  "type": "CHAT_SEND",
  "client_msg_id": "cmsg_1786000200_a1b2",
  "content": "Tadi beli kopi 15rb pakai jago qris",
  "intent_hint": "FINANCE",
  "reply_to": {
    "message_id": "smsg_991",
    "role": "assistant",
    "preview": "Transaksi Pemasukan sebesar Rp150.000 berhasil dicatat"
  }
}
```

### 7.2. Kirim Pesan Suara Native (`CHAT_VOICE_SEND`)
Mendukung kontainer audio `audio/ogg` (`OggS`), `audio/mp4` / `audio/m4a` (`ftyp`), dan `audio/webm` (`EBML`) hingga batas keras **1,5 MB** (~3 menit / 185.000 ms).

**Outbound (HP ➔ Server):**
```json
{
  "type": "CHAT_VOICE_SEND",
  "client_msg_id": "vmsg_1786000210_c3d4",
  "audio_b64": "T2dnUwACAAAAAAAAAAB...",
  "mime_type": "audio/ogg",
  "duration_ms": 8450,
  "waveform": [12, 35, 68, 91, 54, 20]
}
```

### 7.3. Status Pemrosesan & Transkripsi (`CHAT_ACK`)
**Inbound (Server ➔ HP):**
```json
{
  "type": "CHAT_ACK",
  "client_msg_id": "vmsg_1786000210_c3d4",
  "status": "THINKING",
  "transcript": "Tadi beli kopi lima belas ribu pakai jago qris"
}
```
*(Nilai `status`: `"TRANSCRIBING"` | `"THINKING"`).*

### 7.4. Aliran Potongan Teks & Penyelesaian Giliran (`CHAT_CHUNK`)
Setiap potongan teks dikirim berurutan (`seq = 1..N`, ukuran default 64 karakter), ditutup dengan frame `STREAM_FINAL` yang dapat menyertakan objek kartu interaktif `card`.

**Inbound A - Potongan Streaming (`STREAMING_CHUNK`):**
```json
{
  "type": "CHAT_CHUNK",
  "client_msg_id": "cmsg_1786000200_a1b2",
  "seq": 1,
  "status": "STREAMING_CHUNK",
  "chunk": "Siap Tuan, transaksi pengeluaran Rp15.000 via Bank Jago (QRIS) "
}
```

**Inbound B - Frame Penutup & Kartu Terlampir (`STREAM_FINAL`):**
```json
{
  "type": "CHAT_CHUNK",
  "client_msg_id": "cmsg_1786000200_a1b2",
  "seq": 2,
  "status": "STREAM_FINAL",
  "chunk": "",
  "intent": "FINANCE",
  "card": {
    "version": 1,
    "card_id": "fin_15000_kopi",
    "card_type": "FINANCE_TX",
    "status": "PENDING"
  }
}
```

**Inbound C - Pembatalan / Galat (`ERROR_ABORT`):**
```json
{
  "type": "CHAT_CHUNK",
  "client_msg_id": "cmsg_1786000200_a1b2",
  "seq": 2,
  "status": "ERROR_ABORT",
  "chunk": "",
  "error": "Dibatalkan oleh pengguna."
}
```

### 7.5. Sinkronisasi Ulang Setelah Putus Jaringan (`CHAT_RESUME` / `CHAT_RESUME_MISS` / `CHAT_STOP`)
Server menyimpan riwayat chunk di memori selama **10 menit** (maks 100 giliran) sehingga ponsel yang berpindah dari Wi-Fi ke 5G dapat melanjutkan stream tanpa kehilangan satu huruf pun.

**Outbound `CHAT_RESUME` (HP ➔ Server):**
```json
{
  "type": "CHAT_RESUME",
  "client_msg_id": "cmsg_1786000200_a1b2",
  "last_seq": 1
}
```
Jika ditemukan, server memutar ulang seluruh `CHAT_CHUNK` dengan `seq > last_seq` (serta `ACTION_PENDING` yang belum kedaluwarsa). Jika sudah lewat 10 menit, server membalas `{"type": "CHAT_RESUME_MISS", "client_msg_id": "..."}`. Untuk menghentikan generasi yang sedang berjalan, klien mengirim `{"type": "CHAT_STOP", "client_msg_id": "..."}`.

### 7.6. Pesan Proaktif Lintas-Kanal (`CHAT_PROACTIVE`)
Dikirim oleh `Notifier.js` / `client.js` saat terjadi peristiwa latar belakang (Morning Briefing, Gmail Auto-Sync Mandiri/Jago, Peringatan Jadwal, atau Alert Disiplin):
```json
{
  "type": "CHAT_PROACTIVE",
  "server_msg_id": "pro_1786000300",
  "kind": "finance_tx_confirm",
  "priority": "P0",
  "content": "Sinkronisasi Keuangan Terbaru: Pengeluaran Rp15.000 di Kopi Kenangan.",
  "card": { ... },
  "ts": 1786000300000
}
```

---

## 🃏 8. Spesifikasi 12 Kartu Interaktif Native (`card`, `CARD_ACTION`, `CARD_UPDATE`)

Diimplementasikan oleh `src/interfaces/bridge/mobile_bridge/card_handler.js` dan dirender secara native oleh `InteractiveCardView.kt`.

### 8.1. Katalog 12 Tipe Kartu (`card_type`)
1. `FINANCE_TX`: Konfirmasi/edit transaksi keuangan (*receipt-first* layout, live account `Balance`, picker Akun/Metode/Kategori, Hard Account Gate, dan countdown auto-save 5 menit).
2. `RECURRING_BILL`: Persetujuan deteksi langganan/tagihan berulang (`approve`, `reject`).
3. `IDENTITY_PROPOSAL`: Persetujuan hipotesis sifat/direktif baru hasil *Weekly Cognitive Pass* (`approve`, `reject` dengan alasan opsional).
4. `MEMORY_HYGIENE`: Pemangkasan memori `PHASE` yang meluruh berdasarkan kurva Ebbinghaus (`archive_selected`, `archive_all`, `keep_all`).
5. `DISCIPLINE_CHECK`: Intervensi batas waktu aplikasi (`valid_research`, `extend_10m` maks 2x/hari, `lock_focus`).
6. `CALENDAR_CONFLICT`: Resolusi bentrok jadwal Google Calendar (`force_create`, `cancel_create`, `pick_slot`).
7. `FINANCE_DELETE`: Konfirmasi penghapusan transaksi beserta sibling `split_group_id` (`confirm_delete`, `cancel_delete`).
8. `TASK_CONFIRM`: Pemilihan kategori daftar tugas Google Tasks (`pick_category`, `cancel`).
9. `PROACTIVE_TASKS`: Daftar checklist tugas harian interaktif dari Morning Briefing (`complete_selected`).
10. `VAULT_METADATA`: Editor metadata catatan *2nd Brain Vault* (`update_vault`, `delete_vault`).
11. `BRIEFING_SUMMARY`: Kartu ringkasan eksekutif Morning/Evening Briefing dengan *quick-prompt chips*.
12. `BUDGET_RECAP`: Grafik batang progres anggaran bulanan/mingguan beserta indikator *safe-to-spend*.

### 8.2. Skema Lengkap Kartu `FINANCE_TX` (Termasuk Field `Balance` Real-Time)
```json
{
  "version": 1,
  "card_id": "fin_150000_khansa",
  "card_type": "FINANCE_TX",
  "status": "PENDING",
  "title": "New Transaction Detected",
  "subtitle": "Bank Jago · Transfer bank",
  "badge": "Income",
  "accent": "EMERALD",
  "hero_value": "+Rp 150.000",
  "hero_tone": "INCOME",
  "expires_at": 1786000600000,
  "expires_label": "Auto-saves in",
  "prompt_text": "",
  "fields": [
    { "key": "time", "label": "Time", "value": "22 Nov · 15:55 WIB" },
    { "key": "merchant", "label": "Merchant", "value": "KHANSA QONITA" },
    { "key": "category", "label": "Category", "value": "Pendapatan (umum)" },
    { "key": "account", "label": "Account", "value": "Bank Jago", "tone": "NEUTRAL" },
    { "key": "balance", "label": "Balance", "value": "Rp1.250.000" },
    { "key": "payment_method", "label": "Method", "value": "Transfer bank", "tone": "NEUTRAL" },
    { "key": "note", "label": "Note", "value": "pendapatan kepanitiaan", "tone": "NEUTRAL" }
  ],
  "categories": ["Pendapatan (umum)", "Gaji, faktur", "Lainnya"],
  "accounts": ["Bank Mandiri", "Bank Jago", "Dana", "Shopee Pay", "Tunai"],
  "payment_methods": ["QRIS", "Direct Debit", "Transfer bank", "Kartu Kredit", "Tunai"],
  "meta": {
    "composite_key": "150000_khansa",
    "nominal": 150000,
    "note": "pendapatan kepanitiaan",
    "category": "Pendapatan (umum)",
    "account": "Bank Jago",
    "payment_method": "Transfer bank",
    "balance": "Rp1.250.000",
    "time_display": "22 Nov · 15:55 WIB"
  },
  "actions": [
    { "id": "confirm", "label": "Confirm", "style": "PRIMARY" },
    { "id": "account", "label": "Account", "style": "SECONDARY", "kind": "PICK_ACCOUNT" },
    { "id": "payment_method", "label": "Method", "style": "SECONDARY", "kind": "PICK_METHOD" },
    { "id": "category", "label": "Category", "style": "SECONDARY", "kind": "PICK_CATEGORY" },
    { "id": "edit", "label": "Edit", "style": "SECONDARY", "kind": "EDIT_FINANCE" },
    { "id": "cancel", "label": "Cancel", "style": "DESTRUCTIVE" }
  ]
}
```
*(Catatan: Jika akun belum dipilih, field `balance` tidak dikirim dan `expires_at` bernilai `0` karena Hard Account Gate menahan auto-save sampai akun dipilih. Untuk transfer antar-akun, `balance` menampilkan kedua saldo akun, misal `"Dana Rp350.000 · Bank Jago Rp1.250.000"`).*

### 8.3. Aksi Pengguna pada Kartu (`CARD_ACTION`) & Patch Server (`CARD_UPDATE`)

**Outbound `CARD_ACTION` (HP ➔ Server):**
```json
{
  "type": "CARD_ACTION",
  "card_id": "fin_150000_khansa",
  "card_type": "FINANCE_TX",
  "action_id": "confirm",
  "payload": {
    "composite_key": "150000_khansa"
  }
}
```

**Inbound `CARD_UPDATE` Setelah Konfirmasi (Server ➔ HP):**
Server mengambil saldo terbaru pasca-pencatatan dari Supabase, memperbarui `updated_card` (`meta.balance` & field `balance` kini mencerminkan saldo akhir setelah transaksi masuk), dan mengunci status kartu menjadi `RESOLVED`:
```json
{
  "type": "CARD_UPDATE",
  "card_id": "fin_150000_khansa",
  "status": "RESOLVED",
  "resolved_badge": "Recorded",
  "resolved_detail": "Bank Jago · Transfer bank · Pendapatan (umum) · Saldo Rp1.400.000",
  "updated_card": { ... },
  "ts": 1786000315000
}
```

---

## 🛡️ 9. Hardware-Biometric Finance Step-Up Gate (`ACTION_PENDING` ➔ `ACTION_AUTH` ➔ `ACTION_RESULT`)

Diimplementasikan oleh `src/core/auth/finance_stepup_service.js` dan `FinanceStepUpBottomSheet.kt`. Ditegakkan langsung pada lapisan eksekusi tool Node.js dengan TTL **30 detik**, nonce sekali pakai, validasi daftar putih penerima (*recipient whitelist*), dan batas nominal harian server.

### 9.1. Tantangan Otorisasi Finansial (`ACTION_PENDING`)
**Inbound (Server ➔ HP):**
```json
{
  "type": "ACTION_PENDING",
  "action_id": "act-35358e02",
  "client_msg_id": "cmsg_1786000400",
  "nonce": "f19a8c3d7e2b4019...",
  "ttl_sec": 30,
  "exp": 1786000430000,
  "canonical_hash": "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918",
  "display": {
    "title": "Otorisasi Pembayaran Tagihan",
    "recipient": "PT PLN (Persero) Tagihan Listrik",
    "bank": "Bank Mandiri",
    "acct_last4": "7710",
    "amount_rp": 350000,
    "admin_fee_rp": 2500,
    "total_rp": 352500,
    "formatted_total": "Rp 352.500",
    "daily_remaining_after_rp": 1647500
  }
}
```

### 9.2. Pengiriman Tanda Tangan Biometrik (`ACTION_AUTH` / `ACTION_CANCEL`)
Setelah pengguna memindai sidik jari pada `BiometricPrompt` (`CryptoObject`), perangkat keras Android Keystore menandatangani string kanonikal berikut dengan algoritma `SHA256withECDSA` (P-256):
$$\text{Payload} = \text{"nexa-action-v1\textbackslash n"} + \text{nonce} + \text{"\textbackslash n"} + \text{action\_id} + \text{"\textbackslash n"} + \text{canonical\_hash}$$

**Outbound `ACTION_AUTH` (HP ➔ Server):**
```json
{
  "type": "ACTION_AUTH",
  "action_id": "act-35358e02",
  "device_id": "samsung-a33-tuan",
  "nonce": "f19a8c3d7e2b4019...",
  "sig": "MEYCIQDa..."
}
```
*(Untuk membatalkan, klien mengirim `{"type": "ACTION_CANCEL", "action_id": "act-35358e02", "device_id": "samsung-a33-tuan", "reason": "USER_CANCELLED"}`).*

### 9.3. Hasil Eksekusi Terkunci (`ACTION_RESULT`)
**Inbound (Server ➔ HP):**
```json
{
  "type": "ACTION_RESULT",
  "action_id": "act-35358e02",
  "client_msg_id": "cmsg_1786000400",
  "status": "EXECUTED",
  "ok": true,
  "code": "OK",
  "daily_remaining": 1647500,
  "formatted_daily_remaining": "Rp 1.647.500",
  "message": "Otorisasi biometrik valid. Pembayaran Rp 352.500 ke PT PLN (Persero) Tagihan Listrik berhasil dieksekusi."
}
```

---

## ⚡ 10. Connection B Binary Framing Protocol (`/io/logs` & `/io/term`, Go Sidecar `nexa-io`)

Diimplementasikan oleh `nexa-io/internal/proto/frame.go` di sisi VPS serta `LogBinaryProtocol.kt` & `TerminalBinaryProtocol.kt` di sisi Android.

### 10.1. Struktur Header Biner 13-Byte (Big-Endian)
Setiap frame WebSocket Binary pada `/io/logs` dan `/io/term` diawali oleh header tetap **13 byte** diikuti oleh `Payload` sepanjang `Length` (maksimum 4.096 byte pada jalur panas):

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Channel (1B) |  Opcode (1B)  |   Flags (1B)  |   Seq (MSB)   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     Seq (3B LSB, uint32 BE)                   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                Aux / Credit / Meta (4B, uint32 BE)            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Length (2B, uint16 BE)  |          Payload ...          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 10.2. Konstanta Channel ID & Opcode
- **Channel IDs:**
  - `0x00`: Control Channel (`ChControl`)
  - `0x01`: Telemetry Channel (`ChTelemetry`)
  - `0x02`: Chat Channel (`ChChat`)
  - `0x03`: Systemd Journal Log Stream (`ChLogs`, `/io/logs`)
  - `0x10..0xFF`: Interactive VT100 Terminal PTY Sessions (`ChTermMin..ChTermMax`, `/io/term`)
- **Opcodes:**
  - `0x01` (`OpOpen`): Buka kanal dengan payload tiket Ed25519 / filter unit log / ukuran awal PTY.
  - `0x02` (`OpData`): Aliran baris log UTF-8 berredaksi rahasia atau byte I/O PTY terminal.
  - `0x03` (`OpCredit`): Pembaruan kredit aliran (*flow-control window*) dari klien ke sidecar.
  - `0x04` (`OpClose`): Penutupan kanal (`0=Normal`, `1=ClientExit`, `2=Idle`, `3=SessionLimit`, `4=Auth`, `5=ServerStop`, `6=DetachExpired`).
  - `0x05` (`OpResize`): Pembaruan dimensi kolom & baris layar VT100 (`cols: u16, rows: u16`).
  - `0x06` (`OpErr`): Kode galat protokol (`1=InvalidFrame`, `2=Unauthorized`, `3=ForbiddenUnit`, `4=RateLimited`, `5=FeatureOff`, `6=ResourceLimit`, `7=ResumeFailed`).
  - `0x07` / `0x08` (`OpPing` / `OpPong`): Detak jantung koneksi biner.
  - `0x09` (`OpResume`): Penyambungan ulang sesi PTY dari ring buffer 64 KB berdasarkan `last_seq`.

### 10.3. Unit IDs Log Streamer (`ChLogs = 0x03`)
- `0x00`: `nexa-server.service`
- `0x01`: `caddy.service`
- `0x02`: `systemd`
- `0x03`: `nexa-io` (`nexa-log.service` & `nexa-term.service`)

---

*Dokumen ini merupakan spesifikasi resmi N.E.X.A Mobile Bridge & Sovereign AI Control Center v4.0.0.*
