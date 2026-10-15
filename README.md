# TWS Audio Analyzer

App Android untuk melihat grafik suara secara realtime dari **audio yang benar-benar diputar HP**, yaitu sinyal digital yang dikirim ke TWS/earphone. Datanya **bukan** dari mikrofon.

## Download & install

1. Buka halaman **[Releases](../../releases/latest)** dari HP.
2. Download file `TWS-Audio-Analyzer-v1.0.x.apk`.
3. Buka file tersebut, lalu izinkan "Instal aplikasi tidak dikenal" untuk browser/My Files jika diminta.
4. Versi baru bisa langsung di-install menimpa versi lama (kunci tanda tangannya sama).

Minimal Android 10. Sudah ditargetkan untuk Galaxy S25 (One UI 7/8).

## Cara pakai

1. Putar lagu (Apple Music, Spotify, YouTube Music, file lokal, dll) dengan TWS tersambung.
2. Buka app, lalu tekan **▶ Audio internal**. Android akan meminta:
   - izin **Mikrofon**: ini wajib untuk *semua* jenis tangkapan audio, termasuk audio internal. Mic tidak dibuka.
   - izin **rekam/bagikan layar**: syarat sistem untuk membaca audio internal. Layar tidak direkam.
3. Kalau app pemutar memblokir tangkapan (Apple Music biasanya begitu karena DRM), app otomatis pindah ke **mode Visualizer**.

### Dua mode sumber

| Mode | Kualitas | Catatan |
|---|---|---|
| **Audio internal** (AudioPlaybackCapture) | PCM 16-bit stereo 48 kHz, FFT sampai 16384 | Bisa diblokir oleh app ber-DRM |
| **Visualizer** (output mix sistem) | 8-bit mono, 1024 sampel (±47 Hz/bin) | Jalan di hampir semua app, termasuk Apple Music |

Keduanya membaca sinyal digital dari HP, bukan dari mikrofon.

## Fitur

- **Spektrum** 20 Hz–20 kHz, skala log dalam dBFS, dengan garis peak hold. Tahan atau geser jari di grafik untuk menampilkan kursor frekuensi, level, dan nada.
- **Spektrogram** (waterfall berwarna).
- **Gelombang** (oscilloscope dengan trigger; ketuk untuk zoom), L/R.
- **Level meter** L/R: RMS, peak, peak hold, indikator CLIP, jumlah sampel clipping, crest factor.
- **Loudness LUFS** (ITU-R BS.1770 / EBU R128): Momentary, Short-term, Integrated.
- **Energi 7 band**: Sub-bass, Bass, Low-mid, Mid, Upper-mid, Presence, Brilliance, dalam dB dan %.
- **Stereo**: vectorscope/goniometer, korelasi fase, lebar stereo, balance L/R.
- **Info**: frekuensi dominan + nama nada, spectral centroid, *bandwidth efektif* sumber (petunjuk kompresi lossy), dan perangkat output (nama TWS, jenis Bluetooth A2DP/LE Audio).
- Pengaturan: ukuran FFT, smoothing, tilt 3/4.5 dB per oktaf, rentang dB, fallback otomatis, layar tetap menyala, tombol bekukan grafik.

## Batasan

- Yang dianalisis adalah sinyal **sebelum** di-encode codec Bluetooth. Suara akhir di telinga (codec SBC/AAC/SSC/LC3, EQ internal TWS, driver) tidak bisa diukur tanpa mikrofon pengukuran.
- Efek Samsung (Dolby Atmos/Equalizer) mungkin tidak ikut terlihat di grafik.
- Codec Bluetooth yang aktif tidak bisa dibaca app biasa. Cek di *Opsi pengembang > Codec Audio Bluetooth*.
- iPhone tidak bisa: iOS tidak mengizinkan app membaca audio dari app lain.

## Build sendiri

```
./gradlew assembleRelease
```

Setiap push ke `main` otomatis di-build oleh GitHub Actions dan diterbitkan sebagai Release baru.
`signing/release.jks` adalah kunci tanda tangan khusus app pribadi ini (password `twsanalyzer`), supaya update bisa menimpa versi lama. Kalau repo ini dijadikan publik dan ingin lebih aman, ganti dengan secrets `SIGNING_KEYSTORE_PATH` / `SIGNING_*`.
