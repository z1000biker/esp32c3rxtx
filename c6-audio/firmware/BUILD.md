# Building the Audio RX firmware

The firmware is based on
[esp-sdr-zephyr](https://github.com/cyber-murmel/esp-sdr-zephyr) at
`3214c16a7c9f136414cc5ae9d245ea290c98a6ce`. This build uses the
`apps/osmosdr_serial` application, restricted to reception, with the
DevKitC-1 USB and flash configuration. The west manifest pins Zephyr,
the Espressif HAL, and the other source dependencies. Zephyr SDK 1.0.1
with the GNU riscv64 toolchain was used.

With west and the Zephyr SDK installed, initialize a workspace from the
pinned upstream revision and copy the three changed files from `firmware/overlay/` (or from the matching app
path in the bundled `firmware/esp-sdr-zephyr/` tree):

```sh
west init -m https://github.com/cyber-murmel/esp-sdr-zephyr \
  --mr 3214c16a7c9f136414cc5ae9d245ea290c98a6ce c6-audio-ws
cd c6-audio-ws
west update
west blobs fetch hal_espressif
west blobs fetch esp-sdr-zephyr
# Set SOURCE to the absolute path of the unpacked firmware/overlay directory.
app=apps/osmosdr_serial
cp "$SOURCE/src/main.c" "esp-sdr-zephyr/$app/src/main.c"
cp "$SOURCE/prj.conf" "esp-sdr-zephyr/$app/prj.conf"
cp "$SOURCE/boards/esp32c6_devkitc_hpcore.overlay" "esp-sdr-zephyr/$app/boards/"
west build -b esp32c6_devkitc/esp32c6/hpcore "esp-sdr-zephyr/$app"
```

The flash image is `build/zephyr/zephyr.bin` at offset `0x0`.
The firmware module's `LICENSE` and binary dependency metadata accompany
its source. For the clients, use `bash windows/build.sh` and
`bash android/build.sh` on Linux. The former needs Go 1.25 or later, and
the latter needs a JDK, OpenSSL, Android API 35 and build-tools 35.0.0.
