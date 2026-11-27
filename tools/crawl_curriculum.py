#!/usr/bin/env python3
"""从国家中小学智慧教育平台抓取 1–9 年级（7–15 岁，六三学制）各科教材单元与课文知识点目录。

输出: question-bank/curriculum/textbook_catalog.json
无需登录或密钥，使用公开静态目录树接口；网络不可达时保留已有目录快照不覆盖。
"""

import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_PATH = ROOT / "question-bank" / "curriculum" / "textbook_catalog.json"

VERSION_URL = "https://s-file-1.ykt.cbern.com.cn/zxx/ndrs/national_lesson/teachingmaterials/version/data_version.json"
TREE_URL = "https://s-file-1.ykt.cbern.com.cn/zxx/ndrs/national_lesson/trees/{book_id}.json"

GRADES = [
    ("一年级", 1, 7),
    ("二年级", 2, 8),
    ("三年级", 3, 9),
    ("四年级", 4, 10),
    ("五年级", 5, 11),
    ("六年级", 6, 12),
    ("七年级", 7, 13),
    ("八年级", 8, 14),
    ("九年级", 9, 15),
]

SUBJECTS = [
    ("语文", "语文"),
    ("数学", "数学"),
    ("英语", "英语"),
    ("科学", "科学"),
    ("道德与法治", "生活"),
    ("历史", "历史"),
    ("地理", "地理"),
    ("物理", "科学"),
    ("化学", "科学"),
    ("生物", "科学"),
]


def fetch_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "GrowQuiz-CurriculumBot/1.0"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def clean_for_grade_match(title: str) -> str:
    return re.sub(r"[（(][^）)]*起点[^）)]*[）)]", "", title)


def is_six_three_mainstream(title: str) -> bool:
    if any(x in title for x in ("五·四", "五•四", "五四", "精通", "新起点", "一年级起点", "盲校", "聋校", "培智")):
        return False
    return any(p in title for p in ("统编版", "人教版", "部编版", "教科版"))


def flatten_lessons(nodes: list[dict]) -> list[str]:
    lessons: list[str] = []
    for node in nodes or []:
        title = (node.get("title") or "").strip()
        children = node.get("child_nodes") or []
        if children:
            sub = flatten_lessons(children)
            if sub:
                lessons.extend(sub)
            elif title:
                lessons.append(title)
        elif title:
            lessons.append(title)
    return lessons


def parse_units(tree: list[dict]) -> list[dict]:
    units = []
    for idx, top in enumerate(tree or []):
        unit_title = (top.get("title") or "").strip()
        if not unit_title:
            continue
        children = top.get("child_nodes") or []
        lessons = flatten_lessons(children) if children else [unit_title]
        # 过滤纯复习/附录空壳目录（若整本书不止这一项）
        units.append({
            "index": idx,
            "unit": unit_title,
            "lessons": lessons[:24],
        })
    return units


def crawl() -> dict:
    ver = fetch_json(VERSION_URL)
    urls = ver["urls"] if isinstance(ver["urls"], list) else ver["urls"].split(",")
    all_books = []
    for u in urls:
        all_books.extend(fetch_json(u.strip()))

    # 按 (grade_num, term_raw, subject_raw) 归组候选教材，按优先级挑第一本有非空章节树的
    candidates: dict[tuple[int, str, str], list[tuple]] = {}
    for b in all_books:
        title = (b.get("title") or "").strip()
        if not is_six_three_mainstream(title):
            continue
        cleaned = clean_for_grade_match(title)
        grade_hit = next(((g_name, g_num, age) for g_name, g_num, age in GRADES if g_name in cleaned), None)
        if not grade_hit:
            continue
        g_name, g_num, age = grade_hit
        # 小学英语统一取三年级起点（3–6 年级）
        if "英语" in title and g_num < 3:
            continue
        term = "fall" if "上册" in cleaned else ("spring" if "下册" in cleaned else ("full" if "全一册" in cleaned else ""))
        if not term:
            continue
        subj_hit = next(((s_raw, s_cat) for s_raw, s_cat in SUBJECTS if s_raw in cleaned), None)
        if not subj_hit:
            continue
        s_raw, s_cat = subj_hit
        # 小学科学优先教科版/人教版，其余科目优先统编版/人教版
        if s_raw == "科学":
            pub_prio = 3 if "教科版" in title else (2 if "人教版" in title else 1)
        else:
            if "教科版" in title and s_raw in ("英语", "语文", "数学"):
                pub_prio = 0
            else:
                pub_prio = 3 if ("统编版" in title or "部编版" in title or "人教版" in title) else 1
        if pub_prio == 0:
            continue
        is_new = 1 if "新教材" in title else 0
        pep = 1 if ("PEP" in title or "三年级起点" in title) else 0
        score = (is_new, pub_prio, pep, b.get("update_time") or "")
        candidates.setdefault((g_num, term, s_raw), []).append((score, b["id"], title, g_name, age, s_cat))

    books_out = []
    for key in sorted(candidates.keys()):
        g_num, term, s_raw = key
        # 若已有同年级上/下册，则跳过冗余的全一册（九年级物理/英语/化学等无上下册时保留全一册）
        if term == "full" and ((g_num, "fall", s_raw) in candidates and (g_num, "spring", s_raw) in candidates):
            # 稍后若上下册均无章节树再兜底
            pass
        ranked = sorted(candidates[key], key=lambda x: x[0], reverse=True)
        chosen = None
        fallback = None
        for score, bid, title, g_name, age, s_cat in ranked:
            try:
                tree = fetch_json(TREE_URL.format(book_id=bid))
            except Exception:
                continue
            units = parse_units(tree)
            if units:
                entry = {
                    "grade": g_num,
                    "gradeName": g_name,
                    "age": age,
                    "term": term,
                    "subject": s_raw,
                    "category": s_cat,
                    "bookId": bid,
                    "bookTitle": title,
                    "units": units,
                }
                if len(units) >= 2:
                    chosen = entry
                    break
                if fallback is None:
                    fallback = entry
        if chosen or fallback:
            books_out.append(chosen or fallback)

    # 若某科目既有上下册非空章节树，又有全一册，去掉重复的全一册
    have_split = {(b["grade"], b["subject"]) for b in books_out if b["term"] in ("fall", "spring")}
    books_out = [b for b in books_out if not (b["term"] == "full" and (b["grade"], b["subject"]) in have_split)]

    return {
        "source": "国家中小学智慧教育平台 (basic.smartedu.cn)",
        "moduleVersion": ver.get("module_version"),
        "crawledAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "bookCount": len(books_out),
        "books": books_out,
    }


def main() -> None:
    try:
        catalog = crawl()
    except Exception as exc:
        if OUT_PATH.is_file():
            print(f"爬取失败（保留已有快照 {OUT_PATH}）: {exc}", file=sys.stderr)
            return
        raise
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    total_units = sum(len(b["units"]) for b in catalog["books"])
    total_lessons = sum(sum(len(u["lessons"]) for u in b["units"]) for b in catalog["books"])
    print(f"已抓取 {catalog['bookCount']} 册教材，共 {total_units} 个单元、{total_lessons} 节课文知识点 -> {OUT_PATH}")


if __name__ == "__main__":
    main()
