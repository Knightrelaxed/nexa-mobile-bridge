# 📑 N.E.X.A Mobile Bridge — Complete JSON Protocol & Data Exchange Specification

**Document ID:** `NEXA-MB-PROTO-001`  
**Version:** 2.0 (Production Edition)  
**Target Hardware:** Samsung Galaxy A33 5G (`SM-A336E`) — Android 16 (One UI 8 / API 36)  
**Transport:** Bidirectional WebSocket (`ws://` / `wss://`)  
**Package:** `com.nexa.mobilebridge`  

---

## 📌 1. Executive Summary & Protocol Overview

Dokumen ini merupakan **spesifikasi referensi teknis lengkap (Single Source of Truth)** untuk seluruh format payload JSON yang dipertukarkan antara **N.E.X.A Cloud/Local Server** dan **N.E.X.A Mobile Bridge (Android Client)**.

### 🔄 Alur Komunikasi Dua Arah (Bidirectional Flow):
1. **Inbound Commands (`Server ➔ Mobile Bridge`):**  
   Peladen mengirimkan instruksi perangkat keras dalam format amplop `NexaCommand`.
2. **Outbound Command Results (`Mobile Bridge ➔ Server`):**  
   Setiap eksekusi command akan membalas secara sinkron atau asinkron dengan amplop `CommandResult`.
3. **Autonomous Outbound Events (`Mobile Bridge ➔ Server`):**  
   Peristiwa yang dipicu secara mandiri oleh HP (Telemetri Periodik, Multi-Sensor Context Engine, Interupsi Panggilan Telepon, Intersepsi Alarm/Notifikasi, dan Pemicu Geofence).

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
*Mengambil tangkapan layar penuh via Accessibility API (JPEG 60%, max 1280px).*
* **Handler:** `ScreenshotHandler.kt` / `NexaAccessibilityService.kt`
* **Izin Terkait:** `BIND_ACCESSIBILITY_SERVICE`

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

**Inbound A — Buka URL (Server ➔ HP):**
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

**Inbound B — Buka Google Maps (Server ➔ HP):**
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

**Inbound C — Bagikan Teks (Server ➔ HP):**
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

### 👁️ GRUP E: Hands & Eyes UI Inspector & Automasi Aksesibilitas

#### 25. `DUMP_UI_HIERARCHY`
*Mengekstrak seluruh pohon elemen antarmuka layar HP aktif ke format JSON terstruktur.*
* **Handler:** `NexaAccessibilityService.kt`
* **Izin Terkait:** `BIND_ACCESSIBILITY_SERVICE`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_dump_01",
  "action": "DUMP_UI_HIERARCHY"
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_dump_01",
  "status": "SUCCESS",
  "data": {
    "nodes": [
      {
        "text": "Cari di YouTube",
        "desc": "Kotak Penelusuran",
        "id": "com.google.android.youtube:id/search_edit_text",
        "class": "EditText",
        "clickable": true,
        "editable": true,
        "bounds": {
          "left": 120,
          "top": 84,
          "right": 960,
          "bottom": 168
        }
      }
    ]
  },
  "message": "UI Hierarchy dumped successfully.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001500
}
```

---

#### 26. `ACCESSIBILITY_CLICK`
*Mengeklik elemen UI berdasarkan Target ID / Teks atau Koordinat Piksel Spasial `(x, y)`.*
* **Handler:** `NexaAccessibilityService.kt`

**Inbound A — Klik Berdasarkan Teks/ID (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_clk_01",
  "action": "ACCESSIBILITY_CLICK",
  "params": {
    "target": "com.google.android.youtube:id/search_edit_text"
  }
}
```

**Inbound B — Klik Berdasarkan Koordinat Spasial `(x, y)` (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_clk_02",
  "action": "ACCESSIBILITY_CLICK",
  "params": {
    "x": 540.0,
    "y": 1200.0
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_clk_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Clicked node: com.google.android.youtube:id/search_edit_text",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001200
}
```

---

#### 27. `ACCESSIBILITY_INPUT_TEXT`
*Mengisikan teks secara otomatis ke form input yang sedang aktif atau berdasarkan target Node.*
* **Handler:** `NexaAccessibilityService.kt`

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_inp_01",
  "action": "ACCESSIBILITY_INPUT_TEXT",
  "params": {
    "text": "Tutorial Kotlin Coroutines",
    "target": "com.google.android.youtube:id/search_edit_text"
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_inp_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Input text set successfully.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

---

#### 28. `ACCESSIBILITY_SCROLL`
*Melakukan simulasi usapan layar (scroll) ke atas atau bawah.*
* **Handler:** `NexaAccessibilityService.kt`
* **Directions:** `"FORWARD"` (Gulir ke bawah) \| `"BACKWARD"` / `"UP"` (Gulir ke atas).

**Inbound (Server ➔ HP):**
```json
{
  "type": "COMMAND",
  "command_id": "cmd_scr_01",
  "action": "ACCESSIBILITY_SCROLL",
  "params": {
    "direction": "FORWARD"
  }
}
```

**Outbound (HP ➔ Server - Success):**
```json
{
  "type": "COMMAND_RESULT",
  "command_id": "cmd_scr_01",
  "status": "SUCCESS",
  "data": null,
  "message": "Scroll FORWARD executed.",
  "device_name": "Samsung_A33_5G",
  "timestamp": 1786000001000
}
```

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

Jika verifikasi tanda tangan gagal, klien Android langsung menolak eksekusi perintah dan mengembalikan:
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

*Dokumen ini merupakan spesifikasi resmi N.E.X.A Mobile Bridge v2.0.*
