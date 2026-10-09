#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""无尽冬日 —— 容器内「自动领取免费物品」工具。

设计要点（务必先读 tools/README.md）：
  1. Unity 游戏内部按钮对 uiautomator 不可见，所以本工具**只做截图 + 图像识别**，
     绝不使用文字/控件树去点击游戏内元素。
  2. 只点两类目标：① 识别出的红色未领取角标；② 识别出的弹窗主按钮。其它坐标一律不点。
  3. 全程只读游戏状态，不写 shared_prefs、不碰快照、不改 App 代码；
     仅使用 screencap / input / am / pidof / uiautomator dump。
  4. 每号每天默认只跑一次（--force 可跳过），所有步骤截图留证 + 生成 report.json。

三种模式：
  record   录制：用户手动领一次，脚本持续截图存样本，供人工挑模板。
  dry-run  只截图 + 检测，报告红点数量与坐标，绝不点击。
  run      真跑：逐账号打开入口 → 等游戏就绪 → 循环清扫红点 → 回号库列表。

用法示例：
  python tools/auto_claim.py record  --label 鲍哥888
  python tools/auto_claim.py dry-run --limit 1
  python tools/auto_claim.py run     --limit 2
  python tools/auto_claim.py run     --labels 鲍哥888,丢丢888
  python tools/auto_claim.py run     --group 大号 --force
"""

from __future__ import annotations

import argparse
import io
import json
import random
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from collections import deque
from dataclasses import dataclass, field, asdict
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# 依赖检查：必须使用装了 Pillow / numpy 的隔离 venv python
# ---------------------------------------------------------------------------
try:
    import numpy as np
    from PIL import Image
except ImportError as exc:  # pragma: no cover - 环境提示
    sys.stderr.write(
        "[FATAL] 缺少 Pillow / numpy。请使用隔离 venv 的 python 运行本脚本：\n"
        "  /Users/liangguansheng/.workbuddy/binaries/python/envs/default/bin/python "
        "tools/auto_claim.py ...\n"
        f"原始错误: {exc}\n"
    )
    raise

TOOLS_DIR = Path(__file__).resolve().parent
DEFAULT_CONFIG_PATH = TOOLS_DIR / "config.json"

# 屏幕物理分辨率，用于兜底（真实值以 config.json 为准）
FALLBACK_W, FALLBACK_H = 1260, 2800


# ===========================================================================
# 一、默认配置（config.json 缺失或缺少某个键时用它兜底）
# ===========================================================================
DEFAULT_CONFIG: Dict[str, Any] = {
    "device": {
        "adb": "adb",
        "serial": "",
        "width": 1260,
        "height": 2800,
        "cmd_timeout_sec": 40,
    },
    "paths": {
        "samples_dir": "samples",
        "logs_dir": "logs",
        "state_dir": "state",
        "ui_dump_remote": "/sdcard/auto_claim_ui.xml",
    },
    "vault": {
        "package": "com.carlos.multiapp",
        "activity": "com.carlos.vault.AccountVaultActivity",
        "prefs_path": "/data/user/0/com.carlos.multiapp/shared_prefs/account_vault.xml",
        "cold_start_wait_sec": 9.0,
        "warm_start_wait_sec": 3.5,
        "scroll_x": 630,
        "scroll_y_from": 2200,
        "scroll_y_to": 700,
        "scroll_duration_ms": 400,
        "header_min_width_px": 800,
        "header_max_height_px": 220,
        "header_tap_x_ratio": 0.25,
        "max_scroll_attempts": 6,
        "ui_dump_retry": 3,
    },
    "game": {
        "package": "com.gof.china",
        "launch_timeout_sec": 35.0,
        "launch_poll_sec": 2.0,
        "ready_wait_sec": 6.0,
        "stop_before_open": True,
        "stop_before_next": True,
        "stop_wait_timeout_sec": 10.0,
    },
    "red_dot": {
        # rgb: 用 RGB 阈值判红；hsv: 用色相/饱和度/明度判红（红底偏暗时更宽容）
        "criterion": "rgb",
        "rgb": {"r_min": 180, "g_max": 110, "b_max": 110, "channel_gap_min": 55},
        "hsv": {
            "h_lo": 0, "h_hi": 14,
            "h2_lo": 166, "h2_hi": 180,
            "s_min": 0.50, "v_min": 0.35,
        },
        "min_radius_px": 4.0,
        "max_radius_px": 28.0,
        "min_fill_ratio": 0.40,      # 面积 / 外接矩形面积，圆点应 ≈0.78，数字挖空后仍应 >0.4
        "min_aspect": 0.50,          # 宽/高 比，排除长条
        "max_aspect": 2.00,
        "merge_distance_px": 26,     # 质心距离小于此值视为同一个点
        "noise_min_neighbors": 3,     # 3x3 邻域内红像素少于此值视为噪声
        "max_component_pixels": 60000,  # 红像素过多（异常画面）时放弃检测，宁可不点
        "sort": "top_down",          # top_down | bottom_up
    },
    "dialog": {
        # 只在中央对话框区域找按钮，避免点到常驻 HUD
        "region": {"x0_ratio": 0.10, "x1_ratio": 0.90, "y0_ratio": 0.28, "y1_ratio": 0.80},
        "min_saturation": 0.45,
        "min_value": 0.35,
        "max_value": 0.98,
        "min_width_px": 160,
        "max_width_px": 1000,
        "min_height_px": 56,
        "max_height_px": 220,
        "min_fill_ratio": 0.55,
        "min_area_px": 6000,
        "color_uniformity_max_std": 26.0,
        "noise_min_neighbors": 3,
        "max_component_pixels": 120000,
        "pick": "bottom",            # bottom(最下方) | largest(面积最大)
    },
    # 黑名单区域（设备像素坐标，null 表示到屏幕边缘）：区域内的红点一律忽略
    "blacklist": [
        {"name": "底部商城/充值常驻栏", "x0": 0, "y0": 2520, "x1": 1260, "y1": 2800, "enabled": True},
        {"name": "右上金币/钻石资源栏", "x0": 880, "y0": 0, "x1": 1260, "y1": 200, "enabled": True},
    ],
    "throttle": {
        "tap_wait_min_sec": 0.8,
        "tap_wait_max_sec": 2.5,
        "swipe_wait_min_sec": 0.5,
        "swipe_wait_max_sec": 1.5,
        "after_tap_settle_sec": 1.6,
        "after_dialog_sec": 1.4,
        "between_shots_sec": 1.2,
    },
    "loop": {
        "max_taps": 40,
        "max_rounds": 12,
        "no_progress_rounds": 2,
        "settle_after_close_sec": 1.2,
    },
    "safety": {
        "close_strategy": "back",   # back | none（back = 残留弹窗时按一次返回键）
        "max_back_presses": 2,
        "tap_only_detected_target": True,
    },
    "record": {
        "interval_sec": 0.7,
        "max_frames": 300,
    },
}


def _deep_merge(base: Any, override: Any) -> Any:
    """把 override 合并进 base（dict 递归，其它类型直接覆盖）。"""
    if isinstance(base, dict) and isinstance(override, dict):
        out = dict(base)
        for key, value in override.items():
            out[key] = _deep_merge(base.get(key), value) if key in base else value
        return out
    return override if override is not None else base


def load_config(path: Path) -> Dict[str, Any]:
    """读取 config.json，缺文件/缺键时用 DEFAULT_CONFIG 兜底。"""
    if not path.exists():
        return json.loads(json.dumps(DEFAULT_CONFIG))
    with path.open("r", encoding="utf-8") as fp:
        user_cfg = json.load(fp)
    user_cfg.pop("_comment", None)
    return _deep_merge(json.loads(json.dumps(DEFAULT_CONFIG)), user_cfg)


def cfg_get(cfg: Dict[str, Any], dotted: str, default: Any = None) -> Any:
    """按 'a.b.c' 路径取配置。"""
    node: Any = cfg
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            return default
        node = node[part]
    return node


# ===========================================================================
# 二、基础工具：日志 / 时间 / 文件名
# ===========================================================================
def log(msg: str, level: str = "INFO") -> None:
    """带时间戳的日志输出。"""
    stamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{stamp}][{level}] {msg}", flush=True)


def sanitize(name: str) -> str:
    """把账号标签清洗成安全的目录名。"""
    cleaned = re.sub(r"[^\w\u4e00-\u9fff.-]+", "_", str(name or "").strip())
    return cleaned or "unknown"


def sleep_rand(lo: float, hi: float) -> None:
    """在区间内随机等待，模拟人手节奏。"""
    time.sleep(random.uniform(max(0.0, lo), max(0.0, hi)))


# ===========================================================================
# 三、adb 封装
# ===========================================================================
class Adb:
    """极简 adb 客户端：只暴露本工具需要的只读/注入能力。"""

    def __init__(self, binary: str = "adb", serial: str = "", timeout: float = 40.0) -> None:
        self.binary = binary
        self.serial = serial
        self.timeout = timeout

    def _base(self) -> List[str]:
        cmd = [self.binary]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def run(self, args: Sequence[str], timeout: Optional[float] = None) -> subprocess.CompletedProcess:
        """执行一次 adb 命令，返回 CompletedProcess（文本模式）。"""
        cmd = self._base() + list(args)
        return subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout or self.timeout,
            text=True,
            errors="replace",
        )

    def shell(self, cmd: str, timeout: Optional[float] = None) -> str:
        """执行 adb shell 命令，返回 stdout。"""
        proc = self.run(["shell", cmd], timeout=timeout)
        return proc.stdout or ""

    def exec_out_bytes(self, args: Sequence[str], timeout: Optional[float] = None) -> bytes:
        """执行 adb exec-out，返回原始字节（用于 screencap）。"""
        cmd = self._base() + ["exec-out"] + list(args)
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout or self.timeout,
        )
        return proc.stdout or b""

    # ---------------- 截图 ----------------
    def screencap(self) -> Optional[Image.Image]:
        """截图并返回 PIL Image；失败返回 None（调用方按「失败即停」处理）。"""
        raw = self.exec_out_bytes(["screencap", "-p"], timeout=20)
        if len(raw) < 8 or not raw.startswith(b"\x89PNG"):
            log(f"screencap 返回异常（{len(raw)} 字节，非 PNG）", "ERROR")
            return None
        try:
            img = Image.open(io.BytesIO(raw))
            img.load()
            return img.convert("RGB")
        except Exception as exc:  # noqa: BLE001
            log(f"screencap 解码失败: {exc}", "ERROR")
            return None

    # ---------------- 注入 ----------------
    def tap(self, x: float, y: float) -> None:
        """点击一个坐标（只允许传检测到的目标）。"""
        self.shell(f"input tap {int(round(x))} {int(round(y))}", timeout=15)

    def swipe(self, x1: float, y1: float, x2: float, y2: float, duration_ms: int = 400) -> None:
        """滑动。"""
        self.shell(
            f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(duration_ms)}",
            timeout=20,
        )

    def keyevent(self, code: int) -> None:
        """发送按键事件（4 = BACK）。"""
        self.shell(f"input keyevent {int(code)}", timeout=15)

    # ---------------- 应用控制 ----------------
    def start_activity(self, component: str) -> str:
        """am start -n <component>。"""
        return self.shell(f"am start -n {component}", timeout=30)

    def force_stop(self, package: str) -> None:
        """am force-stop <package>。"""
        self.shell(f"am force-stop {package}", timeout=20)

    def pidof(self, package: str) -> List[int]:
        """返回进程的 pid 列表，空列表表示未运行。"""
        out = self.shell(f"pidof {package}", timeout=15)
        return [int(v) for v in re.findall(r"\d+", out)]

    # ---------------- 控件树（仅用于号库宿主列表） ----------------
    def dump_ui(self, remote_path: str, retry: int = 3) -> Optional[str]:
        """uiautomator dump 并把 XML 读回来；失败重试，仍失败返回 None。"""
        for attempt in range(1, max(1, retry) + 1):
            self.shell(f"uiautomator dump {remote_path}", timeout=30)
            xml_text = self.shell(f"cat {remote_path}", timeout=20)
            if "<?xml" in xml_text and "</hierarchy>" in xml_text:
                return xml_text[xml_text.index("<?xml"):]
            time.sleep(1.2)
            log(f"uiautomator dump 第 {attempt} 次失败，重试", "WARN")
        return None

    def read_prefs(self, package: str, prefs_path: str) -> Optional[str]:
        """run-as 读取宿主 shared_prefs（只读）。"""
        out = self.shell(f"run-as {package} cat {prefs_path}", timeout=30)
        if "<map>" not in out:
            return None
        return out[out.index("<map>"):]


# ===========================================================================
# 四、号库入口数据
# ===========================================================================
@dataclass
class Account:
    """一个号库入口。"""
    id: str = ""
    fpid: str = ""
    label: str = ""
    group: str = ""
    last_login: int = 0
    order: int = 0

    @property
    def safe_label(self) -> str:
        return sanitize(self.label)


def load_accounts(adb: Adb, cfg: Dict[str, Any]) -> Tuple[List[Account], List[Dict[str, Any]]]:
    """从设备只读拉取账号列表与分组元信息。"""
    prefs_xml = adb.read_prefs(
        cfg_get(cfg, "vault.package", "com.carlos.multiapp"),
        cfg_get(cfg, "vault.prefs_path", ""),
    )
    if not prefs_xml:
        raise RuntimeError("读取 account_vault.xml 失败（run-as 被拒绝或文件不存在）")

    root = ET.fromstring(prefs_xml)
    raw_accounts = ""
    raw_groups = ""
    for node in root.findall("string"):
        name = node.get("name")
        if name == "accounts":
            raw_accounts = node.text or "[]"
        elif name == "group_meta":
            raw_groups = node.text or "[]"

    accounts: List[Account] = []
    for idx, item in enumerate(json.loads(raw_accounts or "[]")):
        accounts.append(
            Account(
                id=str(item.get("id") or ""),
                fpid=str(item.get("fpid") or ""),
                label=str(item.get("label") or item.get("id") or f"#{idx}"),
                group=str(item.get("group") or ""),
                last_login=int(item.get("lastLogin") or 0),
                order=idx,
            )
        )

    groups: List[Dict[str, Any]] = []
    try:
        groups = json.loads(raw_groups or "[]")
    except json.JSONDecodeError:
        groups = []
    return accounts, groups


def select_accounts(
    accounts: Sequence[Account],
    limit: Optional[int],
    labels: Optional[Sequence[str]],
    group: Optional[str],
) -> List[Account]:
    """按 --limit / --labels / --group 三种方式筛选。"""
    picked: List[Account] = list(accounts)
    if labels:
        wanted = {s.strip() for s in labels if s.strip()}
        picked = [a for a in picked if a.label in wanted or a.id in wanted]
    if group:
        picked = [a for a in picked if a.group == group]
    if limit is not None and limit >= 0:
        picked = picked[:limit]
    return picked


# ===========================================================================
# 五、图像识别（numpy + Pillow，不依赖 OpenCV）
# ===========================================================================
@dataclass
class Blob:
    """一个连通域。"""
    x: float
    y: float
    area: int
    width: int
    height: int
    fill: float
    bbox: Tuple[int, int, int, int]
    color_std: float = 0.0


def to_rgb_np(img: Image.Image) -> np.ndarray:
    """PIL Image -> (H, W, 3) uint8 numpy 数组。"""
    return np.asarray(img.convert("RGB"), dtype=np.uint8)


def rgb_to_hsv(rgb: np.ndarray) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """RGB(uint8) -> (H 0~360, S 0~1, V 0~1) 的向量化实现。"""
    f = rgb.astype(np.float32) / 255.0
    r, g, b = f[:, :, 0], f[:, :, 1], f[:, :, 2]
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    diff = mx - mn
    h = np.zeros_like(mx)
    nz = diff > 1e-6
    m = nz & (mx == r)
    h[m] = np.mod((g[m] - b[m]) / diff[m], 6.0)
    m = nz & (mx == g)
    h[m] = (b[m] - r[m]) / diff[m] + 2.0
    m = nz & (mx == b)
    h[m] = (r[m] - g[m]) / diff[m] + 4.0
    h = h * 60.0
    s = np.where(mx > 1e-6, diff / np.maximum(mx, 1e-6), 0.0)
    return h, s, mx


def _integral(mask: np.ndarray) -> np.ndarray:
    """布尔掩码的积分图，shape (H+1, W+1)。"""
    h, w = mask.shape
    out = np.zeros((h + 1, w + 1), dtype=np.int32)
    out[1:, 1:] = mask.astype(np.int32).cumsum(0).cumsum(1)
    return out


def _box_sum(integral: np.ndarray, ys: np.ndarray, xs: np.ndarray, radius: int,
             h: int, w: int) -> Tuple[np.ndarray, np.ndarray]:
    """对每个 (y, x) 求半径 radius 的方框内掩码像素数与方框面积（自动裁剪边界）。"""
    y0 = np.clip(ys - radius, 0, h)
    y1 = np.clip(ys + radius + 1, 0, h)
    x0 = np.clip(xs - radius, 0, w)
    x1 = np.clip(xs + radius + 1, 0, w)
    total = (integral[y1, x1] - integral[y0, x1] - integral[y1, x0] + integral[y0, x0])
    capacity = (y1 - y0) * (x1 - x0)
    return total, capacity.astype(np.float32)


def label_components(mask: np.ndarray, max_pixels: int) -> Tuple[List[Tuple[np.ndarray, np.ndarray]], bool]:
    """8 邻域连通域标记。

    返回 (组件列表, 是否因为像素过多而截断)。每个组件是 (ys, xs) 两个数组。
    """
    h, w = mask.shape
    ys, xs = np.nonzero(mask)
    if ys.size == 0:
        return [], False
    if ys.size > max_pixels:
        return [], True

    visited = np.zeros((h, w), dtype=bool)
    components: List[Tuple[np.ndarray, np.ndarray]] = []
    flat_ys = ys.tolist()
    flat_xs = xs.tolist()

    for start_y, start_x in zip(flat_ys, flat_xs):
        if visited[start_y, start_x]:
            continue
        queue = deque([(start_y, start_x)])
        visited[start_y, start_x] = True
        cy: List[int] = []
        cx: List[int] = []
        while queue:
            y, x = queue.popleft()
            cy.append(y)
            cx.append(x)
            for dy in (-1, 0, 1):
                ny = y + dy
                if ny < 0 or ny >= h:
                    continue
                for dx in (-1, 0, 1):
                    nx = x + dx
                    if nx < 0 or nx >= w:
                        continue
                    if mask[ny, nx] and not visited[ny, nx]:
                        visited[ny, nx] = True
                        queue.append((ny, nx))
        components.append((np.asarray(cy, dtype=np.int32), np.asarray(cx, dtype=np.int32)))
    return components, False


def _blob_from_component(cys: np.ndarray, cxs: np.ndarray, rgb: Optional[np.ndarray] = None) -> Blob:
    """把一组像素坐标整理成 Blob（质心、外接矩形、填充率、颜色标准差）。"""
    y0, y1 = int(cys.min()), int(cys.max())
    x0, x1 = int(cxs.min()), int(cxs.max())
    width = x1 - x0 + 1
    height = y1 - y0 + 1
    area = int(cys.size)
    fill = area / float(max(1, width * height))
    color_std = 0.0
    if rgb is not None:
        patch = rgb[y0:y1 + 1, x0:x1 + 1].reshape(-1, 3).astype(np.float32)
        color_std = float(patch.std(axis=0).mean())
    return Blob(
        x=float(cxs.mean()),
        y=float(cys.mean()),
        area=area,
        width=width,
        height=height,
        fill=fill,
        bbox=(x0, y0, x1, y1),
        color_std=color_std,
    )


def _dilate_coarse(mask: np.ndarray, radius_px: int, cell: int = 16) -> np.ndarray:
    """近似膨胀（Chebyshev 距离 radius_px）。

    下采样到 cell×cell 的粗网格上做膨胀再上采样，避免对 1260×2800 做逐像素膨胀。
    """
    h, w = mask.shape
    ys, xs = np.nonzero(mask)
    if ys.size == 0:
        return np.zeros_like(mask)
    gh = (h + cell - 1) // cell
    gw = (w + cell - 1) // cell
    grid = np.zeros((gh, gw), dtype=bool)
    grid[ys // cell, xs // cell] = True

    steps = max(1, int(np.ceil(radius_px / float(cell))))
    out = grid.copy()
    for dy in range(-steps, steps + 1):
        for dx in range(-steps, steps + 1):
            if dy == 0 and dx == 0:
                continue
            shifted = np.roll(grid, (dy, dx), axis=(0, 1))
            if dy > 0:
                shifted[:dy, :] = False
            elif dy < 0:
                shifted[dy:, :] = False
            if dx > 0:
                shifted[:, :dx] = False
            elif dx < 0:
                shifted[:, dx:] = False
            out |= shifted

    up = np.repeat(np.repeat(out, cell, axis=0), cell, axis=1)
    return up[:h, :w]


def _prune_mask(mask: np.ndarray, min_neighbors: int,
                big_probe_radius: int, big_fill_ratio: float,
                big_expand_px: int = 0) -> np.ndarray:
    """剪枝：去掉孤立噪声点，以及整块「大块纯色」区域（游戏大红按钮/红色主题背景）。

    做法：先用积分图找出「大块核心」（半径 big_probe_radius 的方框内几乎全是红色），
    再把核心膨胀 big_expand_px 后整片剔除 —— 这样大红块不会只被削掉中间、留下一圈
    环状残渣（环状残渣会让连通域像素数暴涨，甚至触发截断保护）。
    """
    h, w = mask.shape
    ys, xs = np.nonzero(mask)
    if ys.size == 0:
        return mask
    integral = _integral(mask)

    near_total, _ = _box_sum(integral, ys, xs, 1, h, w)
    keep = near_total >= min_neighbors

    if big_probe_radius > 0:
        big_total, big_capacity = _box_sum(integral, ys, xs, big_probe_radius, h, w)
        core = big_total >= big_fill_ratio * np.maximum(big_capacity, 1.0)
        if core.any():
            core_mask = np.zeros_like(mask)
            core_mask[ys[core], xs[core]] = True
            blocked = _dilate_coarse(core_mask, max(1, big_expand_px or big_probe_radius))
            # 核心本身 + 膨胀范围内的像素全部剔除
            keep &= ~blocked[ys, xs]
            keep &= ~core

    pruned = np.zeros_like(mask)
    pruned[ys[keep], xs[keep]] = True
    return pruned


def build_red_mask(rgb: np.ndarray, cfg: Dict[str, Any]) -> np.ndarray:
    """按配置构造「红色」掩码。"""
    criterion = str(cfg_get(cfg, "red_dot.criterion", "rgb")).lower()
    if criterion == "hsv":
        hsv_h, hsv_s, hsv_v = rgb_to_hsv(rgb)
        h_lo = float(cfg_get(cfg, "red_dot.hsv.h_lo", 0))
        h_hi = float(cfg_get(cfg, "red_dot.hsv.h_hi", 14))
        h2_lo = float(cfg_get(cfg, "red_dot.hsv.h2_lo", 166))
        h2_hi = float(cfg_get(cfg, "red_dot.hsv.h2_hi", 180))
        s_min = float(cfg_get(cfg, "red_dot.hsv.s_min", 0.50))
        v_min = float(cfg_get(cfg, "red_dot.hsv.v_min", 0.35))
        hue_ok = ((hsv_h >= h_lo) & (hsv_h <= h_hi)) | ((hsv_h >= h2_lo) & (hsv_h <= h2_hi))
        return hue_ok & (hsv_s >= s_min) & (hsv_v >= v_min)

    r_min = float(cfg_get(cfg, "red_dot.rgb.r_min", 180))
    g_max = float(cfg_get(cfg, "red_dot.rgb.g_max", 110))
    b_max = float(cfg_get(cfg, "red_dot.rgb.b_max", 110))
    gap = float(cfg_get(cfg, "red_dot.rgb.channel_gap_min", 55))
    r = rgb[:, :, 0].astype(np.int16)
    g = rgb[:, :, 1].astype(np.int16)
    b = rgb[:, :, 2].astype(np.int16)
    return (r >= r_min) & (g <= g_max) & (b <= b_max) & ((r - g) >= gap) & ((r - b) >= gap)


def in_blacklist(x: float, y: float, cfg: Dict[str, Any], w: int, h: int) -> Optional[str]:
    """坐标落在黑名单区域则返回区域名，否则 None。"""
    for zone in cfg_get(cfg, "blacklist", []) or []:
        if not bool(zone.get("enabled", True)):
            continue
        x0 = 0 if zone.get("x0") is None else int(zone["x0"])
        y0 = 0 if zone.get("y0") is None else int(zone["y0"])
        x1 = w if zone.get("x1") is None else int(zone["x1"])
        y1 = h if zone.get("y1") is None else int(zone["y1"])
        if x0 <= x <= x1 and y0 <= y <= y1:
            return str(zone.get("name", "blacklist"))
    return None


def find_red_dots(img: Image.Image, cfg: Dict[str, Any]) -> Tuple[List[Blob], Dict[str, Any]]:
    """检测游戏内红色未领取角标。

    判据：先按颜色阈值取掩码 → 剪枝（孤立噪声 + 大块纯色内部）→ 8 邻域连通域 →
    按等效半径 / 外接矩形填充率 / 宽高比筛选 → 邻近质心去重 → 黑名单过滤。

    返回 (红点列表, 诊断信息)。
    """
    rgb = to_rgb_np(img)
    h, w = rgb.shape[:2]
    diag: Dict[str, Any] = {
        "criterion": cfg_get(cfg, "red_dot.criterion", "rgb"),
        "image_wh": [w, h],
        "mask_pixels": 0,
        "pruned_pixels": 0,
        "components": 0,
        "rejected": {"too_small": 0, "too_big": 0, "shape": 0, "blacklist": 0, "merged": 0},
        "truncated": False,
    }

    mask = build_red_mask(rgb, cfg)
    diag["mask_pixels"] = int(mask.sum())

    max_radius = float(cfg_get(cfg, "red_dot.max_radius_px", 28.0))
    # disable_big_blob_prune=true 时不做「大块纯色剔除」：红色主题的活动页可用，
    # 代价是大红按钮/红色背景也会被当成候选（靠半径与形状过滤兜底）。
    probe = 0 if bool(cfg_get(cfg, "red_dot.disable_big_blob_prune", False)) else int(max_radius * 2)
    pruned = _prune_mask(
        mask,
        min_neighbors=int(cfg_get(cfg, "red_dot.noise_min_neighbors", 3)),
        big_probe_radius=probe,
        big_fill_ratio=0.90,
        big_expand_px=(probe + 8) if probe else 0,
    )
    diag["pruned_pixels"] = int(pruned.sum())

    components, truncated = label_components(
        pruned, int(cfg_get(cfg, "red_dot.max_component_pixels", 60000))
    )
    diag["truncated"] = truncated
    diag["components"] = len(components)

    min_radius = float(cfg_get(cfg, "red_dot.min_radius_px", 4.0))
    min_fill = float(cfg_get(cfg, "red_dot.min_fill_ratio", 0.40))
    min_aspect = float(cfg_get(cfg, "red_dot.min_aspect", 0.50))
    max_aspect = float(cfg_get(cfg, "red_dot.max_aspect", 2.00))

    dots: List[Blob] = []
    for cys, cxs in components:
        blob = _blob_from_component(cys, cxs, rgb)
        radius = float(np.sqrt(blob.area / np.pi))
        if radius < min_radius:
            diag["rejected"]["too_small"] += 1
            continue
        if radius > max_radius:
            diag["rejected"]["too_big"] += 1
            continue
        aspect = blob.width / float(max(1, blob.height))
        if blob.fill < min_fill or aspect < min_aspect or aspect > max_aspect:
            diag["rejected"]["shape"] += 1
            continue
        dots.append(blob)

    # 邻近去重
    merge_dist = float(cfg_get(cfg, "red_dot.merge_distance_px", 26))
    merged: List[Blob] = []
    for dot in sorted(dots, key=lambda d: d.area, reverse=True):
        if any((dot.x - m.x) ** 2 + (dot.y - m.y) ** 2 <= merge_dist ** 2 for m in merged):
            diag["rejected"]["merged"] += 1
            continue
        merged.append(dot)

    # 黑名单过滤
    final: List[Blob] = []
    for dot in merged:
        zone = in_blacklist(dot.x, dot.y, cfg, w, h)
        if zone:
            diag["rejected"]["blacklist"] += 1
            log(f"  忽略黑名单区域「{zone}」内的红点 ({dot.x:.0f}, {dot.y:.0f})", "WARN")
            continue
        final.append(dot)

    if str(cfg_get(cfg, "red_dot.sort", "top_down")) == "bottom_up":
        final.sort(key=lambda d: -d.y)
    else:
        final.sort(key=lambda d: d.y)
    return final, diag


def find_dialog_primary_button(img: Image.Image, cfg: Dict[str, Any]) -> Tuple[Optional[Blob], Dict[str, Any]]:
    """在中央对话框区域找「按钮状」色块。

    判据：高饱和 + 明度适中 → 剪枝 → 连通域 → 宽>高、尺寸与面积适中、填充率高、
    颜色均匀 → 取最下方（或面积最大）的一个。找不到就返回 None（宁可漏，不可乱点）。
    """
    rgb = to_rgb_np(img)
    h, w = rgb.shape[:2]
    region = cfg_get(cfg, "dialog.region", {}) or {}
    x0 = int(w * float(region.get("x0_ratio", 0.10)))
    x1 = int(w * float(region.get("x1_ratio", 0.90)))
    y0 = int(h * float(region.get("y0_ratio", 0.28)))
    y1 = int(h * float(region.get("y1_ratio", 0.80)))
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(w, x1), min(h, y1)

    diag: Dict[str, Any] = {
        "region": [x0, y0, x1, y1],
        "mask_pixels": 0,
        "pruned_pixels": 0,
        "components": 0,
        "rejected": {"size": 0, "shape": 0, "uniformity": 0},
        "truncated": False,
        "candidates": 0,
    }
    if x1 <= x0 or y1 <= y0:
        return None, diag

    sub = rgb[y0:y1, x0:x1]
    hsv_h, hsv_s, hsv_v = rgb_to_hsv(sub)
    mask = (
        (hsv_s >= float(cfg_get(cfg, "dialog.min_saturation", 0.45)))
        & (hsv_v >= float(cfg_get(cfg, "dialog.min_value", 0.35)))
        & (hsv_v <= float(cfg_get(cfg, "dialog.max_value", 0.98)))
    )
    diag["mask_pixels"] = int(mask.sum())

    pruned = _prune_mask(mask, int(cfg_get(cfg, "dialog.noise_min_neighbors", 3)), 0, 0.0)
    diag["pruned_pixels"] = int(pruned.sum())

    components, truncated = label_components(
        pruned, int(cfg_get(cfg, "dialog.max_component_pixels", 120000))
    )
    diag["truncated"] = truncated
    diag["components"] = len(components)

    min_w = int(cfg_get(cfg, "dialog.min_width_px", 160))
    max_w = int(cfg_get(cfg, "dialog.max_width_px", 1000))
    min_hh = int(cfg_get(cfg, "dialog.min_height_px", 56))
    max_hh = int(cfg_get(cfg, "dialog.max_height_px", 220))
    min_fill = float(cfg_get(cfg, "dialog.min_fill_ratio", 0.55))
    min_area = int(cfg_get(cfg, "dialog.min_area_px", 6000))
    max_std = float(cfg_get(cfg, "dialog.color_uniformity_max_std", 26.0))

    candidates: List[Blob] = []
    for cys, cxs in components:
        blob = _blob_from_component(cys, cxs, sub)
        if not (min_w <= blob.width <= max_w) or not (min_hh <= blob.height <= max_hh):
            diag["rejected"]["size"] += 1
            continue
        if blob.width <= blob.height or blob.fill < min_fill or blob.area < min_area:
            diag["rejected"]["shape"] += 1
            continue
        if blob.color_std > max_std:
            diag["rejected"]["uniformity"] += 1
            continue
        # 贴着区域边缘的通常是背景，不是按钮
        bx0, by0, bx1, by1 = blob.bbox
        if bx0 <= 1 or by0 <= 1 or bx1 >= (x1 - x0) - 2 or by1 >= (y1 - y0) - 2:
            diag["rejected"]["shape"] += 1
            continue
        blob.x += x0
        blob.y += y0
        candidates.append(blob)

    diag["candidates"] = len(candidates)
    if not candidates:
        return None, diag

    if str(cfg_get(cfg, "dialog.pick", "bottom")) == "largest":
        candidates.sort(key=lambda b: b.area, reverse=True)
    else:
        candidates.sort(key=lambda b: -b.y)
    return candidates[0], diag


# ===========================================================================
# 六、号库列表导航（宿主 App，uiautomator 可用）
# ===========================================================================
def parse_bounds(text: Optional[str]) -> Optional[Tuple[int, int, int, int]]:
    """解析 '[l,t][r,b]' 形式的 bounds。"""
    if not text:
        return None
    nums = [int(v) for v in re.findall(r"-?\d+", text)]
    if len(nums) != 4:
        return None
    return nums[0], nums[1], nums[2], nums[3]


def build_parent_map(root: ET.Element) -> Dict[ET.Element, Optional[ET.Element]]:
    """建立 子节点 -> 父节点 映射（用于向上找可点击的卡片）。"""
    mapping: Dict[ET.Element, Optional[ET.Element]] = {root: None}
    for parent in root.iter():
        for child in parent:
            mapping[child] = parent
    return mapping


def find_text_node(root: ET.Element, label: str) -> Optional[ET.Element]:
    """按 text / content-desc 精确或包含匹配找节点（精确优先）。

    注意：ElementTree 的 Element 在布尔上下文里等于「有没有子节点」，
    所以这里必须用 `is not None` 判断，不能写 `return exact or fuzzy`。
    """
    exact: Optional[ET.Element] = None
    fuzzy: Optional[ET.Element] = None
    for node in root.iter():
        text = node.get("text") or ""
        desc = node.get("content-desc") or ""
        if text == label or desc == label:
            exact = node
            break
        if fuzzy is None and label and (label in text or label in desc):
            fuzzy = node
    return exact if exact is not None else fuzzy


def choose_tap_node(node: ET.Element, parent_map: Dict[ET.Element, Optional[ET.Element]],
                    cfg: Dict[str, Any]) -> ET.Element:
    """向上找最多 3 层的可点击祖先，避免点到整屏容器。"""
    current = node
    for _ in range(3):
        bounds = parse_bounds(current.get("bounds"))
        if bounds and (bounds[3] - bounds[1]) > 420:
            break
        if current.get("clickable") == "true":
            return current
        parent = parent_map.get(current)
        if parent is None:
            break
        current = parent
    return current


def find_group_headers(root: ET.Element, cfg: Dict[str, Any]) -> List[Tuple[str, ET.Element]]:
    """找分组表头节点：宽度 > 阈值 的横条。"""
    min_w = int(cfg_get(cfg, "vault.header_min_width_px", 800))
    max_h = int(cfg_get(cfg, "vault.header_max_height_px", 220))
    headers: List[Tuple[str, ET.Element]] = []
    for node in root.iter():
        bounds = parse_bounds(node.get("bounds"))
        if not bounds:
            continue
        l, t, r, b = bounds
        if (r - l) >= min_w and (b - t) <= max_h:
            headers.append((node.get("text") or "", node))
    return headers


def ensure_group_expanded(adb: Adb, cfg: Dict[str, Any], root: ET.Element,
                          group_name: str, throttle: "Throttle") -> bool:
    """若 group_meta 显示该分组是折叠的，点表头展开它。"""
    if not group_name:
        return False
    for text, node in find_group_headers(root, cfg):
        if group_name in text:
            bounds = parse_bounds(node.get("bounds"))
            if not bounds:
                continue
            l, t, r, b = bounds
            x = l + (r - l) * float(cfg_get(cfg, "vault.header_tap_x_ratio", 0.25))
            y = (t + b) / 2.0
            log(f"  展开分组表头「{text}」-> tap ({x:.0f}, {y:.0f})")
            adb.tap(x, y)
            throttle.wait_tap()
            return True
    return False


def find_and_tap_entry(adb: Adb, cfg: Dict[str, Any], account: Account,
                       collapsed_groups: Iterable[str], throttle: "Throttle",
                       first_open: bool) -> bool:
    """打开号库 → 找到入口卡片 → 点击。找不到时展开分组 / 上滑滚动重试。"""
    vault_pkg = cfg_get(cfg, "vault.package", "com.carlos.multiapp")
    activity = cfg_get(cfg, "vault.activity", "")
    remote = cfg_get(cfg, "paths.ui_dump_remote", "/sdcard/auto_claim_ui.xml")

    adb.start_activity(f"{vault_pkg}/{activity}")
    time.sleep(cfg_get(cfg, "vault.cold_start_wait_sec", 9.0) if first_open
               else cfg_get(cfg, "vault.warm_start_wait_sec", 3.5))

    max_attempts = int(cfg_get(cfg, "vault.max_scroll_attempts", 6))
    expanded_this_round = False

    for attempt in range(1, max_attempts + 1):
        xml_text = adb.dump_ui(remote, int(cfg_get(cfg, "vault.ui_dump_retry", 3)))
        if not xml_text:
            log("uiautomator dump 持续失败，放弃该账号", "ERROR")
            return False
        try:
            root = ET.fromstring(xml_text)
        except ET.ParseError as exc:
            log(f"UI XML 解析失败: {exc}", "ERROR")
            return False

        node = find_text_node(root, account.label)
        if node is not None:
            parent_map = build_parent_map(root)
            target = choose_tap_node(node, parent_map, cfg)
            bounds = parse_bounds(target.get("bounds"))
            if not bounds:
                log("入口节点无 bounds，放弃", "ERROR")
                return False
            l, t, r, b = bounds
            x, y = (l + r) / 2.0, (t + b) / 2.0
            log(f"  找到入口「{account.label}」bounds={bounds} -> tap ({x:.0f}, {y:.0f})")
            adb.tap(x, y)
            return True

        # 找不到：先尝试展开目标分组（仅一次），再上滑滚动
        if not expanded_this_round and account.group in set(collapsed_groups):
            log(f"  第 {attempt} 次未找到入口，先展开分组「{account.group}」")
            ensure_group_expanded(adb, cfg, root, account.group, throttle)
            expanded_this_round = True
            time.sleep(1.2)
            continue

        log(f"  第 {attempt}/{max_attempts} 次未找到入口「{account.label}」，上滑滚动")
        adb.swipe(
            cfg_get(cfg, "vault.scroll_x", 630),
            cfg_get(cfg, "vault.scroll_y_from", 2200),
            cfg_get(cfg, "vault.scroll_x", 630),
            cfg_get(cfg, "vault.scroll_y_to", 700),
            int(cfg_get(cfg, "vault.scroll_duration_ms", 400)),
        )
        throttle.wait_swipe()

    log(f"滚动 {max_attempts} 次仍未找到入口「{account.label}」，跳过", "ERROR")
    return False


def wait_game_ready(adb: Adb, cfg: Dict[str, Any]) -> bool:
    """轮询 pidof，等游戏进程真正起来。"""
    package = cfg_get(cfg, "game.package", "com.gof.china")
    timeout = float(cfg_get(cfg, "game.launch_timeout_sec", 35.0))
    poll = float(cfg_get(cfg, "game.launch_poll_sec", 2.0))
    deadline = time.time() + timeout
    while time.time() < deadline:
        pids = adb.pidof(package)
        if pids:
            log(f"  游戏已启动 pid={pids}")
            return True
        time.sleep(poll)
    log(f"等待游戏启动超时（{timeout:.0f}s）", "ERROR")
    return False


def stop_game(adb: Adb, cfg: Dict[str, Any]) -> bool:
    """停掉游戏进程并等它消失。"""
    package = cfg_get(cfg, "game.package", "com.gof.china")
    adb.force_stop(package)
    deadline = time.time() + float(cfg_get(cfg, "game.stop_wait_timeout_sec", 10.0))
    while time.time() < deadline:
        if not adb.pidof(package):
            return True
        time.sleep(1.0)
    log("游戏进程未能停止（继续后续流程，但状态可能异常）", "WARN")
    return False


# ===========================================================================
# 七、节流器（只点检测到的目标）
# ===========================================================================
class Throttle:
    """模拟人手节奏的等待 + 统一的「安全点击」入口。"""

    def __init__(self, cfg: Dict[str, Any]) -> None:
        self.cfg = cfg

    def wait_tap(self) -> None:
        sleep_rand(cfg_get(self.cfg, "throttle.tap_wait_min_sec", 0.8),
                   cfg_get(self.cfg, "throttle.tap_wait_max_sec", 2.5))

    def wait_swipe(self) -> None:
        sleep_rand(cfg_get(self.cfg, "throttle.swipe_wait_min_sec", 0.5),
                   cfg_get(self.cfg, "throttle.swipe_wait_max_sec", 1.5))

    def settle(self, key: str = "after_tap_settle_sec", default: float = 1.6) -> None:
        time.sleep(float(cfg_get(self.cfg, f"throttle.{key}", default)))

    def safe_tap(self, adb: Adb, x: float, y: float, kind: str,
                 taps_log: List[Dict[str, Any]]) -> None:
        """唯一的点击入口：kind 必须是检测到的目标类型，否则拒绝点击。"""
        allowed = {"red_dot", "dialog_button"}
        if kind not in allowed:
            raise ValueError(f"拒绝点击：只允许 {sorted(allowed)}，收到 kind={kind!r}")
        if not bool(cfg_get(self.cfg, "safety.tap_only_detected_target", True)):
            raise RuntimeError("安全开关被关闭，拒绝执行点击")
        self.wait_tap()
        log(f"  >>> tap [{kind}] ({x:.0f}, {y:.0f})")
        adb.tap(x, y)
        taps_log.append({
            "kind": kind,
            "x": int(round(x)),
            "y": int(round(y)),
            "ts": datetime.now().strftime("%H:%M:%S"),
        })


# ===========================================================================
# 八、留证：日志目录 / report.json / 每日一次状态
# ===========================================================================
class Evidence:
    """每个账号一份目录：逐步截图 + report.json。"""

    def __init__(self, cfg: Dict[str, Any], label: str, mode: str) -> None:
        logs_root = TOOLS_DIR / str(cfg_get(cfg, "paths.logs_dir", "logs"))
        self.dir = logs_root / datetime.now().strftime("%Y-%m-%d") / sanitize(label)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.seq = 0
        self.report: Dict[str, Any] = {
            "mode": mode,
            "label": label,
            "started_at": datetime.now().isoformat(timespec="seconds"),
            "finished_at": None,
            "duration_sec": 0.0,
            "status": "running",
            "error": None,
            "red_dots_seen": 0,
            "taps_total": 0,
            "steps": [],
            "taps": [],
        }
        self._start = time.time()
        self._mode = mode
        self.debug = False

    def shot(self, img: Image.Image, tag: str, extra: Optional[Dict[str, Any]] = None,
             mask: Optional[np.ndarray] = None) -> str:
        """保存一张截图，返回文件名。"""
        self.seq += 1
        name = f"{self.seq:03d}_{tag}.png"
        img.save(self.dir / name)
        if self.debug and mask is not None:
            Image.fromarray((mask.astype(np.uint8) * 255)).save(self.dir / f"{self.seq:03d}_{tag}_mask.png")
        step: Dict[str, Any] = {
            "seq": self.seq,
            "tag": tag,
            "file": name,
            "ts": datetime.now().strftime("%H:%M:%S"),
        }
        if extra:
            step.update(extra)
        self.report["steps"].append(step)
        return name

    def finish(self, status: str, error: Optional[str] = None) -> None:
        """写 report.json。"""
        self.report["finished_at"] = datetime.now().isoformat(timespec="seconds")
        self.report["duration_sec"] = round(time.time() - self._start, 2)
        self.report["status"] = status
        self.report["error"] = error
        with (self.dir / "report.json").open("w", encoding="utf-8") as fp:
            json.dump(self.report, fp, ensure_ascii=False, indent=2)


def load_state(cfg: Dict[str, Any]) -> Dict[str, Any]:
    """读取 state/last_run.json。"""
    path = TOOLS_DIR / str(cfg_get(cfg, "paths.state_dir", "state")) / "last_run.json"
    if not path.exists():
        return {}
    try:
        with path.open("r", encoding="utf-8") as fp:
            return json.load(fp)
    except (json.JSONDecodeError, OSError):
        return {}


def save_state(cfg: Dict[str, Any], state: Dict[str, Any]) -> None:
    """写回 state/last_run.json。"""
    path = TOOLS_DIR / str(cfg_get(cfg, "paths.state_dir", "state")) / "last_run.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fp:
        json.dump(state, fp, ensure_ascii=False, indent=2)


def already_ran_today(state: Dict[str, Any], label: str) -> Optional[Dict[str, Any]]:
    """今天是否已经跑过该账号。"""
    today = datetime.now().strftime("%Y-%m-%d")
    return (state.get(today) or {}).get(label)


def mark_ran_today(state: Dict[str, Any], label: str, info: Dict[str, Any]) -> None:
    """记录该账号今天已跑。"""
    today = datetime.now().strftime("%Y-%m-%d")
    state.setdefault(today, {})[label] = info


# ===========================================================================
# 九、模式实现
# ===========================================================================
def open_entry(adb: Adb, cfg: Dict[str, Any], account: Account,
               collapsed_groups: Iterable[str], throttle: Throttle,
               first_open: bool) -> bool:
    """打开号库 → 点入口 → 等游戏就绪。失败返回 False。"""
    package = cfg_get(cfg, "game.package", "com.gof.china")
    if bool(cfg_get(cfg, "game.stop_before_open", True)) and adb.pidof(package):
        log("  打开前先停掉残留游戏进程")
        stop_game(adb, cfg)

    if not find_and_tap_entry(adb, cfg, account, collapsed_groups, throttle, first_open):
        return False
    if not wait_game_ready(adb, cfg):
        return False
    throttle.settle("after_tap_settle_sec", 1.6)
    time.sleep(float(cfg_get(cfg, "game.ready_wait_sec", 6.0)))
    return True


def return_to_vault(adb: Adb, cfg: Dict[str, Any]) -> None:
    """回到号库列表：停游戏 → 重新拉起 AccountVaultActivity。"""
    if bool(cfg_get(cfg, "game.stop_before_next", True)):
        stop_game(adb, cfg)
    vault_pkg = cfg_get(cfg, "vault.package", "com.carlos.multiapp")
    activity = cfg_get(cfg, "vault.activity", "")
    adb.start_activity(f"{vault_pkg}/{activity}")
    time.sleep(float(cfg_get(cfg, "vault.warm_start_wait_sec", 3.5)))


def describe_dots(dots: Sequence[Blob]) -> str:
    """把红点列表格式化成人能读的一行。"""
    if not dots:
        return "无"
    return ", ".join(f"({d.x:.0f},{d.y:.0f}) r≈{np.sqrt(d.area / np.pi):.1f} "
                     f"{d.width}x{d.height} fill={d.fill:.2f}" for d in dots[:10]) + \
           (f" ... 共 {len(dots)} 个" if len(dots) > 10 else "")


def mode_record(adb: Adb, cfg: Dict[str, Any], args: argparse.Namespace,
                accounts: Sequence[Account], collapsed_groups: Iterable[str]) -> int:
    """record：打开某个入口后持续截图，供人工挑选按钮模板。"""
    label = args.label or (accounts[0].label if accounts else "manual")
    account = next((a for a in accounts if a.label == label), None)
    if account is None:
        account = Account(id=label, label=label)

    throttle = Throttle(cfg)
    if not args.no_open:
        log(f"打开入口「{label}」…")
        if not open_entry(adb, cfg, account, collapsed_groups, throttle, first_open=True):
            log("入口打开失败，录制中止", "ERROR")
            return 2

    out_dir = TOOLS_DIR / str(cfg_get(cfg, "paths.samples_dir", "samples")) / sanitize(label)
    out_dir.mkdir(parents=True, exist_ok=True)
    interval = float(args.interval or cfg_get(cfg, "record.interval_sec", 0.7))
    max_frames = int(args.max_frames or cfg_get(cfg, "record.max_frames", 300))
    log(f"开始录制到 {out_dir}（间隔 {interval}s，上限 {max_frames} 帧，Ctrl-C 结束）")

    frames: List[Dict[str, Any]] = []
    t0 = time.time()
    try:
        for idx in range(1, max_frames + 1):
            img = adb.screencap()
            if img is None:
                log("截图失败，录制中止", "ERROR")
                break
            name = f"{idx:03d}.png"
            img.save(out_dir / name)
            frames.append({
                "file": name,
                "t": round(time.time() - t0, 2),
                "ts": datetime.now().isoformat(timespec="seconds"),
            })
            if idx % 10 == 0:
                log(f"  已录制 {idx} 帧")
            time.sleep(interval)
    except KeyboardInterrupt:
        log("收到 Ctrl-C，录制结束")

    index = {
        "label": label,
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "interval_sec": interval,
        "frames": frames,
    }
    with (out_dir / "index.json").open("w", encoding="utf-8") as fp:
        json.dump(index, fp, ensure_ascii=False, indent=2)
    log(f"共 {len(frames)} 张，样本目录：{out_dir}")
    return 0


def mode_dry_run(adb: Adb, cfg: Dict[str, Any], args: argparse.Namespace,
                 accounts: Sequence[Account], collapsed_groups: Iterable[str]) -> int:
    """dry-run：只截图 + 检测红点，**绝不点击**。"""
    state = load_state(cfg)
    targets = select_accounts(accounts, args.limit, args.labels_csv, args.group)
    if not targets:
        log("没有匹配的账号", "WARN")
        return 1

    log(f"dry-run 目标 {len(targets)} 个账号：{[a.label for a in targets]}")
    throttle = Throttle(cfg)
    shots = max(1, min(3, int(args.shots)))
    first = True
    failed: List[str] = []

    for account in targets:
        log(f"=== [dry-run] {account.label} ===")
        evidence = Evidence(cfg, account.label, "dry-run")
        evidence.debug = bool(args.debug)
        try:
            if not open_entry(adb, cfg, account, collapsed_groups, throttle, first):
                evidence.finish("error", "打开入口失败")
                failed.append(account.label)
                return_to_vault(adb, cfg)
                first = False
                continue
            first = False

            scan_status = "ok"
            for i in range(1, shots + 1):
                img = adb.screencap()
                if img is None:
                    scan_status = "error"
                    failed.append(account.label)
                    break
                dots, diag = find_red_dots(img, cfg)
                evidence.report["red_dots_seen"] = max(evidence.report["red_dots_seen"], len(dots))
                evidence.shot(
                    img, f"scan{i}",
                    extra={
                        "red_dots": len(dots),
                        "dots": [{"x": int(d.x), "y": int(d.y), "area": d.area,
                                  "w": d.width, "h": d.height, "fill": round(d.fill, 3)}
                                 for d in dots],
                        "diag": diag,
                    },
                    mask=build_red_mask(to_rgb_np(img), cfg) if args.debug else None,
                )
                log(f"  第 {i} 张：检测到 {len(dots)} 个红点 -> {describe_dots(dots)}")
                btn0, ddiag = find_dialog_primary_button(img, cfg)
                log(f"          弹窗主按钮：{'有' if btn0 is not None else '无'}"
                    f"（候选 {ddiag['candidates']} / 连通域 {ddiag['components']} / 掩码像素 {ddiag['mask_pixels']}）")
                if btn0 is not None:
                    log(f"          按钮 ({btn0.x:.0f}, {btn0.y:.0f}) {btn0.width}x{btn0.height} "
                        f"fill={btn0.fill:.2f} std={btn0.color_std:.1f}")
                if i < shots:
                    time.sleep(float(cfg_get(cfg, "throttle.between_shots_sec", 1.2)))

            evidence.finish(scan_status, None if scan_status == "ok" else "截图失败")
            log(f"  报告：{evidence.dir / 'report.json'}")
        except Exception as exc:  # noqa: BLE001
            log(f"  异常：{exc}", "ERROR")
            evidence.finish("error", str(exc))
            failed.append(account.label)
        finally:
            return_to_vault(adb, cfg)

    log(f"dry-run 完成，失败/跳过：{failed or '无'}")
    return 0 if not failed else 2


def mode_run(adb: Adb, cfg: Dict[str, Any], args: argparse.Namespace,
             accounts: Sequence[Account], collapsed_groups: Iterable[str]) -> int:
    """run：逐账号打开入口 → 等就绪 → 循环清扫红点 → 回号库列表。"""
    state = load_state(cfg)
    targets = select_accounts(accounts, args.limit, args.labels_csv, args.group)
    if not targets:
        log("没有匹配的账号", "WARN")
        return 1

    log(f"run 目标 {len(targets)} 个账号：{[a.label for a in targets]}")
    throttle = Throttle(cfg)
    max_taps = int(args.max_taps or cfg_get(cfg, "loop.max_taps", 40))
    max_rounds = int(args.max_rounds or cfg_get(cfg, "loop.max_rounds", 12))
    no_progress_limit = int(cfg_get(cfg, "loop.no_progress_rounds", 2))
    close_strategy = str(cfg_get(cfg, "safety.close_strategy", "back"))
    max_backs = int(cfg_get(cfg, "safety.max_back_presses", 2))

    first = True
    failed: List[str] = []
    skipped: List[str] = []

    for account in targets:
        log(f"=== [run] {account.label} ===")
        if not args.force:
            ran = already_ran_today(state, account.label)
            if ran:
                log(f"  今天已跑过（{ran.get('status')}），跳过；加 --force 可重跑")
                skipped.append(account.label)
                continue

        evidence = Evidence(cfg, account.label, "run")
        evidence.debug = bool(args.debug)
        status, error = "ok", None
        taps_done = 0
        try:
            if not open_entry(adb, cfg, account, collapsed_groups, throttle, first):
                evidence.finish("error", "打开入口失败")
                failed.append(account.label)
                return_to_vault(adb, cfg)
                first = False
                continue
            first = False

            no_progress = 0
            prev_count: Optional[int] = None

            for round_no in range(1, max_rounds + 1):
                if taps_done >= max_taps:
                    log(f"  已达 --max-taps={max_taps}，停止该号")
                    break

                img = adb.screencap()
                if img is None:
                    raise RuntimeError("截图失败")
                dots, diag = find_red_dots(img, cfg)
                evidence.report["red_dots_seen"] = max(evidence.report["red_dots_seen"], len(dots))
                evidence.shot(
                    img, f"round{round_no}_scan",
                    extra={"red_dots": len(dots),
                           "dots": [{"x": int(d.x), "y": int(d.y), "area": d.area} for d in dots],
                           "diag": diag},
                    mask=build_red_mask(to_rgb_np(img), cfg) if args.debug else None,
                )
                log(f"  round {round_no}: {len(dots)} 个红点 -> {describe_dots(dots)}")

                if not dots:
                    # 没有红点了：若还有残留弹窗，按策略关一次再确认
                    btn, _ = find_dialog_primary_button(img, cfg)
                    if btn is not None and close_strategy == "back" and max_backs > 0:
                        log("  无红点但仍有弹窗，按返回键关闭")
                        for _ in range(max_backs):
                            throttle.wait_swipe()
                            adb.keyevent(4)
                            throttle.settle("after_dialog_sec", 1.4)
                            img2 = adb.screencap()
                            if img2 is None:
                                raise RuntimeError("截图失败")
                            if find_dialog_primary_button(img2, cfg)[0] is None:
                                break
                        evidence.shot(img, f"round{round_no}_closed")
                    log("  清扫完成（无红点）")
                    break

                target = dots[0]
                throttle.safe_tap(adb, target.x, target.y, "red_dot", evidence.report["taps"])
                taps_done += 1
                evidence.report["taps_total"] = taps_done
                throttle.settle("after_tap_settle_sec", 1.6)

                img2 = adb.screencap()
                if img2 is None:
                    raise RuntimeError("截图失败")
                btn, ddiag = find_dialog_primary_button(img2, cfg)
                evidence.shot(img2, f"round{round_no}_after_tap",
                              extra={"dialog_button": {"x": int(btn.x), "y": int(btn.y),
                                                       "w": btn.width, "h": btn.height,
                                                       "fill": round(btn.fill, 3),
                                                       "std": round(btn.color_std, 2)}
                                     if btn else None,
                                     "diag": ddiag})

                progressed = False
                if btn is not None:
                    log(f"  检测到弹窗主按钮 ({btn.x:.0f}, {btn.y:.0f}) {btn.width}x{btn.height} "
                        f"fill={btn.fill:.2f} std={btn.color_std:.1f}")
                    throttle.safe_tap(adb, btn.x, btn.y, "dialog_button", evidence.report["taps"])
                    taps_done += 1
                    evidence.report["taps_total"] = taps_done
                    throttle.settle("after_dialog_sec", 1.4)
                    progressed = True

                    img3 = adb.screencap()
                    if img3 is None:
                        raise RuntimeError("截图失败")
                    still, _ = find_dialog_primary_button(img3, cfg)
                    evidence.shot(img3, f"round{round_no}_after_confirm",
                                  extra={"dialog_still_present": still is not None})
                    if still is not None:
                        log("  弹窗仍在，按策略关闭残留弹窗", "WARN")
                        if close_strategy == "back":
                            for _ in range(max_backs):
                                throttle.wait_swipe()
                                adb.keyevent(4)
                                throttle.settle("after_dialog_sec", 1.4)
                                img4 = adb.screencap()
                                if img4 is None:
                                    raise RuntimeError("截图失败")
                                if find_dialog_primary_button(img4, cfg)[0] is None:
                                    break
                            evidence.shot(img3, f"round{round_no}_after_close")
                        else:
                            log("  close_strategy=none，不处理残留弹窗", "WARN")
                else:
                    log("  未检测到弹窗主按钮，不点击（本轮记为无进展）", "WARN")

                if diag.get("truncated"):
                    log("  红像素过多，结果不可靠，记无进展", "WARN")
                    no_progress += 1
                elif progressed:
                    no_progress = 0
                elif prev_count is not None and len(dots) >= prev_count:
                    no_progress += 1
                prev_count = len(dots)

                if no_progress >= no_progress_limit:
                    log(f"  连续 {no_progress} 轮无进展，停止该号（失败即停）", "ERROR")
                    status, error = "error", "连续无进展"
                    break
        except Exception as exc:  # noqa: BLE001
            log(f"  异常：{exc}", "ERROR")
            status, error = "error", str(exc)

        evidence.report["taps_total"] = taps_done
        evidence.finish(status, error)
        log(f"  报告：{evidence.dir / 'report.json'}（status={status}, taps={taps_done}）")

        if status == "ok":
            mark_ran_today(state, account.label, {
                "status": "ok",
                "taps": taps_done,
                "dots": evidence.report["red_dots_seen"],
                "at": datetime.now().isoformat(timespec="seconds"),
            })
            save_state(cfg, state)
        else:
            failed.append(account.label)

        return_to_vault(adb, cfg)

    log(f"run 完成。成功 {len(targets) - len(failed) - len(skipped)} / 失败 {failed or '无'} / 跳过 {skipped or '无'}")
    return 0 if not failed else 2


# ===========================================================================
# 十、入口
# ===========================================================================
def build_parser() -> argparse.ArgumentParser:
    """构造命令行参数。"""
    parser = argparse.ArgumentParser(
        prog="auto_claim.py",
        description="无尽冬日 容器内自动领取免费物品（截图 + 图像识别，只点红点与弹窗按钮）",
    )
    parser.add_argument("--config", default=str(DEFAULT_CONFIG_PATH), help="配置文件路径")
    parser.add_argument("--adb", default=None, help="adb 可执行文件路径")
    parser.add_argument("--serial", default=None, help="设备序列号（多设备时指定）")
    sub = parser.add_subparsers(dest="mode", required=True)

    def add_common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--limit", type=int, default=None, help="只跑前 N 个账号")
        p.add_argument("--labels", dest="labels_csv", default=None, help="只跑这些标签，逗号分隔")
        p.add_argument("--group", default=None, help="只跑该分组")
        p.add_argument("--debug", action="store_true", help="额外保存检测掩码 PNG，便于调参")

    p_rec = sub.add_parser("record", help="录制：手动领一次，脚本持续截图存样本")
    add_common(p_rec)
    p_rec.add_argument("--label", default=None, help="要录制的账号标签")
    p_rec.add_argument("--no-open", action="store_true", help="不自动打开入口，直接录制当前屏幕")
    p_rec.add_argument("--interval", type=float, default=None, help="截图间隔秒")
    p_rec.add_argument("--max-frames", type=int, default=None, help="最多录多少帧")

    p_dry = sub.add_parser("dry-run", help="只截图检测红点，绝不点击")
    add_common(p_dry)
    p_dry.add_argument("--shots", type=int, default=2, help="每个账号截几张（1~3）")
    p_dry.add_argument("--force", action="store_true", help="dry-run 不受每日一次限制（默认本就不受限）")

    p_run = sub.add_parser("run", help="真跑：打开入口并清扫红点")
    add_common(p_run)
    p_run.add_argument("--max-taps", type=int, default=None, help="每号最多点击次数")
    p_run.add_argument("--max-rounds", type=int, default=None, help="每号最多清扫轮数")
    p_run.add_argument("--force", action="store_true", help="跳过「每号每天一次」限制")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    """程序入口。"""
    args = build_parser().parse_args(argv)
    cfg = load_config(Path(args.config))
    if args.adb:
        cfg["device"]["adb"] = args.adb
    if args.serial:
        cfg["device"]["serial"] = args.serial

    adb = Adb(
        binary=cfg_get(cfg, "device.adb", "adb"),
        serial=cfg_get(cfg, "device.serial", ""),
        timeout=float(cfg_get(cfg, "device.cmd_timeout_sec", 40)),
    )

    # 设备连通性检查：离线时给出明确的排查提示，而不是让后面每一步都超时
    devices = [line for line in adb.run(["devices"], timeout=15).stdout.splitlines()
               if line.strip().endswith("device")]
    if not devices:
        log("没有可用的 adb 设备。请检查：USB 线是否插好 / 手机是否已授权 USB 调试 / "
            "`adb devices` 是否有输出。", "ERROR")
        return 2
    log(f"已连接设备：{devices[0].split()[0]}")

    try:
        accounts, groups = load_accounts(adb, cfg)
    except Exception as exc:  # noqa: BLE001
        log(f"读取号库入口失败：{exc}", "ERROR")
        return 2

    collapsed = [g.get("name") for g in groups if g.get("collapsed")]
    log(f"号库共 {len(accounts)} 个入口，折叠分组：{collapsed or '无'}")

    if args.mode == "record":
        return mode_record(adb, cfg, args, accounts, collapsed)
    if args.mode == "dry-run":
        return mode_dry_run(adb, cfg, args, accounts, collapsed)
    if args.mode == "run":
        return mode_run(adb, cfg, args, accounts, collapsed)
    log(f"未知模式 {args.mode}", "ERROR")
    return 2


if __name__ == "__main__":
    sys.exit(main())
