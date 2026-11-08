# ESP32-C6 RX Workbench

Adaptation of SV1EEX's ESP32-C3 browser workbench for the ESP32-C6-DevKitC-1.
The spectrum, waterfall and fine FFT display come from the C3 interface.
This C6 edition uses the ESPARGOS ESP-SDR C6 firmware and its `C6SDR` protocol.

- **Windows x64** — Run the portable EXE. It opens the bundled workbench in
  Edge (or Chrome) in an app window. The EXE serves the files locally over
  loopback and the browser provides Web Serial access. No Internet access is needed.
- **Android 8+** — Install the signed preview APK and connect the board's
  native USB connector with a USB data/OTG cable. Approve the USB permission
  prompt. The app uses an embedded offline view and Android USB Host.
  A preview build generated without a persistent signing key cannot be upgraded
  over a differently signed build. Uninstall the preview first if necessary.
- **Browser** — Run `python -m http.server 8000 --directory web` and open
  http://localhost:8000 in desktop Edge or Chrome.

Flash the C6 firmware first (see [the flash notes](FLASH_EL.md)), then connect to
the board's **ESP32-C6 USB** port. Close other applications using that port.
Use **Connect USB**, set **2442 MHz** as a starting point, then select
**Live spectrum** (on-device 256-bin FFT) or **Fine snapshots**
(16,380 captured I/Q pairs, FFT in the app). The current firmware supports
neither continuous audio demodulation nor TX. The C3 firmware's RXAV audio
protocol is different and does not run on the C6 firmware.

## Build the clients

On Linux with Go 1.25 or later, and for Android a JDK, Android API 35 and
Android build-tools 35.0.0 installed:

```sh
bash windows/build.sh
bash android/build.sh
node --test tests/workbench.test.js
```

Artifacts appear in `dist/`. For a reusable APK signing identity, set
`C6_KEYSTORE`, `C6_KEY_ALIAS` and `C6_KEYSTORE_PASSWORD` before building.
Keep the keystore and its password out of version control.

## Firmware provenance

[ESPARGOS ESP-SDR](https://github.com/ESPARGOS/esp-sdr/tree/9b61e7465bee6da15209c5c09308043d08c46a1d)
is the corresponding firmware source, under GPL-3.0-or-later. The bootloader
and application are unchanged from its C6 profile. `firmware-manifest.json`
records the original image offsets and checksums. See the flash notes for
the pinned ESP-IDF version and build commands.

The client builds are previews. Their USB paths were reviewed against the
protocol and compiled. Hardware reception was not exercised on Windows or
Android during packaging.
