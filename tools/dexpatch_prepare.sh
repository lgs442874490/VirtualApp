#!/bin/bash
# DexPatch 补丁发布 staging 生成器（只准备，不部署）。
#
# 用法：
#   tools/dexpatch_prepare.sh <old.apk> <new.apk> [secret]
#
# 例：
#   tools/dexpatch_prepare.sh /tmp/dexpatch/base.apk /tmp/dexpatch/new.apk "$LICENSE_SECRET"
#
# 产物（默认 .dexpatch/，可用 DEXPATCH_OUT 覆盖）：
#   .dexpatch/patch/code.dex  -> 服务器 <dataDir>/patch/code.dex
#   .dexpatch/patch.json      -> 合并进 hotconfig.json 的 patches 块
#
# secret 与服务端 LICENSE_SECRET 同值（App 端用 LicenseConfig.appSecret() 校验 HMAC）；
# 不传则不生成 hmac 字段（只做 sha256 校验）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

OLD="${1:-}"
NEW="${2:-}"
SECRET="${3:-}"

if [ -z "$OLD" ] || [ -z "$NEW" ]; then
  echo "用法: tools/dexpatch_prepare.sh <old.apk> <new.apk> [secret]" >&2
  exit 1
fi
[ -f "$OLD" ] || { echo "[dexpatch] 错误：old.apk 不存在：$OLD" >&2; exit 1; }
[ -f "$NEW" ] || { echo "[dexpatch] 错误：new.apk 不存在：$NEW" >&2; exit 1; }

OUT="${DEXPATCH_OUT:-$ROOT/.dexpatch}"
mkdir -p "$OUT/patch"

# dexlib2 等依赖从 gradle 缓存解析（先构建一次 app 让缓存就绪）
CP=$(find ~/.gradle/caches/modules-2/files-2.1 \
    \( -path '*dexlib2*' -o -path '*guava*' -o -path '*jsr305*' -o -path '*xml-apis*' \) \
    -name '*.jar' 2>/dev/null | tr '\n' ':')
if [ -z "$CP" ]; then
  echo "[dexpatch] 错误：找不到 dexlib2 依赖（先跑一次 ./gradlew :app:assembleDebug）" >&2
  exit 1
fi

TOOL_DIR="$OUT/tool"
if [ ! -f "$TOOL_DIR/GeneratePatch.class" ]; then
  echo "[dexpatch] 编译补丁生成器…"
  mkdir -p "$TOOL_DIR"
  javac -source 8 -target 8 -cp "$CP" -d "$TOOL_DIR" tools/dexpatch/GeneratePatch.java 2>&1 | grep -v "警告\|warning" || true
fi

echo "[dexpatch] 生成补丁: $OLD -> $NEW"
OUTPUT=$(java -cp "$TOOL_DIR:$CP" GeneratePatch "$OLD" "$NEW" "$OUT/patch/code.dex" "$SECRET" 2>&1)
echo "$OUTPUT"

META=$(echo "$OUTPUT" | grep '^META:' | sed 's/^META://')
if [ -z "$META" ]; then
  echo "[dexpatch] 错误：补丁生成失败（见上方输出）" >&2
  exit 1
fi

VERSION="${DEXPATCH_VERSION:-$(date +%Y%m%d%H)}"
python3 - "$OUT" "$VERSION" "$META" <<'PY'
import json, sys, os
out, version, meta = sys.argv[1], sys.argv[2], json.loads(sys.argv[3])
code = {
    "version": int(version),
    "baseApkMd5": meta["baseApkMd5"],
    "url": "{base}/v1/vault/patch/code.dex",
    "sha256": meta["sha256"],
    "sizeBytes": meta["sizeBytes"],
}
if meta.get("hmac"):
    code["hmac"] = meta["hmac"]
with open(os.path.join(out, "patch.json"), "w", encoding="utf-8") as f:
    json.dump({"patches": {"code": code}}, f, ensure_ascii=False, indent=2)
print("[dexpatch] patch.json 已生成")
PY

SIZE=$(wc -c < "$OUT/patch/code.dex" | tr -d '[:space:]')
echo
echo "[dexpatch] 产物:"
echo "  patch   = $OUT/patch/code.dex ($SIZE 字节)"
echo "  config  = $OUT/patch.json"
echo
echo "[dexpatch] 部署（手动，本脚本不联网）:"
echo "  1) ssh root@<server> 'mkdir -p <dataDir>/patch'"
echo "  2) scp $OUT/patch/code.dex root@<server>:<dataDir>/patch/code.dex"
echo "  3) 把 patch.json 的 patches 块合并进 <dataDir>/hotconfig.json"
echo
echo "[dexpatch] 自检: curl -sSI https://<host>/v1/vault/patch/code.dex"
