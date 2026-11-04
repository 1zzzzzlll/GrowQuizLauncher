#!/usr/bin/env python3
"""Deterministically build and validate the offline 5-14 year-old question bank.

Composition per age (800 questions):
  - 500 procedural math/logic questions (build_math)
  - 200 language questions sampled from the age band's LANG pool
  - 100 general-knowledge questions sampled from the band's CURATED pool
Band pools live in tools/question_data/band_<lo>_<hi>.py
"""
import importlib.util
import json
import random
from math import gcd
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/assets/questions.json"
DATA_DIR = ROOT / "tools/question_data"

MATH_TARGET = 500
LANG_TARGET = 200
CURATED_TARGET = 100

BANDS = {5: "5_6", 6: "5_6", 7: "7_8", 8: "7_8", 9: "9_10", 10: "9_10",
         11: "11_12", 12: "11_12", 13: "13_14", 14: "13_14"}


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


def build_math(age, rng):
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
    while len(questions) < MATH_TARGET:
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
    all_questions = []
    for age in range(5, 15):
        rng = random.Random(20261103 + age)
        generated = build_math(age, rng)
        band_key = BANDS[age]
        if band_key not in band_cache:
            lang, curated = load_band(band_key)
            validate_pool(f"LANG[{band_key}]", lang)
            validate_pool(f"CURATED[{band_key}]", curated)
            assert len(lang) >= LANG_TARGET, (band_key, len(lang))
            assert len(curated) >= CURATED_TARGET, (band_key, len(curated))
            band_cache[band_key] = (lang, curated)
        lang, curated = band_cache[band_key]
        lang_pick = rng.sample(lang, LANG_TARGET)
        curated_pick = rng.sample(curated, CURATED_TARGET)
        assert len(generated) == MATH_TARGET, (age, len(generated))

        for index, (category, prompt, correct, distractors, explanation) in enumerate(
                generated + lang_pick + curated_pick, 1):
            options = list(distractors) + [correct]
            rng.shuffle(options)
            all_questions.append({
                "id": f"a{age}-{index:04d}",
                "age": age,
                "category": category,
                "question": prompt,
                "options": options,
                "answerIndex": options.index(correct),
                "explanation": explanation,
            })

    ids = set()
    prompts = set()
    counts = {age: 0 for age in range(5, 15)}
    for q in all_questions:
        assert q["id"] not in ids
        assert (q["age"], q["question"]) not in prompts
        assert len(q["options"]) == 4
        assert len(set(q["options"])) == 4, q
        assert 0 <= q["answerIndex"] < 4
        assert q["question"].strip() and q["explanation"].strip()
        ids.add(q["id"])
        prompts.add((q["age"], q["question"]))
        counts[q["age"]] += 1
    assert len(all_questions) == 10 * (MATH_TARGET + LANG_TARGET + CURATED_TARGET)
    assert all(count >= 800 for count in counts.values()), counts
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"version": 2, "questions": all_questions}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(json.dumps({"total": len(all_questions), "perAge": counts, "file": str(OUT)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
