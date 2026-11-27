# 内置语音（朗读题目）

答题页会用语音把题目念给还认不全字的孩子听。这个文档说明这条语音是怎么来的、为什么不用「装一个 TTS 引擎 APP」，以及以后怎么换声音。

## 为什么不是「从服务器装一个语音引擎 APK」

最开始的想法是：检测到设备没有 TTS，就让用户点一下，应用从服务器拉一个引擎 APK 装上，再把它设成默认引擎。这条路在真机上是断的，断在三处，每一处都足以让一台电视永远没有声音：

1. **安装要先打开「安装未知来源应用」**，这个开关在系统设置里。华为 DESC-260SV、XIGIMI DOHKO、TCL ak30a5 三台设备**没有系统设置页**（`SETTINGS` / `WIFI_SETTINGS` 都无人应答，见 v1.14.5），开关打不开就装不了。
2. **装完还有系统的确认框**，由厂商的 `com.android.packageinstaller` 提供。TCL 改过这个应用，带签名白名单，侧载会被 `INSTALL_FAILED_VERIFICATION_FAILURE` 拦掉（安装手册里那套 `pm disable-user` 绕过就是为它写的）。
3. **装上不等于默认**。`tts_default_synth` 是 secure setting，只有拿到 `WRITE_SECURE_SETTINGS` 才能改（TCL 靠 adb 脚本授权），其余设备只能指望用户去系统设置里手动选——又回到第 1 条。

三条里任何一条不成立，孩子面对的就是一道静音的题。所以语音改成**在应用进程里合成**：不装东西、不改设置、不要权限。

## 组成

| 位置 | 职责 |
| --- | --- |
| `voice/CloudTts.kt` | 24 kHz 云端神经语音（服务端 `edge-tts` + 响度归一化 + 双端缓存），题干先播、选项并行预取，断网 45 秒熔断回退离线语音 |
| `app/libs/sherpa-onnx.aar` | 官方 sherpa-onnx 2.0.2 静态链接 AAR，裁到 `arm64-v8a` + `armeabi-v7a` 两个 ABI（Apache-2.0），负责离线兜底 |
| `voice/VoicePack.kt` | 纯 JVM：清单解析、URL 解析、大小文案、SHA-256、zip 条目校验、片段缓存名（支持 VITS 与 Matcha） |
| `voice/PackStore.kt` | 下载（断点续传）、校验、解压、原子替换、删除；`state()` 供界面轮询 |
| `voice/EmbeddedTts.kt` | 离线模型加载、`generateWithCallback` 流式合成、整句响度归一化 + 软限幅、AudioTrack 播放、句子缓存 |
| `voice/Voice.kt` | 门面：云端语音优先 → 离线语音包兜底 → 本机系统引擎兜底；`describe()` 给管理台和环境检测用 |
| `EnvCheck.kt` / `web_admin.html` | 环境检测的「语音朗读引擎」一行；手机端「设置 → 语音朗读」卡片 |

模型**不放进 APK**：28 MB 会被每一台设备在每一次更新时重复付一遍流量。APK 只多出原生库（压缩后约 19 MB，`useLegacyPackaging = true`）。

## 服务器

清单与包都放在更新服务器（`secrets.properties` 的 `updateBase`）的 `/tts/` 下，和 `latest.json` 同一个静态目录：

```
https://<updateBase>/tts/voice.json
https://<updateBase>/tts/zh-aishell3-v1.zip
```

`voice.json`：

```json
{
  "packs": [
    {
      "id": "zh-aishell3-v1",
      "name": "标准普通话女声",
      "url": "tts/zh-aishell3-v1.zip",
      "bytes": 27995532,
      "sha256": "313208fd…",
      "speaker": 0,
      "speed": 0.9
    }
  ]
}
```

- `url` 可以是相对路径，也可以是完整 URL（换 CDN/镜像时不用改客户端）。
- `bytes` 用来算下载百分比；`sha256` 是硬闸门，不匹配就整包丢弃，旧语音继续用。
- `speaker` 是模型里的发音人编号（AISHELL-3 有 174 个），`speed` 是语速（0.9 比原速慢一点，四个选项不会连成一句）。
- 顺序即优先级，客户端取第一条。

包内只有三个文件，平铺在 zip 根目录：`model.onnx`、`lexicon.txt`、`tokens.txt`。模型自带的 `rule.far`（180 MB，数字/日期归一化）没有打进去——`SpeechText` 已经在朗读前把符号、数字、单位换成可朗读的汉字了。

## 换声音 / 加一个包

1. 从 [sherpa-onnx tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models) 下载一个中文 VITS 模型。**先用 `vits-icefall-zh-aishell3`**：它只有 8 kHz，但在这类电视上 6 秒音频约 8 秒合成完；22 kHz 的 `vits-zh-aishell3`（int8）实测一句要 160 秒以上，等于没有。
2. `python tools/make_voice_pack.py --model-dir <解压目录> --id zh-xxx-v1 --name "..." --speaker 0`，脚本会打出 zip 和一段清单 JSON。
3. 把 zip 和新的 `voice.json` 传到服务器同一目录。
4. 客户端不需要发版：已经装过语音的设备会继续用旧包，直到删掉重新下载；想强制换音，改 `id` 让老包对不上即可（`PackStore` 装好新包会删掉旧目录）。

## 运行时行为

- **什么时候下载**：不自动。电视能出声时没人会去下 28 MB；只有家长在环境检测或管理后台点「内置语音」才下载。
- **什么时候启用**：下载完自动启用（`QuizPrefs.embeddedVoice`）。也可以随时切回电视自带引擎。
- **没有引擎、也没下载**：答题页照旧弹一次提示，告诉家长去哪里加载。
- **加载失败**：`EmbeddedTts.reason()` 记下原因并上报 `tts_local_fail`，`Voice` 自动退回系统引擎；两条都不行才算没有声音。
- **缓存**：合成结果按句子存成 PCM（`files/voice_clips/`，上限 40 MB，按最近使用淘汰）。同一道题重复朗读、隔天再考同一题，都不再合成。
- **内存**：模型常驻约 150 MB（模型 30 MB + onnxruntime 运行时）。桌面启动时预热，答题页不再等模型。

## 已知限制

- **只有 arm64-v8a 和 armeabi-v7a**：x86 模拟器（比如 `sdk_google_atv64_x86_64`）会走到 `UnsatisfiedLinkError`，上报 `tts_local_fail` 后回退系统引擎。
- **中文模型**：英文单词和生僻符号可能念不出或跳过，中文题目不受影响。
- **首次合成要几秒**：A53 级别的电视盒子上，一句 20 字的题约 5～9 秒才出声；命中缓存约 1 秒内。所以答题页在显示当前题时就把下一题合成好（`QuizActivity.prefetchNext` + `Voice.warmParts`），第二题起不用等；控制台的试听按钮会等到真的出声才报成功。
- **音质**：8 kHz 带宽窄，声音清楚但发闷；换成 22 kHz 会更自然，但这类电视算不动（见上）。
- **语速**：`EmbeddedTts.SPEED` 目前 1.12（0.9 偏慢、1.0 仍偏慢，按实际听感定的）。缓存名里带语速，改它不会读出旧语速的片段。
