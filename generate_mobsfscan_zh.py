import re
from pathlib import Path
from collections import Counter


SRC = Path("E:/rethink-app/mobsfscan.log")
OUT = Path("E:/rethink-app/mobsfscan-zh.log")


def uniq_keep(seq):
    seen = set()
    out = []
    for x in seq:
        if x not in seen:
            seen.add(x)
            out.append(x)
    return out


CN = {
    "hardcoded_api_key": "检测到硬编码的 API Key/密钥。建议避免将密钥直接写入代码或资源，改为使用环境变量/服务端下发/安全存储，并确保不出现在提交内容中。",
    "sha1_hash": "检测到使用 SHA1 哈希算法。SHA1 存在碰撞风险，建议改用 SHA-256/更强算法，并检查相关验证逻辑。",

    "android_logging": "应用会输出日志。需要确保日志中不包含敏感信息（密钥、账号、隐私数据等），尤其是生产环境与调试日志开关。",
    "android_kotlin_logging": "Kotlin 代码中存在日志相关风险点。建议避免记录敏感数据，必要时对日志做脱敏/关闭调试输出。",

    "android_kotlin_hardcoded": "Kotlin 代码疑似存在硬编码敏感信息/密钥。建议移除硬编码内容，改用安全配置来源，并审查同类代码与构建产物。",
    "android_kotlin_hiddenui": "存在隐藏 UI 元素的风险。隐藏内容可能被用于欺骗用户或造成信息泄露，需核查 UI 逻辑与显示/遮挡策略。",
    "android_kotlin_sql_raw_query": "使用 raw SQL 查询。若拼接不可信输入，可能导致 SQL 注入或数据越权，建议使用参数化查询并进行输入校验。",

    "android_manifest_missing_explicit_allow_backup": "未显式禁用 `android:allowBackup`。默认值可能允许通过 `adb` 备份应用数据，生产环境建议显式设置为 `false`。",
    "android_manifest_allow_backup": "`android:allowBackup` 未被禁用，可能允许通过 `adb` 备份应用数据。建议在发布配置中将其设为 `false`。",

    "android_manifest_base_config_cleartext": "配置允许明文 HTTP（cleartext）。生产环境建议禁用 cleartext，改为仅使用 HTTPS，并检查 Network Security Config。",
    "android_manifest_insecure_minsdk_error": "最小支持版本过低（日志显示可安装到更旧系统）。这些系统可能缺少足够的安全更新。建议抬高 minSdk 或为旧版本提供额外安全兜底。",
    "android_manifest_debugging_enabled": "应用处于可调试状态（debuggable=true）。会显著增加逆向/注入攻击难度，发布版应关闭。",

    "android_task_hijacking1": "发现 Task Hijacking/StrandHogg 相关风险点（launchMode/taskAffinity 配置不安全）。建议按描述调整 launchMode（如 singleInstance）或设置空 taskAffinity。",
    "android_task_hijacking2": "发现 StrandHogg 2.0 task hijacking 风险点。建议按描述调整 launchMode 与 taskAffinity，必要时提升 targetSdk。",

    "android_root_detection": "未发现 root 检测能力。若应用依赖设备完整性（安全关键功能），可考虑加入合规的完整性校验，但要注意兼容性与误判。",
    "android_tapjacking": "未发现防 Tapjacking 能力（如对 overlay/点击劫持的防护）。建议实现并启用防护策略，避免恶意覆盖诱导点击。",
    "android_safetynet": "未使用 SafetyNet Attestation（或类似完整性校验）。这会降低服务端验证设备真实性的能力。",
    "android_certificate_transparency": "未强制 TLS 证书透明度（CT）。这会影响检测错误签发或恶意获取的证书。",
    "android_prevent_screenshot": "未具备防截图能力（如从最近任务/Now on Tap 截屏等）。对敏感界面建议启用 FLAG_SECURE 等策略。",
}


def main():
    if not SRC.exists():
        raise SystemExit(f"找不到源文件: {SRC}")

    text = SRC.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()

    # 按 RULE ID 切分规则块（一个 RULE ID 对应一个块）
    blocks = []
    cur = None
    rule_line_pat = re.compile(r"RULE ID\s+│\s*([^\s│]+)")

    for line in lines:
        m = rule_line_pat.search(line)
        if m:
            if cur is not None:
                blocks.append(cur)
            cur = {"rule_id": m.group(1), "lines": [line]}
        else:
            if cur is not None:
                cur["lines"].append(line)
    if cur is not None:
        blocks.append(cur)

    sev_pat = re.compile(r"SEVERITY\s+│\s*([A-Z]+)")
    desc_pat = re.compile(r"DESCRIPTION\s+│\s*(.*?)\s*│\s*$")
    ref_pat = re.compile(r"REFERENCE\s+│\s*(.*?)\s*│\s*$")
    file_pat = re.compile(r"│\s*│\s*File\s*│\s*([^│]+?)\s*│")
    match_pos_pat = re.compile(r"Match Position\s+│\s*([^│]+?)\s*│")
    match_str_pat = re.compile(r"Match String\s+│\s*([^│]+?)\s*│")

    parsed = []
    for b in blocks:
        rule_id = b["rule_id"]
        blk_text = "\n".join(b["lines"])

        sev = None
        m = sev_pat.search(blk_text)
        if m:
            sev = m.group(1)

        desc = None
        md = re.search(r"DESCRIPTION\s+│\s*(.*?)\s*│\s*$", blk_text, flags=re.MULTILINE)
        if md:
            desc = md.group(1).strip()

        ref = None
        mr = re.search(r"REFERENCE\s+│\s*(.*?)\s*│\s*$", blk_text, flags=re.MULTILINE)
        if mr:
            ref = mr.group(1).strip()

        files = []
        match_positions = []
        match_strings = []
        for line in b["lines"]:
            mf = file_pat.search(line)
            if mf:
                files.append(mf.group(1).strip())
            mp = match_pos_pat.search(line)
            if mp:
                match_positions.append(mp.group(1).strip())
            ms = match_str_pat.search(line)
            if ms:
                match_strings.append(ms.group(1).strip())

        parsed.append(
            {
                "rule_id": rule_id,
                "severity": sev or "UNKNOWN",
                "cn": CN.get(rule_id, "（暂无内置中文解读，将以原始英文描述为准。）"),
                "desc": desc or "",
                "ref": ref or "",
                "files": uniq_keep(files),
                "match_positions": uniq_keep(match_positions),
                "match_strings": uniq_keep(match_strings),
            }
        )

    cnt = Counter(p["severity"] for p in parsed)

    order = ["ERROR", "WARNING", "INFO", "UNKNOWN"]
    by_sev = {k: [p for p in parsed if p["severity"] == k] for k in order}

    out_lines = []
    out_lines.append("# mobsfscan 中文安全报告")
    out_lines.append("")
    out_lines.append(f"- 原始日志: `{SRC.name}`")
    out_lines.append(f"- 规则块数: {len(parsed)}")
    out_lines.append(f"- 严重级别统计: {dict(cnt)}")
    out_lines.append("")

    for sev in order:
        group = by_sev.get(sev, [])
        if not group:
            continue
        out_lines.append(f"## {sev}（{len(group)} 条）")
        out_lines.append("")
        for p in group:
            out_lines.append(f"### {p['rule_id']}")
            out_lines.append(f"- 严重性: {p['severity']}")
            out_lines.append(f"- 中文解读: {p['cn']}")
            if p["desc"]:
                out_lines.append(f"- 原始描述: {p['desc']}")
            if p["ref"]:
                out_lines.append(f"- 参考: {p['ref']}")

            if p["files"]:
                out_lines.append("- 命中文件（最多显示前 10 个）:")
                for f in p["files"][:10]:
                    out_lines.append(f"  - {f}")
                if len(p["files"]) > 10:
                    out_lines.append(f"  - ...（共 {len(p['files'])} 个）")

            if p["match_strings"]:
                show = p["match_strings"][:5]
                out_lines.append(f"- 命中 Match String（最多显示前 5 个）: {', '.join(show)}")
                if len(p["match_strings"]) > 5:
                    out_lines.append(f"  - ...（共 {len(p['match_strings'])} 个）")
            out_lines.append("")

    OUT.write_text("\n".join(out_lines), encoding="utf-8")
    print(f"已生成: {OUT}")
    print(f"规则块: {len(parsed)}")
    print(f"严重级别统计: {dict(cnt)}")


if __name__ == "__main__":
    main()

