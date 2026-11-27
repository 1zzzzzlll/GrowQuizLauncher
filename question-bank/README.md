# GrowQuiz 在线题库目录 (`question-bank/`)

本目录专门用于在 GitHub 上维护宝贝成长桌面的**在线同步题库**，无需重新打包发布 APK，修改推送到 `main` 分支后：

1. **服务器自动同步（`mylinux`）**：`growquiz-sync.timer` 每 3 小时从 GitHub 本目录拉取最新全学年题库池（`pool/`）与人工补充题（`manual/`），按当天校历日期自动推算当前学期与教学周（共 18 教学周），生成 `/root/nginx/web/growquiz/questions/manifest.json` 与 `age_<7..15>.json`；
2. **GitHub Actions 每周演进**：`.github/workflows/question-bank.yml` 每周一自动抓取国家中小学智慧教育平台最新教材章节目录并推进仓库内当前教学周快照；
3. **客户端后台随机拉取**：电视/平板运行期间，按随机抖动延迟与 3–8 小时随机周期在后台检查 `questions/manifest.json`，发现分片 SHA-256 变化后自动下载校验并热加载进本地题库；每道题本地答对满 **3 次** 自动禁用退休。

## 目录结构

| 路径 | 说明 |
| --- | --- |
| `curriculum/textbook_catalog.json` | 自动从国家中小学智慧教育平台抓取的 1–9 年级（7–15 岁，六三学制）上下册教材单元与课文目录快照 |
| `pool/index.json` | 全学年题库池索引与手工题文件清单 |
| `pool/age_7.json` … `pool/age_15.json` | 1–9 年级全学年上下册题库池（每题带 `term: "fall"\|"spring"`、`week: 1..18`、`unit` 教材章节定位） |
| `manual/custom_questions.json` | 手工或 AI 补充的在线题库文件，直接在 GitHub 网页编辑提交即可生效 |
| `age_7.json` … `age_15.json` | 按当前日期教学周过滤后的激活在线分片 |
| `manifest.json` | 当前学期进度元数据与各年龄分片 SHA-256 清单 |

## 学期教学进度预估规则

- **第一学期（上册，`fall`）**：9 月 1 日开学（扣除国庆假期停课），按每周推进解锁上册第 `1..W` 教学周题目（共 18 周）；
- **寒假复习（1 月下旬 – 2 月下旬）**：上册全册 18 周题目全部开放复习；
- **第二学期（下册，`spring`）**：2 月 25 日起按周解锁下册第 `1..W` 周题目，同时保留已学完的上册题目供巩固；
- **暑假巩固（7 月上旬 – 8 月 31 日）**：本年级上下册全部题目开放巩固。

## 手动新增在线题

直接编辑 `manual/custom_questions.json`（或在 `manual/` 下新增 `.json` 并运行 `python tools/update_online_bank.py`）：

```json
{
  "age": 8,
  "category": "语文",
  "question": "“欲穷千里目”的下一句是什么？",
  "options": ["更上一层楼", "黄河入海流", "低头思故乡", "疑是地上霜"],
  "answerIndex": 0,
  "explanation": "出自王之涣《登鹳雀楼》。",
  "term": "any",
  "week": 1
}
```

- `age`：7–15 对应小学一年级至初三（5–6 对应学前）；
- `term`：`"any"` 全年启用，`"fall"` 上册按 `week` 解锁，`"spring"` 下册按 `week` 解锁。
