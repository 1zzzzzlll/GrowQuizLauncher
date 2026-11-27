#!/usr/bin/env python3
"""把 sherpa-onnx 的中文 VITS 或 Matcha 模型打成一个语音包，并打出要贴进 voice.json 的清单。

用法:
    python tools/make_voice_pack.py --model-dir /path/vits-icefall-zh-aishell3 \
        --id zh-aishell3-v2 --name "标准普通话女声" [--speaker 0] [--speed 1.08]

产物（当前目录）:
    <id>.zip       平铺在 zip 根目录：模型文件 + lexicon.txt + tokens.txt + 可选多音字/数字 FST
    <id>.json      一条 packs 记录，粘到服务器的 tts/voice.json 里

模型自带 rule.far（180 MB 的全量归一化规则）不打包；体积很小的 phone.fst / date.fst /
number.fst / new_heteronym.fst（合计约 230 KB）若存在则一并打入，用于多音字消歧。
"""

import argparse
import hashlib
import json
import os
import sys
import zipfile

COMMON_FILES = ["lexicon.txt", "tokens.txt"]
OPTIONAL_FSTS = [
    "phone.fst", "date.fst", "number.fst", "new_heteronym.fst",
    "phone-zh.fst", "date-zh.fst", "number-zh.fst",
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True, help="解压后的模型目录")
    ap.add_argument("--vocoder", default="", help="Matcha 模型的 vocoder.onnx 路径（VITS 留空）")
    ap.add_argument("--id", required=True, help="包 id，客户端用它做目录名，换包时换 id")
    ap.add_argument("--name", required=True, help="给家长看的名字")
    ap.add_argument("--speaker", type=int, default=0, help="发音人编号")
    ap.add_argument("--speed", type=float, default=1.08, help="语速，1.0 是原速")
    ap.add_argument("--out-dir", default=".", help="产物目录")
    args = ap.parse_args()

    entries_to_pack: list[tuple[str, str]] = []
    vits_model = os.path.join(args.model_dir, "model.onnx")
    matcha_model = os.path.join(args.model_dir, "model-steps-3.onnx")
    if os.path.isfile(vits_model):
        entries_to_pack.append((vits_model, "model.onnx"))
    elif os.path.isfile(matcha_model):
        if not args.vocoder or not os.path.isfile(args.vocoder):
            sys.exit("Matcha 模型需要通过 --vocoder 指定 vocoder.onnx")
        entries_to_pack.append((matcha_model, "model-steps-3.onnx"))
        entries_to_pack.append((args.vocoder, "vocoder.onnx"))
    else:
        sys.exit(f"缺少 model.onnx 或 model-steps-3.onnx: {args.model_dir}")

    for name in COMMON_FILES:
        path = os.path.join(args.model_dir, name)
        if not os.path.isfile(path):
            sys.exit(f"缺少 {path}")
        entries_to_pack.append((path, name))

    for fst in OPTIONAL_FSTS:
        path = os.path.join(args.model_dir, fst)
        if os.path.isfile(path):
            entries_to_pack.append((path, fst))

    os.makedirs(args.out_dir, exist_ok=True)
    zip_path = os.path.join(args.out_dir, f"{args.id}.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for src_path, arc_name in entries_to_pack:
            z.write(src_path, arc_name)

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
