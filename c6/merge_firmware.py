"""Verify and merge the pinned C6 components supplied by ESPARGOS."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
BINARY = "esp32c6_devkitc1_espsdr_rx_merged.bin"

def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: python merge_firmware.py PARTS_DIRECTORY OUTPUT_DIRECTORY")
    parts, output = map(Path, sys.argv[1:])
    manifest = json.loads((ROOT / "firmware-manifest.json").read_text())
    image = bytearray()
    for part in manifest["parts"]:
        data = (parts / part["name"]).read_bytes()
        if len(data) != part["size"] or hashlib.sha256(data).hexdigest() != part["sha256"]:
            raise SystemExit(f"Image does not match pinned firmware: {part['name']}")
        gap = part["offset"] - len(image)
        if gap < 0:
            raise SystemExit("Firmware regions overlap")
        image.extend(b"\xff" * gap)
        image.extend(data)
    output.mkdir(parents=True, exist_ok=True)
    (output / BINARY).write_bytes(image)

if __name__ == "__main__":
    main()
