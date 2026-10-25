#!/usr/bin/env bash
# 编队英雄头像素材：从线上网页版静态资源下载英雄立绘 → 缩放 96×96 → WebP(q80)
#
# 用途：为 App「编队」功能生成内置英雄头像 assets（app/src/main/assets/hero_icons/{id}.webp）。
# 线上链路为**只读静态资源**：GET http://<host>/hero/{id}.png ，无需登录、不携带任何凭据。
#
# 用法：
#   tools/formation_icons.sh              # 默认探测 50000..50120，下载有效 id 并生成 assets
#   tools/formation_icons.sh --dry-run    # 只探测，不写 assets
#   ID_FROM=50000 ID_TO=50120 tools/formation_icons.sh
#   HERO_ICON_BASE=http://127.0.0.1:8888/hero tools/formation_icons.sh   # 换源
#   SIZE=96 QUALITY=80 MAX_TOTAL_MB=2.5 tools/formation_icons.sh          # 覆盖尺寸/质量/体积上限
#
# 产物：
#   app/src/main/assets/hero_icons/{id}.webp  （96×96，WebP q80；体积超限时自动降档）
#
# 依赖：curl、python3 + Pillow（需 webp 编码支持；缺失时回退 `sips` 降尺寸 + PNG 输出并告警）
# 幂等：原始 PNG 缓存在 HERO_ICON_WORK（默认 /tmp/formation_heroes/src），二次运行直接复用。
# 备注：脚本不含任何账号/密码；如站点未来改成鉴权资源，请另配 HERO_ICON_COOKIE 环境变量，勿硬编码凭据。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT_DIR="$ROOT/app/src/main/assets/hero_icons"
WORK="${HERO_ICON_WORK:-/tmp/formation_heroes}"
SRC_DIR="$WORK/src"
BASE="${HERO_ICON_BASE:-http://111.228.32.189:8888/hero}"
ID_FROM="${ID_FROM:-50000}"
ID_TO="${ID_TO:-50120}"
SIZE="${SIZE:-96}"
QUALITY="${QUALITY:-80}"
MAX_TOTAL_MB="${MAX_TOTAL_MB:-2.5}"
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

mkdir -p "$SRC_DIR"
echo "== 探测 $BASE/{id}.png  范围 $ID_FROM..$ID_TO =="

# ---------- 1) 探测 + 下载（并发 8） ----------
probe_one() {
  local id=$1 code f
  f="$SRC_DIR/$id.png"
  if [ -s "$f" ]; then
    # 缓存命中：校验 PNG magic 后复用
    if [ "$(head -c 8 "$f" | od -An -tx1 | tr -d ' \n')" = "89504e470d0a1a0a" ]; then
      echo "$id"
      return 0
    fi
    rm -f "$f"
  fi
  code=$(curl -sS -o "$f" -w '%{http_code}' --max-time 25 "$BASE/$id.png" 2>/dev/null || echo 000)
  if [ "$code" = "200" ] && [ -s "$f" ] && [ "$(head -c 8 "$f" | od -An -tx1 | tr -d ' \n')" = "89504e470d0a1a0a" ]; then
    echo "$id"
  else
    rm -f "$f"
  fi
}
export -f probe_one
export SRC_DIR BASE
seq "$ID_FROM" "$ID_TO" | xargs -P 8 -I{} bash -c 'probe_one "$@"' _ {} > "$WORK/ok_ids.txt"
sort -n -o "$WORK/ok_ids.txt" "$WORK/ok_ids.txt"
COUNT=$(wc -l < "$WORK/ok_ids.txt" | tr -d ' ')
echo "有效 id：$COUNT 个（404 跳过）"
echo "id 列表：$(tr '\n' ' ' < "$WORK/ok_ids.txt")"
MISSING=$(comm -23 <(seq "$ID_FROM" "$ID_TO") <(sort -n "$WORK/ok_ids.txt") | tr '\n' ' ')
echo "缺失 id：${MISSING:-无}"
[ "$COUNT" -eq 0 ] && { echo "无有效头像，退出"; exit 1; }

# ---------- 2) 缩放 + 转码 ----------
if [ "$DRY_RUN" = "1" ]; then
  echo "--dry-run：跳过写 assets（原始 PNG 在 $SRC_DIR）"
  exit 0
fi

mkdir -p "$OUT_DIR"
python3 - "$SRC_DIR" "$OUT_DIR" "$WORK/ok_ids.txt" "$SIZE" "$QUALITY" "$MAX_TOTAL_MB" <<'PY'
import os, sys
from PIL import Image, features

src_dir, out_dir, ids_file, size, quality, max_mb = sys.argv[1:7]
size, quality, max_total = int(size), int(quality), float(max_mb) * 1024 * 1024
ids = [l.strip() for l in open(ids_file) if l.strip()]
if not features.check("webp"):
    sys.exit("Pillow 缺少 WebP 编码支持（pip install Pillow 或改用 sips 路径）")

def encode(px, q):
    total = 0
    files = []
    for hid in ids:
        src = os.path.join(src_dir, hid + ".png")
        dst = os.path.join(out_dir, hid + ".webp")
        im = Image.open(src).convert("RGBA")
        im = im.resize((px, px), Image.LANCZOS)
        im.save(dst, "WEBP", quality=q, method=6)
        total += os.path.getsize(dst)
        files.append(dst)
    return total, files

# 体积上限策略：先 96/q80，超限则依次降质量 → 降尺寸
plan = [(size, quality), (size, 70), (size, 60), (72, 80), (64, 80)]
final = None
for px, q in plan:
    total, files = encode(px, q)
    print(f"  尝试 {px}x{px} q{q}: 合计 {total/1024/1024:.2f} MB")
    if total <= max_total:
        final = (px, q, total)
        break
if final is None:
    print(f"  ! 最低档仍超 {max_total/1024/1024:.1f} MB，保留最后一档")
    final = (plan[-1][0], plan[-1][1], total)

px, q, total = final
# 清理降档过程中可能残留的旧文件（只保留当前 ids）
keep = {os.path.basename(f) for f in files}
for f in os.listdir(out_dir):
    if f.endswith(".webp") and f not in keep:
        os.remove(os.path.join(out_dir, f))

print(f"FINAL: {len(ids)} 张 {px}x{px} WebP q{q}，合计 {total/1024:.1f} KB ({total/1024/1024:.2f} MB)")
with open(os.path.join(os.environ.get("HERO_ICON_WORK", "/tmp/formation_heroes"), "hero_icons.manifest.txt"), "w") as fh:
    fh.write(f"count={len(ids)}\nsize={px}\nquality={q}\nbytes={total}\nids={' '.join(ids)}\n")
PY

# 清单写在 HERO_ICON_WORK（/tmp），不进 assets
echo "== 完成 =="
echo "目录：$OUT_DIR"
ls "$OUT_DIR" | wc -l | xargs echo "文件数："
du -sh "$OUT_DIR"
