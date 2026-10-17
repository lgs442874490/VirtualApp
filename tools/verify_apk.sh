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
else
  ok "GameKeepAliveService 位于宿主主进程"
fi

# ---- 5) 通知权限 ----
# pipefail 下 `cmd | grep -q` 会因上游 SIGPIPE 误判失败，先整段捕获再判断
PERM_OUT="$("$AAPT2" dump permissions "$APK" 2>/dev/null || true)"
if echo "$PERM_OUT" | grep -q 'android.permission.POST_NOTIFICATIONS'; then
  ok "已声明 POST_NOTIFICATIONS"
else
  bad "未声明 POST_NOTIFICATIONS（Android 13+ 前台服务通知不可见）"
fi

if [ "$fail" -ne 0 ]; then
  echo "[verify] 门禁未通过：$APK" >&2
  exit 1
fi
echo "[verify] 门禁全部通过：$APK"
