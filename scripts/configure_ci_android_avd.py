#!/usr/bin/env python3
"""Set the hosted tablet AVD framebuffer before the emulator starts."""

from __future__ import annotations

import os
from pathlib import Path
import re
import sys


DISPLAY_PROPERTIES = {
    "hw.lcd.width": "1280",
    "hw.lcd.height": "800",
    "hw.lcd.density": "160",
}


def main() -> None:
    if len(sys.argv) != 2 or not re.fullmatch(r"[A-Za-z0-9_-]+", sys.argv[1]):
        raise SystemExit("usage: configure_ci_android_avd.py <avd-name>")

    avd_root = Path(os.environ.get("ANDROID_AVD_HOME") or Path.home() / ".android" / "avd")
    config_path = avd_root / f"{sys.argv[1]}.avd" / "config.ini"
    if not config_path.is_file():
        raise SystemExit(f"AVD configuration is missing: {config_path}")

    original = config_path.read_text(encoding="utf-8").splitlines()
    preserved = [
        line for line in original
        if line.split("=", 1)[0].strip() not in DISPLAY_PROPERTIES
    ]
    configured = preserved + [
        f"{name}={value}" for name, value in DISPLAY_PROPERTIES.items()
    ]
    config_path.write_text("\n".join(configured) + "\n", encoding="utf-8")
    print("Physical CI tablet framebuffer: 1280x800 at 160 dpi")


if __name__ == "__main__":
    main()
