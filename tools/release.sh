#!/bin/bash
# 合并到 master 后自动发版：versionCode +1、版本号 patch +1、构建 release APK。
#
# 由 .git/hooks/post-merge 自动调用；也可手动执行：
#   tools/release.sh            # 真正发版（仅 master、工作区必须干净）
#   tools/release.sh --dry-run  # 只预览下一个版本号
#
# 产物：仓库根目录 无尽号库-v<versionName>[版].apk（根目录 *.apk 已被 git 忽略）。
# 版本号唯一来源：VAConfig.gradle 的 VA_VERSION_CODE / VA_VERSION。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DRY=0
if [ "${1:-}" = "--dry-run" ]; then DRY=1; fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "master" ]; then
  if [ "$DRY" = "1" ]; then
    echo "[release] 提示：当前分支 ${BRANCH}，dry-run 仅预览版本号"
  else
    echo "[release] 当前分支 ${BRANCH}，不是 master，跳过发版"
    exit 0
  fi
fi

if [ "$DRY" != "1" ] && [ -n "$(git status --porcelain)" ]; then
  echo "[release] 工作区不干净，跳过发版。请先提交或清理："
  git status --porcelain
  exit 0
fi

CFG="VAConfig.gradle"
CODE="$(grep -E '^[[:space:]]*VA_VERSION_CODE[[:space:]]*=' "$CFG" | sed -E 's/.*=[[:space:]]*([0-9]+).*/\1/' || true)"
VER="$(grep -E '^[[:space:]]*VA_VERSION[[:space:]]*=' "$CFG" | sed -E "s/.*=[[:space:]]*'([^']+)'.*/\1/" || true)"

if [ -z "$CODE" ] || [ -z "$VER" ]; then
  echo "[release] 无法从 $CFG 解析 VA_VERSION_CODE / VA_VERSION"
  exit 1
fi

NEW_CODE=$((CODE + 1))
if [[ "$VER" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(.*)$ ]]; then
  NEW_VER="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))${BASH_REMATCH[4]}"
else
  NEW_VER="${VER}.${NEW_CODE}"
fi

if [[ "$NEW_VER" == *版 ]]; then
  APK_NAME="无尽号库-v${NEW_VER}.apk"
else
  APK_NAME="无尽号库-v${NEW_VER}版.apk"
fi

echo "[release] 版本：${VER} (${CODE}) → ${NEW_VER} (${NEW_CODE})"
echo "[release] 产物：${APK_NAME}"

if [ "$DRY" = "1" ]; then
  exit 0
fi

# 写回版本号（保留行内其他内容）
sed -i '' -E "s/^([[:space:]]*VA_VERSION_CODE[[:space:]]*=[[:space:]]*)[0-9]+/\1${NEW_CODE}/" "$CFG"
sed -i '' -E "s/^([[:space:]]*VA_VERSION[[:space:]]*=[[:space:]]*)'[^']*'/\1'${NEW_VER}'/" "$CFG"

AAR="app/sdk/lib-release.aar"
PATCHED_AAR="tools/engine/lib-release.aar.patched"

restore_all() {
  git checkout -- "$CFG" 2>/dev/null || true
  # 仓库 AAR 是 committed 原版；构建期临时换入补丁版，退出前必须按字节还原
  git checkout -- "$AAR" 2>/dev/null || true
}
trap restore_all EXIT

# ---- 门禁 1：单测全绿（发版前必须通过，禁止带红发版） ----
echo "[release] 门禁 1/3：单元测试…"
if ! ./gradlew :app:testDebugUnitTest --offline --console=plain; then
  echo "[release] 单元测试未通过，发版中止"
  exit 1
fi

# ---- 门禁 2：补丁引擎 AAR 就位并校验哈希 ----
# 背景：仓库 AAR 内 libvbox.so 是原版（a0f06e48…），Android 14+ renameat2 丢 flags
# ⇒ EINVAL ⇒ 容器内游戏下载/更新失败。修复版 so=0dd16cb7…，发版必须强制换入。
echo "[release] 门禁 2/3：补丁引擎 AAR…"
if [ ! -f "$PATCHED_AAR" ]; then
  echo "[release] 缺少 $PATCHED_AAR（tools/engine/），发版中止"
  exit 1
fi
if ! (cd tools/engine && shasum -a 256 -c SHA256SUMS >/dev/null 2>&1); then
  echo "[release] tools/engine/SHA256SUMS 校验失败（AAR 可能被改动），发版中止"
  exit 1
fi
cp "$PATCHED_AAR" "$AAR"
# flatDir AAR 会被 Gradle transforms-3 缓存吞掉：停 daemon + 删缓存，确保 APK 用的是补丁 so
./gradlew --stop >/dev/null 2>&1 || true
find "$HOME/.gradle/caches/transforms-3" -maxdepth 3 -name "*lib-release*" -type d \
  -exec rm -rf {} + 2>/dev/null || true

# ---- 构建（clean 保证 merged native libs 不残留旧 so） ----
echo "[release] 构建 release…"
if ! ./gradlew :app:clean :app:assembleRelease --offline; then
  echo "[release] 构建失败，已还原 $CFG 与 $AAR"
  exit 1
fi

APK_SRC="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_SRC" ]; then
  echo "[release] 未找到 ${APK_SRC}，发版中止"
  exit 1
fi

# ---- 门禁 3：APK 内容校验（引擎哈希/签名/版本/服务进程/权限） ----
echo "[release] 门禁 3/3：APK 校验…"
if ! tools/verify_apk.sh "$APK_SRC" "$NEW_CODE" "$NEW_VER"; then
  echo "[release] APK 门禁未通过，发版中止（未产出安装包）"
  exit 1
fi

# AAR 立即还原（verify 已确认 APK 内是补丁 so，源码目录保持 committed 原版）
git checkout -- "$AAR"

cp "$APK_SRC" "$ROOT/$APK_NAME"

git add "$CFG"
git commit -m "chore(release): v${NEW_VER} (${NEW_CODE})"

trap - EXIT
echo "[release] 完成：$ROOT/$APK_NAME"
echo "[release] 热更 JSON 记得同步 apk 块："
echo "  \"apk\": { \"versionCode\": ${NEW_CODE}, \"versionName\": \"${NEW_VER}\", \"url\": \"<下载地址>\" }"
