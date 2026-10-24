#!/bin/bash
# 热更新发布 staging 生成器（只准备，不部署）。
#
# 用法：
#   tools/hotupdate_prepare.sh <apk> <versionCode> <versionName> [notes]
#
# 例：
#   tools/hotupdate_prepare.sh 无尽号库-v4.2.9-HTTPS加密版.apk 23519 "4.2.9-HTTPS加密" "修复xxx"
#
# 产物（默认 .hotupdate/，可用 HOTUPDATE_OUT 覆盖）：
#   .hotupdate/hotconfig.json           -> 服务器 <dataDir>/hotconfig.json
#   .hotupdate/download/app-latest.apk  -> 服务器 <dataDir>/download/app-latest.apk
#
# hotconfig.json 约定（见 /tmp/va-tasks/hotupdate-spec.md）：
#   version = 当前时间 yyyyMMddHH（App 只做“远端 version > 本地 version”比对）；
#   apk.url = "{base}/v1/vault/app-latest.apk"，由 vault-server 在响应时把 {base}
#   替换为实际的 scheme://Host（X-Forwarded-Proto 优先），故同一份 JSON 在 IP/域名、
#   http/https 下都能下载。部署由 Lead 手动 scp，本脚本绝不连服务器。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
用法: tools/hotupdate_prepare.sh <apk> <versionCode> <versionName> [notes]

  apk           已签名 APK 路径（release 包，任意位置）
  versionCode   整数版本号，例如 23519（App 与本机 versionCode 比较后引导整包下载）
  versionName   版本名，例如 "4.2.9-HTTPS加密"
  notes         更新说明（可选，可含中文/空格，需整体加引号）

可选环境变量:
  HOTUPDATE_OUT       staging 目录（默认 .hotupdate）
  HOTUPDATE_VERSION   hotconfig.version 覆盖（默认 date +%Y%m%d%H）

只生成本地 staging，不做任何部署/上传。
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; exit 0; fi
if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then usage; exit 1; fi

APK="$1"
CODE="$2"
NAME="$3"
NOTES="${4:-}"

# ---- 参数校验 ----
if [ ! -f "$APK" ]; then
  echo "[hotupdate] 错误：APK 不存在：$APK" >&2
  exit 1
fi
if ! [[ "$CODE" =~ ^[0-9]+$ ]] || [ "$CODE" -le 0 ]; then
  echo "[hotupdate] 错误：versionCode 必须是正整数，实际：$CODE" >&2
  exit 1
fi
if [ -z "$NAME" ]; then
  echo "[hotupdate] 错误：versionName 不能为空" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "[hotupdate] 错误：需要 python3 生成 JSON（macOS: xcode-select --install）" >&2
  exit 1
fi

VERSION="${HOTUPDATE_VERSION:-$(date +%Y%m%d%H)}"
if ! [[ "$VERSION" =~ ^[0-9]+$ ]]; then
  echo "[hotupdate] 错误：HOTUPDATE_VERSION 必须是数字（App 用 optLong 比对），实际：$VERSION" >&2
  exit 1
fi

OUT="${HOTUPDATE_OUT:-$ROOT/.hotupdate}"
# 自忽略目录：避免 staging（含 APK/JSON）被 git 误提交，无需改仓库根 .gitignore
mkdir -p "$OUT/download"
printf '*\n' > "$OUT/.gitignore"

APK_DST="$OUT/download/app-latest.apk"
cp -f "$APK" "$APK_DST"

SIZE="$(wc -c < "$APK_DST" | tr -d '[:space:]')"
SHA="$(shasum -a 256 "$APK_DST" | awk '{print $1}')"

# ---- 生成 hotconfig.json（python3 负责 JSON 转义，notes 含引号/换行也不会坏） ----
python3 - "$VERSION" "$CODE" "$NAME" "$NOTES" "$SHA" "$SIZE" > "$OUT/hotconfig.json" <<'PY'
import json
import sys

version, code, name, notes, sha, size = sys.argv[1:7]
apk = {
    "versionCode": int(code),
    "versionName": name,
    # vault-server 响应时把 {base} 替换为 scheme://Host
    "url": "{base}/v1/vault/app-latest.apk",
    "sha256": sha,
    "sizeBytes": int(size),
}
if notes:
    apk["notes"] = notes
print(json.dumps({"version": int(version), "apk": apk}, ensure_ascii=False, indent=2))
PY

# ---- 结果 ----
echo "[hotupdate] staging 已生成：$OUT"
echo "[hotupdate]   version      = $VERSION (yyyyMMddHH)"
echo "[hotupdate]   apk          = $APK -> download/app-latest.apk"
echo "[hotupdate]   versionCode  = $CODE"
echo "[hotupdate]   versionName  = $NAME"
if [ -n "$NOTES" ]; then echo "[hotupdate]   notes        = $NOTES"; fi
echo "[hotupdate]   sizeBytes    = $SIZE"
echo "[hotupdate]   sha256       = $SHA"
echo "[hotupdate]   hotconfig    = $OUT/hotconfig.json"
echo
echo "[hotupdate] hotconfig.json 内容："
sed 's/^/    /' "$OUT/hotconfig.json"
echo
echo "[hotupdate] 下一步（部署，手动执行；本脚本不联网）："
echo "  服务器 dataDir 见 /etc/vault-server.env 的 VAULT_DATA_DIR（生产为 /opt/vault-server/data）"
echo "  1) 确保服务器有下载目录：ssh root@<server> 'mkdir -p <dataDir>/download'"
echo "  2) 上传配置：scp $OUT/hotconfig.json root@<server>:<dataDir>/hotconfig.json"
echo "  3) 上传整包：scp $OUT/download/app-latest.apk root@<server>:<dataDir>/download/app-latest.apk"
echo
echo "  部署后自检（GET 无需签名；{base} 会被替换成实际 scheme://Host）："
echo "    curl -sS https://103.236.96.82:64868/v1/vault/hotconfig"
echo "    curl -sSI https://103.236.96.82:64868/v1/vault/app-latest.apk"
echo "  App 侧：我的页 -> 检查更新（adb 广播 com.carlos.multiapp.HOT_REFRESH 也可触发）"
