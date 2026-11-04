#!/bin/bash
# 发布包门禁校验：对单个 APK 逐项检查，任一失败即非零退出。
# 用法：tools/verify_apk.sh <apk路径> [期望versionCode] [期望versionName]
#
# 检查项（全部来自 qa/修复计划-20261018.md P0-1 与 MEMORY.md 的判据）：
#   1) lib/arm64-v8a/libvbox.so sha256 == 补丁引擎（Android 14+ renameat2 修复），
#      仓库 app/sdk/lib-release.aar 是原版未打补丁，直接构建必挂此项
#   2) v1/v2 签名证书 SHA-256 == scorpion.jks（CN=scorpion），保证可覆盖安装
#   3) versionCode/versionName 与 VAConfig.gradle 一致（可选传参覆盖）
#   4) GameKeepAliveService 无 android:process（必须在宿主主进程，防 FGS 5 秒超时回归）
#   5) 声明 POST_NOTIFICATIONS（Android 13+ 前台服务通知）
#   6) autofill 字节码补丁已进包：InvocationStubManager.injectInternal 里
#      `new-instance ...AutoFillManagerStub;` 之前是 `const/4`（补丁版），
#      而不是 `invoke-static BuildCompat.isTiramisu()`（未打补丁）。
#      未打补丁 ⇒ 容器内 autofill startSession 5s 死等 ⇒ Input dispatch ANR
#      ⇒ 分身闪退（2026-11-04 事故，已装 APK sha256 c6b725bb… 就是这种包）。
set -euo pipefail

APK="${1:-}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "[verify] 未找到 APK：$APK" >&2
  exit 2
fi

EXPECT_SO_SHA256="0dd16cb7f9dc5a2e2eac5ba6cda3c59aa42897754b3c5c0127e4f3f7b2666e39"
EXPECT_CERT_SHA256="5022610a197e4ec99cf11a210203ffaee24e2ee2638b4eb87feaee2e97e3de1e"
EXPECT_VC="${2:-}"
EXPECT_VN="${3:-}"

# 取最新 build-tools（aapt2 / apksigner）
BT="$(ls -d "$HOME/Library/Android/sdk/build-tools"/* 2>/dev/null | sort -V | tail -1)"
if [ -z "$BT" ]; then
  echo "[verify] 未找到 Android build-tools（需要 aapt2/apksigner）" >&2
  exit 2
fi
AAPT2="$BT/aapt2"
APKSIGNER="$BT/apksigner"

fail=0
ok()   { echo "[verify] ✅ $1"; }
bad()  { echo "[verify] ❌ $1" >&2; fail=1; }

# ---- 1) 引擎 so 哈希 ----
SO_SHA="$(unzip -p "$APK" lib/arm64-v8a/libvbox.so 2>/dev/null | shasum -a 256 | awk '{print $1}')"
if [ "$SO_SHA" = "$EXPECT_SO_SHA256" ]; then
  ok "libvbox.so = 补丁引擎 ${EXPECT_SO_SHA256:0:16}…"
else
  bad "libvbox.so = ${SO_SHA:0:16}…（期望补丁引擎 ${EXPECT_SO_SHA256:0:16}…；a0f06e48…=原版未打补丁）"
fi

# ---- 2) 签名证书 ----
# 注意：set -o pipefail 下上游命令 | head 会因 SIGPIPE 以 141 中断脚本，
# 先整段捕获再解析
CERT_OUT="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null || true)"
CERT_SHA="$(echo "$CERT_OUT" | grep -m1 -o 'SHA-256 digest: [0-9a-f]*' | awk '{print $3}')"
if [ "$CERT_SHA" = "$EXPECT_CERT_SHA256" ]; then
  ok "签名证书 = scorpion（${CERT_SHA:0:16}…）"
else
  bad "签名证书 = ${CERT_SHA:-读取失败}（期望 ${EXPECT_CERT_SHA256:0:16}…）"
fi

# ---- 3) 版本号 ----
BADGE_OUT="$("$AAPT2" dump badging "$APK" 2>/dev/null || true)"
BADGE="$(echo "$BADGE_OUT" | sed -n '1p')"
VC="$(echo "$BADGE" | grep -oE "versionCode='[0-9]+'" | grep -oE '[0-9]+')"
VN="$(echo "$BADGE" | grep -oE "versionName='[^']*'" | sed "s/versionName='//;s/'$//")"
if [ -n "$EXPECT_VC" ] && [ "$VC" != "$EXPECT_VC" ]; then
  bad "versionCode=$VC（期望 $EXPECT_VC）"
else
  ok "versionCode=$VC versionName=$VN"
fi
if [ -n "$EXPECT_VN" ] && [ "$VN" != "$EXPECT_VN" ]; then
  bad "versionName=$VN（期望 $EXPECT_VN）"
fi

# ---- 4) GameKeepAliveService 必须在宿主主进程 ----
TREE="$("$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml 2>/dev/null)"
SVC_BLOCK="$(echo "$TREE" | grep -A6 'GameKeepAliveService' || true)"
if [ -z "$SVC_BLOCK" ]; then
  bad "清单中找不到 GameKeepAliveService"
elif echo "$SVC_BLOCK" | grep -q 'android:process'; then
  bad "GameKeepAliveService 声明了 android:process（FGS 5 秒超时崩溃会回归）"
elif ! echo "$SVC_BLOCK" | grep -q 'foregroundServiceType'; then
  bad "GameKeepAliveService 未声明 foregroundServiceType（targetSdk 升级会崩）"
else
  ok "GameKeepAliveService 位于宿主主进程且声明了 FGS 类型"
fi

# ---- 5) 通知权限 ----
# pipefail 下 `cmd | grep -q` 会因上游 SIGPIPE 误判失败，先整段捕获再判断
PERM_OUT="$("$AAPT2" dump permissions "$APK" 2>/dev/null || true)"
if echo "$PERM_OUT" | grep -q 'android.permission.POST_NOTIFICATIONS'; then
  ok "已声明 POST_NOTIFICATIONS"
else
  bad "未声明 POST_NOTIFICATIONS（Android 13+ 前台服务通知不可见）"
fi

# ---- 6) autofill 字节码补丁必须进包 ----
# 判据（dexdump 反汇编 InvocationStubManager.injectInternal）：
#   未打补丁：`if (isOreo()) { if (!isTiramisu()) addInjector(new AutoFillManagerStub()) }`
#             ⇒ new AutoFillManagerStub 之前能看到 `invoke-static BuildCompat.isTiramisu()`
#             + `move-result` + `if-nez`（Android 13+ 走另一条分支，代理不注册）
#   补丁版：  `iconst_0; nop; nop; ifne`（javac 层）⇒ d8/R8 常量折叠后 isTiramisu 分支
#             整段消失，只剩 isOreo 守卫；未做常量折叠的构建（如 debug/d8）则会看到
#             `const/4 v0, #0`。两种形态都算补丁版。
#
# 注意：set -o pipefail 下 `grep -q` / `head -1` 会因上游 SIGPIPE 误判（见第 2 项注释），
# 所以这里把「定位 + 取窗口」全部放进单趟 awk，结果用 bash 通配符判断，不走管道。
DEXDUMP="$BT/dexdump"
AF_NO_AUTOFILL="__NO_AUTOFILL_INSTANCE__"

# 输出：空 = 该 dex 里没有 injectInternal；$AF_NO_AUTOFILL = 有方法但没注册代码；
#       否则 = AutoFillManagerStub 注册语句前最近 6 条指令（换行分隔）
extract_autofill_window() {
  "$DEXDUMP" -d "$1" 2>/dev/null | awk -v MISS="$AF_NO_AUTOFILL" '
    /^[0-9a-f]+: +\|\[[0-9a-f]+\] com\.lody\.virtual\.client\.core\.InvocationStubManager\.injectInternal:\(\)V/ {
      inb = 1; n = 0; next
    }
    inb {
      if ($0 ~ /^[0-9a-f]+: +\|\[[0-9a-f]+\] /) exit
      if ($0 ~ /^  (source_file_idx|Class descriptor)/) exit
      if ($0 ~ /new-instance v[0-9]+, Lcom\/lody\/virtual\/client\/hook\/proxies\/view\/AutoFillManagerStub;/) {
        for (i = 1; i <= 6 && i <= n; i++) print buf[(n - i + 1) % 7]
        found = 1
        exit
      }
      if ($0 ~ /\|[0-9a-f][0-9a-f][0-9a-f][0-9a-f]: /) { n++; buf[n % 7] = $0 }
    }
    END { if (inb && !found) print MISS }' || true
}

if [ ! -x "$DEXDUMP" ]; then
  bad "找不到 dexdump（$DEXDUMP），无法校验 autofill 补丁"
else
  TMP_DEX="$(mktemp -d "${TMPDIR:-/tmp}/verify_apk_dex.XXXXXX")"
  trap 'rm -rf "$TMP_DEX"' EXIT
  if ! unzip -o -q "$APK" 'classes*.dex' -d "$TMP_DEX" >/dev/null 2>&1; then
    bad "APK 内没有 classes*.dex，无法校验 autofill 补丁"
  else
    AF_SEEN=""
    AF_WINDOW=""
    AF_DEX=""
    for dex in "$TMP_DEX"/classes*.dex; do
      # 先用 dex 里的类描述符字符串粗筛，避免对每个 dex 都全量反汇编
      LC_ALL=C grep -qa 'com/lody/virtual/client/core/InvocationStubManager;' "$dex" || continue
      w="$(extract_autofill_window "$dex")"
      [ -n "$w" ] || continue
      AF_SEEN=1
      AF_WINDOW="$w"
      AF_DEX="$(basename "$dex")"
      break
    done
    if [ -z "$AF_SEEN" ]; then
      bad "dex 里找不到 InvocationStubManager.injectInternal（autofill 注册代码缺失）"
    elif [ "$AF_WINDOW" = "$AF_NO_AUTOFILL" ]; then
      bad "injectInternal 里没有 new AutoFillManagerStub（$AF_DEX：代理注册代码缺失）"
    elif [[ "$AF_WINDOW" == *"BuildCompat;.isTiramisu"* ]]; then
      bad "autofill 补丁未进包（$AF_DEX：new AutoFillManagerStub 前仍有 invoke-static BuildCompat.isTiramisu ⇒ SDK 版本 guard 还在，代理可能不注册）"
    elif [[ "$AF_WINDOW" == *"const/4"* ]]; then
      ok "autofill 补丁已进包（$AF_DEX：new AutoFillManagerStub 前为 const/4，isTiramisu guard 已被常量 0 取代）"
    elif [[ "$AF_WINDOW" == *"BuildCompat;.isOreo"* ]]; then
      ok "autofill 补丁已进包（$AF_DEX：new AutoFillManagerStub 前只剩 isOreo 守卫，isTiramisu 分支已被 d8/R8 常量折叠掉）"
    else
      bad "无法判定 autofill 补丁（$AF_DEX：new AutoFillManagerStub 前 6 条指令既无 const/4、也无 isOreo/isTiramisu 守卫）"
    fi
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "[verify] 门禁未通过：$APK" >&2
  exit 1
fi
echo "[verify] 门禁全部通过：$APK"
