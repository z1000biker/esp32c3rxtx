# C3TRX for Android

The C3TRX transceiver on an Android phone or tablet: the ESP32‑C3 (firmware **C3TRX 1**) plugs into the device with a **USB OTG** cable and the app does the same DSP as the desktop app (`app/c3trx_app.py`).

- **Android 10 (API 29) or later**, with USB host (OTG) support
- No root, no extra drivers: the app talks CDC‑ACM to the ESP32‑C3 native USB Serial/JTAG port (VID `303A`, PID `1001`) through the Android USB host API
- Plug the board in and Android offers to open C3TRX straight away

## Install

Download `C3TRX-<version>.apk` from the [Releases](../../releases) page, open it on the device and allow installing from that source.

Every push to `android/` also builds an APK. You can download it from the **Actions** tab, under the run's artifacts (`C3TRX-apk`).

## Features (same as the desktop app)

| | |
|---|---|
| **RX** | AM, FM, USB, LSB, CW · BFO · BW 200 Hz–20 kHz · AGC fast/slow/off · squelch · volume |
| **Front end** | DC notch, blind IQ amplitude/phase balance with live readout |
| **Display** | 1024‑point spectrum + 300‑line turbo waterfall, zoom up to 64× around the VFO, passband shading |
| **Tuning** | touch or drag on the spectrum/waterfall = VFO · −1k/−10/+10/+1k · VFO→0 · Re‑center (signal 10 kHz off DC) |
| **TX** | USB, LSB, AM, FM · sources: microphone, tone, carrier, waterfall text · Amp, AM %, FM deviation |
| **Waterfall text** | vertical/horizontal layout, Height, Pixel, Line, Base, RX‑waterfall direction, **Preview**, **Save WAV** |
| **Demo** | the **Demo** button runs the built‑in simulator (+1 kHz carrier with DC offset and IQ imbalance), no hardware needed |
| **Log** | every command sent (`>`) and every firmware line (`<`) |

Settings (frequency, mode, TX and text parameters, zoom) are remembered.

## Use

1. Flash `firmware/c3trx1_merged.bin` to the board (see the main README).
2. Connect the board via OTG → Android asks to open C3TRX → **OK**. Or open the app and press **Connect**.
3. **Start RX**, touch a signal, choose the mode in the **RX** tab.
4. To transmit: in the **TX** tab choose mode/source → **PTT (TX)**. The first time you use the microphone, Android asks for permission.

## Build

```bash
cd android
gradle assembleRelease          # Gradle 8.11+ and Android SDK 35; or open the folder in Android Studio
gradle testDebugUnitTest        # DSP, frame parser, waterfall text and simulator protocol tests
```

The workflow `.github/workflows/android.yml` runs the tests, builds the APK on every push, and attaches it to every GitHub release you publish.

### Signing

The workflow signs the APK with, in order of preference:

1. a private key from the repository secrets `C3TRX_KEYSTORE_B64` (the keystore in base64), `C3TRX_KEYSTORE_PASSWORD` and `C3TRX_KEY_ALIAS`;
2. `android/app/c3trx-public.keystore` if that file is in the repository (password `c3trx-public`, alias `c3trx`);
3. otherwise, a key made for that run only. That APK installs fine, but a later APK signed with a different key can only be installed after uninstalling it.

For updates that install over each other, add the secrets (1).

## Notes

- The link is half‑duplex, as on the desktop: PTT stops RX, and after TX you press **Start RX** again.
- The phone powers the ESP32‑C3 over OTG. During TX the current draw is small, but some phones cut power to the OTG port when the screen turns off. The app keeps the screen on while it is open.
- iOS is not supported: iOS does not allow apps to access USB CDC serial devices.
