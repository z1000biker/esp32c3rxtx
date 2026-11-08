#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/ws" "$work/sdk" "$root/dist"
ws="$work/ws"

fetch_source() {
  local repo="$1" commit="$2" dir="$3"
  mkdir -p "$dir"
  curl -fsSL --retry 3 --max-time 180 "https://codeload.github.com/$repo/tar.gz/$commit" |
    tar -xz --strip-components=1 -C "$dir"
}

fetch_source cyber-murmel/esp-sdr-zephyr 3214c16a7c9f136414cc5ae9d245ea290c98a6ce "$ws/esp-sdr-zephyr" & app_pid=$!
fetch_source cyber-murmel/zephyr ffb8a7cce5d31e098a09854d065e2e5ef7c493a4 "$ws/modules/zephyr" & zephyr_pid=$!
fetch_source cyber-murmel/hal_espressif 54d64a1289e8d0cddccef6359a385ffb4c68b213 "$ws/modules/hal/espressif" & hal_pid=$!
fetch_source ESPARGOS/esp-sdr 1fe55351036337359bcac1ccca6db04c32fde4f8 "$ws/modules/lib/esp-sdr" & lib_pid=$!
fetch_source zephyrproject-rtos/mbedtls c43f34b93797e81fd0257c30004dcd0ae332ae51 "$ws/modules/crypto/mbedtls" & mbed_pid=$!
fetch_source zephyrproject-rtos/tf-psa-crypto 765af96a20a2b408474dcd950372ba9af4d26b62 "$ws/modules/crypto/tf-psa-crypto" & psa_pid=$!

sdk_release=https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1
curl -fsSL --retry 3 --max-time 180 "$sdk_release/zephyr-sdk-1.0.1_linux-x86_64_minimal.tar.xz" -o "$work/sdk.xz" & sdk_pid=$!
curl -fsSL --retry 3 --max-time 180 "$sdk_release/toolchain_gnu_linux-x86_64_riscv64-zephyr-elf.tar.xz" -o "$work/riscv.xz" & riscv_pid=$!
for pid in "$app_pid" "$zephyr_pid" "$hal_pid" "$lib_pid" "$mbed_pid" "$psa_pid" "$sdk_pid" "$riscv_pid"; do wait "$pid"; done

(
  cd "$work"
  printf '%s  %s\n' \
    ca9bc0ff66fafca1dac9d592a36d953cf16d096a9d09b1c0357f021cf9f6a7eb sdk.xz \
    01750834c471fbdb335c1b8b8aee17010a1968938957db85640c366235771a38 riscv.xz | sha256sum -c -
)
tar -xJf "$work/sdk.xz" -C "$work/sdk"
export ZEPHYR_SDK_INSTALL_DIR="$work/sdk/zephyr-sdk-1.0.1"
mkdir -p "$ZEPHYR_SDK_INSTALL_DIR/gnu"
tar -xJf "$work/riscv.xz" -C "$ZEPHYR_SDK_INSTALL_DIR/gnu"

python -m venv "$work/venv"
export PATH="$work/venv/bin:$PATH"
python -m pip install -q -r "$ws/modules/zephyr/scripts/requirements-base.txt" west cmake ninja 'esptool==5.6.0'
python - "$ws" <<'PY'
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from pathlib import Path
import subprocess
import sys
import yaml

ws = Path(sys.argv[1])
modules = (ws / 'modules/hal/espressif', ws / 'esp-sdr-zephyr')

def fetch(item):
    module, blob = item
    path = module / 'zephyr/blobs' / blob['path']
    path.parent.mkdir(parents=True, exist_ok=True)
    url = blob['url'].replace('https://github.com/', 'https://raw.githubusercontent.com/').replace('/raw/', '/')
    subprocess.run(['curl', '-fsSL', '--retry', '3', '--max-time', '40', url, '-o', str(path)], check=True)
    if sha256(path.read_bytes()).hexdigest() != blob['sha256']:
        raise ValueError(f'Invalid checksum for {path}')

with ThreadPoolExecutor(max_workers=10) as pool:
    list(pool.map(fetch, [(module, blob) for module in modules
                         for blob in yaml.safe_load((module / 'zephyr/module.yml').read_text())['blobs']
                         if '/esp32c6/' in blob['path']]))
PY

app="$ws/esp-sdr-zephyr/apps/osmosdr_serial"
cp -r "$root/firmware/overlay/." "$app"
export ZEPHYR_BASE="$ws/modules/zephyr"
export ZEPHYR_TOOLCHAIN_VARIANT=zephyr
cmake -G Ninja -S "$app" -B "$ws/build" -DBOARD=esp32c6_devkitc/esp32c6/hpcore \
  "-DZEPHYR_MODULES=$ws/modules/hal/espressif;$ws/esp-sdr-zephyr;$ws/modules/crypto/mbedtls;$ws/modules/crypto/tf-psa-crypto" \
  "-DESP_SDR_UPSTREAM_DIR=$ws/modules/lib/esp-sdr"
cmake --build "$ws/build" --parallel
grep -qx 'CONFIG_ESP_SIMPLE_BOOT=y' "$ws/build/zephyr/.config"
grep -qx 'CONFIG_FLASH_LOAD_OFFSET=0' "$ws/build/zephyr/.config"
image="$root/dist/esp32c6_devkitc1_audio_rx.bin"
cp "$ws/build/zephyr/zephyr.bin" "$image"
esptool --chip esp32c6 image-info "$image"

# Ship the corresponding firmware source with the binaries.
mkdir -p "$root/firmware/esp-sdr-zephyr"
tar -C "$ws/esp-sdr-zephyr" --exclude='./zephyr/blobs/lib' --exclude='./.github' -cf - . |
  tar -C "$root/firmware/esp-sdr-zephyr" -xf -
