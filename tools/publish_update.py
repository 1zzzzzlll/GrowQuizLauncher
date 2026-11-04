#!/usr/bin/env python3
"""把一个新版本 APK 发布到自动更新服务器（mylinux 的 nginx 静态目录）。

用法:
    python tools/publish_update.py path/to/GrowQuiz-vX.Y.Z.apk

流程:
  1. 校验文件名格式 GrowQuiz-vX.Y.Z.apk
  2. scp 把 APK 上传到 mylinux:/root/nginx/web/growquiz/
  3. 生成 latest.json（{"version": "X.Y.Z", "apk": "GrowQuiz-vX.Y.Z.apk"}）并上传

依赖 ~/.ssh/config 里的 Host mylinux（root@www.rengouzi.top:2266）。
应用端在启动时读取 https://www.rengouzi.top:8443/growquiz/latest.json 检查新版本。

发版前记得: app/build.gradle.kts 的 versionName/versionCode 与文件名一致且高于已装版本。
"""

import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HOST = "mylinux"
REMOTE_DIR = "/root/nginx/web/growquiz"
NAME_RE = re.compile(r"^GrowQuiz-v(\d+\.\d+(?:\.\d+)?)\.apk$")


def run(cmd: list[str]) -> None:
    print("+", " ".join(cmd))
    result = subprocess.run(cmd)
    if result.returncode != 0:
        sys.exit(f"命令失败 (exit {result.returncode}): {' '.join(cmd)}")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk = Path(sys.argv[1])
    if not apk.is_file():
        sys.exit(f"找不到文件: {apk}")
    m = NAME_RE.match(apk.name)
    if not m:
        sys.exit(f"文件名需形如 GrowQuiz-v1.2.0.apk，当前: {apk.name}")
    version = m.group(1)

    latest = json.dumps({"version": version, "apk": apk.name}, ensure_ascii=False, indent=2) + "\n"
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        f.write(latest)
        latest_path = f.name

    run(["scp", str(apk), f"{HOST}:{REMOTE_DIR}/"])
    run(["scp", str(latest_path), f"{HOST}:{REMOTE_DIR}/latest.json"])
    print(f"已发布 {version}: {REMOTE_DIR}/{apk.name}")
    run(["curl", "-fsSL", f"https://www.rengouzi.top:8443/growquiz/latest.json"])


if __name__ == "__main__":
    main()
