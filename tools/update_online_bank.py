#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建并按当前学期教学进度更新 GitHub `question-bank/` 在线题库目录。

流程:
  1. 可选执行 `crawl_curriculum.py` 从国家中小学智慧教育平台同步最新 1–9 年级上下册教材目录树；
  2. 将 `curriculum_questions.py` 的各科知识点模块与真实教材单元目录匹配，自动推算每个单元对应的教学周（1..18 周）；
  3. 与 APK 离线内置题库 (`app/src/main/assets/questions/age_*.json`) 去重，写出全学年题库池 `question-bank/pool/age_<7..15>.json`；
  4. 按当前日期预估教学周进度，写出当前激活的 `question-bank/age_<7..15>.json` 与 `question-bank/manifest.json`。

用法:
  python tools/update_online_bank.py            # 基于现有教材目录快照更新进度题库
  python tools/update_online_bank.py --crawl    # 先重新爬取教材章节目录再更新题库
"""

from __future__ import annotations
import argparse
import hashlib
import json
import random
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "server"))

import crawl_curriculum  # noqa: E402
import curriculum_questions as cq  # noqa: E402

BANK_DIR = ROOT / "question-bank"
POOL_DIR = BANK_DIR / "pool"
MANUAL_DIR = BANK_DIR / "manual"
CATALOG_PATH = BANK_DIR / "curriculum" / "textbook_catalog.json"
OFFLINE_DIR = ROOT / "app" / "src" / "main" / "assets" / "questions"

TZ_CN = timezone(timedelta(hours=8))
TOTAL_WEEKS = 18


def estimate_progress(today: date) -> dict:
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


def match_catalog_unit(books: list[dict], grade: int, default_term: str, default_week: int, subject: str, keywords: list[str]) -> tuple[str, int, str]:
    """在爬取的教材目录中匹配最贴合的单元，返回 `(term, week, unit_label)`。"""
    grade_books = [b for b in books if b["grade"] == grade]
    if keywords:
        # 先在同科目中找命中关键词最多的单元，允许跨上下册（适应新教材章节迁移）
        best = None
        for b in grade_books:
            if b["subject"] != subject and not (subject == "科学" and b["subject"] in ("物理", "化学", "生物", "科学")):
                continue
            units = b["units"]
            n_units = max(1, len(units))
            for idx, u in enumerate(units):
                blob = u["unit"] + " " + " ".join(u.get("lessons", []))
                hits = sum(1 for kw in keywords if kw in blob)
                if hits > 0:
                    term = b["term"] if b["term"] in ("fall", "spring") else default_term
                    est_week = max(1, min(TOTAL_WEEKS, round((idx + 0.6) / n_units * TOTAL_WEEKS)))
                    term_bonus = 1 if term == default_term else 0
                    cand = (hits, term_bonus, term, est_week, f"{b['bookTitle']}·{u['unit']}")
                    if best is None or cand[:2] > best[:2]:
                        best = cand
        if best is not None:
            return best[2], best[3], best[4]

    # 按默认学期与教学周定位对应科目教材该周所在单元标题
    same_term_books = [b for b in grade_books if b["term"] == default_term and (
        b["subject"] == subject or (subject == "科学" and b["subject"] in ("物理", "化学", "生物", "科学"))
    )]
    if same_term_books:
        b = same_term_books[0]
        units = b["units"]
        idx = min(len(units) - 1, max(0, int((default_week - 1) / TOTAL_WEEKS * len(units))))
        return default_term, default_week, f"{b['bookTitle']}·{units[idx]['unit']}"
    return default_term, default_week, f"{grade}年级{'上册' if default_term == 'fall' else '下册'}·第{default_week}周"


def load_offline_prompts() -> dict[int, set[str]]:
    by_age: dict[int, set[str]] = {}
    for age in range(5, 16):
        p = OFFLINE_DIR / f"age_{age}.json"
        if p.is_file():
            data = json.loads(p.read_text(encoding="utf-8"))
            by_age[age] = {q["question"].strip() for q in data.get("questions", [])}
        else:
            by_age[age] = set()
    return by_age


def build_pools(books: list[dict]) -> dict[int, list[dict]]:
    rng = random.Random(20261127)
    offline = load_offline_prompts()
    all_mods = cq.build_math_modules(rng) + cq.CURATED_MODULES
    for g in range(1, 10):
        all_mods.extend(cq.generate_weekly_practice(g, rng))

    pools: dict[int, list[dict]] = {age: [] for age in range(7, 16)}
    seen_prompts: dict[int, set[str]] = {age: set(offline.get(age, set())) for age in range(7, 16)}

    for mod in all_mods:
        grade = mod["grade"]
        age = grade + 6
        term, week, unit_label = match_catalog_unit(
            books, grade, mod["term"], mod["week"], mod["subject"], mod.get("keywords", []),
        )
        for item in mod["items"]:
            if len(item) == 4:
                prompt, correct, distractors, explanation = item
                category = mod["category"]
            else:
                category, prompt, correct, distractors, explanation = item
            prompt = prompt.strip()
            correct = str(correct).strip()
            d_clean = [str(x).strip() for x in distractors]
            if prompt in seen_prompts[age]:
                continue
            if len(d_clean) != 3 or len(set(d_clean + [correct])) != 4 or any(not x for x in d_clean + [correct]):
                raise ValueError(f"选项不合法: age={age} prompt={prompt} correct={correct} distractors={d_clean}")
            seen_prompts[age].add(prompt)

            # 基于题干哈希确定性地打乱选项，保证多次运行同一题的答案下标恒定
            h = hashlib.sha1(f"{age}|{prompt}".encode("utf-8")).hexdigest()
            q_rng = random.Random(int(h[:12], 16))
            options = d_clean + [correct]
            q_rng.shuffle(options)
            qid = f"o{age}-{term[0]}w{week:02d}-{h[:8]}"
            pools[age].append({
                "id": qid,
                "age": age,
                "category": category,
                "question": prompt,
                "options": options,
                "answerIndex": options.index(correct),
                "explanation": explanation.strip(),
                "term": term,
                "week": week,
                "unit": unit_label,
            })

    for age in pools:
        pools[age].sort(key=lambda q: (0 if q["term"] == "fall" else 1, q["week"], q["id"]))
    return pools


def ensure_manual_seed() -> list[str]:
    MANUAL_DIR.mkdir(parents=True, exist_ok=True)
    seed_file = MANUAL_DIR / "custom_questions.json"
    if not seed_file.is_file():
        seed = {
            "description": "在此文件中直接维护手工或 AI 补充的在线题（term 填 any 表示全年启用，fall/spring 配合 week 1..18 按学期周解锁）。",
            "questions": [
                {
                    "id": "o7-m-safety01",
                    "age": 7,
                    "category": "安全",
                    "question": "放学路上遇到红绿灯路口，绿灯亮起准备过斑马线时应该怎么做？",
                    "options": ["先左右观察确认车辆礼让后再通过", "闭着眼睛快速冲过去", "边看动画片边慢慢走", "翻越马路中间的护栏"],
                    "answerIndex": 0,
                    "explanation": "绿灯通行时也要遵循「一停二看三通过」，确认安全再走斑马线。",
                    "term": "any",
                    "week": 1
                },
                {
                    "id": "o8-m-safety01",
                    "age": 8,
                    "category": "安全",
                    "question": "家里炒菜油锅突然起火时，大人最正确的灭火方法是什么（小朋友应先远离并呼救）？",
                    "options": ["迅速盖上锅盖并关掉燃气阀门", "往热油锅里泼一盆冷水", "用扇子使劲扇灭火焰", "端起着火的油锅往外跑"],
                    "answerIndex": 0,
                    "explanation": "油锅起火千万不能浇水（热油遇水会剧烈飞溅），盖上锅盖可隔绝空气灭火。",
                    "term": "any",
                    "week": 1
                }
            ],
        }
        seed_file.write_text(json.dumps(seed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return [f"manual/{p.name}" for p in sorted(MANUAL_DIR.glob("*.json"))]


def main() -> None:
    parser = argparse.ArgumentParser(description="更新 GrowQuiz 在线教材进度题库")
    parser.add_argument("--crawl", action="store_true", help="先从国家中小学智慧教育平台重新抓取教材目录")
    parser.add_argument("--date", type=str, default="", help="指定演进日期 YYYY-MM-DD（默认按今天北京时间）")
    args = parser.parse_args()

    if args.crawl or not CATALOG_PATH.is_file():
        crawl_curriculum.main()

    catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    books = catalog.get("books", [])
    pools = build_pools(books)
    manual_files = ensure_manual_seed()

    POOL_DIR.mkdir(parents=True, exist_ok=True)
    pool_entries = []
    for age, questions in sorted(pools.items()):
        rel = f"pool/age_{age}.json"
        payload = {
            "age": age,
            "grade": age - 6,
            "total": len(questions),
            "questions": questions,
        }
        (BANK_DIR / rel).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        pool_entries.append({"age": age, "grade": age - 6, "file": rel, "count": len(questions)})

    pool_index = {
        "catalogCrawledAt": catalog.get("crawledAt"),
        "bookCount": catalog.get("bookCount"),
        "manualFiles": manual_files,
        "pools": pool_entries,
    }
    (POOL_DIR / "index.json").write_text(json.dumps(pool_index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # 读取人工补充题并合并生成当前学期教学周激活题库
    manual_by_age: dict[int, list[dict]] = {}
    for mf in manual_files:
        raw_m = json.loads((BANK_DIR / mf).read_text(encoding="utf-8"))
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

    today = date.fromisoformat(args.date) if args.date else datetime.now(TZ_CN).date()
    prog = estimate_progress(today)

    shards_meta = []
    content_hasher = hashlib.sha256()
    all_ages = sorted(set(pools.keys()) | set(manual_by_age.keys()))
    for age in all_ages:
        candidates = list(pools.get(age, [])) + manual_by_age.get(age, [])
        active = []
        seen_ids = set()
        seen_prompts = set()
        for q in candidates:
            if not unlocked(q, prog["phase"], prog["teachingWeek"]):
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
        (BANK_DIR / shard_name).write_bytes(shard_bytes)
        shards_meta.append({
            "age": age,
            "grade": max(0, age - 6),
            "url": f"questions/{shard_name}",
            "count": len(active),
            "bytes": len(shard_bytes),
            "sha256": sha,
        })

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
    (BANK_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    total_pool = sum(len(v) for v in pools.values()) + sum(len(v) for v in manual_by_age.values())
    total_active = sum(s["count"] for s in shards_meta)
    print(json.dumps({
        "semester": prog["semester"],
        "teachingWeek": prog["teachingWeek"],
        "progressPct": prog["progressPct"],
        "poolTotal": total_pool,
        "activeTotal": total_active,
        "perAgeActive": {s["age"]: s["count"] for s in shards_meta},
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
