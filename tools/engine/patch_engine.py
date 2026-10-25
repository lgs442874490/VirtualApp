#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重建 tools/engine/lib-release.aar.patched（引擎二进制补丁 AAR）

背景
----
`app/sdk/lib-release.aar` 是厂商 4.2.10 正式引擎包（committed 原版，classes 数 3854，
含 WxMirrorFix / WechatWebFix / WxBindLog 等微信修复类）。
旧版 `tools/engine/lib-release.aar.patched` 是在**更早版本引擎**上打的补丁包，
classes 只有 3836 个（少了上述微信修复类），不能作为 4.2.10 的发版引擎。

本脚本的正确做法：**以 committed `app/sdk/lib-release.aar` 为基座**，只做两件事：

  1. 换入 patched 原生库 `jni/arm64-v8a/libvbox.so`
     （sha256 = 0dd16cb7f9dc5a2e2eac5ba6cda3c59aa42897754b3c5c0127e4f3f7b2666e39，
      committed 原版里的 libvbox.so 是 a0f06e48…，不是补丁 so）；
  2. 对 `classes.jar` 里两个 `.class` 打字节码补丁（见下）。

其余条目原样保留（解压后逐字节一致），classes 数保持 3854（=基座）。

两处字节码补丁
--------------
1) `com/lody/virtual/client/core/InvocationStubManager.class`
   - 模式定位（全文件唯一）：`b8 01 29 9a` = invokestatic BuildCompat.isTiramisu(); ifne
   - 改为：`03 00 00 9a` = iconst_0; nop; nop; ifne
   - 作用：Android 13+ 时 isTiramisu() 结果恒为 false，绕过 InvocationStubManager
     里 “SDK>=33 跳过注册” 的 guard，让 AutoFillManagerStub 代理重新注册。

2) `com/lody/virtual/client/hook/proxies/view/AutoFillManagerStub.class`
   - 模式定位（全文件唯一）：`b2 00 37 03 bd 00 39 b6 00 3f 4c`
     = inject() 的 code[4]: getstatic currentActivityThread; iconst_0;
       anewarray; invokevirtual call; astore_1
   - 改为：`a7 00 d3` + 原模式第 3 字节之后的内容 = goto +211（code offset 4 -> 215）
   - 作用：跳过 inject() 开头 getSystemService("autofill") + 反射私有 mService 的
     段落（注入时机太早时该段会 NPE，catch 后直接 return，导致代理一个都没挂上），
     直接跳到 code offset 215 的 `aload_0; new AutoFillManagerStub$1; ...;
     addMethodProxy(...)` 段。脚本会校验目标 offset 215 处确实是 `aload_0`(0x2a)。

两处补丁的定位依据与作用见 qa/evidence-20261025-clone-autofill-anr/README.md §8-9。

libvbox.so 来源
---------------
优先取 `--so-aar` / `--so-file` 指定来源；未指定时依次尝试：
  1. 现有 `tools/engine/lib-release.aar.patched`
  2. `git show HEAD:tools/engine/lib-release.aar.patched`
从 AAR 中取出 SO_ENTRY，并校验 sha256 == EXPECTED_SO_SHA256；找不到就报错。
（这样即使产物已被覆盖成新 AAR，仍可重跑；本次提交后 HEAD 里也是正确 so。）

用法
----
    python3 tools/engine/patch_engine.py                # 重建 + 校验 + 更新 SHA256SUMS
    python3 tools/engine/patch_engine.py --expect-classes 3854   # 额外校验 classes 数
    python3 tools/engine/patch_engine.py --check-only   # 不重建，只校验现有产物

输出：`tools/engine/lib-release.aar.patched` 与 `tools/engine/SHA256SUMS`。
脚本不 commit、不构建 APK、不改 `app/sdk/lib-release.aar`。

依赖：仅 python3 标准库（zipfile/hashlib/io/subprocess）。
"""

from __future__ import annotations

import argparse
import hashlib
import io
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BASE_AAR = REPO_ROOT / "app/sdk/lib-release.aar"
OUT_AAR = REPO_ROOT / "tools/engine/lib-release.aar.patched"
SHA256SUMS = REPO_ROOT / "tools/engine/SHA256SUMS"

CLASSES_JAR = "classes.jar"
SO_ENTRY = "jni/arm64-v8a/libvbox.so"
EXPECTED_SO_SHA256 = "0dd16cb7f9dc5a2e2eac5ba6cda3c59aa42897754b3c5c0127e4f3f7b2666e39"
AAR_BASENAME = "lib-release.aar.patched"

# 必须保留的微信修复类（committed 4.2.10 正式包自带的类，旧 patched AAR 曾缺失）
REQUIRED_CLASSES = [
    "mirror/WxMirrorFix.class",
    "com/carlos/common/reverse/wechat/WechatWebFix.class",
    "com/lody/virtual/client/hook/proxies/am/WxBindLog.class",
]

# 两处字节码补丁：entry / 定位模式 / 替换字节 / 打完后应出现的字节（相对模式起点）
PATCHES = [
    {
        "label": "InvocationStubManager: isTiramisu() 恒 false（解除 Android 13+ 注册 guard）",
        "entry": "com/lody/virtual/client/core/InvocationStubManager.class",
        "find": bytes.fromhex("b801299a"),
        "replacement": bytes.fromhex("0300009a"),
        "expect": [
            (0, bytes.fromhex("030000")),  # iconst_0; nop; nop
            (3, bytes.fromhex("9a")),      # ifne 保留，但栈上为 0 -> 不跳转
        ],
    },
    {
        "label": "AutoFillManagerStub.inject(): goto 215（跳过会 NPE 的 getSystemService 段）",
        "entry": "com/lody/virtual/client/hook/proxies/view/AutoFillManagerStub.class",
        "find": bytes.fromhex("b2003703bd0039b6003f4c"),
        # goto +211：goto 指令在 code offset 4，目标 code offset 215
        "replacement": bytes.fromhex("a700d3")
        + bytes.fromhex("b2003703bd0039b6003f4c")[3:],
        "expect": [
            (0, bytes.fromhex("a700d3")),       # goto
            (215 - 4, bytes.fromhex("2a")),     # 目标 215 = aload_0（addMethodProxy 段入口）
        ],
    },
]


# ---------------------------------------------------------------- 通用小工具


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def git_show(rev_path: str):
    """读取 git 对象内容（bytes）；失败返回 None。"""
    try:
        proc = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "show", rev_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=True,
        )
        return proc.stdout
    except (OSError, subprocess.CalledProcessError):
        return None


def class_entries(classes_jar_bytes: bytes):
    with zipfile.ZipFile(io.BytesIO(classes_jar_bytes)) as zf:
        return [i.filename for i in zf.infolist()]


def count_classes(classes_jar_bytes: bytes) -> int:
    return sum(1 for n in class_entries(classes_jar_bytes) if n.endswith(".class"))


def find_all(haystack: bytes, needle: bytes):
    positions = []
    start = 0
    while True:
        pos = haystack.find(needle, start)
        if pos < 0:
            return positions
        positions.append(pos)
        start = pos + 1


# ---------------------------------------------------------------- 字节码补丁


def apply_patch(class_bytes: bytes, patch: dict):
    """按模式定位并修改一个 .class；返回 (新字节, 命中偏移)。"""
    positions = find_all(class_bytes, patch["find"])
    if len(positions) != 1:
        raise RuntimeError(
            f"{patch['entry']}: 模式 {patch['find'].hex()} 命中 {len(positions)} 次（期望 1 次）。"
            f"基座 AAR 可能不是 committed 原版，或已被打过补丁。"
        )
    pos = positions[0]
    out = bytearray(class_bytes)
    out[pos : pos + len(patch["replacement"])] = patch["replacement"]
    for rel, expected in patch["expect"]:
        actual = bytes(out[pos + rel : pos + rel + len(expected)])
        if actual != expected:
            raise RuntimeError(
                f"{patch['entry']}: 偏移 {pos}+{rel} 期望 {expected.hex()} 实际 {actual.hex()}"
            )
    print(f"  [patch] {patch['entry']}")
    print(f"          模式 {patch['find'].hex()} @ {pos} -> {patch['replacement'].hex()}")
    print(f"          作用：{patch['label']}")
    return bytes(out), pos


def patch_classes_jar(classes_jar_bytes: bytes):
    """在 classes.jar 内按模式打两处补丁；其余条目原样重打包。"""
    patches_by_entry = {p["entry"]: p for p in PATCHES}
    with zipfile.ZipFile(io.BytesIO(classes_jar_bytes)) as zin:
        infos = zin.infolist()
        missing = set(patches_by_entry) - {i.filename for i in infos}
        if missing:
            raise RuntimeError(f"classes.jar 缺少目标类：{sorted(missing)}")
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in infos:
                data = zin.read(info.filename)
                patch = patches_by_entry.get(info.filename)
                if patch is not None:
                    data, _ = apply_patch(data, patch)
                zi = zipfile.ZipInfo(info.filename, info.date_time)
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                zi.internal_attr = info.internal_attr
                zi.create_system = info.create_system
                zi.comment = info.comment
                zi.extra = info.extra
                zout.writestr(zi, data)
    return out.getvalue()


# ---------------------------------------------------------------- so 来源


def find_patched_so(args):
    """找到含补丁 libvbox.so 的 AAR，返回 so 字节。"""
    candidates = []
    if args.so_file:
        so = Path(args.so_file).read_bytes()
        if sha256_bytes(so) != EXPECTED_SO_SHA256:
            raise SystemExit(f"--so-file {args.so_file} 的 sha256 不是 {EXPECTED_SO_SHA256}")
        return so, f"--so-file {args.so_file}"
    if args.so_aar:
        candidates.append((f"--so-aar {args.so_aar}", Path(args.so_aar).read_bytes()))
    if OUT_AAR.exists():
        candidates.append((str(OUT_AAR), OUT_AAR.read_bytes()))
    head_blob = git_show(f"HEAD:tools/engine/{AAR_BASENAME}")
    if head_blob:
        candidates.append((f"git HEAD:tools/engine/{AAR_BASENAME}", head_blob))
    if BASE_AAR.exists():
        candidates.append((str(BASE_AAR), BASE_AAR.read_bytes()))

    for label, blob in candidates:
        try:
            with zipfile.ZipFile(io.BytesIO(blob)) as zf:
                so = zf.read(SO_ENTRY)
        except (zipfile.BadZipFile, KeyError):
            continue
        if sha256_bytes(so) == EXPECTED_SO_SHA256:
            print(f"  libvbox.so 来源：{label}")
            return so, label
    raise SystemExit(
        "找不到含补丁 libvbox.so（sha256 0dd16cb7…）的 AAR。\n"
        "旧 patched AAR 可从历史提交取：\n"
        "  git show HEAD~N:tools/engine/lib-release.aar.patched > /tmp/old_patched.aar\n"
        "再执行：python3 tools/engine/patch_engine.py --so-aar /tmp/old_patched.aar"
    )


# ---------------------------------------------------------------- 构建 / 校验


def build_aar(base_bytes: bytes, classes_jar_bytes: bytes, so_bytes: bytes) -> bytes:
    """以 committed AAR 为基座，只替换 classes.jar 与 libvbox.so，保持条目顺序。"""
    with zipfile.ZipFile(io.BytesIO(base_bytes)) as zin:
        infos = zin.infolist()
        if SO_ENTRY not in {i.filename for i in infos}:
            raise RuntimeError(f"基座 AAR 中不存在 {SO_ENTRY}")
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in infos:
                if info.filename == CLASSES_JAR:
                    data = classes_jar_bytes
                elif info.filename == SO_ENTRY:
                    data = so_bytes
                else:
                    data = zin.read(info.filename)
                zi = zipfile.ZipInfo(info.filename, info.date_time)
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                zi.internal_attr = info.internal_attr
                zi.create_system = info.create_system
                zi.comment = info.comment
                zi.extra = info.extra
                zout.writestr(zi, data)
    return out.getvalue()


def verify_aar(base_bytes: bytes, out_bytes: bytes, expect_classes):
    """校验新 AAR：条目集合/顺序、除 classes.jar+so 外逐字节一致、classes 数、补丁。"""
    with zipfile.ZipFile(io.BytesIO(base_bytes)) as bz, zipfile.ZipFile(io.BytesIO(out_bytes)) as oz:
        base_infos = bz.infolist()
        out_infos = oz.infolist()
        base_names = [i.filename for i in base_infos]
        out_names = [i.filename for i in out_infos]
        assert base_names == out_names, "AAR 条目集合/顺序与基座不一致"
        print(f"  [ok] AAR 条目数 {len(out_names)}，集合与顺序同基座")

        allowed = {CLASSES_JAR, SO_ENTRY}
        changed = []
        for name in base_names:
            if name in allowed:
                continue
            if bz.read(name) != oz.read(name):
                changed.append(name)
        assert not changed, f"除 classes.jar / libvbox.so 外有条目被改动：{changed}"
        print(f"  [ok] 除 classes.jar / libvbox.so 外其余 {len(base_names) - 2} 个条目解压后逐字节一致")

        base_classes = bz.read(CLASSES_JAR)
        out_classes = oz.read(CLASSES_JAR)
        n_base = count_classes(base_classes)
        n_out = count_classes(out_classes)
        assert n_base == n_out, f"classes 数变化：基座 {n_base} -> 产物 {n_out}"
        if expect_classes is not None:
            assert n_out == expect_classes, f"classes 数 {n_out} != 期望 {expect_classes}"
        print(f"  [ok] classes 数 = {n_out}（基座 {n_base}）")

        # 逐个 class 对比：只允许两处补丁类不同，且差异内容符合预期
        with zipfile.ZipFile(io.BytesIO(base_classes)) as bcj, zipfile.ZipFile(
            io.BytesIO(out_classes)
        ) as ocj:
            b_map = {i.filename: bcj.read(i.filename) for i in bcj.infolist()}
            o_map = {i.filename: ocj.read(i.filename) for i in ocj.infolist()}
            assert list(b_map) == list(o_map), "classes.jar 条目集合/顺序不一致"
            patch_entries = {p["entry"]: p for p in PATCHES}
            unexpected = []
            for name, data in b_map.items():
                if name in patch_entries:
                    continue
                if o_map[name] != data:
                    unexpected.append(name)
            assert not unexpected, f"非补丁类被改动：{unexpected[:5]}"
            for name, patch in patch_entries.items():
                orig, patched, pos = None, o_map[name], None
                # 用原字节重新打一遍补丁，确认产物与该结果完全一致
                orig = b_map[name]
                replayed, pos = apply_patch(orig, patch)
                assert patched == replayed, f"{name} 补丁结果与重放不一致"
            print(f"  [ok] classes.jar 仅 {len(patch_entries)} 个补丁类不同，内容与补丁重放一致")

            names = set(o_map)
        for required in REQUIRED_CLASSES:
            assert required in names, f"缺少微信修复类 {required}"
        print("  [ok] 微信修复类存在：" + ", ".join(REQUIRED_CLASSES))

        so = oz.read(SO_ENTRY)
        so_sha = sha256_bytes(so)
        assert so_sha == EXPECTED_SO_SHA256, f"libvbox.so sha256={so_sha} != {EXPECTED_SO_SHA256}"
        print(f"  [ok] libvbox.so sha256 = {so_sha}")

    return n_out


def update_sums(digest: str):
    line = f"{digest}  {AAR_BASENAME}"
    lines = []
    if SHA256SUMS.exists():
        lines = SHA256SUMS.read_text(encoding="utf-8").splitlines()
    replaced = False
    for i, old in enumerate(lines):
        fields = old.split()
        if fields and fields[-1].lstrip("*") == AAR_BASENAME:
            lines[i] = line
            replaced = True
    if not replaced:
        lines.append(line)
    SHA256SUMS.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"  [ok] 已更新 {SHA256SUMS} -> {digest}")


# ---------------------------------------------------------------- main


def main() -> int:
    parser = argparse.ArgumentParser(description="重建 tools/engine/lib-release.aar.patched")
    parser.add_argument("--so-aar", help="含补丁 libvbox.so 的旧 AAR")
    parser.add_argument("--so-file", help="直接指定补丁 libvbox.so 文件")
    parser.add_argument(
        "--expect-classes", type=int, default=None, help="断言产物 classes 数（4.2.10 = 3854）"
    )
    parser.add_argument("--check-only", action="store_true", help="只校验现有产物，不重建")
    args = parser.parse_args()

    if not BASE_AAR.exists():
        raise SystemExit(f"找不到基座 AAR：{BASE_AAR}")
    base_bytes = BASE_AAR.read_bytes()
    base_sha = sha256_bytes(base_bytes)
    print(f"基座 AAR：{BASE_AAR}")
    print(f"  sha256 = {base_sha}")

    if args.check_only:
        if not OUT_AAR.exists():
            raise SystemExit(f"找不到产物：{OUT_AAR}")
        out_bytes = OUT_AAR.read_bytes()
        n = verify_aar(base_bytes, out_bytes, args.expect_classes)
        digest = sha256_bytes(out_bytes)
        print(f"产物 AAR：{OUT_AAR} size={len(out_bytes)} sha256={digest} classes={n}")
        expected_line = f"{digest}  {AAR_BASENAME}"
        assert SHA256SUMS.exists() and expected_line in SHA256SUMS.read_text(encoding="utf-8"), (
            "SHA256SUMS 未记录产物 sha256，请去掉 --check-only 重建"
        )
        print("  [ok] SHA256SUMS 与产物一致")
        return 0

    # 1) 读取基座 classes.jar
    with zipfile.ZipFile(io.BytesIO(base_bytes)) as bz:
        base_classes = bz.read(CLASSES_JAR)
    n_base = count_classes(base_classes)
    print(f"  基座 classes 数 = {n_base}")
    if args.expect_classes is not None and n_base != args.expect_classes:
        raise SystemExit(f"基座 classes 数 {n_base} != --expect-classes {args.expect_classes}")

    # 2) 找补丁 so
    print("定位补丁 libvbox.so：")
    so_bytes, so_source = find_patched_so(args)

    # 3) 打字节码补丁
    print("打字节码补丁：")
    patched_classes = patch_classes_jar(base_classes)

    # 4) 重建 AAR
    print("重建 AAR：")
    out_bytes = build_aar(base_bytes, patched_classes, so_bytes)
    if OUT_AAR.exists() and OUT_AAR.read_bytes() == out_bytes:
        print("  [i] 产物已是最新（重建结果与现有文件逐字节一致）")
    print(f"  产物 size={len(out_bytes)}")

    # 5) 全面校验
    print("校验产物：")
    n_out = verify_aar(base_bytes, out_bytes, args.expect_classes)

    # 6) 落盘（先写临时文件再原子替换）并更新 SHA256SUMS
    OUT_AAR.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=".patch_engine.", dir=str(OUT_AAR.parent))
    os.close(fd)
    try:
        with open(tmp_path, "wb") as f:
            f.write(out_bytes)
        os.replace(tmp_path, OUT_AAR)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
    digest = sha256_file(OUT_AAR)
    update_sums(digest)
    print(f"\n产物：{OUT_AAR}")
    print(f"  classes = {n_out}")
    print(f"  sha256  = {digest}")
    print(f"  so 来源 = {so_source}")
    print("\n下一步（Lead 统一执行）：真机构建/验证按 tools/release.sh 门禁进行；" "本脚本不 commit。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
