#!/usr/bin/env python3
"""GrowQuiz 云端神经语音合成与缓存服务端。

入口:  Caddy :8443 /growquiz/tts/speak -> 127.0.0.1:8093（本服务）
缓存:  /root/growquiz-tts-cache/<sha256前2位>/<sha256>.mp3（上限 1.5 GiB，自动淘汰最久未用）
安全:  校验 X-Report-Token 共享口令（与 growquiz-report 共用 /root/growquiz/report.env）；
      单条文本上限 500 字符；仅允许内置普通话发音人白名单；未配口令时拒绝启动（fail-closed）。
音频:  使用 edge-tts 24 kHz 神经语音合成，并对整句做统一响度归一化与软限幅（-13.5 dBFS），
      避免短句开头偏轻或电视外放音量低于视频的问题。
"""

from array import array
import asyncio
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time

import aiohttp
import edge_tts

CACHE_DIR = Path("/root/growquiz-tts-cache")
TOKEN_FILE = Path("/root/growquiz/report-token")
LISTEN = ("127.0.0.1", 8093)
MAX_BODY = 16 * 1024
MAX_CHARS = 500
MAX_CACHE_BYTES = 1536 * 1024 * 1024  # 1.5 GiB (~35,000 MP3 clips)
NORM_VERSION = "v1"

VOICES = {
    "": "zh-CN-XiaoyiNeural",
    "xiaoyi": "zh-CN-XiaoyiNeural",
    "zh-CN-XiaoyiNeural": "zh-CN-XiaoyiNeural",
    "xiaoxiao": "zh-CN-XiaoxiaoNeural",
    "zh-CN-XiaoxiaoNeural": "zh-CN-XiaoxiaoNeural",
    "yunxi": "zh-CN-YunxiNeural",
    "zh-CN-YunxiNeural": "zh-CN-YunxiNeural",
}
DEFAULT_VOICE = "zh-CN-XiaoyiNeural"
# XiaoyiNeural's raw +0% cadence is brisk (~4.2 chars/s); -8% lands at standard ~3.8 chars/s normal speech.
SPEECH_RATE = "-8%"
_PROXY_ENV_KEYS = ("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")


def _init_network_env() -> tuple[str, str]:
    """Strip inherited system proxies so edge-tts connects directly to Microsoft's domestic edge.

    Returns (explicit_proxy, fallback_proxy):
    - explicit_proxy: set only when GROWQUIZ_TTS_PROXY is intentionally configured;
    - fallback_proxy: any stripped HTTP(S) proxy from the environment, kept strictly as last resort.
    """
    explicit = os.environ.get("GROWQUIZ_TTS_PROXY", "").strip()
    inherited_http = (
        os.environ.get("HTTPS_PROXY")
        or os.environ.get("https_proxy")
        or os.environ.get("HTTP_PROXY")
        or os.environ.get("http_proxy")
        or ""
    ).strip()
    for key in _PROXY_ENV_KEYS:
        os.environ.pop(key, None)
    return explicit, inherited_http


EXPLICIT_PROXY, FALLBACK_PROXY = _init_network_env()
_SYNTH_SEM = threading.BoundedSemaphore(3)


def _load_tokens() -> tuple[str, ...]:
    raw = os.environ.get("GROWQUIZ_REPORT_TOKEN", "").strip()
    if not raw:
        try:
            raw = TOKEN_FILE.read_text(encoding="utf-8").strip()
        except OSError:
            raw = ""
    return tuple(t.strip() for t in raw.split(",") if t.strip())


TOKENS = _load_tokens()
_key_locks_guard = threading.Lock()
_key_locks: dict[str, threading.Lock] = {}
_prune_counter = 0


def _lock_for(key: str) -> threading.Lock:
    with _key_locks_guard:
        lock = _key_locks.get(key)
        if lock is None:
            if len(_key_locks) > 512:
                _key_locks.clear()
            lock = threading.Lock()
            _key_locks[key] = lock
        return lock


def _normalize_mp3(raw_mp3: bytes) -> bytes:
    """Decode MP3 to 24 kHz mono PCM, apply uniform active-RMS loudness normalization + soft clip, re-encode."""
    try:
        dec = subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", "pipe:0", "-f", "s16le", "-ar", "24000", "-ac", "1", "pipe:1",
            ],
            input=raw_mp3,
            capture_output=True,
            timeout=8,
            check=True,
        )
        pcm = array("h")
        pcm.frombytes(dec.stdout[: len(dec.stdout) & ~1])
        if not pcm:
            return raw_mp3
        if sys.byteorder != "little":
            pcm.byteswap()

        # Measure active speech RMS (ignore silence frames below -42 dBFS ~ 260)
        active_sq = 0.0
        active_n = 0
        peak = 0
        for s in pcm:
            a = abs(s)
            if a > peak:
                peak = a
            if a > 260:
                active_sq += s * s
                active_n += 1

        if peak == 0:
            return raw_mp3

        rms = math.sqrt(active_sq / max(1, active_n)) / 32768.0
        # Target active speech RMS ~ 0.21 (-13.5 dBFS) so quiz speech matches TV video loudness
        gain = (0.21 / max(rms, 0.01)) if active_n > 200 else (0.88 / (peak / 32768.0))
        gain = max(1.0, min(gain, 6.0))

        knee = 0.84
        headroom = 0.14
        for i in range(len(pcm)):
            v = (pcm[i] / 32768.0) * gain
            av = abs(v)
            if av > knee:
                av = knee + headroom * math.tanh((av - knee) / headroom)
                v = av if v >= 0 else -av
            iv = int(v * 32767.0)
            pcm[i] = 32767 if iv > 32767 else (-32768 if iv < -32768 else iv)

        if sys.byteorder != "little":
            pcm.byteswap()

        enc = subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-f", "s16le", "-ar", "24000", "-ac", "1", "-i", "pipe:0",
                "-b:a", "64k", "-f", "mp3", "pipe:1",
            ],
            input=pcm.tobytes(),
            capture_output=True,
            timeout=8,
            check=True,
        )
        return enc.stdout if len(enc.stdout) > 200 else raw_mp3
    except Exception as e:
        print(f"growquiz-tts: normalize fallback ({e})", file=sys.stderr, flush=True)
        return raw_mp3


async def _edge_synth_once(text: str, voice: str, proxy: str = "") -> bytes:
    # Fresh IPv4 connector with trust_env=False: avoids broken IPv6 SYN delays and accidental proxy hijacking.
    conn = aiohttp.TCPConnector(family=socket.AF_INET)
    comm = edge_tts.Communicate(
        text,
        voice=voice,
        rate=SPEECH_RATE,
        volume="+20%",
        connector=conn,
        proxy=proxy,
    )
    buf = bytearray()
    async for chunk in comm.stream():
        if chunk.get("type") == "audio":
            buf.extend(chunk["data"])
    if len(buf) < 200:
        raise RuntimeError(f"empty audio ({len(buf)}B)")
    return bytes(buf)


def _synthesize(text: str, voice: str) -> bytes:
    primary = EXPLICIT_PROXY
    attempts: list[tuple[str, float]] = [(primary, 8.0), (primary, 8.0)]
    if not primary and FALLBACK_PROXY:
        attempts.append((FALLBACK_PROXY, 10.0))
    last_err: Exception | None = None
    with _SYNTH_SEM:
        for idx, (proxy, timeout_s) in enumerate(attempts):
            try:
                raw = asyncio.run(asyncio.wait_for(_edge_synth_once(text, voice, proxy=proxy), timeout=timeout_s))
                return _normalize_mp3(raw)
            except Exception as e:
                last_err = e
                if idx + 1 < len(attempts):
                    time.sleep(0.12)
    raise RuntimeError(f"synth failed: {last_err}")


def _maybe_prune() -> None:
    global _prune_counter
    _prune_counter += 1
    if _prune_counter % 200 != 0:
        return
    try:
        files = []
        total = 0
        for p in CACHE_DIR.rglob("*.mp3"):
            try:
                st = p.stat()
                total += st.st_size
                files.append((st.st_mtime, st.st_size, p))
            except OSError:
                pass
        if total <= MAX_CACHE_BYTES:
            return
        files.sort(key=lambda x: x[0])
        for _, sz, p in files:
            if total <= MAX_CACHE_BYTES * 0.85:
                break
            try:
                p.unlink()
                total -= sz
            except OSError:
                pass
    except Exception:
        pass


def get_or_create_mp3(text: str, voice: str) -> tuple[bytes, bool]:
    digest = hashlib.sha256(f"{NORM_VERSION}|{voice}|{SPEECH_RATE}|{text}".encode("utf-8")).hexdigest()
    shard = CACHE_DIR / digest[:2]
    target = shard / f"{digest}.mp3"

    if target.is_file():
        try:
            data = target.read_bytes()
            if len(data) > 200:
                os.utime(target, None)
                return data, True
        except OSError:
            pass

    with _lock_for(digest):
        if target.is_file():
            try:
                data = target.read_bytes()
                if len(data) > 200:
                    return data, True
            except OSError:
                pass

        mp3 = _synthesize(text, voice)
        shard.mkdir(parents=True, exist_ok=True)
        tmp = shard / f"{digest}.{os.getpid()}.{threading.get_ident()}.tmp"
        tmp.write_bytes(mp3)
        os.replace(tmp, target)
        _maybe_prune()
        return mp3, False


class TtsHTTPServer(ThreadingHTTPServer):
    request_queue_size = 64
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    server_version = "growquiz-tts/1.1"

    def log_message(self, fmt, *args):
        pass

    def _reply_json(self, code: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self) -> None:
        profile = f"{NORM_VERSION}|{DEFAULT_VOICE}|{SPEECH_RATE}"
        self._reply_json(200, {"ok": True, "service": "growquiz-tts", "voice": DEFAULT_VOICE, "rate": SPEECH_RATE, "profile": profile})

    def do_POST(self) -> None:
        given = self.headers.get("X-Report-Token") or ""
        if not any(hmac.compare_digest(given, t) for t in TOKENS):
            return self._reply_json(403, {"ok": False, "error": "bad token"})
        try:
            size = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            size = 0
        if size <= 0 or size > MAX_BODY:
            return self._reply_json(413, {"ok": False, "error": "bad size"})
        try:
            payload = json.loads(self.rfile.read(size).decode("utf-8"))
        except Exception:
            return self._reply_json(400, {"ok": False, "error": "bad json"})

        text = str(payload.get("text") or "").strip()
        if not text or len(text) > MAX_CHARS:
            return self._reply_json(400, {"ok": False, "error": "bad text"})

        req_voice = str(payload.get("voice") or "").strip()
        voice = VOICES.get(req_voice)
        if voice is None:
            return self._reply_json(400, {"ok": False, "error": "bad voice"})

        t0 = time.monotonic()
        try:
            mp3, hit = get_or_create_mp3(text, voice)
        except Exception as e:
            print(f"{time.strftime('%F %T')} tts_fail len={len(text)} err={e}", file=sys.stderr, flush=True)
            return self._reply_json(502, {"ok": False, "error": "synth failed"})

        ms = int((time.monotonic() - t0) * 1000)
        if not hit:
            print(f"{time.strftime('%F %T')} tts_miss voice={voice} chars={len(text)} bytes={len(mp3)} ms={ms}", flush=True)

        try:
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(mp3)))
            self.send_header("X-TTS-Cache", "HIT" if hit else "MISS")
            self.send_header("X-TTS-Profile", f"{NORM_VERSION}|{voice}|{SPEECH_RATE}")
            self.end_headers()
            self.wfile.write(mp3)
        except (BrokenPipeError, ConnectionResetError):
            print(f"{time.strftime('%F %T')} tts_client_abort chars={len(text)} ms={ms}", file=sys.stderr, flush=True)


if __name__ == "__main__":
    if not TOKENS:
        sys.exit(
            "growquiz-tts: 未配置口令。设置 GROWQUIZ_REPORT_TOKEN 或写入 "
            f"{TOKEN_FILE}（见 server/README.md）后再启动。"
        )
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    TtsHTTPServer(LISTEN, Handler).serve_forever()
