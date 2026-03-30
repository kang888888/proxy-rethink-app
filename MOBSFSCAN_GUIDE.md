# mobsfscan 使用与日志查看指南

你在仓库根目录执行 `mobsfscan .` 后，通常会把扫描结果写到控制台。你当前这次的结果已保存为：`mobsfscan.log`（位于仓库根目录）。

---

## 1. 运行方式（本地wsl）

在仓库根目录执行：

```powershell
mobsfscan .
```

如果你希望把输出固定到文件，建议使用重定向（覆盖/追加按你的习惯调整）：

```powershell
mobsfscan . | Out-File -Encoding utf8 mobsfscan.log
```

> CI 工作流里同样会调用 mobsfscan，并在参数中使用 `--sarif --output results.sarif`（见 `.github/workflows/mobsf.yml`）。如果你本地也需要 SARIF，可尝试同类参数，但本指南的“查看方式”主要以 `mobsfscan.log` 为准。

---

## 2. 输出/报告结果在哪里？

本次扫描在仓库根目录生成的主要制品是：

- `mobsfscan.log`：完整扫描输出（包含规则表格、严重等级、命中的文件/位置等）

我在仓库中未发现本次额外生成的 `*.html` / `*.json` 报告文件；因此你需要直接阅读 `mobsfscan.log`。

---

## 3. `mobsfscan.log` 怎么读？

`mobsfscan.log` 的结构是“规则块（Rule Block）”拼接在一起。每个规则块里你会看到类似字段：

- `RULE ID`：规则编号（例如 `android_manifest_debugging_enabled`）
- `SEVERITY`：严重级别（`ERROR` / `WARNING` / `INFO`）
- `DESCRIPTION`：这条规则为什么会命中、风险点是什么
- `REFERENCE`：对应的参考链接（MobSF / OWASP MSTG）
- （有时还有）`FILES`：命中位置对应的源码文件路径、`Match Position`、以及可能的 `Match String`

查看“最需要先处理”的通常是 `SEVERITY = ERROR` 的规则块。

---

## 4. 快速定位要看的块（推荐）

### 4.1 先找 ERROR

```powershell
rg "SEVERITY\\s+│\\s+ERROR" mobsfscan.log
```

或用 PowerShell：

```powershell
Select-String -Path mobsfscan.log -Pattern "SEVERITY" | Select-Object -First 50
```

然后在光标/编辑器中直接搜索对应的 `RULE ID`，就能定位到整块规则内容（`DESCRIPTION / REFERENCE / FILES` 等）。

### 4.2 再看 WARNING

```powershell
rg "SEVERITY\\s+│\\s+WARNING" mobsfscan.log
```

---

## 5. 你这次日志里（`mobsfscan.log`）ERROR 规则有哪些？

根据你当前 `mobsfscan.log`，共有 `SEVERITY = ERROR` 的规则块 6 条。建议按下面顺序优先排查（每条规则的命中细节以你日志中的该 `RULE ID` 块为准）：

1. `android_kotlin_hiddenui`  
   - 风险：View 中隐藏元素可能导致数据对用户被“隐藏”，但仍可能被泄露
2. `android_task_hijacking1`  
   - 风险：Activity 的 `launchMode` 不符合要求（例如包含 `singleTask` 风险），可能导致 Task Hijacking / StrandHogg 1.0
3. `android_task_hijacking2`  
   - 风险：Activity 可被利用进行 StrandHogg 2.0 task hijacking（可通过 `singleInstance` 或 `taskAffinity=""` 修复，具体按日志描述）
4. `android_manifest_base_config_cleartext`  
   - 风险：允许 Cleartext HTTP 通信；生产环境应关闭（改用 HTTPS/禁用 cleartext）
5. `android_manifest_insecure_minsdk_error`  
   - 风险：支持 Android 6.0–6.0.1（API 23）；可能导致安装到无法获得足够安全更新的旧系统
6. `android_manifest_debugging_enabled`  
   - 风险：应用处于可调试（debugging enabled），会让逆向/调试更容易

---

## 6. 如何把“找到的规则”映射回代码？

当你定位到某个 `RULE ID` 后，重点看该块里的：

- `DESCRIPTION`：它具体指出应该改哪里（例如 Manifest 中某 flag、Activity launchMode、minSdk 等）
- `FILES`（如果有）：会给出实际命中的文件路径（例如 `app/src/.../AndroidManifest.xml`、某些 `*.kt`）
- `Match Position`/`Match String`：用于在文件中快速定位具体片段

如果你愿意，你可以把某个 `RULE ID` 对应的日志块（从 `RULE ID` 到下一个 `RULE ID` 之前）贴出来，我也可以帮你直接判断应该优先改哪个文件/怎么改。

