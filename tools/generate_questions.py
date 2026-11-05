#!/usr/bin/env python3
"""Deterministically build and validate the offline 5-15 year-old question bank (v3, 33k).

Composition per age (3000 questions):
  - legacy 800 (ids a{age}-0001..0800, bit-identical to v2):
      500 procedural math/logic (build_math_legacy)
      200 language questions sampled from the band's LANG pool
      100 general-knowledge questions sampled from the band's CURATED pool
  - extra 2200 (ids a{age}-0801..3000):
      1500 math/logic (build_math_extra, wider ranges + contextual word problems)
      500 language from tools/question_data/lang_bank.py data tables
      200 knowledge from the programmatic knowledge generators below
Band pools live in tools/question_data/band_<lo>_<hi>.py
Output: per-age shards app/src/main/assets/questions/age_<N>.json (lazy-loaded by the app).
"""
import importlib.util
import json
import random
from math import gcd
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "app/src/main/assets/questions"
LEGACY_OUT = ROOT / "app/src/main/assets/questions.json"
DATA_DIR = ROOT / "tools/question_data"

LEGACY_MATH = 500
LEGACY_LANG = 200
LEGACY_KNOW = 100
EXTRA_MATH = 1500
EXTRA_LANG = 500
EXTRA_KNOW = 200
PER_AGE = LEGACY_MATH + LEGACY_LANG + LEGACY_KNOW + EXTRA_MATH + EXTRA_LANG + EXTRA_KNOW

BANDS = {5: "5_6", 6: "5_6", 7: "7_8", 8: "7_8", 9: "9_10", 10: "9_10",
         11: "11_12", 12: "11_12", 13: "13_14", 14: "13_14", 15: "15"}


def numeric_distractors(answer, rng, count=3):
    values = []
    offsets = [-10, -3, -2, -1, 1, 2, 3, 10]
    rng.shuffle(offsets)
    for offset in offsets:
        value = round(answer + offset, 2)
        if value >= 0 and value not in values and value != answer:
            values.append(value)
        if len(values) == count:
            break
    while len(values) < count:
        value = round(answer + rng.randint(4, 20), 2)
        if value not in values and value != answer:
            values.append(value)
    return values


def frac(num, den):
    """Simplify and format a fraction; integer when it divides evenly."""
    if den == 0:
        raise ValueError("zero denominator")
    g = gcd(abs(num), abs(den))
    num, den = num // g, den // g
    if den < 0:
        num, den = -num, -den
    return str(num) if den == 1 else f"{num}/{den}"


def fmt(x):
    """Compact number formatting: 3.0 -> '3', 3.14 -> '3.14'."""
    if isinstance(x, float):
        if x == int(x):
            return str(int(x))
        return f"{x:.2f}".rstrip("0").rstrip(".")
    return str(x)


# ---------------------------------------------------------------------------
# legacy generator (v2) — kept byte-for-byte so the first 800 ids never change
# ---------------------------------------------------------------------------

def build_math_legacy(age, rng):
    questions = []
    seen = set()

    def add(category, prompt, answer, distractors=None, explanation=None):
        answer = str(answer)
        if prompt in seen:
            return False
        if distractors is None:
            try:
                number = int(answer)
            except ValueError:
                try:
                    number = float(answer)
                except ValueError:
                    return False
            distractors = numeric_distractors(number, rng)
        options = [str(x) for x in distractors]
        if len(options) != 3 or len(set(options + [answer])) != 4:
            return False
        seen.add(prompt)
        questions.append((category, prompt, answer, options, explanation or f"正确答案是{answer}。"))
        return True

    attempts = 0
    while len(questions) < LEGACY_MATH:
        attempts += 1
        if attempts > 400_000:
            raise RuntimeError(f"Unable to build enough unique questions for age {age}")

        if age == 5:
            kind = rng.randrange(16)
            a, b = rng.randint(1, 10), rng.randint(1, 10)
            if kind == 0:
                if a + b <= 20:
                    add("数学", f"{a} + {b} = ?", a + b, explanation=f"{a}和{b}合起来是{a+b}。")
            elif kind == 1:
                high, low = max(a, b), min(a, b)
                add("数学", f"{high} - {low} = ?", high - low)
            elif kind == 2:
                if a != b:
                    add("数学", f"{a}和{b}中，哪个数更大？", max(a, b), [min(a, b), max(a, b) + 1, max(a, b) + 2])
            elif kind == 3:
                if a != b:
                    add("数学", f"{a}和{b}中，哪个数更小？", min(a, b), [max(a, b), max(0, min(a, b) - 1), min(a, b) + 2])
            elif kind == 4:
                if a + b <= 20:
                    add("数学", f"{a} + □ = {a + b}，□里是几？", b, [max(0, b - 1), b + 1, b + 2])
            elif kind == 5:
                add("逻辑", f"数一数：{'● ' * a}，一共有几个圆点？", a)
            elif kind == 6:
                start = rng.randint(1, 15)
                add("逻辑", f"按规律填数：{start}，{start + 1}，{start + 2}，□", start + 3)
            elif kind == 7:
                start = rng.randint(1, 12)
                add("逻辑", f"按规律填数：{start}，{start + 2}，{start + 4}，□", start + 6)
            elif kind == 8:
                total = a + b
                if total >= 3:
                    add("数学", f"哪两个数合起来是{total}？", f"{a}和{b}",
                        [f"{a}和{b + 1}", f"{a + 1}和{b}", f"{a}和{max(1, b - 1)}"])
            elif kind == 9:
                name, sides = rng.choice([("三角形", 3), ("正方形", 4), ("长方形", 4), ("圆形", 0)])
                if rng.random() < 0.5:
                    add("图形", f"{name}有几条边？", sides, [sides + 1, sides + 2, sides + 3] if sides == 0 else [sides - 1, sides + 1, sides + 2])
                else:
                    corners = sides
                    add("图形", f"{name}有几个角？", corners, [corners + 1, corners + 2, corners + 3] if corners == 0 else [corners - 1, corners + 1, corners + 2])
            elif kind == 10:
                if a + b <= 20:
                    add("应用", f"小明有{a}个苹果，又得到{b}个，现在有几个？", a + b)
            elif kind == 11:
                high, low = max(a, b), min(a, b)
                add("应用", f"盒里有{high}块糖，吃了{low}块，还剩几块？", high - low)
            elif kind == 12:
                third = a + b + 1
                vals = sorted({a, b, third})
                if len(vals) == 3:
                    add("数学", f"{vals[0]}、{vals[1]}、{vals[2]}中，最大的是几？", vals[2], [vals[0], vals[1], vals[2] + 2])
            elif kind == 13:
                third = a + b + 1
                vals = sorted({a, b, third})
                if len(vals) == 3:
                    add("数学", f"{vals[0]}、{vals[1]}、{vals[2]}中，最小的是几？", vals[0], [vals[1], vals[2], vals[0] + 3])
            elif kind == 14:
                n = rng.randint(2, 19)
                if rng.random() < 0.5:
                    add("数学", f"{n}前面的一个数是几？", n - 1)
                else:
                    add("数学", f"{n}后面的一个数是几？", n + 1)
            else:
                x, y = rng.randint(2, 6), rng.randint(2, 6)
                add("应用", f"小朋友排队，小明从前往后数排第{x}，从后往前数排第{y}，这一排共有几人？", x + y - 1,
                    explanation=f"从前往后第{x}、从后往前第{y}，小明被数了两次，所以是{x}+{y}-1={x+y-1}人。")

        elif age == 6:
            kind = rng.randrange(16)
            a, b = rng.randint(2, 20), rng.randint(2, 15)
            if kind == 0:
                add("数学", f"{a} + {b} = ?", a + b)
            elif kind == 1:
                add("数学", f"{a + b} - {b} = ?", a)
            elif kind == 2:
                x, y = rng.randint(11, 99), rng.randint(11, 99)
                if x != y:
                    if rng.random() < 0.5:
                        add("数学", f"{x}和{y}中，哪个数更大？", max(x, y), [min(x, y), max(x, y) + 1, max(0, max(x, y) - 10)])
                    else:
                        add("数学", f"{x}和{y}中，哪个数更小？", min(x, y), [max(x, y), min(x, y) + 1, max(0, min(x, y) - 1)])
            elif kind == 3:
                add("数学", f"□ + {b} = {a + b}，□里是几？", a)
            elif kind == 4:
                if a >= b:
                    add("数学", f"{a} - □ = {a - b}，□里是几？", b)
            elif kind == 5:
                start = rng.randint(1, 30)
                add("逻辑", f"按规律填数：{start}，{start + 2}，{start + 4}，□", start + 6)
            elif kind == 6:
                start = rng.randint(1, 60)
                add("逻辑", f"按规律填数：{start}，{start + 5}，{start + 10}，□", start + 15)
            elif kind == 7:
                start = rng.randint(1, 60)
                add("逻辑", f"按规律填数：{start}，{start + 10}，{start + 20}，□", start + 30)
            elif kind == 8:
                n = rng.randint(11, 99)
                if rng.random() < 0.5:
                    add("数学", f"{n}的个位是几？", n % 10, [(n + 1) % 10, (n + 2) % 10, n // 10])
                else:
                    add("数学", f"{n}的十位是几？", n // 10, [(n // 10) + 1, max(0, (n // 10) - 1), n % 10])
            elif kind == 9:
                name, sides = rng.choice([("长方形", 4), ("正方形", 4), ("三角形", 3), ("圆形", 0)])
                if rng.random() < 0.5:
                    add("图形", f"{name}有几条边？", sides, [sides + 1, sides + 2, sides + 3] if sides == 0 else [sides - 1, sides + 1, sides + 2])
                else:
                    add("图形", f"{name}有几个角？", sides, [sides + 1, sides + 2, sides + 3] if sides == 0 else [sides - 1, sides + 1, sides + 2])
            elif kind == 10:
                add("应用", f"小红做了{a}朵花，小华做了{b}朵，一共做了多少朵？", a + b)
            elif kind == 11:
                add("应用", f"停车场有{a + b}辆车，开走{b}辆，还剩几辆？", a)
            elif kind == 12:
                add("数学", f"哪个算式的得数等于{a + b}？", f"{a}+{b}", [f"{a}+{b + 1}", f"{a + 1}+{b}", f"{a}+{b + 2}"])
            elif kind == 13:
                n = rng.randint(1, 9)
                add("应用", f"1张10元和{n}张1元合起来是多少元？", 10 + n)
            elif kind == 14:
                x, y = rng.randint(1, 8) * 10, rng.randint(1, 8) * 10
                add("数学", f"{x} + {y} = ?", x + y)
            else:
                h = rng.randint(1, 10)
                add("应用", f"早上{h}点过2小时是几点？", h + 2)

        elif age in (7, 8):
            kind = rng.randrange(18)
            max_factor = 5 if age == 7 else 9
            a, b = rng.randint(6, 60), rng.randint(2, 30)
            x, y = rng.randint(2, max_factor), rng.randint(2, max_factor)
            if kind == 0:
                add("数学", f"{a} + {b} = ?", a + b)
            elif kind == 1:
                add("数学", f"{a + b} - {b} = ?", a)
            elif kind == 2:
                add("数学", f"{x} × {y} = ?", x * y)
            elif kind == 3:
                add("数学", f"{x * y} ÷ {x} = ?", y)
            elif kind == 4:
                add("数学", f"□ + {b} = {a + b}，□里是几？", a)
            elif kind == 5:
                add("逻辑", f"按规律填数：{a}，{a + x}，{a + 2 * x}，□", a + 3 * x)
            elif kind == 6:
                add("数学", f"{x}的{y}倍是多少？", x * y)
            elif kind == 7:
                length, width = rng.randint(3, 12), rng.randint(2, 8)
                add("图形", f"长方形长{length}厘米、宽{width}厘米，周长是多少厘米？", 2 * (length + width))
            elif kind == 8:
                add("应用", f"每盒有{x}支笔，{y}盒一共有多少支？", x * y)
            elif kind == 9:
                add("应用", f"把{x * y}个苹果平均分成{x}份，每份几个？", y)
            elif kind == 10:
                add("应用", f"一本书有{a + b}页，已经看了{b}页，还剩多少页？", a)
            elif kind == 11:
                add("数学", f"{x} × {y} + {x} = ?", x * y + x)
            elif kind == 12:
                c = rng.randint(2, max_factor)
                add("数学", f"{a} - {c} × {c} = ?", a - c * c) if a > c * c else None
            elif kind == 13:
                m = rng.randint(1, 9)
                add("数学", f"{m}米等于多少厘米？", m * 100, explanation="1米=100厘米。")
            elif kind == 14:
                big1, big2 = rng.randint(100, 500), rng.randint(100, 400)
                add("数学", f"{big1} + {big2} = ?", big1 + big2)
            elif kind == 15:
                n = rng.randint(2, 9)
                add("应用", f"一节课40分钟，{n}节课一共多少分钟？", 40 * n)
            elif kind == 16:
                divisor = rng.randint(2, max_factor)
                quotient = rng.randint(2, 9)
                remainder = rng.randint(1, divisor - 1)
                add("数学", f"{divisor * quotient + remainder} ÷ {divisor}，余数是几？", remainder)
            else:
                c = rng.randint(1, min(b, 15))
                add("数学", f"{a} + {b} - {c} = ?", a + b - c)

        elif age in (9, 10):
            kind = rng.randrange(20)
            a, b = rng.randint(25, 500), rng.randint(12, 120)
            x, y = rng.randint(3, 12), rng.randint(12, 40)
            if kind == 0:
                add("数学", f"{a} + {b} = ?", a + b)
            elif kind == 1:
                add("数学", f"{a + b} - {b} = ?", a)
            elif kind == 2:
                add("数学", f"{y} × {x} = ?", x * y)
            elif kind == 3:
                add("数学", f"{x * y} ÷ {x} = ?", y)
            elif kind == 4:
                divisor = rng.randint(3, 9)
                quotient = rng.randint(5, 25)
                remainder = rng.randint(1, divisor - 1)
                add("数学", f"{divisor * quotient + remainder} ÷ {divisor}，余数是几？", remainder)
            elif kind == 5:
                add("逻辑", f"按规律填数：{a}，{a + x}，{a + 2 * x}，□", a + 3 * x)
            elif kind == 6:
                length, width = rng.randint(4, 15), rng.randint(3, 12)
                if age == 9:
                    add("图形", f"正方形边长{length}厘米，周长是多少厘米？", 4 * length)
                else:
                    add("图形", f"长方形长{length}厘米、宽{width}厘米，面积是多少平方厘米？", length * width)
            elif kind == 7:
                n = rng.randint(12, 72)
                divisors = [d for d in range(2, 13) if n % d == 0]
                non = [d for d in range(2, 13) if n % d != 0]
                if divisors and len(non) >= 3:
                    add("数学", f"下面哪个数是{n}的因数？", rng.choice(divisors), non[:3])
            elif kind == 8:
                price, count = rng.randint(3, 20), rng.randint(3, 9)
                add("应用", f"每本练习本{price}元，买{count}本需要多少元？", price * count)
            elif kind == 9:
                add("应用", f"{x * y}名学生平均分成{x}组，每组多少人？", y)
            elif kind == 10:
                whole = rng.choice([2, 4, 5, 8, 10])
                part = rng.randint(1, whole - 1)
                add("数学", f"把一个整体平均分成{whole}份，其中{part}份用分数怎样表示？",
                    f"{part}/{whole}", [f"{whole}/{part}", f"{part}/{whole + 1}", f"{part + 1}/{whole}"])
            elif kind == 11:
                if age == 9:
                    if a % 10 != 5:
                        add("数学", f"{a}最接近哪个整十数？", round(a / 10) * 10,
                            [round(a / 10) * 10 - 20, round(a / 10) * 10 - 10, round(a / 10) * 10 + 10])
                else:
                    decimal = rng.randint(11, 99) / 10
                    other = rng.randint(1, 9) / 10
                    add("数学", f"{decimal:.1f} + {other:.1f} = ?", f"{decimal + other:.1f}",
                        [f"{decimal + other + 0.1:.1f}", f"{decimal + other - 0.1:.1f}", f"{decimal + other + 1:.1f}"])
            elif kind == 12:
                nums = [rng.randint(2, 20) for _ in range(3)]
                avg = sum(nums) / 3
                if avg == int(avg):
                    add("数学", f"{nums[0]}、{nums[1]}、{nums[2]}的平均数是多少？", int(avg))
            elif kind == 13:
                base, height = rng.randint(2, 12) * 2, rng.randint(2, 10)
                add("图形", f"三角形底{base}厘米、高{height}厘米，面积是多少平方厘米？", base * height // 2)
            elif kind == 14:
                if rng.random() < 0.5:
                    km = rng.randint(1, 9)
                    add("数学", f"{km}千米等于多少米？", km * 1000, explanation="1千米=1000米。")
                else:
                    kg = rng.randint(1, 9)
                    add("数学", f"{kg}千克等于多少克？", kg * 1000, explanation="1千克=1000克。")
            elif kind == 15:
                den = rng.choice([5, 7, 9, 10])
                n1, n2 = rng.randint(1, den - 1), rng.randint(1, den - 1)
                if n1 != n2:
                    add("数学", f"{n1}/{den} 和 {n2}/{den}，哪个更大？", f"{max(n1, n2)}/{den}",
                        [f"{min(n1, n2)}/{den}", f"{max(n1, n2) + 1}/{den}", f"{max(1, min(n1, n2) - 1)}/{den}"])
            elif kind == 16:
                xa = rng.randint(2, 30)
                xb = rng.randint(2, 30)
                add("数学", f"x + {xa} = {xa + xb}，x = ?", xb)
            elif kind == 17:
                name, deg = rng.choice([("直角", 90), ("平角", 180), ("周角", 360)])
                others = [90, 180, 360]
                others.remove(deg)
                add("图形", f"{name}等于多少度？", deg, [others[0], others[1], deg + 45])
            elif kind == 18:
                speed, time = rng.randint(3, 15), rng.randint(2, 8)
                add("应用", f"汽车每小时行驶{speed * 5}千米，{time}小时行驶多少千米？", speed * 5 * time)
            else:
                n = rng.randint(2, 9)
                m = rng.randint(2, 9)
                add("数学", f"{n}和{n * m}的最大公因数是多少？", n,
                    explanation=f"{n * m}是{n}的倍数，所以最大公因数是{n}。")

        elif age in (11, 12):
            kind = rng.randrange(19)
            a, b = rng.randint(20, 300), rng.randint(11, 99)
            if kind == 0:
                c = rng.randint(2, 9)
                add("数学", f"({a} + {b}) × {c} = ?", (a + b) * c)
            elif kind == 1:
                dec = rng.randint(11, 99) / 10
                m = rng.randint(2, 9)
                add("数学", f"{dec:.1f} × {m} = ?", f"{dec * m:.1f}",
                    [f"{dec * m + 0.1:.1f}", f"{dec * m - 0.1:.1f}", f"{dec * m + 1:.1f}"])
            elif kind == 2:
                m = rng.randint(2, 9)
                q = rng.randint(11, 99) / 10
                add("数学", f"{q * m:.1f} ÷ {m} = ?", f"{q:.1f}", [f"{q + 0.1:.1f}", f"{q - 0.1:.1f}", f"{q + 1:.1f}"])
            elif kind == 3:
                den = rng.choice([5, 7, 9, 11, 12])
                n1 = rng.randint(1, den - 2)
                n2 = rng.randint(1, den - 1 - n1)
                ans = frac(n1 + n2, den)
                add("数学", f"{n1}/{den} + {n2}/{den} = ?", ans,
                    [frac(n1 + n2, den * 2), frac(max(1, n1 + n2 - 1), den), frac(n1 + n2 + 1, den)])
            elif kind == 4:
                den = rng.choice([3, 4, 5, 6, 7, 8])
                num = rng.randint(1, den - 1)
                m = rng.randint(2, 6)
                add("数学", f"{num}/{den} × {m} = ?", frac(num * m, den),
                    [frac(num * m, den * m), frac(num, den * m), frac(num * m + 1, den)])
            elif kind == 5:
                base = rng.choice([20, 40, 50, 60, 80, 100, 120, 200])
                pct = rng.choice([10, 20, 25, 50, 75])
                add("数学", f"{base}的{pct}%是多少？", base * pct // 100)
            elif kind == 6:
                price = rng.choice([20, 30, 40, 50, 60, 80, 100, 120, 150, 200])
                disc = rng.choice([5, 6, 7, 8, 9])
                ans = price * disc // 10
                if price * disc % 10 == 0:
                    add("应用", f"一件商品原价{price}元，打{disc}折后是多少元？", ans,
                        explanation=f"打{disc}折即按原价的{disc * 10}%出售，{price}×0.{disc}={ans}元。")
            elif kind == 7:
                r1, r2 = rng.randint(1, 12), rng.randint(1, 12)
                g = gcd(r1, r2)
                add("数学", f"把比{r1 * g}:{r2 * g}化成最简整数比。", f"{r1}:{r2}",
                    [f"{r1 * g}:{r2}", f"{r1}:{r2 * g}", f"{r2}:{r1}"])
            elif kind == 8:
                xv = rng.randint(2, 15)
                m = rng.randint(2, 9)
                c = rng.randint(1, 20)
                add("数学", f"{m}x + {c} = {m * xv + c}，x = ?", xv)
            elif kind == 9:
                r = rng.randint(1, 10)
                add("图形", f"圆的半径是{r}厘米，周长是多少厘米？（π取3.14）", round(2 * 3.14 * r, 2),
                    [round(3.14 * r, 2), round(3.14 * r * r, 2), round(2 * 3.14 * r + 1, 2)])
            elif kind == 10:
                r = rng.randint(1, 10)
                add("图形", f"圆的半径是{r}厘米，面积是多少平方厘米？（π取3.14）", round(3.14 * r * r, 2),
                    [round(2 * 3.14 * r, 2), round(3.14 * r, 2), round(3.14 * r * r + 3.14, 2)])
            elif kind == 11:
                upper, lower, height = rng.randint(2, 9), rng.randint(10, 18), rng.randint(2, 10) * 2
                add("图形", f"梯形上底{upper}厘米、下底{lower}厘米、高{height}厘米，面积是多少平方厘米？",
                    (upper + lower) * height // 2)
            elif kind == 12:
                l_, w_, h_ = rng.randint(2, 10), rng.randint(2, 10), rng.randint(2, 10)
                add("图形", f"长方体长{l_}厘米、宽{w_}厘米、高{h_}厘米，体积是多少立方厘米？", l_ * w_ * h_)
            elif kind == 13:
                nums = [rng.randint(10, 99) for _ in range(4)]
                if sum(nums) % 4 == 0:
                    add("数学", f"{nums[0]}、{nums[1]}、{nums[2]}、{nums[3]}的平均数是多少？", sum(nums) // 4)
            elif kind == 14:
                speed, time = rng.randint(40, 90), rng.randint(2, 6)
                add("应用", f"一辆汽车每小时行驶{speed}千米，行驶{time}小时共行多少千米？", speed * time)
            elif kind == 15:
                n1, n2 = rng.randint(2, 12), rng.randint(2, 12)
                if n1 != n2:
                    g = gcd(n1, n2)
                    l = n1 * n2 // g
                    if rng.random() < 0.5:
                        add("数学", f"{n1}和{n2}的最大公因数是多少？", g,
                            [g + 1, max(1, g - 1), min(n1, n2)] if g not in (min(n1, n2),) else [g + 1, g + 2, g + 3])
                    else:
                        add("数学", f"{n1}和{n2}的最小公倍数是多少？", l, [l + n1, n1 * n2, l - n2 if l - n2 > 0 else l + n2])
            elif kind == 16:
                neg = rng.randint(-9, -1)
                pos = rng.randint(1, 15)
                add("数学", f"({neg}) + {pos} = ?", neg + pos)
            elif kind == 17:
                principal = rng.choice([1000, 2000, 5000, 10000])
                rate = rng.choice([2, 3, 4, 5])
                add("应用", f"本金{principal}元，年利率{rate}%，存一年的利息是多少元？", principal * rate // 100)
            else:
                neg1, neg2 = rng.randint(-9, -1), rng.randint(-9, -1)
                add("数学", f"({neg1}) × ({neg2}) = ?", neg1 * neg2, explanation="负数乘负数得正数。")

        elif age == 15:
            # 初三（中考）水平：一元二次方程、二次函数、三角函数、圆、概率统计
            kind = rng.randrange(22)
            if kind == 0:
                r1, r2 = sorted(rng.sample(range(-9, 12), 2))
                add("数学", f"方程 x² - ({r1 + r2})x + ({r1 * r2}) = 0 的较小根是？", r1, [r2, -r1, -r2],
                    explanation=f"方程可分解为 (x - ({r1}))(x - ({r2})) = 0。")
            elif kind == 1:
                aa, bb, cc = rng.randint(1, 5), rng.randint(-8, 8), rng.randint(-5, 6)
                add("数学", f"方程 {aa}x² + ({bb})x + ({cc}) = 0 的判别式 Δ = b² - 4ac 的值是？",
                    bb * bb - 4 * aa * cc)
            elif kind == 2:
                h, k = rng.randint(-6, 6), rng.randint(-9, 9)
                if h != 0 and k != 0:
                    add("数学", f"抛物线 y = (x - ({h}))² + ({k}) 的顶点坐标是？", f"({h}, {k})",
                        [f"({-h}, {k})", f"({h}, {-k})", f"({-h}, {-k})"],
                        explanation="顶点式 y = (x - h)² + k 的顶点是 (h, k)。")
            elif kind == 3:
                bb = rng.randint(-8, 8) * 2
                add("数学", f"抛物线 y = x² + ({bb})x + {rng.randint(-9, 9)} 的对称轴是直线 x = ？", -bb // 2,
                    explanation="对称轴为 x = -b/2a。")
            elif kind == 4:
                n, m = rng.randint(2, 9), rng.choice([2, 3, 5, 6, 7])
                add("数学", f"化简：√{n * n * m} = ？", f"{n}√{m}", [f"{m}√{n}", f"{n * m}", f"{n}√{n * m}"],
                    explanation=f"√{n * n * m} = √{n * n} × √{m} = {n}√{m}。")
            elif kind == 5:
                b = rng.choice([2, 3, 5])
                m, n = rng.randint(2, 6), rng.randint(2, 6)
                sub = rng.randrange(3)
                if sub == 0:
                    add("数学", f"{b}^{m} × {b}^{n} = {b}的几次方？", m + n, [m * n, abs(m - n), m + n + 1],
                        explanation="同底数幂相乘，底数不变，指数相加。")
                elif sub == 1:
                    add("数学", f"({b}^{m})^{n} = {b}的几次方？", m * n, [m + n, m * n + 1, m * n + 2],
                        explanation="幂的乘方，底数不变，指数相乘。")
                else:
                    add("数学", f"{b}^{m + n} ÷ {b}^{m} = {b}的几次方？", n, [n + 1, max(1, n - 1), m + n],
                        explanation="同底数幂相除，底数不变，指数相减。")
            elif kind == 6:
                table = [("sin30°", "1/2"), ("cos60°", "1/2"), ("tan45°", "1"), ("sin45°", "√2/2"),
                         ("cos45°", "√2/2"), ("tan30°", "√3/3"), ("tan60°", "√3"), ("sin60°", "√3/2"),
                         ("cos30°", "√3/2")]
                name, value = rng.choice(table)
                others = rng.sample([v for v in dict.fromkeys(v for _, v in table) if v != value], 3)
                add("数学", f"{name} 的值是？", value, others)
            elif kind == 7:
                x_, y_ = rng.randint(1, 12), rng.randint(1, 12)
                add("数学", f"反比例函数 y = k/x 的图象经过点 ({x_}, {y_})，k = ？", x_ * y_,
                    explanation=f"k = x × y = {x_} × {y_} = {x_ * y_}。")
            elif kind == 8:
                ratio, small = rng.randint(2, 5), rng.randint(2, 9)
                add("图形", f"两个相似三角形的相似比为 1:{ratio}，小三角形一边长 {small}，大三角形对应边长是？",
                    small * ratio, [small + ratio, small * ratio + 1, max(1, small * ratio - 1)],
                    explanation="相似三角形对应边的比等于相似比。")
            elif kind == 9:
                central = rng.randint(20, 100) * 2
                if central != 180:
                    add("图形", f"同弧所对的圆心角是 {central}°，则该弧所对的圆周角是？", central // 2,
                        [central, central // 4, 180 - central // 2],
                        explanation="同弧所对的圆周角等于圆心角的一半。")
            elif kind == 10:
                n = rng.choice([30, 45, 60, 90, 120, 180])
                r = rng.choice([2, 3, 6, 9])
                arc = round(n * 3.14 * r / 180, 2)
                add("图形", f"半径为 {r} 厘米的圆中，{n}° 的圆心角所对的弧长是多少厘米？（π取3.14）", arc,
                    [round(n * 3.14 * r / 90, 2), round(n * 3.14 * r / 360, 2), round(n * 3.14 * r * r / 360, 2)],
                    explanation="弧长 l = nπr ÷ 180。")
            elif kind == 11:
                n = rng.choice([30, 45, 60, 90, 120, 180])
                r = rng.choice([2, 4, 6, 10])
                area = round(n * 3.14 * r * r / 360, 2)
                add("图形", f"半径为 {r} 厘米的圆中，圆心角 {n}° 的扇形面积是多少平方厘米？（π取3.14）", area,
                    [round(n * 3.14 * r / 180, 2), round(n * 3.14 * r * r / 180, 2), round(n * 3.14 * r * r / 720, 2)],
                    explanation="扇形面积 S = nπr² ÷ 360。")
            elif kind == 12:
                if rng.random() < 0.4:
                    add("数学", "同时掷两枚硬币，两枚都正面朝上的概率是？", "1/4", ["1/2", "1/3", "3/4"],
                        explanation="共有正正、正反、反正、反反4种等可能结果。")
                else:
                    red, white = rng.randint(1, 5), rng.randint(1, 5)
                    if red != white:
                        add("数学", f"袋中有{red}个红球和{white}个白球，随机摸出一个，摸到白球的概率是？（结果要约分）",
                            frac(white, red + white), [frac(red, red + white), frac(1, red + white), f"{white}/{red + white}"])
            elif kind == 13:
                p, q = rng.randint(1, 9), rng.randint(1, 9)
                if p != q:
                    add("数学", f"把 x² + {p + q}x + {p * q} 因式分解，结果是？", f"(x + {p})(x + {q})",
                        [f"(x + {p})(x - {q})", f"(x - {p})(x - {q})", f"(x + {p * q})(x + 1)"],
                        explanation=f"{p} + {q} = {p + q}，{p} × {q} = {p * q}。")
            elif kind == 14:
                lo, hi = sorted(rng.sample(range(-5, 10), 2))
                add("数学", f"不等式组 x > {lo} 且 x < {hi} 的解集是？", f"{lo} < x < {hi}",
                    [f"x > {hi}", f"x < {lo}", "无解"])
            elif kind == 15:
                mant = rng.choice([1.2, 2.5, 3.4, 4.7, 5.6, 6.3, 7.8, 8.9, 9.1])
                exp = rng.randint(3, 6)
                num = "0." + "0" * (exp - 1) + str(int(mant * 10))
                add("数学", f"用科学记数法表示 {num}，应为{mant}×10的负几次方？", exp,
                    [exp - 1, exp + 1, -exp])
            elif kind == 16:
                a_, b_ = rng.randint(1, 6), rng.randint(1, 6)
                if a_ != b_:
                    add("数学", f"计算：{a_}/x + {b_}/x = ？", f"{a_ + b_}/x",
                        [f"{a_ * b_}/x", f"{a_ + b_}/(2x)", f"{a_ + b_}/x²"],
                        explanation="同分母分式相加，分母不变，分子相加。")
            elif kind == 17:
                b_score = rng.choice([70, 75, 80, 85, 90, 95])
                f_score = rng.choice([70, 75, 80, 85, 90, 95])
                total = 3 * b_score + 2 * f_score
                if total % 5 == 0 and b_score != f_score:
                    add("数学", f"某同学笔试成绩{b_score}分、面试成绩{f_score}分，按笔试60%、面试40%计算总成绩是？",
                        total // 5, [(b_score + f_score) // 2, total // 5 + 2, max(1, total // 5 - 2)],
                        explanation=f"总成绩 = {b_score}×0.6 + {f_score}×0.4 = {total // 5}分。")
            elif kind == 18:
                base = rng.randint(3, 15)
                extra1, extra2 = rng.sample([x for x in range(1, 21) if x != base], 2)
                data = sorted([base, base, base, extra1, extra2])
                add("数学", f"数据 {'，'.join(map(str, data))} 的众数是？", base,
                    [extra1, extra2, sum(data) // 5],
                    explanation="出现次数最多的数是众数。")
            elif kind == 19:
                nums = sorted(rng.sample(range(1, 30), 5))
                add("数学", f"数据 {'，'.join(map(str, nums))} 的极差是？", nums[-1] - nums[0],
                    [nums[-1] - nums[1], nums[-2] - nums[0], nums[-1] + nums[0]],
                    explanation="极差 = 最大值 - 最小值。")
            elif kind == 20:
                nums = sorted(rng.sample(range(2, 40), 6))
                if (nums[2] + nums[3]) % 2 == 0:
                    med = (nums[2] + nums[3]) // 2
                    if med not in (nums[2], nums[3]):
                        add("数学", f"数据 {'，'.join(map(str, nums))} 的中位数是？", med,
                            [nums[2], nums[3], med + 1],
                            explanation="偶数个数据的中位数是中间两数的平均数。")
            else:
                add("图形", *rng.choice([
                    ("对角线互相垂直的平行四边形是？", "菱形", ["矩形", "正方形", "梯形"],
                     "对角线互相垂直的平行四边形是菱形。"),
                    ("对角线相等的平行四边形是？", "矩形", ["菱形", "梯形", "任意四边形"],
                     "对角线相等的平行四边形是矩形。"),
                    ("圆的切线垂直于经过切点的什么？", "半径", ["弦", "割线", "弧"],
                     "切线垂直于经过切点的半径。"),
                    ("平行四边形的对角线互相什么？", "平分", ["垂直", "相等", "垂直且相等"],
                     "平行四边形的对角线互相平分。"),
                    ("菱形的对角线互相什么？", "垂直平分", ["相等", "只平分不垂直", "只垂直不平分"],
                     "菱形的对角线互相垂直平分。"),
                    ("矩形的对角线有什么性质？", "相等且互相平分", ["互相垂直", "互相垂直且平分", "只互相垂直"],
                     "矩形的对角线相等且互相平分。"),
                ]))

        else:  # 13-14
            kind = rng.randrange(20)
            if kind == 0:
                a, b = rng.randint(-30, 30), rng.randint(-30, 30)
                add("数学", f"({a}) + ({b}) = ?", a + b)
            elif kind == 1:
                a, b = rng.randint(-12, 12), rng.randint(-12, 12)
                if b != 0:
                    add("数学", f"({a}) × ({b}) = ?", a * b)
            elif kind == 2:
                base, exp = rng.choice([(-2, 3), (-3, 2), (2, 4), (3, 3), (4, 2), (5, 2), (-5, 2), (2, 5)])
                add("数学", f"({base})^{exp} = ?", base ** exp)
            elif kind == 3:
                n = rng.randint(2, 15)
                add("数学", f"√{n * n} = ?", n)
            elif kind == 4:
                n = rng.randint(2, 9)
                add("数学", f"³√{n ** 3} = ?", n)
            elif kind == 5:
                n = rng.randint(-30, -1)
                add("数学", f"|{n}| = ?", -n)
            elif kind == 6:
                if rng.random() < 0.5:
                    n = rng.randint(-20, 20)
                    if n != 0:
                        add("数学", f"{n}的相反数是多少？", -n)
                else:
                    n = rng.randint(2, 9)
                    add("数学", f"{n}的倒数是多少？", f"1/{n}", [str(n), f"-{n}", f"-1/{n}"])
            elif kind == 7:
                xv = rng.randint(-9, 12)
                m = rng.randint(2, 9)
                c = rng.randint(-10, 10)
                add("数学", f"{m}x + ({c}) = {m * xv + c}，x = ?", xv)
            elif kind == 8:
                xv, yv = rng.randint(1, 12), rng.randint(1, 12)
                add("数学", f"已知 x + y = {xv + yv}，x - y = {xv - yv}，则 x = ?", xv)
            elif kind == 9:
                m, c = rng.randint(2, 6), rng.randint(1, 10)
                bound = rng.randint(8, 30)
                sol = (bound - c) // m + 1
                add("数学", f"不等式 {m}x + {c} > {bound} 的最小整数解是？", sol)
            elif kind == 10:
                mant = rng.choice([1.5, 2.3, 2.5, 3.2, 4.7, 5.6, 6.1, 7.8, 8.9])
                exp = rng.randint(3, 6)
                add("数学", f"用科学记数法表示 {int(mant * 10 ** exp)}，应为{mant}×10的几次方？", exp)
            elif kind == 11:
                a1, a2 = rng.randint(20, 80), rng.randint(20, 80)
                if a1 + a2 < 175:
                    add("图形", f"三角形两个内角分别是{a1}°和{a2}°，第三个内角是多少度？", 180 - a1 - a2,
                        explanation="三角形内角和为180°。")
            elif kind == 12:
                ang = rng.randint(20, 160)
                if rng.random() < 0.5:
                    add("图形", f"两条直线相交，一个角是{ang}°，它的对顶角是多少度？", ang,
                        explanation="对顶角相等。")
                else:
                    add("图形", f"两条直线相交，一个角是{ang}°，它的邻补角是多少度？", 180 - ang,
                        explanation="邻补角之和为180°。")
            elif kind == 13:
                triple = rng.choice([(3, 4, 5), (6, 8, 10), (5, 12, 13), (9, 12, 15), (8, 15, 17), (7, 24, 25)])
                add("图形", f"直角三角形两条直角边是{triple[0]}和{triple[1]}，斜边长是多少？", triple[2],
                    [triple[2] + 1, triple[2] - 1, triple[0] + triple[1]],
                    explanation="由勾股定理：斜边²=两直角边平方和。")
            elif kind == 14:
                m, c = rng.randint(2, 9), rng.randint(-9, 9)
                xv = rng.randint(-5, 8)
                add("数学", f"一次函数 y = {m}x + ({c})，当 x = {xv} 时，y = ?", m * xv + c)
            elif kind == 15:
                red, white = rng.randint(1, 6), rng.randint(1, 6)
                unsimplified = f"{red}/{red + white}"
                simplified = frac(red, red + white)
                if unsimplified != simplified:
                    add("数学", f"袋中有{red}个红球和{white}个白球，随机摸一个，摸到红球的概率是多少？（结果要约分）",
                        simplified,
                        [frac(white, red + white), unsimplified, frac(1, red + white)])
            elif kind == 16:
                nums = sorted(rng.randint(1, 20) for _ in range(5))
                add("数学", f"数据 {nums[0]}，{nums[1]}，{nums[2]}，{nums[3]}，{nums[4]} 的中位数是？", nums[2],
                    [nums[1], nums[3], sum(nums) // 5])
            elif kind == 17:
                xq, yq = rng.choice([(-1, 1), (1, -1), (-1, -1), (1, 1)])
                xv, yv = rng.randint(1, 9) * xq, rng.randint(1, 9) * yq
                quad = {(1, 1): "第一象限", (-1, 1): "第二象限", (-1, -1): "第三象限", (1, -1): "第四象限"}[(xq, yq)]
                others = [q for q in ("第一象限", "第二象限", "第三象限", "第四象限") if q != quad]
                add("数学", f"点 ({xv}, {yv}) 在平面直角坐标系的第几象限？", quad, others)
            elif kind == 18:
                r1, r2 = sorted(rng.sample(range(-6, 8), 2))
                add("数学", f"方程 x² - ({r1 + r2})x + ({r1 * r2}) = 0 的较大根是？", r2, [r1, -r1, -r2],
                    explanation=f"方程可分解为 (x - ({r1}))(x - ({r2})) = 0。")
            else:
                ea, eb = rng.randint(2, 6), rng.randint(2, 6)
                add("数学", f"2^{ea} × 2^{eb} = 2的几次方？", ea + eb, [ea * eb, abs(ea - eb), ea + eb + 1],
                    explanation="同底数幂相乘，底数不变，指数相加。")

    return questions


# ---------------------------------------------------------------------------
# extra math generator (v3): wider ranges + contextual word problems
# ---------------------------------------------------------------------------

NAMES = ["小明", "小红", "小华", "小丽", "小刚", "小芳", "小军", "小燕"]
ITEMS = [("苹果", "个"), ("糖果", "块"), ("贴纸", "张"), ("气球", "个"),
         ("铅笔", "支"), ("积木", "块"), ("弹珠", "颗"), ("卡片", "张")]
EMOJI_COUNTS = [("●", "圆点"), ("★", "星星"), ("▲", "三角形"), ("■", "方块"), ("♥", "爱心")]

CN_DIGITS = "零一二三四五六七八九"


def _cn4(n):
    """Chinese reading of 0 < n <= 9999 (一万以内)。"""
    units = ["", "十", "百", "千"]
    s = str(n)
    res = []
    zero = False
    for i, ch in enumerate(s):
        d = int(ch)
        pos = len(s) - 1 - i
        if d == 0:
            zero = True
        else:
            if zero and res:
                res.append("零")
            res.append(CN_DIGITS[d] + units[pos])
            zero = False
    return "".join(res)


def cn_num(n):
    """Chinese reading of 0 < n < 1,000,000, e.g. 30500 -> 三万零五百。"""
    if n < 10000:
        return _cn4(n)
    hi, lo = divmod(n, 10000)
    s = _cn4(hi) + "万"
    if lo == 0:
        return s
    if lo < 1000:
        s += "零"
    return s + _cn4(lo)


def build_math_extra(age, rng, target, seen):
    questions = []

    def add(category, prompt, answer, distractors=None, explanation=None):
        answer = str(answer)
        if prompt in seen:
            return False
        if distractors is None:
            try:
                number = int(answer)
            except ValueError:
                try:
                    number = float(answer)
                except ValueError:
                    return False
            distractors = numeric_distractors(number, rng)
        options = [str(x) for x in distractors]
        if len(options) != 3 or len(set(options + [answer])) != 4:
            return False
        seen.add(prompt)
        questions.append((category, prompt, answer, options, explanation or f"正确答案是{answer}。"))
        return True

    def ctx():
        name = rng.choice(NAMES)
        item, unit = rng.choice(ITEMS)
        return name, item, unit

    attempts = 0
    while len(questions) < target:
        attempts += 1
        if attempts > 600_000:
            raise RuntimeError(f"math extra: not enough unique prompts for age {age} ({len(questions)}/{target})")

        if age == 5:
            kind = rng.randrange(20)
            if kind == 0:
                a, b = rng.randint(1, 15), rng.randint(1, 15)
                if a + b <= 20:
                    add("数学", f"{a} + {b} = ?", a + b, explanation=f"{a}和{b}合起来是{a+b}。")
            elif kind == 1:
                a = rng.randint(2, 20)
                b = rng.randint(1, a - 1)
                add("数学", f"{a} - {b} = ?", a - b)
            elif kind == 2:
                a, b, c = rng.randint(1, 8), rng.randint(1, 8), rng.randint(1, 8)
                if a + b + c <= 20:
                    add("数学", f"{a} + {b} + {c} = ?", a + b + c)
            elif kind == 3:
                a, b = rng.randint(1, 15), rng.randint(1, 15)
                if a + b <= 20:
                    add("数学", f"□ + {b} = {a + b}，□里是几？", a, [max(0, a - 1), a + 1, a + 2])
            elif kind == 4:
                a = rng.randint(2, 20)
                b = rng.randint(1, a - 1)
                add("数学", f"{a} - □ = {a - b}，□里是几？", b, [max(0, b - 1), b + 1, b + 2])
            elif kind == 5:
                x, y = rng.sample(range(1, 21), 2)
                big, small = max(x, y), min(x, y)
                if rng.random() < 0.5:
                    add("数学", f"{x}和{y}中，哪个数更大？", big, [small, big + 1, big + 2])
                else:
                    add("数学", f"{x}和{y}中，哪个数更小？", small, [big, max(0, small - 1), small + 2])
            elif kind == 6:
                vals = rng.sample(range(1, 21), 3)
                vals.sort()
                if rng.random() < 0.5:
                    add("数学", f"{vals[0]}、{vals[1]}、{vals[2]}中，最大的是几？", vals[2],
                        [vals[0], vals[1], vals[2] - 1 if vals[2] - 1 != vals[1] else vals[2] + 2])
                else:
                    add("数学", f"{vals[0]}、{vals[1]}、{vals[2]}中，最小的是几？", vals[0],
                        [vals[1], vals[2], vals[0] + 1 if vals[0] + 1 != vals[1] else vals[0] + 3])
            elif kind == 7:
                total = rng.randint(3, 20)
                part = rng.randint(1, total - 1)
                if rng.random() < 0.5:
                    add("数学", f"{total}可以分成{part}和几？", total - part,
                        explanation=f"{total}-{part}={total - part}，所以{total}可以分成{part}和{total - part}。")
                else:
                    add("数学", f"{part}和几合起来是{total}？", total - part)
            elif kind == 8:
                n = rng.randint(2, 50)
                if rng.random() < 0.5:
                    add("数学", f"{n}前面的一个数是几？", n - 1)
                else:
                    add("数学", f"{n}后面的一个数是几？", n + 1)
            elif kind == 9:
                step = rng.choice([1, 2, 5, 10])
                start = rng.randint(1, 15 if step == 1 else 10)
                direction = rng.random() < 0.7
                if direction:
                    add("逻辑", f"按规律填数：{start}，{start + step}，{start + 2 * step}，□", start + 3 * step)
                elif start + 3 * step <= 20:
                    hi = start + 3 * step
                    add("逻辑", f"按规律填数：{hi}，{hi - step}，{hi - 2 * step}，□", hi - 3 * step)
            elif kind == 10:
                emoji, label = rng.choice(EMOJI_COUNTS)
                n = rng.randint(1, 15)
                add("逻辑", f"数一数：{emoji * n}，一共有几个{label}？", n)
            elif kind == 11:
                name, item, unit = ctx()
                a, b = rng.randint(1, 15), rng.randint(1, 15)
                if a + b <= 20:
                    add("应用", f"{name}有{a}{unit}{item}，又得到{b}{unit}，现在有几{unit}？", a + b)
            elif kind == 12:
                name, item, unit = ctx()
                a = rng.randint(2, 20)
                b = rng.randint(1, a - 1)
                verb = rng.choice(["吃了", "送给同学", "用掉了"])
                add("应用", f"{name}有{a}{unit}{item}，{verb}{b}{unit}，还剩几{unit}？", a - b)
            elif kind == 13:
                n1, n2 = rng.sample(NAMES, 2)
                item, unit = rng.choice(ITEMS)
                x, y = rng.sample(range(1, 21), 2)
                who = n1 if x > y else n2
                diff = abs(x - y)
                add("应用", f"{n1}有{x}{unit}{item}，{n2}有{y}{unit}，谁的多？多几{unit}？",
                    f"{who}，多{diff}{unit}",
                    [f"{n2 if x > y else n1}，多{diff}{unit}", f"{who}，多{diff + 1}{unit}", f"{who}，多{max(1, diff - 1)}{unit}"])
            elif kind == 14:
                name = rng.choice(NAMES)
                x, y = rng.randint(1, 9), rng.randint(1, 9)
                add("应用", f"小朋友排队，{name}从前往后数排第{x}，从后往前数排第{y}，这一排共有几人？", x + y - 1,
                    explanation=f"{name}被数了两次，所以是{x}+{y}-1={x + y - 1}人。")
            elif kind == 15:
                h = rng.randint(1, 12)
                what = rng.choice(["现在是几点", "时针指向几"])
                add("逻辑", f"钟面上分针指向12，时针指向{h}，{what}？", f"{h}点",
                    [f"{(h % 12) + 1}点", f"{((h + 1) % 12) + 1}点", f"{((h + 5) % 12) + 1}点"])
            elif kind == 16:
                day = rng.randint(1, 7)
                offset, label = rng.choice([(1, "明天"), (2, "后天"), (-1, "昨天")])
                names = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
                ans = names[(day - 1 + offset) % 7]
                others = [names[(day - 1 + offset + d) % 7] for d in (1, 2, 3)]
                add("逻辑", f"今天是{names[day - 1]}，{label}是星期几？", ans.replace("星期", "星期"), others)
            elif kind == 17:
                n5 = rng.randint(0, 2)
                n1 = rng.randint(1, 9)
                total = n5 * 5 + n1
                if 0 < total <= 20:
                    parts = []
                    if n5:
                        parts.append(f"{n5}张5元")
                    parts.append(f"{n1}张1元")
                    add("应用", f"{'和'.join(parts)}合起来是多少元？", total,
                        [total + 1, max(1, total - 1), total + 5 if n5 == 0 else total + 2])
            elif kind == 18:
                n = rng.choice([2, 4, 6, 8, 10, 12, 14, 16, 18, 20])
                name, item, unit = ctx()
                add("应用", f"{name}把{n}{unit}{item}平均分成两份，每份是几{unit}？", n // 2,
                    explanation=f"{n}的一半是{n // 2}。")
            else:
                n = rng.randint(1, 20)
                odd = n % 2 == 1
                ans = "单数" if odd else "双数"
                other = "双数" if odd else "单数"
                add("数学", f"{n}是单数还是双数？", ans, [other, "既是单数也是双数", "无法判断"])

        elif age == 6:
            kind = rng.randrange(20)
            if kind == 0:
                t1, o1 = rng.randint(1, 8), rng.randint(0, 9)
                t2, o2 = rng.randint(1, 9 - t1), rng.randint(0, 9 - o1)
                a, b = t1 * 10 + o1, t2 * 10 + o2
                add("数学", f"{a} + {b} = ?", a + b)
            elif kind == 1:
                t1, o1 = rng.randint(2, 9), rng.randint(0, 9)
                t2, o2 = rng.randint(1, t1 - 1 if o1 == 0 else t1), rng.randint(0, o1)
                a, b = t1 * 10 + o1, t2 * 10 + o2
                if b > 0 and a - b > 0:
                    add("数学", f"{a} - {b} = ?", a - b)
            elif kind == 2:
                o1 = rng.randint(1, 9)
                o2 = rng.randint(10 - o1, 9)
                a = rng.randint(1, 7) * 10 + o1
                b = rng.randint(1, 8) * 10 + o2
                if (a // 10 + b // 10 + 1) < 10:
                    add("数学", f"{a} + {b} = ?", a + b)
            elif kind == 3:
                o1 = rng.randint(0, 8)
                a = rng.randint(2, 9) * 10 + o1
                o2 = rng.randint(o1 + 1, 9)
                b = rng.randint(1, a // 10 - 1) * 10 + o2
                add("数学", f"{a} - {b} = ?", a - b)
            elif kind == 4:
                x, y = rng.randint(1, 9) * 10, rng.randint(1, 9) * 10
                if rng.random() < 0.5:
                    add("数学", f"{x} + {y} = ?", x + y)
                else:
                    big, small = max(x, y), min(x, y)
                    add("数学", f"{big} - {small} = ?", big - small)
            elif kind == 5:
                a, b = rng.randint(11, 88), rng.randint(11, 88)
                s = a + b
                if s <= 99:
                    add("数学", f"□ + {b} = {s}，□里是几？", a, [a + 1, max(1, a - 1), a + 10])
            elif kind == 6:
                a = rng.randint(20, 99)
                b = rng.randint(11, a - 1)
                add("数学", f"{a} - □ = {a - b}，□里是几？", b, [b + 1, max(1, b - 1), b + 10])
            elif kind == 7:
                x, y = rng.sample(range(11, 100), 2)
                big, small = max(x, y), min(x, y)
                if rng.random() < 0.5:
                    add("数学", f"{x}和{y}中，哪个数更大？", big, [small, big + 1 if big < 99 else big - 2, big + 10 if big < 90 else big - 10])
                else:
                    add("数学", f"{x}和{y}中，哪个数更小？", small, [big, small + 1, max(1, small - 1)])
            elif kind == 8:
                n = rng.randint(11, 99)
                which = rng.random() < 0.5
                if which:
                    add("数学", f"{n}的个位是几？", n % 10, [(n + 1) % 10, (n + 2) % 10, n // 10])
                else:
                    add("数学", f"{n}的十位是几？", n // 10, [(n // 10 + 1) % 10, max(0, n // 10 - 1), n % 10])
            elif kind == 9:
                t, o = rng.randint(1, 9), rng.randint(0, 9)
                add("数学", f"{t}个十和{o}个一组成的数是几？", t * 10 + o,
                    [o * 10 + t if o else t * 10 + o + 10, t * 10 + o + 1, t * 10 + o + 10 if t < 9 else t * 10 + o - 10],
                    explanation=f"{t}个十是{t * 10}，加上{o}个一是{t * 10 + o}。")
            elif kind == 10:
                step = rng.choice([2, 3, 5, 10])
                start = rng.randint(1, 60)
                if start + 3 * step <= 99:
                    add("逻辑", f"按规律填数：{start}，{start + step}，{start + 2 * step}，□", start + 3 * step)
            elif kind == 11:
                name, item, unit = ctx()
                a, b = rng.randint(11, 60), rng.randint(11, 39)
                add("应用", f"{name}摘了{a}{unit}{item}，又摘了{b}{unit}，一共摘了几{unit}？", a + b)
            elif kind == 12:
                name, item, unit = ctx()
                a = rng.randint(30, 99)
                b = rng.randint(11, a - 5)
                add("应用", f"篮子里有{a}{unit}{item}，{name}拿走了{b}{unit}，还剩几{unit}？", a - b)
            elif kind == 13:
                groups, each = rng.randint(2, 5), rng.randint(2, 9)
                name, item, unit = ctx()
                add("应用", f"{groups}个小朋友，每人有{each}{unit}{item}，一共有几{unit}？", groups * each,
                    explanation=f"{groups}个{each}相加是{groups * each}。")
            elif kind == 14:
                total = rng.choice([4, 6, 8, 10, 12, 14, 16, 18, 20])
                name, item, unit = ctx()
                add("应用", f"把{total}{unit}{item}平均分给2个小朋友，每人几{unit}？", total // 2)
            elif kind == 15:
                n1, n2 = rng.randint(0, 2), rng.randint(1, 9)
                total = n1 * 10 + n2
                if total > 0:
                    parts = []
                    if n1:
                        parts.append(f"{n1}张10元")
                    parts.append(f"{n2}张1元")
                    add("应用", f"{'和'.join(parts)}合起来是多少元？", total, [total + 1, total + 10, max(1, total - 1)])
            elif kind == 16:
                h = rng.randint(1, 12)
                half = rng.random() < 0.5
                if half:
                    add("逻辑", f"钟面上分针指向6，时针在{h}和{(h % 12) + 1}之间，是几点半？", f"{h}点半",
                        [f"{(h % 12) + 1}点半", f"{h}点", f"{((h + 1) % 12) + 1}点半"])
                else:
                    add("逻辑", f"钟面上分针指向12，时针指向{h}，是几点？", f"{h}点",
                        [f"{h}点半", f"{(h % 12) + 1}点", f"{((h + 10) % 12) + 1}点"])
            elif kind == 17:
                price = rng.randint(2, 15)
                name, item, unit = ctx()
                add("应用", f"{name}用20元买了一{unit}{price}元的{item}，应找回多少元？", 20 - price)
            elif kind == 18:
                name = rng.choice(NAMES)
                x, y = rng.randint(1, 12), rng.randint(1, 12)
                add("应用", f"同学们排队，{name}前面有{x}人，后面有{y}人，这一队共有几人？", x + y + 1,
                    explanation=f"前面{x}人加后面{y}人，再加上{name}自己，共{x}+{y}+1={x + y + 1}人。")
            else:
                n = rng.randint(1, 60)
                odd = n % 2 == 1
                ans = "单数" if odd else "双数"
                add("数学", f"{n}是单数还是双数？", ans, ["双数" if odd else "单数", "无法判断", "都是"])

        elif age in (7, 8):
            max_factor = 5 if age == 7 else 9
            kind = rng.randrange(20)
            x, y = rng.randint(2, max_factor), rng.randint(2, max_factor)
            if kind == 0:
                a, b = rng.randint(50, 500), rng.randint(50, 400)
                add("数学", f"{a} + {b} = ?", a + b)
            elif kind == 1:
                a, b = rng.randint(200, 999), rng.randint(50, 199)
                add("数学", f"{a} - {b} = ?", a - b)
            elif kind == 2:
                add("数学", f"{x} × {y} = ?", x * y)
            elif kind == 3:
                add("数学", f"{x * y} ÷ {x} = ?", y)
            elif kind == 4:
                divisor, quotient = rng.randint(2, max_factor), rng.randint(2, 9)
                remainder = rng.randint(1, divisor - 1)
                add("数学", f"{divisor * quotient + remainder} ÷ {divisor}，商是几？余数是几？",
                    f"商{quotient}余{remainder}",
                    [f"商{quotient}余{remainder + 1 if remainder + 1 < divisor else remainder - 1}",
                     f"商{quotient + 1}余{remainder}", f"商{quotient}余{remainder + 2 if remainder + 2 < divisor else max(1, remainder - 2)}"])
            elif kind == 5:
                a, b, c = rng.randint(20, 80), rng.randint(2, 30), rng.randint(1, 20)
                add("数学", f"{a} + {b} - {c} = ?", a + b - c)
            elif kind == 6:
                add("数学", f"{x} × {y} + {x} = ?", x * y + x)
            elif kind == 7:
                add("数学", f"({x} + {y}) × {x} = ?", (x + y) * x)
            elif kind == 8:
                length, width = rng.randint(3, 15), rng.randint(2, 10)
                if age == 7:
                    add("图形", f"长方形长{length}厘米、宽{width}厘米，周长是多少厘米？", 2 * (length + width))
                else:
                    side = rng.randint(2, 12)
                    add("图形", f"正方形边长{side}厘米，面积是多少平方厘米？", side * side,
                        explanation=f"正方形面积=边长×边长，{side}×{side}={side * side}。")
            elif kind == 9:
                name, item, unit = ctx()
                add("应用", f"每盒装{x}{unit}{item}，{name}买了{y}盒，一共有几{unit}？", x * y)
            elif kind == 10:
                name, item, unit = ctx()
                add("应用", f"把{x * y}{unit}{item}平均分成{x}份，每份几{unit}？", y)
            elif kind == 11:
                name = rng.choice(NAMES)
                price, count = rng.randint(2, 9), rng.randint(2, max_factor)
                add("应用", f"一支笔{price}元，{name}买{count}支要多少元？", price * count)
            elif kind == 12:
                unit_from, unit_to, rate = rng.choice([("米", "厘米", 100), ("千克", "克", 1000), ("元", "角", 10), ("小时", "分钟", 60)])
                n = rng.randint(1, 9)
                add("数学", f"{n}{unit_from}等于多少{unit_to}？", n * rate,
                    explanation=f"1{unit_from}={rate}{unit_to}。")
            elif kind == 13:
                start = rng.randint(1, 50)
                add("逻辑", f"按规律填数：{start}，{start + x}，{start + 2 * x}，□", start + 3 * x)
            elif kind == 14:
                total = x * y
                name = rng.choice(NAMES)
                add("应用", f"{name}每天读{x}页书，{y}天一共读多少页？", total)
            elif kind == 15:
                n = rng.randint(2, 9)
                add("应用", f"一节课40分钟，{n}节课一共多少分钟？", 40 * n)
            elif kind == 16:
                a = rng.randint(12, 60)
                b = rng.randint(2, 12)
                if a > b * 2:
                    add("数学", f"{a} - {b} × 2 = ?", a - b * 2)
            elif kind == 17:
                big = rng.randint(30, 99)
                small = rng.randint(2, 9)
                q, r = divmod(big, small)
                if r == 0:
                    add("数学", f"{big} ÷ {small} = ?", q)
            elif kind == 18:
                n = rng.randint(100, 999)
                which = rng.choice(["百位", "十位", "个位"])
                digit = {"百位": n // 100, "十位": n // 10 % 10, "个位": n % 10}[which]
                pool = [(digit + 1) % 10, (digit + 2) % 10, max(0, digit - 1)]
                add("数学", f"{n}的{which}是几？", digit, pool)
            else:
                hour = rng.randint(1, 10)
                mins = rng.choice([15, 20, 30, 45])
                add("应用", f"动画片下午{hour}点开始，播放{mins}分钟，几点几分结束？（用“{hour}点{mins}分”的格式回答）",
                    f"{hour}点{mins}分", [f"{hour + 1}点{mins}分", f"{hour}点{mins + 10}分", f"{hour}点{max(5, mins - 10)}分"])

        elif age in (9, 10):
            kind = rng.randrange(22)
            if kind == 0:
                a, b = rng.randint(100, 999), rng.randint(100, 899)
                add("数学", f"{a} + {b} = ?", a + b)
            elif kind == 1:
                a, b = rng.randint(300, 999), rng.randint(100, 299)
                add("数学", f"{a} - {b} = ?", a - b)
            elif kind == 2:
                x, y = rng.randint(12, 99), rng.randint(3, 9)
                add("数学", f"{x} × {y} = ?", x * y)
            elif kind == 3:
                x, y = rng.randint(12, 30), rng.randint(12, 19 if age == 10 else 15)
                add("数学", f"{x} × {y} = ?", x * y)
            elif kind == 4:
                y = rng.randint(3, 9)
                q = rng.randint(12, 99)
                add("数学", f"{y * q} ÷ {y} = ?", q)
            elif kind == 5:
                divisor = rng.randint(3, 9)
                quotient = rng.randint(11, 60)
                remainder = rng.randint(1, divisor - 1)
                add("数学", f"{divisor * quotient + remainder} ÷ {divisor}，余数是几？", remainder)
            elif kind == 6:
                length, width = rng.randint(4, 20), rng.randint(3, 15)
                if rng.random() < 0.5:
                    add("图形", f"长方形长{length}厘米、宽{width}厘米，周长是多少厘米？", 2 * (length + width))
                else:
                    add("图形", f"长方形长{length}厘米、宽{width}厘米，面积是多少平方厘米？", length * width)
            elif kind == 7:
                base, height = rng.randint(2, 15) * 2, rng.randint(2, 12)
                add("图形", f"三角形底{base}厘米、高{height}厘米，面积是多少平方厘米？", base * height // 2,
                    explanation=f"三角形面积=底×高÷2，{base}×{height}÷2={base * height // 2}。")
            elif kind == 8:
                if age == 10:
                    base, height = rng.randint(3, 15), rng.randint(2, 12)
                    add("图形", f"平行四边形底{base}厘米、高{height}厘米，面积是多少平方厘米？", base * height)
            elif kind == 9:
                den = rng.choice([5, 7, 9, 10, 12])
                n1 = rng.randint(1, den - 2)
                n2 = rng.randint(1, den - 1 - n1)
                add("数学", f"{n1}/{den} + {n2}/{den} = ?", f"{n1 + n2}/{den}",
                    [f"{n1 + n2}/{den * 2}", f"{n1 + n2 + 1}/{den}", f"{max(1, n1 + n2 - 1)}/{den}"])
            elif kind == 10:
                d1 = rng.randint(11, 99) / 10
                d2 = rng.randint(11, 99) / 10
                add("数学", f"{d1:.1f} + {d2:.1f} = ?", f"{d1 + d2:.1f}",
                    [f"{d1 + d2 + 0.1:.1f}", f"{max(0.1, d1 + d2 - 0.1):.1f}", f"{d1 + d2 + 1:.1f}"])
            elif kind == 11:
                d1 = rng.randint(15, 99) / 10
                d2 = rng.randint(1, int(d1 * 10) - 10) / 10
                add("数学", f"{d1:.1f} - {d2:.1f} = ?", f"{d1 - d2:.1f}",
                    [f"{d1 - d2 + 0.1:.1f}", f"{max(0.1, d1 - d2 - 0.1):.1f}", f"{d1 - d2 + 1:.1f}"])
            elif kind == 12:
                nums = [rng.randint(2, 30) for _ in range(3)]
                if sum(nums) % 3 == 0:
                    add("数学", f"{nums[0]}、{nums[1]}、{nums[2]}的平均数是多少？", sum(nums) // 3)
            elif kind == 13:
                n = rng.randint(12, 96)
                divisors = [d for d in range(2, 13) if n % d == 0]
                non = [d for d in range(2, 13) if n % d != 0]
                if divisors and len(non) >= 3:
                    add("数学", f"下面哪个数是{n}的因数？", rng.choice(divisors), non[:3])
            elif kind == 14:
                n = rng.randint(4, 12)
                m = rng.randint(2, 9)
                add("数学", f"{n}的{m}倍是多少？", n * m)
            elif kind == 15:
                n = rng.randint(1000, 99999)
                reading = cn_num(n)
                d1 = cn_num(n + 100) if n + 100 < 100000 else cn_num(n - 110)
                d2 = cn_num(n + 1000) if n + 1000 < 100000 else cn_num(n - 1010)
                d3 = cn_num(n - 100) if n - 100 > 0 else cn_num(n + 210)
                add("数学", f"{n}读作什么？", reading, [d1, d2, d3])
            elif kind == 16:
                n = rng.randint(101, 999)
                if n % 10 != 0:
                    add("数学", f"{n}四舍五入到十位是多少？", round(n / 10) * 10,
                        [round(n / 10) * 10 - 10, round(n / 10) * 10 + 10, round(n / 100) * 100 if n >= 150 else round(n / 10) * 10 + 20])
            elif kind == 17:
                xa, xb = rng.randint(2, 60), rng.randint(2, 60)
                add("数学", f"x + {xa} = {xa + xb}，x = ?", xb)
            elif kind == 18:
                speed, time = rng.randint(4, 15) * 5, rng.randint(2, 9)
                add("应用", f"汽车每小时行驶{speed}千米，{time}小时行驶多少千米？", speed * time)
            elif kind == 19:
                price, count = rng.randint(3, 30), rng.randint(3, 9)
                name = rng.choice(NAMES)
                add("应用", f"每本练习本{price}元，{name}买{count}本需要多少元？", price * count)
            elif kind == 20:
                name, item, unit = ctx()
                total = rng.randint(2, 12) * rng.randint(2, 12)
                groups = rng.choice([d for d in range(2, 13) if total % d == 0])
                add("应用", f"{total}{unit}{item}平均分成{groups}份，每份几{unit}？", total // groups)
            else:
                n = rng.randint(2, 9)
                m = rng.randint(2, 9)
                add("数学", f"{n}和{n * m}的最小公倍数是多少？", n * m,
                    [n, n * m * 2, n * (m + 1)],
                    explanation=f"{n * m}是{n}的倍数，所以最小公倍数就是{n * m}。")

        elif age in (11, 12):
            kind = rng.randrange(22)
            if kind == 0:
                a, b, c = rng.randint(20, 300), rng.randint(11, 99), rng.randint(2, 9)
                add("数学", f"({a} + {b}) × {c} = ?", (a + b) * c)
            elif kind == 1:
                a, b, c = rng.randint(50, 300), rng.randint(11, 60), rng.randint(2, 9)
                add("数学", f"{a} - {b} × {c} = ?", a - b * c) if a > b * c else None
            elif kind == 2:
                dec = rng.randint(11, 199) / 10
                m = rng.randint(2, 9)
                add("数学", f"{dec:.1f} × {m} = ?", f"{dec * m:.1f}",
                    [f"{dec * m + 0.1:.1f}", f"{dec * m - 0.1:.1f}", f"{dec * m + 1:.1f}"])
            elif kind == 3:
                m = rng.randint(2, 9)
                q = rng.randint(11, 199) / 10
                add("数学", f"{q * m:.1f} ÷ {m} = ?", f"{q:.1f}", [f"{q + 0.1:.1f}", f"{q - 0.1:.1f}", f"{q + 1:.1f}"])
            elif kind == 4:
                den = rng.choice([5, 7, 9, 11, 12, 13])
                n1 = rng.randint(1, den - 2)
                n2 = rng.randint(1, den - 1 - n1)
                add("数学", f"{n1}/{den} + {n2}/{den} = ?", frac(n1 + n2, den),
                    [frac(n1 + n2, den * 2), frac(max(1, n1 + n2 - 1), den), frac(n1 + n2 + 1, den)])
            elif kind == 5:
                den = rng.choice([3, 4, 5, 6, 7, 8, 9])
                n1 = rng.randint(2, den - 1)
                n2 = rng.randint(1, n1 - 1)
                add("数学", f"{n1}/{den} - {n2}/{den} = ?", frac(n1 - n2, den),
                    [frac(n1 - n2, den * 2), frac(n1 - n2 + 1, den), frac(n1 + n2, den)])
            elif kind == 6:
                den = rng.choice([3, 4, 5, 6, 7, 8])
                num = rng.randint(1, den - 1)
                m = rng.randint(2, 6)
                add("数学", f"{num}/{den} × {m} = ?", frac(num * m, den),
                    [frac(num * m, den * m), frac(num, den * m), frac(num * m + 1, den)])
            elif kind == 7:
                d1, d2 = rng.choice([(2, 3), (3, 4), (2, 5), (3, 5), (4, 5), (5, 6)])
                n1, n2 = rng.randint(1, d1 - 1), rng.randint(1, d2 - 1)
                ans_num = n1 * d2 + n2 * d1
                add("数学", f"{n1}/{d1} + {n2}/{d2} = ?", frac(ans_num, d1 * d2),
                    [frac(n1 + n2, d1 + d2), frac(ans_num + 1, d1 * d2), frac(max(1, ans_num - 1), d1 * d2)])
            elif kind == 8:
                base = rng.choice([20, 40, 50, 60, 80, 100, 120, 150, 200, 300])
                pct = rng.choice([5, 10, 15, 20, 25, 30, 40, 50, 60, 75, 80])
                add("数学", f"{base}的{pct}%是多少？", base * pct // 100)
            elif kind == 9:
                price = rng.choice([20, 30, 40, 50, 60, 80, 100, 120, 150, 200, 250])
                disc = rng.choice([5, 6, 7, 8, 9])
                if price * disc % 10 == 0:
                    add("应用", f"一件商品原价{price}元，打{disc}折后是多少元？", price * disc // 10,
                        explanation=f"打{disc}折即按原价的{disc * 10}%出售，{price}×0.{disc}={price * disc // 10}元。")
            elif kind == 10:
                r1, r2 = rng.randint(1, 12), rng.randint(1, 12)
                g = gcd(r1, r2)
                add("数学", f"把比{r1 * g}:{r2 * g}化成最简整数比。", f"{r1}:{r2}",
                    [f"{r1 * g}:{r2}", f"{r1}:{r2 * g}", f"{r2}:{r1}"])
            elif kind == 11:
                xv = rng.randint(2, 20)
                m = rng.randint(2, 9)
                c = rng.randint(1, 30)
                add("数学", f"{m}x + {c} = {m * xv + c}，x = ?", xv)
            elif kind == 12:
                r = rng.randint(1, 12)
                add("图形", f"圆的半径是{r}厘米，周长是多少厘米？（π取3.14）", round(2 * 3.14 * r, 2),
                    [round(3.14 * r, 2), round(3.14 * r * r, 2), round(2 * 3.14 * r + 1, 2)])
            elif kind == 13:
                r = rng.randint(1, 12)
                add("图形", f"圆的半径是{r}厘米，面积是多少平方厘米？（π取3.14）", round(3.14 * r * r, 2),
                    [round(2 * 3.14 * r, 2), round(3.14 * r, 2), round(3.14 * r * r + 3.14, 2)])
            elif kind == 14:
                upper, lower, height = rng.randint(2, 9), rng.randint(10, 18), rng.randint(2, 10) * 2
                add("图形", f"梯形上底{upper}厘米、下底{lower}厘米、高{height}厘米，面积是多少平方厘米？",
                    (upper + lower) * height // 2)
            elif kind == 15:
                l_, w_, h_ = rng.randint(2, 12), rng.randint(2, 12), rng.randint(2, 12)
                if rng.random() < 0.5:
                    add("图形", f"长方体长{l_}厘米、宽{w_}厘米、高{h_}厘米，体积是多少立方厘米？", l_ * w_ * h_)
                else:
                    add("图形", f"长方体长{l_}厘米、宽{w_}厘米、高{h_}厘米，表面积是多少平方厘米？", 2 * (l_ * w_ + l_ * h_ + w_ * h_))
            elif kind == 16:
                if age == 12:
                    r, h = rng.randint(1, 8), rng.randint(2, 10)
                    add("图形", f"圆柱底面半径{r}厘米、高{h}厘米，体积是多少立方厘米？（π取3.14）",
                        round(3.14 * r * r * h, 2),
                        [round(2 * 3.14 * r * h, 2), round(3.14 * r * r * h / 3, 2), round(3.14 * r * h, 2)])
            elif kind == 17:
                n1, n2 = rng.randint(2, 15), rng.randint(2, 15)
                if n1 != n2:
                    g = gcd(n1, n2)
                    l = n1 * n2 // g
                    if rng.random() < 0.5:
                        add("数学", f"{n1}和{n2}的最大公因数是多少？", g,
                            [g + 1, max(1, g - 1), min(n1, n2)] if g != min(n1, n2) else [g + 1, g + 2, g + 3])
                    else:
                        add("数学", f"{n1}和{n2}的最小公倍数是多少？", l,
                            [l + n1, n1 * n2, l - n2 if l - n2 > 0 else l + n2])
            elif kind == 18:
                neg, pos = rng.randint(-15, -1), rng.randint(1, 20)
                add("数学", f"({neg}) + {pos} = ?", neg + pos)
            elif kind == 19:
                neg1, neg2 = rng.randint(-9, -1), rng.randint(-9, -1)
                add("数学", f"({neg1}) × ({neg2}) = ?", neg1 * neg2, explanation="负数乘负数得正数。")
            elif kind == 20:
                principal = rng.choice([1000, 2000, 5000, 10000])
                rate = rng.choice([2, 3, 4, 5])
                add("应用", f"本金{principal}元，年利率{rate}%，存一年的利息是多少元？", principal * rate // 100)
            else:
                speed, time = rng.randint(40, 100), rng.randint(2, 8)
                name = rng.choice(NAMES)
                add("应用", f"{name}开车每小时行驶{speed}千米，行驶{time}小时共行多少千米？", speed * time)

        elif age == 15:
            # 初三（中考）水平，extra：更宽数值范围 + 应用情境
            kind = rng.randrange(30)
            if kind == 0:
                a0 = rng.choice([100, 200, 300, 400, 500, 600, 800, 1000])
                p = rng.choice([10, 20, 50])
                grown = a0 * (100 + p) * (100 + p) // 10000
                add("应用", f"某工厂今年产值{a0}万元，若每年增长{p}%，两年后的产值是多少万元？", grown,
                    [a0 * (100 + 2 * p) // 100, grown - a0, a0 * (100 + p) // 100],
                    explanation=f"两年后产值 = {a0}×(1+{p}%)² = {grown}万元。")
            elif kind == 1:
                m = rng.randint(1, 8)
                c = rng.randint(-9, 9)
                sq = m * m
                add("数学", f"把 x² + {2 * m}x + {c} 配方成 (x + {m})² + n 的形式，n = ？", c - sq,
                    [c + sq, sq - c, -sq],
                    explanation=f"(x + {m})² = x² + {2 * m}x + {sq}，所以 n = {c} - {sq} = {c - sq}。")
            elif kind == 2:
                aa, bb = rng.randint(1, 4), rng.randint(-9, 9)
                cc = rng.randint(-9, 9)
                delta = bb * bb - 4 * aa * cc
                if delta > 0:
                    ans, others = "两个不相等的实数根", ["两个相等的实数根", "没有实数根", "只有一个实数根"]
                elif delta == 0:
                    ans, others = "两个相等的实数根", ["两个不相等的实数根", "没有实数根", "只有一个实数根"]
                else:
                    ans, others = "没有实数根", ["两个不相等的实数根", "两个相等的实数根", "只有一个实数根"]
                add("数学", f"方程 {aa}x² + ({bb})x + ({cc}) = 0 的根的情况是？", ans, others,
                    explanation=f"判别式 Δ = {bb}² - 4×{aa}×({cc}) = {delta}。")
            elif kind == 3:
                a_, b_ = sorted(rng.sample(range(1, 9), 2))
                c = rng.randint(1, 9)
                if (a_ * c) % (b_ - a_) == 0:
                    x = a_ * c // (b_ - a_)
                    if x > 0:
                        add("数学", f"分式方程 {a_}/x = {b_}/(x + {c}) 的解是 x = ？", x,
                            [x + 1, x + c, max(1, x - 1)],
                            explanation=f"交叉相乘得 {a_}(x + {c}) = {b_}x，解得 x = {x}。")
            elif kind == 4:
                h, k = rng.randint(-6, 6), rng.randint(1, 20)
                add("数学", f"二次函数 y = -(x - ({h}))² + {k} 的最大值是？", k,
                    [-k, h, -h],
                    explanation="开口向下的抛物线，顶点处取最大值。")
            elif kind == 5:
                x_, y_ = rng.randint(-12, 12), rng.randint(-12, 12)
                if x_ != 0 and y_ != 0:
                    add("数学", f"点 ({x_}, {y_}) 关于原点对称的点的坐标是？", f"({-x_}, {-y_})",
                        [f"({x_}, {-y_})", f"({-x_}, {y_})", f"({y_}, {x_})"],
                        explanation="关于原点对称的点，横、纵坐标都互为相反数。")
            elif kind == 6:
                m = rng.randint(-6, 6)
                b_ = rng.randint(-9, 9)
                if m != 0 and b_ != 0:
                    add("数学", f"一次函数 y = {m}x + ({b_}) 的图象与 y 轴的交点坐标是？", f"(0, {b_})",
                        [f"({b_}, 0)", f"(0, {m})", f"({m}, {b_})"],
                        explanation="与 y 轴交点的横坐标为 0，代入得 y = b。")
            elif kind == 7:
                # 解直角三角形：30° 对边是斜边的一半
                opp = rng.randint(2, 15)
                if rng.random() < 0.5:
                    add("图形", f"直角三角形中，30°角所对的直角边是{opp}，斜边长是？", 2 * opp,
                        [opp + 2, 3 * opp, opp],
                        explanation="直角三角形中，30°角所对的直角边等于斜边的一半。")
                else:
                    add("图形", f"直角三角形中，斜边长是{2 * opp}，30°角所对的直角边长是？", opp,
                        [2 * opp, opp // 2 if opp % 2 == 0 else opp + 1, opp + 1],
                        explanation="直角三角形中，30°角所对的直角边等于斜边的一半。")
            elif kind == 8:
                n, m = rng.randint(2, 9), rng.choice([2, 3, 5, 6, 7])
                b_ = rng.choice([2, 3, 5])
                val = n * n * m * b_ * b_
                add("数学", f"化简：√{n * n * m} × √{b_ * b_} = ？", n * b_ * int(m ** 0.5) if m in (4, 9) else f"{n * b_}√{m}",
                    [f"{n * b_ * m}", f"{n + b_}√{m}", f"{n * b_ + m}"],
                    explanation=f"原式 = {n}√{m} × {b_} = {n * b_}√{m}。")
            elif kind == 9:
                b = rng.choice([2, 3, 5])
                m, n, p = rng.randint(2, 5), rng.randint(2, 5), rng.randint(1, 3)
                add("数学", f"{b}^{m} × {b}^{n} ÷ {b}^{p} = {b}的几次方？", m + n - p,
                    [m + n + p, m * n - p, m + n - p + 1],
                    explanation="同底数幂相乘指数相加，相除指数相减。")
            elif kind == 10:
                r, d = rng.choice([(5, 3), (5, 4), (10, 6), (10, 8), (13, 5), (13, 12)])
                half = int((r * r - d * d) ** 0.5)
                add("图形", f"半径为{r}的圆中，圆心到一条弦的距离是{d}，这条弦的长是？", 2 * half,
                    [half, r - d, r + d],
                    explanation=f"半弦长 = √({r}² - {d}²) = {half}，弦长 = 2×{half} = {2 * half}。")
            elif kind == 11:
                # 相似测高：同一时刻物高与影长成正比
                s1 = rng.choice([1, 2, 4])
                s2 = rng.choice([3, 5, 6, 8, 10, 12])
                if s2 % s1 == 0 and s2 != s1:
                    pole = rng.choice([1, 2])
                    add("应用", f"同一时刻，{pole}米高的标杆影长{s1}米，旗杆影长{s2}米，旗杆高多少米？", pole * s2 // s1,
                        [s2 - s1, pole * s2 // s1 + 1, pole * s1],
                        explanation=f"旗杆高 = {pole} × {s2} ÷ {s1} = {pole * s2 // s1}米。")
            elif kind == 12:
                m = rng.choice([1, 2, 3])
                h = rng.randint(2, 12)
                add("应用", f"一段斜坡的坡度 i = 1:{m}，铅直高度是{h}米，水平距离是多少米？", m * h,
                    [h + m, h * (m + 1), max(1, h - m)],
                    explanation="坡度 i = 铅直高度 : 水平距离，水平距离 = 高度 × 坡比的分母。")
            elif kind == 13:
                lo, hi = sorted(rng.sample(range(-8, 12), 2))
                add("数学", f"不等式 {lo} < x ≤ {hi} 的整数解有多少个？", hi - lo,
                    [hi - lo + 1, hi - lo - 1, hi - lo + 2],
                    explanation=f"整数解为 {lo + 1} 到 {hi}，共 {hi - lo} 个。")
            elif kind == 14:
                add("数学", "化简：(x² - 1) ÷ (x + 1) = ？", "x - 1", ["x + 1", "x² - x", "1 - x"],
                    explanation="x² - 1 = (x + 1)(x - 1)，约去 (x + 1) 得 x - 1。")
            elif kind == 15:
                n = rng.choice([x for x in range(5, 100) if int(x ** 0.5) ** 2 != x])
                lo = int(n ** 0.5)
                add("数学", f"√{n} 的值在哪两个相邻整数之间？", f"{lo}和{lo + 1}",
                    [f"{lo - 1}和{lo}", f"{lo + 1}和{lo + 2}", f"{lo}和{lo + 2}"],
                    explanation=f"{lo}² = {lo * lo} < {n} < {lo + 1}² = {(lo + 1) * (lo + 1)}。")
            elif kind == 16:
                red, white = rng.randint(2, 5), rng.randint(1, 5)
                total = red + white
                add("数学", f"袋中有{red}个红球和{white}个白球，不放回地摸两次，两次都摸到红球的概率是？",
                    frac(red * (red - 1), total * (total - 1)),
                    [frac(red, total), frac(red * red, total * total), frac(red - 1, total - 1)],
                    explanation=f"第一次 {red}/{total}，第二次 {red - 1}/{total - 1}，相乘并约分。")
            elif kind == 17:
                k0 = rng.randint(1, 50)
                add("数学", f"数据 {k0}，{k0 + 1}，{k0 + 2}，{k0 + 3}，{k0 + 4} 的方差是？", "2",
                    ["1", "2.5", "4"],
                    explanation="5个连续整数的平均数是中间数，方差 = (4+1+0+1+4)÷5 = 2。")
            elif kind == 18:
                v1, v2 = rng.randint(3, 9) * 10, rng.randint(2, 9) * 10
                if v1 != v2:
                    t = rng.randint(2, 6)
                    add("应用", f"甲、乙两车同时从{(v1 + v2) * t}千米外的两地相向开出，甲每小时行{v1}千米，乙每小时行{v2}千米，几小时后相遇？",
                        t, [t + 1, max(1, t - 1), t + 2],
                        explanation=f"相遇时间 = 路程 ÷ 速度和 = {(v1 + v2) * t} ÷ {v1 + v2} = {t}小时。")
            elif kind == 19:
                aa, bb = rng.choice([(6, 3), (4, 12), (6, 12), (8, 8), (10, 5), (12, 4), (9, 18), (10, 15)])
                days = aa * bb // (aa + bb)
                if aa * bb % (aa + bb) == 0:
                    add("应用", f"一项工程，甲单独做{aa}天完成，乙单独做{bb}天完成，两人合作几天完成？", days,
                        [(aa + bb) // 2, days + 1, max(1, days - 1)],
                        explanation=f"合作效率 = 1/{aa} + 1/{bb}，天数 = {aa}×{bb}÷({aa}+{bb}) = {days}天。")
            elif kind == 20:
                base, exp = rng.choice([(2, 10), (2, 12), (3, 6), (3, 8), (5, 4), (5, 6), (7, 4), (2, 8), (10, 6), (4, 5)])
                add("数学", f"(-{base})^{exp} ÷ {base}^{exp - 2} = ？", base * base,
                    [-base * base, base, -(base * base)],
                    explanation=f"偶次幂为正：(-{base})^{exp} = {base}^{exp}，再除以 {base}^{exp - 2} 得 {base}² = {base * base}。")
            elif kind == 21:
                p, q = rng.randint(-9, 9), rng.randint(-9, 9)
                if p != 0 and q != 0 and p != -q:
                    add("数学", f"把 x² + ({p + q})x + ({p * q}) 因式分解，结果是？", f"(x + ({p}))(x + ({q}))",
                        [f"(x - ({p}))(x - ({q}))", f"(x + ({p}))(x - ({q}))", f"(x + ({p * q}))(x + 1)"],
                        explanation=f"({p}) + ({q}) = {p + q}，({p}) × ({q}) = {p * q}。")
            elif kind == 22:
                mant = rng.choice([1.05, 2.03, 3.7, 4.56, 6.08, 7.5, 8.02, 9.99])
                exp = rng.randint(6, 10)
                add("数学", f"用科学记数法表示 {int(mant * 10 ** exp)}，应为{mant}×10的几次方？", exp,
                    [exp - 1, exp + 1, exp + 2])
            elif kind == 23:
                nums = sorted(rng.sample(range(1, 60), 5))
                med = nums[2]
                if sum(nums) % 5 == 0:
                    mean = sum(nums) // 5
                    if mean != med and abs(mean - med) > 1:
                        add("数学", f"数据 {'，'.join(map(str, nums))} 的中位数比平均数小多少？", mean - med,
                            [mean - med + 1, max(1, mean - med - 1), med - nums[1]],
                            explanation=f"中位数是 {med}，平均数是 {mean}，相差 {mean - med}。")
            elif kind == 24:
                r1, r2 = sorted(rng.sample(range(-12, 13), 2))
                add("数学", f"已知一元二次方程的两根是 {r1} 和 {r2}，则两根之和是？", r1 + r2,
                    [r1 * r2, r2 - r1, -(r1 + r2)],
                    explanation="由韦达定理，x₁ + x₂ = -b/a。")
            elif kind == 25:
                r1, r2 = sorted(rng.sample(range(-9, 10), 2))
                if r1 != 0 and r2 != 0:
                    add("数学", f"已知一元二次方程的两根是 {r1} 和 {r2}，则两根之积是？", r1 * r2,
                        [r1 + r2, -(r1 * r2), r2 - r1],
                        explanation="由韦达定理，x₁ · x₂ = c/a。")
            elif kind == 26:
                price = rng.choice([40, 60, 80, 100, 120, 150, 200])
                up = rng.choice([10, 20, 25, 50])
                cost = price * 100 // (100 + up)
                if price * 100 % (100 + up) == 0:
                    add("应用", f"一件商品按进价提高{up}%后售价为{price}元，进价是多少元？", cost,
                        [price * (100 - up) // 100, cost + 5, price - up],
                        explanation=f"进价 = 售价 ÷ (1 + {up}%) = {price} ÷ 1.{up if up >= 10 else '0' + str(up)} = {cost}元。")
            elif kind == 27:
                x_, y_ = rng.randint(-9, 9), rng.randint(-9, 9)
                if x_ != 0 and y_ != 0:
                    axis = rng.random() < 0.5
                    if axis:
                        add("数学", f"点 ({x_}, {y_}) 关于 x 轴对称的点的坐标是？", f"({x_}, {-y_})",
                            [f"({-x_}, {y_})", f"({-x_}, {-y_})", f"({y_}, {x_})"],
                            explanation="关于 x 轴对称，横坐标不变，纵坐标变号。")
                    else:
                        add("数学", f"点 ({x_}, {y_}) 关于 y 轴对称的点的坐标是？", f"({-x_}, {y_})",
                            [f"({x_}, {-y_})", f"({-x_}, {-y_})", f"({y_}, {x_})"],
                            explanation="关于 y 轴对称，纵坐标不变，横坐标变号。")
            elif kind == 28:
                central = rng.choice([40, 60, 80, 100, 120, 140, 160])
                add("图形", f"一条弧所对的圆周角是 {central // 2}°，则这条弧所对的圆心角是？", central,
                    [central // 2, central // 4, 180 - central],
                    explanation="同弧所对的圆心角是圆周角的2倍。")
            else:
                ratio = rng.randint(2, 5)
                s_small = rng.randint(2, 12)
                add("图形", f"两个相似三角形的相似比为 1:{ratio}，小三角形面积是{s_small}平方厘米，大三角形面积是？",
                    s_small * ratio * ratio, [s_small * ratio, s_small * 2 * ratio, s_small + ratio * ratio],
                    explanation=f"相似三角形面积比等于相似比的平方：{s_small}×{ratio}² = {s_small * ratio * ratio}。")

        else:  # 13-14
            kind = rng.randrange(24)
            if kind == 0:
                a, b = rng.randint(-50, 50), rng.randint(-50, 50)
                add("数学", f"({a}) + ({b}) = ?", a + b)
            elif kind == 1:
                a, b = rng.randint(-30, 30), rng.randint(-30, 30)
                add("数学", f"({a}) - ({b}) = ?", a - b)
            elif kind == 2:
                a, b = rng.randint(-12, 12), rng.randint(-12, 12)
                if b != 0:
                    add("数学", f"({a}) × ({b}) = ?", a * b)
            elif kind == 3:
                base, exp = rng.choice([(-2, 3), (-3, 2), (2, 4), (3, 3), (4, 2), (5, 2), (-5, 2), (2, 5), (6, 2), (-4, 3), (3, 4), (-2, 5)])
                add("数学", f"({base})^{exp} = ?", base ** exp)
            elif kind == 4:
                n = rng.randint(2, 20)
                add("数学", f"√{n * n} = ?", n)
            elif kind == 5:
                n = rng.randint(2, 12)
                add("数学", f"³√{n ** 3} = ?", n)
            elif kind == 6:
                n = rng.randint(-50, -1)
                add("数学", f"|{n}| = ?", -n)
            elif kind == 7:
                if rng.random() < 0.5:
                    n = rng.randint(-30, 30)
                    if n != 0:
                        add("数学", f"{n}的相反数是多少？", -n)
                else:
                    n = rng.randint(2, 12)
                    add("数学", f"{n}的倒数是多少？", f"1/{n}", [str(n), f"-{n}", f"-1/{n}"])
            elif kind == 8:
                xv = rng.randint(-12, 15)
                m = rng.randint(2, 9)
                c = rng.randint(-15, 15)
                add("数学", f"{m}x + ({c}) = {m * xv + c}，x = ?", xv)
            elif kind == 9:
                xv, yv = rng.randint(1, 15), rng.randint(1, 15)
                add("数学", f"已知 x + y = {xv + yv}，x - y = {xv - yv}，则 x = ?", xv)
            elif kind == 10:
                m, c = rng.randint(2, 6), rng.randint(1, 12)
                bound = rng.randint(8, 40)
                sol = (bound - c) // m + 1
                add("数学", f"不等式 {m}x + {c} > {bound} 的最小整数解是？", sol)
            elif kind == 11:
                mant = rng.choice([1.5, 2.3, 2.5, 3.2, 4.7, 5.6, 6.1, 7.8, 8.9, 1.2, 9.9])
                exp = rng.randint(3, 8)
                add("数学", f"用科学记数法表示 {int(mant * 10 ** exp)}，应为{mant}×10的几次方？", exp)
            elif kind == 12:
                a1, a2 = rng.randint(15, 100), rng.randint(15, 100)
                if a1 + a2 < 175:
                    add("图形", f"三角形两个内角分别是{a1}°和{a2}°，第三个内角是多少度？", 180 - a1 - a2,
                        explanation="三角形内角和为180°。")
            elif kind == 13:
                ang = rng.randint(15, 165)
                if rng.random() < 0.5:
                    add("图形", f"两条直线相交，一个角是{ang}°，它的对顶角是多少度？", ang, explanation="对顶角相等。")
                else:
                    add("图形", f"两条直线相交，一个角是{ang}°，它的邻补角是多少度？", 180 - ang,
                        explanation="邻补角之和为180°。")
            elif kind == 14:
                triple = rng.choice([(3, 4, 5), (6, 8, 10), (5, 12, 13), (9, 12, 15), (8, 15, 17), (7, 24, 25), (10, 24, 26), (12, 16, 20)])
                add("图形", f"直角三角形两条直角边是{triple[0]}和{triple[1]}，斜边长是多少？", triple[2],
                    [triple[2] + 1, triple[2] - 1, triple[0] + triple[1]],
                    explanation="由勾股定理：斜边²=两直角边平方和。")
            elif kind == 15:
                m, c = rng.randint(2, 9), rng.randint(-9, 9)
                xv = rng.randint(-8, 10)
                add("数学", f"一次函数 y = {m}x + ({c})，当 x = {xv} 时，y = ?", m * xv + c)
            elif kind == 16:
                red, white = rng.randint(1, 8), rng.randint(1, 8)
                unsimplified = f"{red}/{red + white}"
                simplified = frac(red, red + white)
                if unsimplified != simplified:
                    add("数学", f"袋中有{red}个红球和{white}个白球，随机摸一个，摸到红球的概率是多少？（结果要约分）",
                        simplified, [frac(white, red + white), unsimplified, frac(1, red + white)])
            elif kind == 17:
                nums = sorted(rng.randint(1, 30) for _ in range(5))
                add("数学", f"数据 {nums[0]}，{nums[1]}，{nums[2]}，{nums[3]}，{nums[4]} 的中位数是？", nums[2],
                    [nums[1], nums[3], sum(nums) // 5])
            elif kind == 18:
                xq, yq = rng.choice([(-1, 1), (1, -1), (-1, -1), (1, 1)])
                xv, yv = rng.randint(1, 9) * xq, rng.randint(1, 9) * yq
                quad = {(1, 1): "第一象限", (-1, 1): "第二象限", (-1, -1): "第三象限", (1, -1): "第四象限"}[(xq, yq)]
                others = [q for q in ("第一象限", "第二象限", "第三象限", "第四象限") if q != quad]
                add("数学", f"点 ({xv}, {yv}) 在平面直角坐标系的第几象限？", quad, others)
            elif kind == 19:
                r1, r2 = sorted(rng.sample(range(-8, 10), 2))
                add("数学", f"方程 x² - ({r1 + r2})x + ({r1 * r2}) = 0 的较大根是？", r2, [r1, -r1, -r2],
                    explanation=f"方程可分解为 (x - ({r1}))(x - ({r2})) = 0。")
            elif kind == 20:
                ea, eb = rng.randint(2, 8), rng.randint(2, 8)
                add("数学", f"2^{ea} × 2^{eb} = 2的几次方？", ea + eb, [ea * eb, abs(ea - eb), ea + eb + 1],
                    explanation="同底数幂相乘，底数不变，指数相加。")
            elif kind == 21:
                n = rng.randint(1, 9)
                add("数学", f"({n})² - ({n + 1})² = ?", n * n - (n + 1) * (n + 1),
                    explanation=f"由平方差公式：({n})²-({n + 1})²=({n}+{n + 1})({n}-{n + 1})={-(2 * n + 1)}。")
            elif kind == 22:
                n = rng.randint(2, 12)
                add("数学", f"({n} + 1)² = ?", (n + 1) ** 2,
                    [n * n + 1, n * n + 2 * n + 2, (n + 2) ** 2],
                    explanation=f"完全平方公式：({n}+1)²={n}²+2×{n}+1={(n + 1) ** 2}。")
            else:
                a = rng.randint(2, 9)
                b = rng.randint(2, 9)
                add("数学", f"{a}的平方与{b}的平方之和是多少？", a * a + b * b)

    return questions


# ---------------------------------------------------------------------------
# extra language generator (v3): data-table driven, from lang_bank.py
# ---------------------------------------------------------------------------

def load_lang_bank():
    path = DATA_DIR / "lang_bank.py"
    spec = importlib.util.spec_from_file_location("lang_bank", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build_lang_extra(age, band_key, rng, target, seen, bank):
    """Build language questions from the lang_bank data tables for this band."""
    half = 0 if age % 2 == 1 else 1  # 5,7,9,11,13 -> first half; 6,8,10,12,14 -> second half

    def half_slice(lst):
        mid = len(lst) // 2
        return lst[:mid] if half == 0 else lst[mid:]

    if band_key == "5_6":
        pinyin = bank.PINYIN_LOW
        syn = bank.SYNONYMS_LOW
        ant = bank.ANTONYMS_LOW
        idioms = []
        poems = bank.POEMS_LOW
        radicals = bank.RADICAL_LOW
    elif band_key == "7_8":
        pinyin = half_slice(bank.PINYIN_MID)
        syn = half_slice(bank.SYNONYMS_MID)
        ant = half_slice(bank.ANTONYMS_MID)
        idioms = half_slice(bank.IDIOMS_MID)
        poems = half_slice(bank.POEMS_MID)
        radicals = half_slice(bank.RADICAL_MID)
    elif band_key == "9_10":
        pinyin = half_slice(bank.PINYIN_MID)
        syn = half_slice(bank.SYNONYMS_MID)
        ant = half_slice(bank.ANTONYMS_MID)
        idioms = half_slice(bank.IDIOMS_MID)
        poems = half_slice(bank.POEMS_MID)
        radicals = half_slice(bank.RADICAL_MID)
    elif band_key == "11_12":
        pinyin = half_slice(bank.PINYIN_HIGH)
        syn = half_slice(bank.SYNONYMS_HIGH)
        ant = half_slice(bank.ANTONYMS_HIGH)
        idioms = half_slice(bank.IDIOMS_HIGH)
        poems = half_slice(bank.POEMS_HIGH)
        radicals = half_slice(bank.RADICAL_MID)
    else:  # 13_14
        pinyin = half_slice(bank.PINYIN_HIGH)
        syn = half_slice(bank.SYNONYMS_HIGH)
        ant = half_slice(bank.ANTONYMS_HIGH)
        idioms = half_slice(bank.IDIOMS_HIGH)
        poems = half_slice(bank.POEMS_HIGH)
        radicals = []

    # 7_8 takes the easier half of MID first; 9_10 should prefer the other half.
    if band_key == "9_10":
        half = 1 - half
        pinyin = half_slice(bank.PINYIN_MID)
        syn = half_slice(bank.SYNONYMS_MID)
        ant = half_slice(bank.ANTONYMS_MID)
        idioms = half_slice(bank.IDIOMS_MID)
        poems = half_slice(bank.POEMS_MID)
        radicals = half_slice(bank.RADICAL_MID)

    def distract(values, answer, count=3):
        pool = []
        for v in values:  # 去重：量词等表里同一个答案值会出现多次，采样不能给出重复选项
            if v != answer and v not in pool:
                pool.append(v)
        if len(pool) < count:
            return None
        return rng.sample(pool, count)

    pool = []
    all_pinyin = [p for _, p in pinyin]
    for ch, py in pinyin:
        d = distract(all_pinyin, py)
        if d:
            pool.append(("语文", f"汉字「{ch}」的正确读音是？", py, d, f"「{ch}」读作 {py}。"))

    syn_answers = [a for _, a in syn]
    for w, s in syn:
        d = distract(syn_answers, s)
        if d:
            pool.append(("语文", f"「{w}」的近义词是？", s, d, f"「{w}」和「{s}」意思相近。"))

    ant_answers = [a for _, a in ant]
    for w, s in ant:
        d = distract(ant_answers, s)
        if d:
            pool.append(("语文", f"「{w}」的反义词是？", s, d, f"「{w}」和「{s}」意思相反。"))

    if idioms:
        idiom_chars = [c for _, c, _, _ in idioms]
        for blanked, ch, full, meaning in idioms:
            d = distract(idiom_chars, ch)
            if d:
                pool.append(("语文", f"补充成语：{blanked}", ch, d, f"完整成语是「{full}」，{meaning}"))

    if poems:
        lines = [down for _, down, _ in poems]
        for up, down, src in poems:
            d = distract(lines, down)
            if d:
                pool.append(("语文", f"「{up}」的下一句是？", down, d, f"出自{src}。"))

    if radicals:
        rads = [r for _, r in radicals]
        for ch, r in radicals:
            d = distract(rads, r)
            if d:
                pool.append(("语文", f"「{ch}」的部首是？", r, d, f"「{ch}」的部首是「{r}」。"))

    if band_key in ("5_6", "7_8"):
        measures = [m for _, m in bank.MEASURE_WORDS]
        for noun, m in bank.MEASURE_WORDS:
            d = distract(measures, m)
            if d:
                pool.append(("语文", f"一（　）{noun}，括号里应填哪个量词？", m, d, f"规范说法是「一{m}{noun}」。"))

    if band_key == "5_6":
        for cat, items, outs in bank.CATEGORY_SORT:
            item_list = items.split(",")
            out_list = outs.split(",")
            if len(item_list) >= 3 and out_list:
                for out in out_list:
                    d = rng.sample(item_list, 3)
                    four = d + [out]
                    rng.shuffle(four)
                    pool.append(("语文", f"{'、'.join(four)}中，哪个不属于「{cat}」？", out, d, f"{out}不属于{cat}。"))

    # a data-table bug must never yield two questions with the same prompt
    unique = {}
    for q in pool:
        unique.setdefault(q[1], q)
    pool = [q for q in unique.values() if q[1] not in seen]
    if len(pool) < target:
        raise RuntimeError(f"lang extra pool too small for band {band_key} age {age}: {len(pool)} < {target}")
    picked = rng.sample(pool, target)
    for q in picked:
        seen.add(q[1])
    return picked


# ---------------------------------------------------------------------------
# extra knowledge generator (v3): programmatic common-sense questions
# ---------------------------------------------------------------------------

ZODIAC = ["鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"]
SOLAR_TERMS = ["立春", "雨水", "惊蛰", "春分", "清明", "谷雨", "立夏", "小满", "芒种", "夏至", "小暑", "大暑",
               "立秋", "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至", "小寒", "大寒"]
WEEKDAYS = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
MONTH_DAYS = {1: 31, 2: 28, 3: 31, 4: 30, 5: 31, 6: 30, 7: 31, 8: 31, 9: 30, 10: 31, 11: 30, 12: 31}
SEASON_OF_MONTH = {1: "冬季", 2: "冬季", 3: "春季", 4: "春季", 5: "春季", 6: "夏季",
                   7: "夏季", 8: "夏季", 9: "秋季", 10: "秋季", 11: "秋季", 12: "冬季"}
FESTIVALS = [
    ("元旦", "1月1日"), ("妇女节", "3月8日"), ("植树节", "3月12日"), ("劳动节", "5月1日"),
    ("青年节", "5月4日"), ("儿童节", "6月1日"), ("建党节", "7月1日"), ("建军节", "8月1日"),
    ("教师节", "9月10日"), ("国庆节", "10月1日"), ("春节", "农历正月初一"), ("元宵节", "农历正月十五"),
    ("端午节", "农历五月初五"), ("中秋节", "农历八月十五"), ("重阳节", "农历九月初九"), ("腊八节", "农历腊月初八"),
]
PROVINCES = [
    ("北京", "北京", "京"), ("天津", "天津", "津"), ("上海", "上海", "沪"), ("重庆", "重庆", "渝"),
    ("河北省", "石家庄", "冀"), ("山西省", "太原", "晋"), ("辽宁省", "沈阳", "辽"), ("吉林省", "长春", "吉"),
    ("黑龙江省", "哈尔滨", "黑"), ("江苏省", "南京", "苏"), ("浙江省", "杭州", "浙"), ("安徽省", "合肥", "皖"),
    ("福建省", "福州", "闽"), ("江西省", "南昌", "赣"), ("山东省", "济南", "鲁"), ("河南省", "郑州", "豫"),
    ("湖北省", "武汉", "鄂"), ("湖南省", "长沙", "湘"), ("广东省", "广州", "粤"), ("海南省", "海口", "琼"),
    ("四川省", "成都", "川"), ("贵州省", "贵阳", "黔"), ("云南省", "昆明", "云"), ("陕西省", "西安", "陕"),
    ("甘肃省", "兰州", "甘"), ("青海省", "西宁", "青"), ("内蒙古自治区", "呼和浩特", "蒙"),
    ("广西壮族自治区", "南宁", "桂"), ("西藏自治区", "拉萨", "藏"), ("宁夏回族自治区", "银川", "宁"),
    ("新疆维吾尔自治区", "乌鲁木齐", "新"), ("台湾省", "台北", "台"), ("香港特别行政区", "香港", "港"),
    ("澳门特别行政区", "澳门", "澳"),
]
DYNASTIES = ["夏", "商", "周", "秦", "汉", "三国", "晋", "南北朝", "隋", "唐", "五代十国", "宋", "元", "明", "清"]
CAPITALS = [
    ("中国", "北京"), ("日本", "东京"), ("韩国", "首尔"), ("美国", "华盛顿"), ("英国", "伦敦"),
    ("法国", "巴黎"), ("德国", "柏林"), ("意大利", "罗马"), ("俄罗斯", "莫斯科"), ("加拿大", "渥太华"),
    ("澳大利亚", "堪培拉"), ("埃及", "开罗"), ("印度", "新德里"), ("巴西", "巴西利亚"), ("泰国", "曼谷"),
]
KNOW_LOW = [
    ("安全", "过马路时应该走哪里？", "斑马线", ["随便穿过去", "翻护栏", "跟着汽车跑"], "过马路要走斑马线，看红绿灯。"),
    ("安全", "红灯亮时应该怎么做？", "停下来等待", ["赶快跑过去", "慢慢走过去", "不看灯直接走"], "红灯停、绿灯行、黄灯亮了等一等。"),
    ("安全", "发生火灾时应该拨打哪个电话？", "119", ["110", "120", "122"], "火警电话是119。"),
    ("安全", "遇到坏人或危险时应该拨打哪个电话？", "110", ["119", "120", "114"], "报警电话是110。"),
    ("安全", "有人生病需要急救时应该拨打哪个电话？", "120", ["110", "119", "122"], "急救电话是120。"),
    ("安全", "陌生人给你糖果让你跟他走，应该怎么办？", "拒绝并告诉家长或老师", ["跟他走", "吃了糖果再走", "带他去家里"], "不能接受陌生人的东西，更不能跟陌生人走。"),
    ("安全", "游泳时应该有大人在旁边吗？", "应该，不能独自下水", ["不需要，自己游更安全", "只和小伙伴去", "去深水区玩"], "未成年人游泳必须有大人陪同。"),
    ("安全", "湿手可以摸电源插座吗？", "不可以，容易触电", ["可以，没关系", "擦一下再摸", "用金属物捅"], "湿手碰电源容易触电，非常危险。"),
    ("生活", "饭前便后应该做什么？", "洗手", ["直接吃饭", "玩玩具", "看电视"], "饭前便后要洗手，防止病从口入。"),
    ("生活", "每天应该睡多长时间的觉比较合适？", "10小时左右", ["3小时", "5小时", "通宵不睡"], "小朋友每天需要充足的睡眠，约10小时。"),
    ("生活", "正确的刷牙频率是？", "每天早晚刷牙", ["一周刷一次", "从不刷牙", "只早上刷"], "早晚刷牙可以保护牙齿。"),
    ("生活", "垃圾分类中，废电池应该投进哪种垃圾桶？", "有害垃圾", ["可回收物", "厨余垃圾", "其他垃圾"], "废电池含有害物质，属于有害垃圾。"),
    ("生活", "喝完的塑料瓶属于哪类垃圾？", "可回收物", ["有害垃圾", "厨余垃圾", "其他垃圾"], "塑料瓶可以回收再利用。"),
    ("生活", "吃剩的饭菜属于哪类垃圾？", "厨余垃圾", ["可回收物", "有害垃圾", "其他垃圾"], "剩菜剩饭是厨余垃圾。"),
    ("生活", "升国旗时我们应该怎么做？", "立正行注目礼", ["大声喧哗", "跑来跑去", "坐着聊天"], "升国旗要肃立行礼，表达尊重。"),
    ("科学", "彩虹一般有几种颜色？", "7种", ["3种", "5种", "10种"], "彩虹有红、橙、黄、绿、蓝、靛、紫7种颜色。"),
    ("科学", "水在标准大气压下多少度会结冰？", "0℃", ["10℃", "-10℃", "100℃"], "水在0℃结冰，100℃沸腾。"),
    ("科学", "植物生长最需要阳光、水和什么？", "空气", ["糖果", "玩具", "音乐"], "植物生长需要阳光、水分和空气（二氧化碳）。"),
    ("科学", "月亮自己会发光吗？", "不会，是反射太阳光", ["会，像灯泡一样", "有时会有时不会", "靠星星照亮"], "月光是月球反射的太阳光。"),
    ("科学", "一年有多少个月？", "12个月", ["10个月", "11个月", "13个月"], "一年有12个月。"),
    ("科学", "一周有几天？", "7天", ["5天", "6天", "8天"], "一周有7天。"),
    ("科学", "一天有多少小时？", "24小时", ["12小时", "20小时", "30小时"], "一天有24小时。"),
    ("科学", "鱼生活在水里，用什么呼吸？", "鳃", ["肺", "鼻子", "皮肤"], "鱼用鳃呼吸水中的氧气。"),
    ("科学", "哪种动物是生活在水里的哺乳动物？", "鲸鱼", ["鲨鱼", "鲤鱼", "金鱼"], "鲸鱼用肺呼吸、是哺乳动物；鲨鱼和鲤鱼都是鱼类。"),
    ("科学", "小鸡是从哪里孵出来的？", "鸡蛋", ["水里", "树上", "土里"], "小鸡从鸡蛋里孵出来。"),
    ("科学", "青蛙小时候叫什么？", "蝌蚪", ["小鱼", "虫子", "虾米"], "青蛙的幼体是蝌蚪，长大后变成青蛙。"),
    ("科学", "蜘蛛有几条腿？", "8条", ["6条", "4条", "10条"], "蜘蛛属于蛛形纲，有8条腿。"),
    ("科学", "蜜蜂采蜜后会酿成什么？", "蜂蜜", ["牛奶", "果汁", "糖水"], "蜜蜂把花蜜酿成蜂蜜。"),
    ("科学", "蚕宝宝最爱吃什么叶子？", "桑叶", ["柳叶", "枫叶", "松针"], "蚕以桑叶为食。"),
    ("科学", "白天看不到星星是因为什么？", "太阳光太亮", ["星星躲起来了", "星星睡着了", "星星消失了"], "白天太阳光太强，把星光盖住了。"),
    ("生活", "中国的国旗是什么？", "五星红旗", ["青天白日旗", "米字旗", "星条旗"], "中华人民共和国国旗是五星红旗。"),
    ("生活", "中国的首都是哪里？", "北京", ["上海", "广州", "西安"], "中国的首都是北京。"),
    ("生活", "我们的国歌叫什么名字？", "《义勇军进行曲》", ["《歌唱祖国》", "《黄河大合唱》", "《茉莉花》"], "国歌是《义勇军进行曲》。"),
    ("生活", "每年6月1日是什么节日？", "儿童节", ["劳动节", "国庆节", "教师节"], "6月1日是国际儿童节。"),
    ("生活", "10月1日是什么节日？", "国庆节", ["劳动节", "元旦", "建军节"], "10月1日是中华人民共和国国庆节。"),
]
KNOW_TOTS = [
    ("数学", "一只手有几根手指？", "5根", ["3根", "4根", "10根"], "一只手有5根手指，两只手有10根。"),
    ("数学", "一双筷子有几根？", "2根", ["1根", "3根", "4根"], "一双筷子有2根。"),
    ("数学", "三角形有几条边？", "3条", ["2条", "4条", "5条"], "三角形有3条边。"),
    ("数学", "正方形有几条边？", "4条", ["3条", "5条", "6条"], "正方形的4条边一样长。"),
    ("数学", "五角星有几个角？", "5个", ["3个", "4个", "6个"], "五角星有5个尖尖的角。"),
    ("数学", "一个星期有几天？", "7天", ["5天", "6天", "10天"], "一个星期有7天。"),
    ("生活", "口渴了最好喝什么？", "白开水", ["可乐", "果汁", "奶茶"], "白开水最解渴，也最健康。"),
    ("生活", "下雨天出门要带什么？", "雨伞", ["太阳镜", "扇子", "风筝"], "下雨天带雨伞可以不被淋湿。"),
    ("生活", "天冷了应该怎么穿衣服？", "穿厚衣服保暖", ["穿短袖", "光着身子", "穿背心"], "天冷要添衣，穿厚衣服保暖。"),
    ("生活", "打雷的时候待在哪里最安全？", "屋里", ["大树下", "空旷的田野", "水边"], "打雷时不要去树下和水边，待在屋里最安全。"),
    ("生活", "过春节时长辈给的红包叫什么？", "压岁钱", ["工资", "学费", "奖金"], "过年长辈给孩子的红包叫压岁钱。"),
    ("生活", "铅笔是用来做什么的？", "写字和画画", ["吃饭", "梳头", "扫地"], "铅笔用来写字和画画。"),
    ("生活", "看到地上有垃圾应该怎么做？", "捡起来扔进垃圾桶", ["踢到一边", "假装没看见", "捡起来放口袋"], "爱护环境，垃圾要扔进垃圾桶。"),
    ("生活", "在图书馆里应该怎么做？", "保持安静", ["大声唱歌", "跑来跑去", "大声说话"], "图书馆是看书的地方，要保持安静。"),
    ("安全", "过马路时要先看什么？", "红绿灯", ["手机", "漫画书", "天上的云"], "过马路先看红绿灯，红灯停、绿灯行。"),
    ("安全", "能不能把手指伸进插座孔里？", "不能，会触电", ["能，很好玩", "能，只要小心", "只伸一根"], "插座里有电，伸手指进去会触电。"),
    ("安全", "能不能拿着剪刀跑？", "不能，容易摔倒伤人", ["能，跑得快就行", "能，只要不摔倒", "能，剪刀不尖"], "剪刀很尖，拿着跑非常危险。"),
    ("安全", "一个人能不能去河边玩水？", "不能，要有大人陪着", ["能，水浅就行", "能，会游泳就行", "能，带着玩具就行"], "小朋友不能独自去河边玩水。"),
    ("科学", "中国的国宝动物是什么？", "大熊猫", ["东北虎", "金丝猴", "丹顶鹤"], "大熊猫是中国特有的国宝动物。"),
    ("科学", "大熊猫最爱吃什么？", "竹子", ["肉骨头", "鱼", "苹果"], "大熊猫最爱吃竹子。"),
    ("科学", "长颈鹿的脖子有什么特点？", "很长", ["很短", "很粗", "会变色"], "长颈鹿的脖子很长，能吃到高处的树叶。"),
    ("科学", "大象的鼻子能做什么？", "卷东西、吸水喷水", ["什么都不能做", "只会叫", "只会闻味道"], "大象鼻子灵活有力，能卷东西、吸水。"),
    ("科学", "兔子最爱吃什么？", "胡萝卜和青菜", ["肉", "鱼", "虫子"], "兔子爱吃胡萝卜和青菜。"),
    ("科学", "小狗的叫声是什么样的？", "汪汪", ["喵喵", "咩咩", "哞哞"], "小狗汪汪叫。"),
    ("科学", "小猫的叫声是什么样的？", "喵喵", ["汪汪", "喔喔", "嘎嘎"], "小猫喵喵叫。"),
    ("科学", "公鸡的叫声是什么样的？", "喔喔", ["喵喵", "汪汪", "哞哞"], "天亮时公鸡喔喔叫。"),
    ("科学", "老牛的叫声是什么样的？", "哞哞", ["汪汪", "喵喵", "喔喔"], "老牛哞哞叫。"),
    ("科学", "太阳出来了是白天还是黑夜？", "白天", ["黑夜", "中午都不是", "傍晚"], "太阳出来是白天，月亮出来是黑夜。"),
    ("科学", "晚上天上亮亮的是太阳吗？", "不是，是月亮和星星", ["是太阳", "是灯泡", "是萤火虫"], "晚上天上是月亮和星星，太阳晚上不出现。"),
    ("科学", "冬天从天上飘下来的白白的东西是什么？", "雪", ["盐", "糖", "棉花"], "冬天下的是雪，白白的、凉凉的。"),
    ("科学", "交通信号灯有哪三种颜色？", "红、黄、绿", ["红、蓝、紫", "红、黑、白", "黄、蓝、粉"], "交通灯是红、黄、绿三种颜色。"),
    ("科学", "绿灯亮了应该怎么做？", "通过马路", ["原地等待", "往后退", "蹲下来"], "绿灯行，红灯停，黄灯亮了等一等。"),
    ("科学", "燕子到了冬天会去哪里？", "飞去温暖的南方", ["待在原地睡觉", "钻进土里", "躲进树洞"], "燕子秋天飞去南方过冬，春天再飞回来。"),
    ("科学", "毛毛虫长大后会变成什么？", "蝴蝶或飞蛾", ["小鸡", "青蛙", "小鸟"], "毛毛虫结茧后会变成蝴蝶或飞蛾。"),
    ("科学", "鸡蛋是谁生的？", "母鸡", ["公鸡", "鸭子", "鹅"], "母鸡生蛋，孵出小鸡。"),
    ("科学", "种下一粒种子，需要什么才能发芽？", "水和阳光", ["糖果和玩具", "牛奶和饼干", "音乐和图画"], "种子需要水、土壤和阳光才能发芽。"),
    ("科学", "影子是什么颜色的？", "黑色的", ["红色的", "白色的", "黄色的"], "影子是黑色的，太阳照在身上，身后就有影子。"),
    ("科学", "照镜子时，镜子里的自己会做什么？", "和你做一样的动作", ["做相反的动作", "一动不动", "走来走去"], "镜子里的像是自己的影像，动作一模一样。"),
    ("科学", "什么东西越洗越脏？", "水", ["衣服", "手", "碗"], "水洗了东西以后就变脏了。"),
    ("科学", "熊到了冬天会做什么？", "冬眠", ["搬到南方", "换厚毛继续玩", "天天捕鱼"], "熊冬天冬眠，靠秋天储存的脂肪生活。"),
]
KNOW_MID = [
    ("科学", "太阳系中离太阳最近的行星是？", "水星", ["金星", "火星", "木星"], "八大行星按离太阳由近到远：水、金、地、火、木、土、天王、海王。"),
    ("科学", "地球唯一的天然卫星是？", "月球", ["火星", "金星", "太阳"], "月球是地球的天然卫星。"),
    ("科学", "地球上面积最大的大洋是？", "太平洋", ["大西洋", "印度洋", "北冰洋"], "太平洋是世界上最大的大洋。"),
    ("科学", "光在真空中的传播速度约是每秒多少千米？", "30万千米", ["3万千米", "3000千米", "300万千米"], "光速约为每秒30万千米。"),
    ("科学", "植物进行光合作用会释放什么气体？", "氧气", ["二氧化碳", "氮气", "氢气"], "光合作用吸收二氧化碳、释放氧气。"),
    ("科学", "人体共有多少块骨骼（成年人）？", "206块", ["106块", "306块", "56块"], "成年人全身共有206块骨骼。"),
    ("科学", "血液呈现红色主要是因为含有什么？", "血红蛋白", ["叶绿素", "黑色素", "淀粉"], "红细胞中的血红蛋白含铁，使血液呈红色。"),
    ("科学", "温度计里的水银是什么金属？", "汞", ["铁", "铜", "铝"], "水银是金属汞的俗称，常温下呈液态。"),
    ("科学", "空气中含量最多的气体是？", "氮气", ["氧气", "二氧化碳", "氢气"], "空气中约78%是氮气，约21%是氧气。"),
    ("科学", "声音不能在下列哪种环境中传播？", "真空", ["空气", "水", "钢铁"], "声音传播需要介质，真空中无法传声。"),
    ("科学", "彩虹通常在什么时候出现？", "雨后转晴时", ["下雪时", "半夜", "刮风时"], "雨后空气中有小水滴，阳光折射形成彩虹。"),
    ("科学", "电池发明后，最早被广泛使用的直流电符号是？", "DC", ["AC", "BC", "EC"], "直流电是DC，交流电是AC。"),
    ("科学", "水在标准大气压下的沸点是多少度？", "100℃", ["80℃", "90℃", "120℃"], "标准大气压下水在100℃沸腾。"),
    ("科学", "下列哪种动物是冬眠动物？", "熊", ["兔子", "麻雀", "金鱼"], "熊冬天会冬眠，降低消耗。"),
    ("科学", "骆驼的驼峰里主要储存的是什么？", "脂肪", ["水", "食物", "空气"], "驼峰储存脂肪，可分解产生水和能量。"),
    ("科学", "蚯蚓生活在土里，它喜欢什么样的环境？", "阴暗潮湿", ["干燥暴晒", "盐水", "高温"], "蚯蚓喜欢阴暗潮湿的土壤。"),
    ("历史", "中国古代四大发明不包括下列哪一项？", "丝绸", ["造纸术", "印刷术", "指南针"], "四大发明是造纸术、印刷术、指南针、火药。"),
    ("历史", "造纸术是东汉哪位人物改进的？", "蔡伦", ["毕昇", "张衡", "祖冲之"], "东汉蔡伦改进了造纸术。"),
    ("历史", "活字印刷术是北宋谁发明的？", "毕昇", ["蔡伦", "沈括", "张衡"], "北宋毕昇发明活字印刷术。"),
    ("历史", "中国历史上第一个统一的封建王朝是？", "秦朝", ["汉朝", "唐朝", "周朝"], "公元前221年秦统一六国。"),
    ("历史", "万里长城主要是为了防御哪个方向的敌人？", "北方游牧民族", ["南方", "东方", "海上"], "长城是为防御北方游牧民族而建。"),
    ("历史", "唐朝最著名的女皇帝是谁？", "武则天", ["慈禧", "王昭君", "杨贵妃"], "武则天是中国历史上唯一的女皇帝。"),
    ("历史", "端午节是为了纪念哪位诗人？", "屈原", ["李白", "杜甫", "苏轼"], "端午节纪念投汨罗江的爱国诗人屈原。"),
    ("历史", "《西游记》中唐僧的原型是唐代哪位高僧？", "玄奘", ["鉴真", "慧能", "法海"], "玄奘西行取经，是唐僧的历史原型。"),
    ("历史", "郑和下西洋发生在哪个朝代？", "明朝", ["唐朝", "宋朝", "清朝"], "明朝郑和七下西洋。"),
    ("地理", "中国最长的河流是？", "长江", ["黄河", "珠江", "黑龙江"], "长江是中国第一长河，全长约6300千米。"),
    ("地理", "被称为“母亲河”的是哪条河？", "黄河", ["长江", "珠江", "淮河"], "黄河孕育了中华文明，被称为母亲河。"),
    ("地理", "世界最高峰是？", "珠穆朗玛峰", ["泰山", "华山", "黄山"], "珠穆朗玛峰海拔约8848米，是世界最高峰。"),
    ("地理", "中国最大的岛屿是？", "台湾岛", ["海南岛", "崇明岛", "舟山岛"], "台湾岛是中国第一大岛。"),
    ("地理", "中国的国土面积约为多少万平方千米？", "960万", ["690万", "1200万", "500万"], "中国陆地面积约960万平方千米。"),
    ("地理", "地球上最大的洲是？", "亚洲", ["非洲", "北美洲", "欧洲"], "亚洲是面积最大、人口最多的大洲。"),
    ("文化", "京剧脸谱中，红脸一般代表什么性格？", "忠勇", ["奸诈", "鲁莽", "胆小"], "红脸象征忠勇，如关羽；白脸多象征奸诈。"),
    ("文化", "毛笔、墨、纸、砚合称什么？", "文房四宝", ["四大发明", "琴棋书画", "笔墨丹青"], "笔墨纸砚合称文房四宝。"),
    ("文化", "琴棋书画中的“棋”通常指什么棋？", "围棋", ["象棋", "跳棋", "五子棋"], "古人说的棋多指围棋。"),
    ("文化", "端午节的传统习俗是？", "吃粽子、赛龙舟", ["吃月饼", "赏月", "贴春联"], "端午节吃粽子、赛龙舟纪念屈原。"),
    ("文化", "中秋节的传统食物是？", "月饼", ["粽子", "汤圆", "饺子"], "中秋节吃月饼、赏月。"),
    ("文化", "元宵节的传统食物是？", "汤圆", ["月饼", "粽子", "饺子"], "元宵节吃汤圆，寓意团圆。"),
    ("文化", "春节贴的红色对联叫什么？", "春联", ["窗花", "年画", "福字"], "春节贴春联，辞旧迎新。"),
]
KNOW_HIGH = [
    ("科学", "地球绕太阳公转一周大约需要多长时间？", "一年", ["一个月", "一天", "十年"], "地球公转周期约为365天，即一年。"),
    ("科学", "光年是长度单位还是时间单位？", "长度单位", ["时间单位", "速度单位", "质量单位"], "光年是光在一年中传播的距离，是长度单位。"),
    ("科学", "地球表面约百分之多少被水覆盖？", "71%", ["50%", "30%", "90%"], "地球表面约71%被海洋等水体覆盖。"),
    ("科学", "人体最大的器官是？", "皮肤", ["心脏", "肝脏", "大脑"], "皮肤是人体面积最大的器官。"),
    ("科学", "DNA的中文名称是？", "脱氧核糖核酸", ["核糖核酸", "蛋白质", "氨基酸"], "DNA即脱氧核糖核酸，携带遗传信息。"),
    ("科学", "地球大气层中，臭氧层主要吸收什么？", "紫外线", ["红外线", "可见光", "声波"], "臭氧层能吸收太阳辐射中的紫外线。"),
    ("科学", "声音在下列哪种介质中传播最快？", "钢铁", ["空气", "水", "真空"], "声音在固体中传播最快，真空中不能传声。"),
    ("科学", "重力加速度g在地球表面约为多少m/s²？", "9.8", ["3.14", "1.6", "100"], "地球表面重力加速度约为9.8m/s²。"),
    ("科学", "元素周期表中，符号为O的元素是？", "氧", ["金", "氢", "碳"], "O是氧元素的符号，H是氢，C是碳。"),
    ("科学", "水的化学式是？", "H₂O", ["CO₂", "O₂", "NaCl"], "水由两个氢原子和一个氧原子构成，化学式H₂O。"),
    ("科学", "二氧化碳的化学式是？", "CO₂", ["H₂O", "O₂", "CO"], "二氧化碳化学式为CO₂。"),
    ("科学", "植物细胞与动物细胞相比，特有的结构是？", "细胞壁", ["细胞膜", "细胞核", "线粒体"], "植物细胞有细胞壁、叶绿体和液泡。"),
    ("科学", "太阳系中体积最大的行星是？", "木星", ["地球", "土星", "火星"], "木星是太阳系最大的行星。"),
    ("科学", "赤道上一年昼夜长短情况是？", "全年昼夜几乎等长", ["夏季极昼", "冬季极夜", "昼夜差最大"], "赤道地区全年昼夜接近各12小时。"),
    ("历史", "中国历史上第一个朝代是？", "夏朝", ["商朝", "周朝", "秦朝"], "夏朝是中国史书记载的第一个世袭制朝代。"),
    ("历史", "“贞观之治”是唐朝哪位皇帝在位时的盛世？", "唐太宗", ["唐玄宗", "唐高祖", "武则天"], "唐太宗李世民在位时开创贞观之治。"),
    ("历史", "丝绸之路的起点是今天的哪座城市？", "西安", ["北京", "南京", "洛阳"], "丝绸之路起点是古长安，今西安。"),
    ("历史", "鸦片战争爆发于哪一年？", "1840年", ["1911年", "1894年", "1860年"], "1840年鸦片战争爆发，中国近代史开端。"),
    ("历史", "辛亥革命爆发于哪一年？", "1911年", ["1840年", "1919年", "1937年"], "1911年辛亥革命推翻了清朝统治。"),
    ("历史", "“五四运动”爆发于哪一年？", "1919年", ["1911年", "1921年", "1949年"], "1919年5月4日爆发五四运动。"),
    ("历史", "中华人民共和国成立的时间是？", "1949年10月1日", ["1949年1月1日", "1945年8月15日", "1921年7月1日"], "1949年10月1日开国大典在北京举行。"),
    ("历史", "中国共产党成立于哪一年？", "1921年", ["1919年", "1927年", "1949年"], "1921年7月中共一大召开，中国共产党成立。"),
    ("历史", "“造纸术、印刷术、指南针、火药”合称什么？", "四大发明", ["文房四宝", "四大名著", "四库全书"], "这是中国古代四大发明。"),
    ("历史", "《史记》的作者是谁？", "司马迁", ["司马光", "班固", "孔子"], "西汉司马迁著《史记》。"),
    ("历史", "《资治通鉴》的主编是谁？", "司马光", ["司马迁", "王安石", "欧阳修"], "北宋司马光主编《资治通鉴》。"),
    ("历史", "明朝迁都北京的皇帝是？", "明成祖朱棣", ["明太祖朱元璋", "崇祯帝", "建文帝"], "明成祖朱棣迁都北京，建紫禁城。"),
    ("地理", "世界上面积最大的国家是？", "俄罗斯", ["中国", "美国", "加拿大"], "俄罗斯面积约1700万平方千米，居世界第一。"),
    ("地理", "世界上最长的河流是？", "尼罗河", ["长江", "亚马孙河", "黄河"], "尼罗河全长约6650千米，是世界第一长河。"),
    ("地理", "世界上面积最大的沙漠是？", "撒哈拉沙漠", ["塔克拉玛干沙漠", "戈壁沙漠", "阿拉伯沙漠"], "撒哈拉沙漠位于非洲北部，是世界最大沙漠。"),
    ("地理", "地球上的五带中，中国大部分位于？", "北温带", ["热带", "南温带", "北寒带"], "中国大部分领土位于北温带。"),
    ("地理", "世界上人口最多的大洲是？", "亚洲", ["非洲", "欧洲", "北美洲"], "亚洲人口约占世界人口的60%。"),
    ("地理", "连接太平洋和大西洋的著名运河是？", "巴拿马运河", ["苏伊士运河", "京杭大运河", "基尔运河"], "巴拿马运河沟通太平洋和大西洋。"),
    ("地理", "沟通地中海和红海的运河是？", "苏伊士运河", ["巴拿马运河", "京杭大运河", "伊利运河"], "苏伊士运河连接地中海与红海。"),
    ("文化", "《红楼梦》的作者是谁？", "曹雪芹", ["罗贯中", "施耐庵", "吴承恩"], "《红楼梦》作者曹雪芹。"),
    ("文化", "《三国演义》的作者是谁？", "罗贯中", ["曹雪芹", "施耐庵", "吴承恩"], "《三国演义》作者罗贯中。"),
    ("文化", "《水浒传》的作者是谁？", "施耐庵", ["罗贯中", "曹雪芹", "吴承恩"], "《水浒传》作者施耐庵。"),
    ("文化", "《西游记》的作者是谁？", "吴承恩", ["罗贯中", "施耐庵", "曹雪芹"], "《西游记》作者吴承恩。"),
    ("文化", "“诗仙”指的是哪位诗人？", "李白", ["杜甫", "白居易", "王维"], "李白被称为诗仙，杜甫被称为诗圣。"),
    ("文化", "“诗圣”指的是哪位诗人？", "杜甫", ["李白", "白居易", "苏轼"], "杜甫被称为诗圣。"),
    ("文化", "儒家学派的创始人是？", "孔子", ["老子", "孟子", "庄子"], "孔子是儒家学派创始人，孟子继承发展。"),
    ("文化", "道家学派的创始人是？", "老子", ["孔子", "庄子", "墨子"], "老子是道家学派创始人，著《道德经》。"),
]


KNOW_KIDS = [
    ("生活", "小狗是怎么叫的？", "汪汪", ["喵喵", "咩咩", "哞哞"], "小狗汪汪叫。"),
    ("生活", "小猫是怎么叫的？", "喵喵", ["汪汪", "嘎嘎", "喔喔"], "小猫喵喵叫。"),
    ("生活", "小羊是怎么叫的？", "咩咩", ["汪汪", "喵喵", "叽叽"], "小羊咩咩叫。"),
    ("生活", "小牛是怎么叫的？", "哞哞", ["咩咩", "汪汪", "呱呱"], "小牛哞哞叫。"),
    ("生活", "小鸡是怎么叫的？", "叽叽", ["喔喔", "嘎嘎", "咩咩"], "小鸡叽叽叫，公鸡喔喔叫。"),
    ("生活", "小鸭子是怎么叫的？", "嘎嘎", ["叽叽", "汪汪", "喵喵"], "小鸭嘎嘎叫。"),
    ("生活", "青蛙是怎么叫的？", "呱呱", ["嘎嘎", "叽叽", "哞哞"], "青蛙呱呱叫。"),
    ("科学", "人有几只眼睛？", "2只", ["1只", "3只", "4只"], "每个人都有2只眼睛。"),
    ("科学", "人有几只耳朵？", "2只", ["1只", "3只", "4只"], "人有2只耳朵，能听到周围的声音。"),
    ("科学", "人有几只手？", "2只", ["1只", "3只", "4只"], "人有2只手。"),
    ("科学", "一只手有几根手指？", "5根", ["4根", "6根", "10根"], "一只手有5根手指，一双手有10根。"),
    ("生活", "爸爸的爸爸应该叫什么？", "爷爷", ["外公", "叔叔", "舅舅"], "爸爸的爸爸是爷爷。"),
    ("生活", "妈妈的妈妈应该叫什么？", "外婆", ["奶奶", "姑姑", "阿姨"], "妈妈的妈妈是外婆（姥姥）。"),
    ("生活", "爸爸的妈妈应该叫什么？", "奶奶", ["外婆", "舅妈", "姨妈"], "爸爸的妈妈是奶奶。"),
    ("生活", "妈妈的爸爸应该叫什么？", "外公", ["爷爷", "叔叔", "伯伯"], "妈妈的爸爸是外公（姥爷）。"),
    ("生活", "生病了我们去找谁看病？", "医生", ["老师", "警察", "司机"], "生病要看医生。"),
    ("生活", "教我们读书写字的是谁？", "老师", ["医生", "厨师", "警察"], "老师教我们知识和本领。"),
    ("生活", "发生火灾时，谁开着消防车来灭火？", "消防员", ["医生", "老师", "邮递员"], "消防员负责灭火救援。"),
    ("生活", "买图书应该去哪里？", "书店", ["超市", "医院", "公园"], "买书要去书店或图书馆借阅。"),
    ("生活", "看病应该去哪里？", "医院", ["学校", "商店", "动物园"], "看病要去医院。"),
    ("生活", "想看动物应该去哪里？", "动物园", ["图书馆", "医院", "银行"], "动物园里有各种各样的动物。"),
    ("生活", "下雨天出门应该带什么？", "雨伞", ["扇子", "墨镜", "游泳圈"], "下雨天要带雨伞或雨衣。"),
    ("生活", "冬天天气冷，出门应该穿什么？", "棉衣", ["短袖", "凉鞋", "泳衣"], "冬天要穿厚衣服保暖。"),
    ("生活", "夏天天气热，我们可以吃什么解暑？", "西瓜", ["火锅", "热汤", "辣椒"], "夏天吃西瓜、多喝水可以解暑。"),
    ("科学", "冬天的天气特点是？", "寒冷，可能下雪", ["炎热", "百花盛开", "树叶变黄"], "冬天寒冷，北方常常会下雪。"),
    ("科学", "春天的天气特点是？", "温暖，百花开放", ["寒冷下雪", "炎热", "落叶"], "春天温暖，万物复苏、百花开放。"),
    ("科学", "秋天树叶通常会变成什么颜色？", "黄色", ["绿色", "蓝色", "紫色"], "秋天很多树叶变黄、飘落。"),
    ("科学", "健康的树叶通常是什么颜色？", "绿色", ["红色", "蓝色", "黑色"], "树叶含叶绿素，通常是绿色的。"),
    ("科学", "香蕉成熟后是什么颜色？", "黄色", ["绿色", "蓝色", "紫色"], "成熟的香蕉是黄色的。"),
    ("科学", "成熟的草莓是什么颜色？", "红色", ["绿色", "黑色", "白色"], "草莓成熟后是红色的。"),
    ("生活", "在天上飞的交通工具是？", "飞机", ["汽车", "轮船", "自行车"], "飞机在天上飞。"),
    ("生活", "在水上行驶的交通工具是？", "轮船", ["飞机", "火车", "汽车"], "轮船在水上航行。"),
    ("生活", "在铁轨上行驶的交通工具是？", "火车", ["汽车", "飞机", "轮船"], "火车沿着铁轨行驶。"),
    ("生活", "别人帮助我们时，应该说什么？", "谢谢", ["再见", "对不起", "没关系"], "得到帮助要说谢谢，做个有礼貌的孩子。"),
    ("生活", "不小心碰到别人时，应该说什么？", "对不起", ["谢谢", "你好", "再见"], "做错事要主动说对不起。"),
    ("生活", "早上见到老师，应该说什么？", "老师早上好", ["老师再见", "老师晚安", "不用理他"], "早上见面要问好。"),
    ("科学", "鱼儿生活在哪里？", "水里", ["树上", "天上", "沙漠里"], "鱼儿离不开水。"),
    ("科学", "小鸟大多住在哪里？", "树上的鸟窝里", ["水里", "地洞里", "冰箱里"], "小鸟在树上做窝。"),
    ("科学", "下面哪种动物会飞？", "麻雀", ["金鱼", "乌龟", "蜗牛"], "麻雀是鸟类，会飞。"),
    ("科学", "下面哪种动物生活在水里？", "金鱼", ["麻雀", "兔子", "小猫"], "金鱼生活在水中。"),
    ("科学", "下面哪种动物跑得快且生活在草原上？", "猎豹", ["蜗牛", "乌龟", "树懒"], "猎豹是跑得最快的陆地动物。"),
    ("科学", "打雷下雨时，我们先看到闪电还是先听到雷声？", "先看到闪电", ["先听到雷声", "同时", "不一定"], "光比声音传播快得多，所以先看到闪电。"),
    ("科学", "冰受热后会变成什么？", "水", ["沙子", "石头", "棉花"], "冰受热融化成水。"),
    ("科学", "水烧开时会产生什么？", "水蒸气", ["冰块", "糖", "烟"], "水受热沸腾变成水蒸气。"),
    ("生活", "吃饭前应该做什么？", "洗手", ["玩泥巴", "跑步", "画画"], "饭前洗手，讲卫生不生病。"),
]
CLASSIFY_KIDS = [
    ("水果", ["苹果", "香蕉", "西瓜", "葡萄", "桃子"], ["白菜", "萝卜", "土豆", "辣椒"]),
    ("蔬菜", ["白菜", "萝卜", "土豆", "黄瓜", "西红柿"], ["苹果", "香蕉", "蛋糕", "糖果"]),
    ("动物", ["小狗", "小猫", "兔子", "猴子", "大象"], ["汽车", "桌子", "铅笔", "花朵"]),
    ("鸟类", ["麻雀", "喜鹊", "燕子", "鸽子", "老鹰"], ["小狗", "金鱼", "兔子", "乌龟"]),
    ("交通工具", ["汽车", "火车", "飞机", "轮船", "自行车"], ["桌子", "椅子", "苹果", "房子"]),
    ("文具", ["铅笔", "橡皮", "尺子", "钢笔", "文具盒"], ["苹果", "水杯", "书包", "鞋子"]),
    ("颜色", ["红色", "黄色", "蓝色", "绿色", "紫色"], ["苹果", "跑步", "桌子", "唱歌"]),
    ("家用电器", ["电视机", "冰箱", "洗衣机", "电风扇", "空调"], ["桌子", "铅笔", "苹果", "小狗"]),
]


def build_knowledge_extra(age, rng, target, seen):
    pool = []

    def add(category, prompt, answer, distractors, explanation=None):
        if prompt in seen:
            return
        if len(set(distractors + [answer])) != 4:
            return
        seen.add(prompt)
        pool.append((category, prompt, answer, [str(d) for d in distractors],
                     explanation or f"正确答案是{answer}。"))

    # --- 全龄通用：星期/月份/季节/方向/节日 ---------------------------
    for i, day in enumerate(WEEKDAYS):
        for offset, label in ((1, "后一天"), (-1, "前一天"), (2, "后两天")):
            ans = WEEKDAYS[(i + offset) % 7]
            others = [WEEKDAYS[(i + offset + d) % 7] for d in (1, 2, 3)]
            add("生活", f"{day}的{label}是星期几？", ans, others)
    DAY_CANDS = ["28天", "29天", "30天", "31天"]
    for m in range(1, 13):
        days = MONTH_DAYS[m]
        d_days = [c for c in DAY_CANDS if c != f"{days}天"]
        add("科学", f"{m}月有多少天？", f"{days}天", d_days,
            "一三五七八十腊，三十一天永不差。" if days == 31 else ("二月平年28天。" if m == 2 else "这个月是小月，有30天。"))
        season = SEASON_OF_MONTH[m]
        others = [s for s in ("春季", "夏季", "秋季", "冬季") if s != season]
        add("科学", f"{m}月属于哪个季节？", season, others, f"{m}月属于{season}。")
    add("科学", "一年有几个季节？", "4个", ["3个", "5个", "2个"], "一年有春、夏、秋、冬4个季节。")
    add("科学", "一年有多少个月？", "12个月", ["10个", "11个", "13个"], "一年有12个月。")
    dirs = [("东", "西"), ("南", "北")]
    for a, b in dirs:
        add("科学", f"与「{a}」相对的方向是？", b, [x for x in ("东", "南", "西", "北") if x not in (a, b)])
    add("科学", "早晨太阳从哪个方向升起？", "东方", ["西方", "南方", "北方"], "太阳东升西落。")
    add("科学", "傍晚太阳从哪个方向落下？", "西方", ["东方", "南方", "北方"], "太阳东升西落。")
    for name, date in FESTIVALS:
        others = [d for n, d in FESTIVALS if d != date]
        add("文化", f"{name}是哪一天？", date, others[:3] if len(others) >= 3 else others)
    add("文化", "农历正月十五是什么节日？", "元宵节", ["春节", "端午节", "中秋节"], "正月十五是元宵节，吃汤圆、赏花灯。")
    add("文化", "农历八月十五是什么节日？", "中秋节", ["端午节", "元宵节", "重阳节"], "八月十五是中秋节，赏月、吃月饼。")

    # --- 生肖 ---------------------------------------------------------
    for i, z in enumerate(ZODIAC):
        nxt = ZODIAC[(i + 1) % 12]
        others = [ZODIAC[(i + k) % 12] for k in (2, 3, 4)]
        add("文化", f"十二生肖中，属{z}的下一年属什么？", nxt, others,
            f"十二生肖顺序：{'、'.join(ZODIAC)}。")
    add("文化", "十二生肖一共有几个？", "12个", ["10个", "11个", "13个"], "十二生肖是：鼠牛虎兔龙蛇马羊猴鸡狗猪。")
    add("文化", "十二生肖中排在第一位的是？", "鼠", ["牛", "虎", "猪"], "鼠是十二生肖之首。")
    add("文化", "十二生肖中排在最后一位的是？", "猪", ["狗", "鼠", "鸡"], "猪排在十二生肖最后。")

    # --- 分龄内容 -----------------------------------------------------
    if age <= 8:
        curated = KNOW_LOW + KNOW_KIDS + KNOW_TOTS
    elif age <= 10:
        curated = KNOW_LOW + KNOW_MID + KNOW_TOTS
    else:
        curated = KNOW_MID + KNOW_HIGH
    for category, prompt, answer, distractors, explanation in curated:
        add(category, prompt, answer, distractors, explanation)

    if age <= 8:
        cat_names = [c for c, _, _ in CLASSIFY_KIDS]
        belongs = {}
        for cat, items, outs in CLASSIFY_KIDS:
            for out in outs:
                belongs.setdefault(out, None)
            for item in items:
                belongs[item] = cat
        for word, cat in belongs.items():
            if cat is None:
                continue
            d = [c for c in cat_names if c != cat]
            add("科学", f"「{word}」属于哪一类？", cat, d[:3], f"{word}属于{cat}。")

    if age >= 7:
        for i, z in enumerate(ZODIAC):
            others = [ZODIAC[(i + k) % 12] for k in (1, 2, 3)]
            add("文化", f"十二生肖中排在第{i + 1}位的是什么？", z, others,
                "十二生肖顺序：鼠、牛、虎、兔、龙、蛇、马、羊、猴、鸡、狗、猪。")
        add("科学", "地图通常是按什么方向绘制的？", "上北下南，左西右东",
            ["上南下北，左东右西", "上东下西，左南右北", "没有固定方向"], "地图定向：上北下南、左西右东。")
        add("科学", "平年的2月有多少天？", "28天", ["29天", "30天", "31天"], "平年2月28天，闰年2月29天。")
        add("科学", "闰年的2月有多少天？", "29天", ["28天", "30天", "31天"], "闰年2月有29天。")
        for year in range(2015, 2033):
            z = ZODIAC[(year - 2020) % 12]
            others = [ZODIAC[(year - 2020 + k) % 12] for k in (1, 2, 3)]
            add("文化", f"{year}年是什么生肖年？", z, others, "2020年是鼠年，往后依次推算。")
        for i, term in enumerate(SOLAR_TERMS):
            if i + 1 < len(SOLAR_TERMS):
                nxt = SOLAR_TERMS[i + 1]
                others = [SOLAR_TERMS[(i + k) % 24] for k in (2, 3, 5)]
                add("文化", f"二十四节气中，「{term}」之后的节气是？", nxt, others)
        add("文化", "一年有多少个节气？", "24个", ["12个", "16个", "36个"], "一年有二十四节气。")
        add("文化", "二十四节气中排在第一个的是？", "立春", ["春分", "雨水", "冬至"], "立春是二十四节气之首。")

    if age >= 9:
        famous = PROVINCES if age >= 11 else PROVINCES[:16]
        capitals = [c for _, c, _ in PROVINCES]
        abbrs = [a for _, _, a in PROVINCES]
        for name, capital, abbr in famous:
            d = [c for c in capitals if c != capital]
            add("地理", f"{name}的省会城市（行政中心）是哪里？", capital, d[:3],
                f"{name}的省会是{capital}。")
            if age >= 11:
                da = [a for a in abbrs if a != abbr]
                add("地理", f"{name}的简称是什么？", abbr, da[:3], f"{name}简称「{abbr}」。")
        for country, capital in (CAPITALS if age >= 11 else CAPITALS[:6]):
            d = [c for _, c in CAPITALS if c != capital]
            add("地理", f"{country}的首都是哪里？", capital, d[:3], f"{country}的首都是{capital}。")

    if age >= 11:
        for i, dyn in enumerate(DYNASTIES):
            if i + 1 < len(DYNASTIES):
                nxt = DYNASTIES[i + 1]
                others = [DYNASTIES[(i + k) % len(DYNASTIES)] for k in (2, 3, 5)]
                add("历史", f"中国历史上，{dyn}朝之后是哪个朝代？", nxt, others,
                    "朝代顺序：夏商周秦汉，三国两晋南北朝，隋唐五代十国，宋元明清。")

    if len(pool) < target:
        raise RuntimeError(f"knowledge pool too small for age {age}: {len(pool)} < {target}")
    return rng.sample(pool, target)


# ---------------------------------------------------------------------------
# band pools (legacy) + validation
# ---------------------------------------------------------------------------

def load_band(band_key):
    path = DATA_DIR / f"band_{band_key}.py"
    spec = importlib.util.spec_from_file_location(f"band_{band_key}", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.LANG, module.CURATED


def validate_pool(name, pool):
    prompts = set()
    for item in pool:
        assert len(item) == 5, (name, item)
        category, prompt, correct, distractors, explanation = item
        assert prompt not in prompts, (name, "duplicate", prompt)
        prompts.add(prompt)
        assert len(distractors) == 3, (name, prompt)
        assert len(set(distractors + [correct])) == 4, (name, prompt)
        assert correct not in distractors, (name, prompt)
        assert prompt.strip() and explanation.strip(), (name, prompt)


def main():
    band_cache = {}
    lang_bank = load_lang_bank()
    shards = {}
    for age in range(5, 16):
        rng = random.Random(20261103 + age)
        legacy_math = build_math_legacy(age, rng)
        band_key = BANDS[age]
        if band_key not in band_cache:
            lang, curated = load_band(band_key)
            validate_pool(f"LANG[{band_key}]", lang)
            validate_pool(f"CURATED[{band_key}]", curated)
            assert len(lang) >= LEGACY_LANG, (band_key, len(lang))
            assert len(curated) >= LEGACY_KNOW, (band_key, len(curated))
            band_cache[band_key] = (lang, curated)
        lang, curated = band_cache[band_key]
        lang_pick = rng.sample(lang, LEGACY_LANG)
        curated_pick = rng.sample(curated, LEGACY_KNOW)
        assert len(legacy_math) == LEGACY_MATH, (age, len(legacy_math))

        legacy = legacy_math + lang_pick + curated_pick
        seen = {prompt for _, prompt, _, _, _ in legacy}

        rng2 = random.Random(20261105 + age)
        extra_math = build_math_extra(age, rng2, EXTRA_MATH, seen)
        extra_lang = build_lang_extra(age, band_key, rng2, EXTRA_LANG, seen, lang_bank)
        extra_know = build_knowledge_extra(age, rng2, EXTRA_KNOW, seen)

        questions = []
        for index, (category, prompt, correct, distractors, explanation) in enumerate(
                legacy + extra_math + extra_lang + extra_know, 1):
            options = list(distractors) + [correct]
            (rng if index <= len(legacy) else rng2).shuffle(options)
            questions.append({
                "id": f"a{age}-{index:04d}",
                "age": age,
                "category": category,
                "question": prompt,
                "options": options,
                "answerIndex": options.index(correct),
                "explanation": explanation,
            })
        assert len(questions) == PER_AGE, (age, len(questions))
        shards[age] = questions

    ids = set()
    prompts = set()
    counts = {}
    for age, questions in shards.items():
        for q in questions:
            assert q["id"] not in ids
            assert (q["age"], q["question"]) not in prompts
            assert len(q["options"]) == 4
            assert len(set(q["options"])) == 4, q
            assert 0 <= q["answerIndex"] < 4
            assert q["question"].strip() and q["explanation"].strip()
            ids.add(q["id"])
            prompts.add((q["age"], q["question"]))
        counts[age] = len(questions)
    assert sum(counts.values()) == 11 * PER_AGE

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for age, questions in shards.items():
        payload = {"version": 3, "age": age, "questions": questions}
        (OUT_DIR / f"age_{age}.json").write_text(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    if LEGACY_OUT.exists():
        LEGACY_OUT.unlink()
    sizes = {age: (OUT_DIR / f"age_{age}.json").stat().st_size for age in shards}
    print(json.dumps({"total": sum(counts.values()), "perAge": counts,
                      "shardBytes": sizes, "dir": str(OUT_DIR)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
