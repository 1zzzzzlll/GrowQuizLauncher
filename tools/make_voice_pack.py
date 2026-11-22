#!/usr/bin/env python3
"""把 sherpa-onnx 的中文 VITS 模型打成一个语音包，并打出要贴进 voice.json 的清单。

用法:
    python tools/make_voice_pack.py --model-dir /path/vits-icefall-zh-aishell3 \
        --id zh-aishell3-v1 --name "标准普通话女声" [--speaker 0] [--speed 0.9]

产物（当前目录）:
    <id>.zip       三个文件，平铺在 zip 根目录：model.onnx / lexicon.txt / tokens.txt
    <id>.json      一条 packs 记录，粘到服务器的 tts/voice.json 里

模型自带 rule.far（180 MB 的文本归一化规则）不打包：朗读前 SpeechText 已经把数字和
符号换成汉字了。打进去只会让每台电视多下 180 MB。

发布步骤见 docs/VOICE.md。
"""

import argparse
import hashlib
import json
import os
import sys
import zipfile

FILES = ["model.onnx", "lexicon.txt", "tokens.txt"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True, help="解压后的模型目录")
    ap.add_argument("--id", required=True, help="包 id，客户端用它做目录名，换包时换 id")
    ap.add_argument("--name", required=True, help="给家长看的名字")
    ap.add_argument("--speaker", type=int, default=0, help="发音人编号（AISHELL-3 有 174 个）")
    ap.add_argument("--speed", type=float, default=0.9, help="语速，1.0 是原速")
    ap.add_argument("--out-dir", default=".", help="产物目录")
    args = ap.parse_args()

    for name in FILES:
        path = os.path.join(args.model_dir, name)
        if not os.path.isfile(path):
            sys.exit(f"缺少 {path}")
    os.makedirs(args.out_dir, exist_ok=True)

    zip_path = os.path.join(args.out_dir, f"{args.id}.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name in FILES:
            z.write(os.path.join(args.model_dir, name), name)

    size = os.path.getsize(zip_path)
    digest = hashlib.sha256()
    with open(zip_path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)

    entry = {
        "id": args.id,
        "name": args.name,
        "url": f"tts/{args.id}.zip",
        "bytes": size,
        "sha256": digest.hexdigest(),
        "speaker": args.speaker,
        "speed": args.speed,
    }
    json_path = os.path.join(args.out_dir, f"{args.id}.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump({"packs": [entry]}, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"{zip_path}  {size} bytes")
    print(f"{json_path}")
    print(json.dumps(entry, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
