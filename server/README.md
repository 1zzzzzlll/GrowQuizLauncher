# 更新与报告服务器

这两组服务跑在同一台服务器（本机 SSH 别名 `mylinux`，只写在本机 `~/.ssh/config`，不进仓库）上，客户端只访问 `https://<更新域名>:8443`。

## 私密配置不进仓库

域名与口令都放在仓库外，仓库里只有占位与说明：

| 位置 | 内容 | 谁读 |
| --- | --- | --- |
| 仓库根 `secrets.properties`（git-ignored） | `updateBase` / `reportUrl` / `reportToken` | Gradle 构建时注入 `BuildConfig` |
| `secrets.properties.example`（入库） | 同样的键，值留空 | 新克隆的人照它建自己的文件 |
| 服务器 `/root/growquiz/report.env`（600） | `GROWQUIZ_REPORT_TOKEN=...` | `growquiz-report.service` 的 `EnvironmentFile` |

没有 `secrets.properties` 时照常能编译、能安装，只是不检查更新、不上报（没有服务器可连）。服务器没有口令时不启动，不会以空口令收报告。

`GROWQUIZ_REPORT_TOKEN` 可以写多个口令，逗号分隔，**每一个都接受**。换口令时把新的放前面、旧的留在后面，等装了新包的设备升级完再删旧的 —— 否则换口令的那一刻，所有还在跑旧包的电视报告都会被 403。

```sh
# 换口令：新的在前，旧的垫后（旧的先别删）
ssh mylinux 'printf "GROWQUIZ_REPORT_TOKEN=%s,%s\n" "<新口令>" "***REMOVED***" > /root/growquiz/report.env && chmod 600 /root/growquiz/report.env && systemctl restart growquiz-report'
# 然后把新口令写进本机 secrets.properties 的 reportToken，重新构建发版
# 设备都升级完之后，再单独保留新口令重启一次
```

## growquiz-sync：更新同步

- `growquiz-sync.py`：把 GitHub 仓库最新 Release 的 APK 与发版说明拉到 `/root/nginx/web/growquiz/`（`latest.json` + APK），Caddy `:8443/growquiz/*` 反代到 nginx `:8088` 静态站点。
- `growquiz-sync.timer`：每 3 小时触发一次；手动：`systemctl start growquiz-sync`。
- 客户端每天检查一次 `<updateBase>/latest.json`，发现新版本自动下载安装。

## growquiz-report：兼容性报告接收（v1.14.0 起）

- `growquiz-report.py`：HTTP 服务监听 `127.0.0.1:8092`，Caddy `:8443/growquiz-report/*` 反代进来。校验 `X-Report-Token` 口令（与 APP 端 `reportToken` 一致，常量时间比较），单份上限 1 MiB。
- 口令来源：环境变量 `GROWQUIZ_REPORT_TOKEN`，其次 `/root/growquiz/report-token`；都没有则拒绝启动。
- 落盘：`/root/growquiz-reports/<设备ID>/<时间>.json`，另维护 `latest/<设备ID>.json` 便于速查。
- `growquiz-report.service`：常驻，`systemctl enable --now growquiz-report`。
- 报告内容：设备探针快照（品牌/固件/权限/TTS）、诊断事件（JSONL 字符串）、上次崩溃栈。设备以随机 UUID 标识。

## 部署

```sh
scp server/growquiz-*.* mylinux:/root/growquiz/
ssh mylinux 'cp /root/growquiz/growquiz-*.service /root/growquiz/growquiz-*.timer /etc/systemd/system/ \
  && systemctl daemon-reload && systemctl enable --now growquiz-sync.timer growquiz-report'
```

首次部署（或换了口令）前要先有 `report.env`，否则 `growquiz-report` 起不来 —— 见上文「私密配置不进仓库」。

## report-tool：报告处理标记（避免重复分析）

```sh
/root/growquiz/report-tool pending            # 列出未处理的报告（设备目录下、非 processed/、不含 latest/）
/root/growquiz/report-tool done <file...>     # 分析完归档到同目录 processed/ 子目录
```

处理约定：每份报告分析完（无论有无新问题）就 `report-tool done` 归档；下次只看 `pending` 的输出。目录结构：

```
/root/growquiz-reports/
  <设备ID>/
    20261109-101515.json          ← 未处理
    processed/20261109-101515.json ← 已分析归档
  latest/<设备ID>.json            ← 每台最新一份速查（不参与处理流程）
```
