#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""极简 GLM 编码 Agent（BigModel OpenAI-compatible tool-calling loop）。

用法:
  GLM_MODEL=glm-5.3-flash python3 agent.py --cwd <repo> --task-file <prompt.md> [--max-steps 30]

- 开发模型: glm-5.3-flash；测试模型: glm-5.3
- 工具: bash/read/write/edit/glob/grep
- 安全: 禁 git 写操作/危险命令；文件读写限制在 cwd 内
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import urllib.error
import ssl
import fnmatch
import glob as pyglob

BASE = os.environ.get("GLM_BASE_URL", "https://open.bigmodel.cn/api/coding/paas/v4")
KEY = os.environ.get("GLM_API_KEY", "")
MODEL = os.environ.get("GLM_MODEL", "glm-5.3-flash")
MAX_OUTPUT_CHARS = 12000
HTTP_TIMEOUT = 600

BLOCKED_BASH = [
    r"git\s+commit", r"git\s+push", r"git\s+merge", r"git\s+reset", r"git\s+checkout",
    r"git\s+switch", r"git\s+branch\s+-D", r"git\s+clean", r"git\s+stash",
    r"rm\s+-rf\s+/", r"sudo\b", r"shutdown", r"reboot", r"kill\s+-9", r"pkill\b",
    r"npm\s+install", r"pip\s+install", r"brew\s+install", r"gradle\s+publish",
]

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "bash",
            "description": "在仓库目录执行 shell 命令（构建/测试/grep 等）。禁止 git 写操作。",
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {"type": "string"},
                    "timeout_sec": {"type": "integer", "default": 300},
                },
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read",
            "description": "读取仓库内文本文件（带行号，最多 2000 行/次）。",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "offset": {"type": "integer", "default": 1},
                    "limit": {"type": "integer", "default": 400},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write",
            "description": "创建或整体覆盖仓库内文本文件（覆盖前必须先 read 该文件）。",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
                "required": ["path", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "edit",
            "description": "把文件中唯一出现的 old_string 替换为 new_string。",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "old_string": {"type": "string"},
                    "new_string": {"type": "string"},
                },
                "required": ["path", "old_string", "new_string"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "glob",
            "description": "按 glob 模式查找仓库内文件（如 **/*.java）。",
            "parameters": {
                "type": "object",
                "properties": {"pattern": {"type": "string"}},
                "required": ["pattern"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "grep",
            "description": "在仓库内按正则搜索文件内容，返回匹配行（最多 200 行）。",
            "parameters": {
                "type": "object",
                "properties": {
                    "pattern": {"type": "string"},
                    "path": {"type": "string", "default": "."},
                    "include": {"type": "string", "default": "*.java"},
                },
                "required": ["pattern"],
            },
        },
    },
]

SYSTEM = """你是资深 Java/Android/服务端工程师，在一个真实生产仓库中执行编码任务。
工作规则（必须遵守）：
1. 只用工具改代码；先 read 再 edit/write；小步修改、边改边验证。
2. 严格限定在任务给出的文件/目录范围内，不要顺手重构无关代码。
3. 禁止任何 git 写操作（commit/push/merge/checkout/reset/stash/clean）；由 Lead 负责提交。
4. App 源树 `app/src/main/java` 使用 Java 8 语法（无 record/var/List.of/Map.of/Set.of；可用 lambda）；
   `com/carlos/vault/wjdr/**` 与 `com/carlos/vault/daily/gather/**` 是 App/Server 共享源码，也必须 Java 8。
5. 构建/测试环境：`export JAVA_HOME=/Users/liangguansheng/java/jdk-17/Contents/Home`；
   App 单测：`./gradlew :app:testDebugUnitTest --offline --tests "..."`；
   Server：`cd vault-server && ./gradlew test --offline --tests "..."`（或 compileJava）。
6. 不要改 `VAConfig.gradle`、许可证/支付/发布配置；不要执行部署/联网安装。
7. 完成后最终消息只报告：改动文件清单（含行数）、关键实现点、执行的测试命令与结果、未决风险。
   不要粘贴大段代码。
8. 如果任务无法完成或需要 Lead 决策，明确写出阻塞原因，不要假装成功。
"""


def log(msg):
    print(msg, flush=True)


def http_chat(messages, tools):
    payload = {
        "model": MODEL,
        "messages": messages,
        "tools": tools,
        "tool_choice": "auto",
        "temperature": 0.15,
        "max_tokens": 8192,
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        BASE.rstrip("/") + "/chat/completions",
        data=data,
        headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
        method="POST",
    )
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT, context=ssl._create_unverified_context()) as r:
                body = json.loads(r.read().decode("utf-8"))
            return body
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "ignore")[:500]
            log(f"[http] {e.code} attempt={attempt+1} {detail}")
            time.sleep(3 * (attempt + 1))
        except Exception as e:
            log(f"[http] error attempt={attempt+1}: {e}")
            time.sleep(3 * (attempt + 1))
    raise RuntimeError("GLM API 调用失败（重试 3 次）")


def safe_path(cwd, p):
    if not p:
        return None
    full = os.path.realpath(os.path.join(cwd, p) if not os.path.isabs(p) else p)
    root = os.path.realpath(cwd)
    if not (full == root or full.startswith(root + os.sep)):
        raise ValueError(f"路径越界（仅允许仓库内）：{p}")
    return full


def truncate(s, n=MAX_OUTPUT_CHARS):
    s = s if s is not None else ""
    if len(s) <= n:
        return s
    return s[: n // 2] + f"\n...（截断 {len(s) - n} 字符）...\n" + s[-n // 2:]


def run_bash(cwd, command, timeout_sec=300):
    for pat in BLOCKED_BASH:
        if re.search(pat, command):
            return f"[拒绝] 命令命中安全黑名单：{pat}"
    env = dict(os.environ)
    env.setdefault("JAVA_HOME", "/Users/liangguansheng/java/jdk-17/Contents/Home")
    try:
        p = subprocess.run(
            command, shell=True, cwd=cwd, capture_output=True, text=True,
            timeout=max(5, min(int(timeout_sec), 900)), env=env,
        )
        out = (p.stdout or "") + (("\n[stderr]\n" + p.stderr) if p.stderr else "")
        return f"[exit {p.returncode}]\n" + truncate(out)
    except subprocess.TimeoutExpired:
        return f"[timeout] 命令超过 {timeout_sec}s"
    except Exception as e:
        return f"[error] {e}"


def exec_tool(cwd, name, args):
    try:
        if name == "bash":
            return run_bash(cwd, str(args.get("command", "")), args.get("timeout_sec", 300))
        if name == "read":
            path = safe_path(cwd, args.get("path"))
            off = max(1, int(args.get("offset", 1)))
            lim = max(1, min(int(args.get("limit", 400)), 2000))
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
            chunk = lines[off - 1: off - 1 + lim]
            numbered = "".join(f"{i+off:5d}| {ln}" for i, ln in enumerate(chunk))
            return truncate(numbered) + f"\n(共 {len(lines)} 行)"
        if name == "write":
            path = safe_path(cwd, args.get("path"))
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(str(args.get("content", "")))
            return f"[ok] 已写入 {args.get('path')}（{len(str(args.get('content','')))} 字符）"
        if name == "edit":
            path = safe_path(cwd, args.get("path"))
            with open(path, "r", encoding="utf-8") as f:
                s = f.read()
            old = str(args.get("old_string", ""))
            new = str(args.get("new_string", ""))
            cnt = s.count(old)
            if cnt != 1:
                return f"[拒绝] old_string 出现 {cnt} 次（必须恰好 1 次）"
            with open(path, "w", encoding="utf-8") as f:
                f.write(s.replace(old, new, 1))
            return f"[ok] 已编辑 {args.get('path')}"
        if name == "glob":
            pattern = str(args.get("pattern", "*"))
            hits = []
            for root, dirs, files in os.walk(cwd):
                dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", "node_modules")]
                for fn in files:
                    rel = os.path.relpath(os.path.join(root, fn), cwd)
                    if fnmatch.fnmatch(rel, pattern) or fnmatch.fnmatch(fn, pattern):
                        hits.append(rel)
                        if len(hits) >= 200:
                            break
                if len(hits) >= 200:
                    break
            return "\n".join(sorted(hits)[:200]) or "(无匹配)"
        if name == "grep":
            pattern = str(args.get("pattern", ""))
            path = args.get("path", ".") or "."
            include = args.get("include", "*.java") or "*.java"
            base = safe_path(cwd, path)
            hits = []
            files = [base] if os.path.isfile(base) else []
            if not files:
                for root, dirs, fs in os.walk(base):
                    dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", "node_modules")]
                    for fn in fs:
                        if fnmatch.fnmatch(fn, include):
                            files.append(os.path.join(root, fn))
            rx = re.compile(pattern)
            for fp in files:
                try:
                    with open(fp, "r", encoding="utf-8", errors="replace") as f:
                        for i, line in enumerate(f, 1):
                            if rx.search(line):
                                hits.append(f"{os.path.relpath(fp, cwd)}:{i}: {line.rstrip()[:300]}")
                                if len(hits) >= 200:
                                    break
                except Exception:
                    pass
                if len(hits) >= 200:
                    break
            return "\n".join(hits) or "(无匹配)"
        return f"[error] 未知工具 {name}"
    except Exception as e:
        return f"[error] {e}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cwd", required=True)
    ap.add_argument("--task-file", required=True)
    ap.add_argument("--max-steps", type=int, default=30)
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    cwd = os.path.realpath(args.cwd)
    task = open(args.task_file, "r", encoding="utf-8").read()
    run_id = time.strftime("%Y%m%d-%H%M%S")
    out_dir = args.out or f"/tmp/glm_agent/{run_id}"
    os.makedirs(out_dir, exist_ok=True)
    transcript = os.path.join(out_dir, "transcript.jsonl")

    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": task},
    ]
    log(f"=== GLM agent model={MODEL} cwd={cwd} out={out_dir} ===")
    with open(transcript, "a", encoding="utf-8") as tf:
        tf.write(json.dumps({"role": "user", "content": task}, ensure_ascii=False) + "\n")

    final_text = ""
    for step in range(1, args.max_steps + 1):
        log(f"--- step {step} ---")
        body = http_chat(messages, TOOLS)
        msg = body.get("choices", [{}])[0].get("message", {})
        content = msg.get("content") or ""
        tool_calls = msg.get("tool_calls") or []
        reasoning = msg.get("reasoning_content") or ""
        with open(transcript, "a", encoding="utf-8") as tf:
            tf.write(json.dumps({"step": step, "assistant": msg}, ensure_ascii=False) + "\n")
        if reasoning:
            log("[thinking] " + truncate(reasoning, 400).replace("\n", " "))
        if content:
            log("[assistant] " + truncate(content, 1200))
        if not tool_calls:
            final_text = content
            log("=== 完成（无工具调用）===")
            break
        # 追加 assistant 消息（保留 tool_calls）
        messages.append({"role": "assistant", "content": content, "tool_calls": tool_calls})
        for tc in tool_calls:
            fn = tc.get("function", {})
            name = fn.get("name")
            try:
                targs = json.loads(fn.get("arguments") or "{}")
            except Exception:
                targs = {}
            log(f"[tool] {name} {json.dumps(targs, ensure_ascii=False)[:300]}")
            result = exec_tool(cwd, name, targs)
            with open(transcript, "a", encoding="utf-8") as tf:
                tf.write(json.dumps({"step": step, "tool": name, "args": targs,
                                     "result": truncate(result, 3000)}, ensure_ascii=False) + "\n")
            messages.append({
                "role": "tool",
                "tool_call_id": tc.get("id", ""),
                "content": truncate(result),
            })
    else:
        log("=== 达到最大步数，未完成 ===")
        final_text = "(达到最大步数)"

    with open(os.path.join(out_dir, "final.txt"), "w", encoding="utf-8") as f:
        f.write(final_text or "")
    log("=== FINAL ===")
    log(final_text or "(空)")


if __name__ == "__main__":
    main()
