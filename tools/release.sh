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

restore_cfg() {
  git checkout -- "$CFG"
}

echo "[release] 构建 release…"
if ! ./gradlew :app:assembleRelease --offline; then
  echo "[release] 构建失败，已还原 $CFG"
  restore_cfg
  exit 1
fi

APK_SRC="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_SRC" ]; then
  echo "[release] 未找到 ${APK_SRC}，已还原 ${CFG}"
  restore_cfg
  exit 1
fi

cp "$APK_SRC" "$ROOT/$APK_NAME"

git add "$CFG"
git commit -m "chore(release): v${NEW_VER} (${NEW_CODE})"

echo "[release] 完成：$ROOT/$APK_NAME"
echo "[release] 热更 JSON 记得同步 apk 块："
echo "  \"apk\": { \"versionCode\": ${NEW_CODE}, \"versionName\": \"${NEW_VER}\", \"url\": \"<下载地址>\" }"
