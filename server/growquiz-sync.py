#!/usr/bin/env python3
"""GrowQuiz 更新与在线题库同步：
1) 把 GitHub 仓库最新 release 的 APK 与发版说明拉到 nginx 更新目录；
2) 从 GitHub 仓库 `question-bank/` 文件夹同步在线教材题库池与人工补充题（带本地缓存与 GitHub API 兜底），
   按当前日期自动推算本学期教学周进度，生成 `/root/nginx/web/growquiz/questions/manifest.json` 与 `age_<7..15>.json`。

源:
  - Release: https://github.com/1zzzzzlll/GrowQuizLauncher/releases/latest
  - 题库目录: https://github.com/1zzzzzlll/GrowQuizLauncher/tree/main/question-bank
目标:
  - /root/nginx/web/growquiz/（Caddy :8443/growquiz/* -> nginx :8088 静态站点）
产出:
  - GrowQuiz-vX.Y.Z.apk、latest.json、release_notes.md
  - questions/manifest.json、questions/age_<N>.json

由 growquiz-sync.timer 每 3 小时触发一次；也可手动: systemctl start growquiz-sync
"""

import hashlib
import json
import sys
import time
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

REPO = "1zzzzzlll/GrowQuizLauncher"
DEST = Path("/root/nginx/web/growquiz")
CACHE_DIR = Path("/root/growquiz/bank-cache")
API = f"https://api.github.com/repos/{REPO}/releases/latest"
CONTENTS_API = f"https://api.github.com/repos/{REPO}/contents/question-bank"
RAW_BANK_BASE = f"https://raw.githubusercontent.com/{REPO}/main/question-bank"
TZ_CN = timezone(timedelta(hours=8))
TOTAL_WEEKS = 18


def fetch(url: str, accept: str = "*/*", dest=None, timeout: int = 30) -> bytes:
    last_err: Exception | None = None
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "growquiz-sync/2.1"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                if dest is None:
                    return r.read()
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        return b""
                    dest.write(chunk)
        except Exception as exc:
            last_err = exc
            if dest is not None:
                dest.seek(0)
                dest.truncate(0)
            if attempt < 2:
                time.sleep(1.5 * (attempt + 1))
    assert last_err is not None
    raise last_err


def atomic_write_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".part")
    tmp.write_bytes(data)
    tmp.replace(path)


def git_blob_sha1(data: bytes) -> str:
    h = hashlib.sha1()
    h.update(f"blob {len(data)}\0".encode("ascii"))
    h.update(data)
    return h.hexdigest()


def sync_bank_subdir(subdir: str) -> None:
    """用 GitHub Contents API 按 git blob sha 增量同步 question-bank/<subdir>，仅拉取变更文件。"""
    listing = json.loads(fetch(f"{CONTENTS_API}/{subdir}", "application/vnd.github+json", timeout=20).decode("utf-8"))
    if not isinstance(listing, list):
        return
    for item in listing:
        if item.get("type") != "file":
            continue
        name = item["name"]
        remote_sha = item.get("sha", "")
        local_path = CACHE_DIR / subdir / name
        if local_path.is_file() and remote_sha and git_blob_sha1(local_path.read_bytes()) == remote_sha:
            continue
        # 优先走 Contents API raw（国内访问比 raw.githubusercontent.com 更稳），失败回退 raw 域名
        try:
            data = fetch(f"{CONTENTS_API}/{subdir}/{name}", "application/vnd.github.raw+json", timeout=25)
        except Exception:
            data = fetch(f"{RAW_BANK_BASE}/{subdir}/{name}", "application/json", timeout=25)
        atomic_write_bytes(local_path, data)


def estimate_progress(today: date) -> dict:
    """根据义务教育校历预估当前所处学期与教学周进度（共 18 教学周）。"""
    y, m, d = today.year, today.month, today.day
    if m >= 9:
        start_year = y
        sep1 = date(y, 9, 1)
        days = (today - sep1).days
        oct_pause = max(0, min(7, (today - date(y, 10, 1)).days + 1))
        eff_days = max(0, days - oct_pause)
        week = max(1, min(TOTAL_WEEKS, 1 + eff_days // 7))
        return {
            "schoolYear": f"{start_year}-{start_year + 1}",
            "phase": "fall",
            "semester": f"{start_year}-{start_year + 1}学年第一学期（上册）",
            "teachingWeek": week,
            "totalWeeks": TOTAL_WEEKS,
            "progressPct": round(week * 100 / TOTAL_WEEKS),
            "versionTag": f"{start_year}-fall-w{week:02d}",
        }
    if m == 1 and d <= 18:
        start_year = y - 1
        sep1 = date(start_year, 9, 1)
        eff_days = max(0, (today - sep1).days - 7)
        week = max(1, min(TOTAL_WEEKS, 1 + eff_days // 7))
        return {
            "schoolYear": f"{start_year}-{y}",
            "phase": "fall",
            "semester": f"{start_year}-{y}学年第一学期（上册）",
            "teachingWeek": week,
            "totalWeeks": TOTAL_WEEKS,
            "progressPct": round(week * 100 / TOTAL_WEEKS),
            "versionTag": f"{start_year}-fall-w{week:02d}",
        }
    if m == 1 or (m == 2 and d < 25):
        start_year = y - 1
        return {
            "schoolYear": f"{start_year}-{y}",
            "phase": "winter",
            "semester": f"{start_year}-{y}学年寒假复习（上册全册）",
            "teachingWeek": TOTAL_WEEKS,
            "totalWeeks": TOTAL_WEEKS,
            "progressPct": 100,
            "versionTag": f"{start_year}-winter-w18",
        }
    if (m == 2 and d >= 25) or (3 <= m <= 6) or (m == 7 and d <= 5):
        start_year = y - 1
        feb25 = date(y, 2, 25)
        days = max(0, (today - feb25).days)
        week = max(1, min(TOTAL_WEEKS, 1 + days // 7))
        return {
            "schoolYear": f"{start_year}-{y}",
            "phase": "spring",
            "semester": f"{start_year}-{y}学年第二学期（下册）",
            "teachingWeek": week,
            "totalWeeks": TOTAL_WEEKS,
            "progressPct": round(week * 100 / TOTAL_WEEKS),
            "versionTag": f"{y}-spring-w{week:02d}",
        }
    start_year = y - 1
    return {
        "schoolYear": f"{start_year}-{y}",
        "phase": "summer",
        "semester": f"{start_year}-{y}学年暑假巩固（上下册全册）",
        "teachingWeek": TOTAL_WEEKS,
        "totalWeeks": TOTAL_WEEKS,
        "progressPct": 100,
        "versionTag": f"{y}-summer-w18",
    }


def unlocked(question: dict, phase: str, week: int) -> bool:
    term = question.get("term") or "any"
    q_week = int(question.get("week") or 1)
    if term == "any":
        return True
    if phase in ("fall", "winter"):
        return term == "fall" and q_week <= week
    if phase == "spring":
        return term == "fall" or (term == "spring" and q_week <= week)
    return True


def valid_question(q: dict, expected_age: int) -> bool:
    if int(q.get("age", -1)) != expected_age or not (5 <= expected_age <= 15):
        return False
    if not str(q.get("id") or "").strip():
        return False
    if not str(q.get("category") or "").strip() or not str(q.get("question") or "").strip():
        return False
    opts = q.get("options")
    if not isinstance(opts, list) or len(opts) != 4:
        return False
    cleaned = [str(o).strip() for o in opts]
    if any(not o for o in cleaned) or len(set(cleaned)) != 4:
        return False
    ans = q.get("answerIndex", -1)
    return isinstance(ans, int) and 0 <= ans <= 3


def sync_release() -> None:
    rel = json.loads(fetch(API, "application/vnd.github+json", timeout=25))
    version = rel["tag_name"].lstrip("v")
    asset = next((a for a in rel["assets"] if a["name"].endswith(".apk")), None)
    if asset is None:
        raise RuntimeError(f"release {version} 没有附 APK 资产")
    DEST.mkdir(parents=True, exist_ok=True)
    latest_path = DEST / "latest.json"
    current = json.loads(latest_path.read_text(encoding="utf-8")) if latest_path.is_file() else {}
    target = DEST / asset["name"]
    if not (current.get("version") == version and target.is_file() and target.stat().st_size == asset["size"]):
        tmp = DEST / (asset["name"] + ".part")
        with open(tmp, "wb") as f:
            fetch(asset["url"], "application/octet-stream", dest=f, timeout=300)
        if tmp.stat().st_size != asset["size"]:
            tmp.unlink()
            raise RuntimeError(f"下载不完整: {asset['name']}")
        tmp.rename(target)
        print(f"已下载 {asset['name']}")
    notes = rel.get("body") or ""
    (DEST / "release_notes.md").write_text(notes, encoding="utf-8")
    latest_path.write_text(
        json.dumps({"version": version, "apk": asset["name"], "notes": notes}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    print(f"已同步发版 {version}: {asset['name']}")


def sync_question_bank(dest_root: Path = DEST) -> None:
    try:
        sync_bank_subdir("pool")
        sync_bank_subdir("manual")
    except Exception as net_exc:
        if not (CACHE_DIR / "pool" / "index.json").is_file():
            raise
        print(f"远端题库增量拉取异常（使用本机缓存继续推进学期进度）: {net_exc}", file=sys.stderr)

    today = datetime.now(TZ_CN).date()
    prog = estimate_progress(today)
    pool_index = json.loads((CACHE_DIR / "pool" / "index.json").read_text(encoding="utf-8"))

    manual_by_age: dict[int, list[dict]] = {}
    for m_file in pool_index.get("manualFiles", []):
        m_path = CACHE_DIR / m_file.lstrip("/")
        if not m_path.is_file():
            continue
        raw_m = json.loads(m_path.read_text(encoding="utf-8"))
        items = raw_m if isinstance(raw_m, list) else raw_m.get("questions", [])
        for item in items:
            age = int(item.get("age", 0))
            if not (5 <= age <= 15):
                continue
            q = dict(item)
            if not q.get("id"):
                digest = hashlib.sha1(f"{age}|{q.get('category','')}|{q.get('question','')}".encode("utf-8")).hexdigest()[:10]
                q["id"] = f"o{age}-m-{digest}"
            manual_by_age.setdefault(age, []).append(q)

    q_dir = dest_root / "questions"
    q_dir.mkdir(parents=True, exist_ok=True)
    shards_meta = []
    content_hasher = hashlib.sha256()

    for entry in pool_index.get("pools", []):
        age = int(entry["age"])
        rel_path = entry["file"].lstrip("/")
        pool_data = json.loads((CACHE_DIR / rel_path).read_text(encoding="utf-8"))
        raw_questions = list(pool_data.get("questions", [])) + manual_by_age.pop(age, [])

        seen_ids: set[str] = set()
        seen_prompts: set[str] = set()
        active: list[dict] = []
        for q in raw_questions:
            if not unlocked(q, prog["phase"], prog["teachingWeek"]):
                continue
            if not valid_question(q, age):
                continue
            qid = str(q["id"]).strip()
            prompt = str(q["question"]).strip()
            if qid in seen_ids or prompt in seen_prompts:
                continue
            seen_ids.add(qid)
            seen_prompts.add(prompt)
            active.append({
                "id": qid,
                "age": age,
                "category": str(q["category"]).strip(),
                "question": prompt,
                "options": [str(o).strip() for o in q["options"]],
                "answerIndex": int(q["answerIndex"]),
                "explanation": str(q.get("explanation") or "").strip(),
                "term": q.get("term", "any"),
                "week": int(q.get("week") or 1),
                "unit": str(q.get("unit") or ""),
            })

        shard_payload = {
            "version": prog["versionTag"],
            "age": age,
            "grade": max(0, age - 6),
            "semester": prog["semester"],
            "teachingWeek": prog["teachingWeek"],
            "questions": active,
        }
        shard_bytes = (json.dumps(shard_payload, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        sha = hashlib.sha256(shard_bytes).hexdigest()
        content_hasher.update(sha.encode("ascii"))
        shard_name = f"age_{age}.json"
        atomic_write_bytes(q_dir / shard_name, shard_bytes)
        shards_meta.append({
            "age": age,
            "grade": max(0, age - 6),
            "url": f"questions/{shard_name}",
            "count": len(active),
            "bytes": len(shard_bytes),
            "sha256": sha,
        })

    for age in sorted(manual_by_age.keys()):
        active = [q for q in manual_by_age[age] if valid_question(q, age)]
        if not active:
            continue
        shard_payload = {
            "version": prog["versionTag"],
            "age": age,
            "grade": max(0, age - 6),
            "semester": prog["semester"],
            "teachingWeek": prog["teachingWeek"],
            "questions": active,
        }
        shard_bytes = (json.dumps(shard_payload, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        sha = hashlib.sha256(shard_bytes).hexdigest()
        content_hasher.update(sha.encode("ascii"))
        shard_name = f"age_{age}.json"
        atomic_write_bytes(q_dir / shard_name, shard_bytes)
        shards_meta.append({
            "age": age,
            "grade": max(0, age - 6),
            "url": f"questions/{shard_name}",
            "count": len(active),
            "bytes": len(shard_bytes),
            "sha256": sha,
        })

    shards_meta.sort(key=lambda s: s["age"])
    short_rev = content_hasher.hexdigest()[:8]
    manifest = {
        "version": f"{prog['versionTag']}-{short_rev}",
        "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "semester": prog["semester"],
        "teachingWeek": prog["teachingWeek"],
        "totalWeeks": prog["totalWeeks"],
        "progressPct": prog["progressPct"],
        "shards": shards_meta,
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    atomic_write_bytes(q_dir / "manifest.json", manifest_bytes)
    total_q = sum(s["count"] for s in shards_meta)
    print(f"已同步在线题库 {manifest['version']}（{prog['semester']} 第{prog['teachingWeek']}周，共 {len(shards_meta)} 个年龄段 {total_q} 题）")


def main() -> None:
    errors: list[str] = []
    try:
        sync_release()
    except Exception as exc:
        errors.append(f"release 同步失败: {exc}")
        print(errors[-1], file=sys.stderr)
    try:
        sync_question_bank()
    except Exception as exc:
        errors.append(f"在线题库同步失败: {exc}")
        print(errors[-1], file=sys.stderr)
    if errors:
        sys.exit(1)


if __name__ == "__main__":
    main()
