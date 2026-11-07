#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
蓝星 - 浏览器自动化模块
通过 CDP 接管用户本机 Chrome，登录美团商家后台，爬取店铺和差评数据
通信方式：stdin 读取 JSON 命令，stdout 输出 JSON 结果
"""
import sys
import json
import os
import re
import time
import hashlib
import subprocess
import traceback

# 端口
CDP_PORT = 9222

# 美团外卖商家后台地址
MEITUAN_LOGIN_URL = "https://e.waimai.meituan.com/"
MEITUAN_HOME_URL = "https://e.waimai.meituan.com/"
# 评价页 hash 路由（商家后台内嵌页）。直跳此 URL 等价于「顾客管理→顾客评价」菜单链，
# 且能从页面卡在加载中/裸首页的状态恢复
REVIEW_PAGE_URL = "https://e.waimai.meituan.com/#https://waimaieapp.meituan.com/frontweb/ffw/userComment_gw"

# 全局缓存：浏览器加载时捕获的 poiList 完整 URL（含签名参数）
_poi_list_url = None
_poi_list_body = None

_chrome_proc = None
_browser = None
_playwright = None


def find_browser():
    """查找浏览器：优先 Edge，Chrome 作为备用。返回 (browser_name, exe_path)"""
    edge_candidates = [
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\Edge\Application\msedge.exe"),
    ]
    chrome_candidates = [
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"),
    ]
    for p in edge_candidates:
        if os.path.exists(p):
            return "edge", p
    for p in chrome_candidates:
        if os.path.exists(p):
            return "chrome", p
    return None, None


def send(data):
    """向 stdout 输出 JSON 响应并刷新（仅用于命令响应）
    ensure_ascii=True：中文转义为 \\uXXXX，纯 ASCII 输出，
    彻底避免不同电脑默认编码(GBK/UTF-8)不一致导致的乱码"""
    sys.stdout.write(json.dumps(data, ensure_ascii=True) + "\n")
    sys.stdout.flush()


def log(msg):
    """日志走 stderr，不污染 stdout 响应队列"""
    sys.stderr.write(f"[browser] {msg}\n")
    sys.stderr.flush()


_rapid_ocr = None


def _get_ocr():
    """懒加载 RapidOCR（仅首次调用时初始化）"""
    global _rapid_ocr
    if _rapid_ocr is None:
        from rapidocr_onnxruntime import RapidOCR
        _rapid_ocr = RapidOCR()
    return _rapid_ocr


# 调试截图开关：默认关闭，避免每次 OCR 都往 data/debug 写 PNG（磁盘 I/O 占 OCR 流程 15%~25%）
_DEBUG_SHOTS = os.environ.get("BLUESTAR_DEBUG_SHOTS", "0") == "1"


def _save_debug(img, path):
    """仅在 BLUESTAR_DEBUG_SHOTS=1 时落盘调试图"""
    if not _DEBUG_SHOTS or not path:
        return
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
    except Exception:
        pass


def _shot(page):
    """截取当前视口，返回 PIL.Image（走内存，不落盘）"""
    import io as _io
    from PIL import Image
    data = page.screenshot()
    return Image.open(_io.BytesIO(data))


def _ocr_img(img):
    """对 PIL.Image 直接 OCR（省去 PNG 写入/再读盘），返回 (res, elapse)"""
    import numpy as _np
    return _get_ocr()(_np.array(img))


def _find_pager_point(page, expected, shot_path):
    """通过 OCR 定位底部分页器上指定期望页码的点击坐标(CSS 像素)。
    美团分页器数字等间距排列：用 OCR 认出的数字做线性拟合，推算目标页码的 x。
    找不到返回 None。"""
    try:
        from PIL import Image
        im = _shot(page)
        W, H = im.size
        y0 = int(H * 0.65)  # 只取底部 35%（分页器所在区域）
        crop = im.crop((0, y0, W, H))
        scale = 3
        crop = crop.resize((crop.width * scale, crop.height * scale), Image.LANCZOS)
        _save_debug(crop, shot_path)
        res, _ = _ocr_img(crop)
        pts = []  # (digit, x, y) in scaled-crop coords
        if res:
            for it in res:
                box, text, conf = it
                t = (text or "").strip()
                if t.isdigit() and 1 <= int(t) <= 99:
                    xs = [p[0] for p in box]
                    ys = [p[1] for p in box]
                    pts.append((int(t), sum(xs) / len(xs), sum(ys) / len(ys)))
        if not pts:
            log("OCR 底部条带未识别到任何数字")
            return None
        # 调试：打印所有识别到的数字及其坐标
        try:
            dpr0 = page.evaluate("() => window.devicePixelRatio || 1")
        except Exception:
            dpr0 = 1
        for d, x, y in pts:
            log(f"  OCR数字 {d} 原图坐标=({x/scale:.0f},{y0 + y/scale:.0f}) CSS=({(x/scale)/dpr0:.0f},{(y0+y/scale)/dpr0:.0f})")
        # 取同一行（y 聚类）的数字，剔除非分页器的数字
        ys = sorted(p[2] for p in pts)
        ymed = ys[len(ys) // 2]
        row = [p for p in pts if abs(p[2] - ymed) < crop.height * 0.2]
        if not row:
            log("OCR 数字无同一行聚类")
            return None
        log(f"  分页行候选(同y): {[(p[0], round(p[1])) for p in row]}")
        # 取最长连续数字段做线性拟合 x = a*digit + b
        row = sorted(set((p[0], p[1], p[2]) for p in row))
        best = [row[0]]
        cur = [row[0]]
        for p in row[1:]:
            if p[0] == cur[-1][0] + 1:
                cur.append(p)
            else:
                if len(cur) > len(best):
                    best = cur
                cur = [p]
        if len(cur) > len(best):
            best = cur
        ds = [p[0] for p in best]
        xs = [p[1] for p in best]
        if len(best) >= 2:
            dmean = sum(ds) / len(ds)
            xmean = sum(xs) / len(xs)
            num = sum((d - dmean) * (x - xmean) for d, x in zip(ds, xs))
            den = sum((d - dmean) ** 2 for d in ds)
            slope = num / den if den else 0.0
        else:
            slope = 0.0
            dmean = ds[0]
            xmean = xs[0]
        if expected in ds:
            cx = xs[ds.index(expected)]
        elif slope != 0:
            cx = xmean + slope * (expected - dmean)
        else:
            return None
        cy = sum(p[2] for p in row) / len(row)
        try:
            dpr = page.evaluate("() => window.devicePixelRatio || 1")
        except Exception:
            dpr = 1
        css_x = (cx / scale) / dpr
        css_y = (y0 + cy / scale) / dpr
        return (css_x, css_y)
    except Exception as e:
        log(f"OCR 定位页码 {expected} 失败: {e}")
    return None


_pager_fit = None       # (slope, intercept, y_css)
_pager_fit_key = None   # "任务ID|视口宽x高|dpr"
_pager_task_id = ""
PAGER_MIN_GAP = 30.0    # 相邻页码间距合理范围（CSS 像素）
PAGER_MAX_GAP = 60.0


def _begin_pager_task(task_id):
    """任务开始：清空分页缓存并记录任务 ID（缓存 key 的第一段）"""
    global _pager_task_id, _pager_fit, _pager_fit_key
    _pager_task_id = str(task_id or "")
    _pager_fit = None
    _pager_fit_key = None
    log(f"分页缓存已清零(任务={_pager_task_id})")


def _reset_pager_fit():
    global _pager_fit, _pager_fit_key
    _pager_fit = None
    _pager_fit_key = None


def _pager_key(vw, vh, dpr):
    return f"{_pager_task_id}|{int(vw)}x{int(vh)}|{dpr}"


def _longest_run(rows):
    """按页码连续的最长一段（rows 为 (页码, x, y) 且已按页码排序）"""
    if not rows:
        return []
    best = cur = [rows[0]]
    for p in rows[1:]:
        if p[0] == cur[-1][0] + 1:
            cur.append(p)
        else:
            cur = [p]
        if len(cur) > len(best):
            best = cur
    return best


def _slope(points):
    """最小二乘斜率 dx/d页码，点数不足返回 None"""
    if len(points) < 2:
        return None
    dm = sum(p[0] for p in points) / len(points)
    xm = sum(p[1] for p in points) / len(points)
    den = sum((p[0] - dm) ** 2 for p in points)
    if den == 0:
        return None
    return sum((p[0] - dm) * (p[1] - xm) for p in points) / den


def _cache_pager_fit(rows_css, key):
    """缓存拟合结果；可用数字点少于 2 或间距不在 30–60px 内一律判定失败"""
    global _pager_fit, _pager_fit_key
    run = _longest_run(rows_css)
    slope = _slope(run) if len(run) >= 2 else None
    if slope is None:
        log(f"分页拟合失败：可用连续数字点={len(run)}（需≥2）")
        return False
    if not (PAGER_MIN_GAP <= slope <= PAGER_MAX_GAP):
        log(f"分页拟合失败：页码间距={slope:.1f}px 不在 {PAGER_MIN_GAP:.0f}-{PAGER_MAX_GAP:.0f}px")
        return False
    dm = sum(p[0] for p in run) / len(run)
    xm = sum(p[1] for p in run) / len(run)
    ym = sum(p[2] for p in run) / len(run)
    _pager_fit = (slope, xm - slope * dm, ym)
    _pager_fit_key = key
    log(f"分页拟合缓存({key}): x={slope:.1f}*页码+{xm - slope * dm:.1f} y={ym:.0f}")
    return True


def _cv2_pager_row(page):
    """cv2 扫描底部条带的「分页块」行，返回 (row, dpr, vw, vh)。
    row 元素为 (cx, cy, w, h, 块内部均值亮度)；扫描失败返回 ([], dpr, vw, vh)。"""
    dpr, vw, vh = 1, 0, 0
    try:
        import numpy as np
        import cv2
    except Exception as e:
        log(f"cv2 不可用: {e}")
        return [], dpr, vw, vh
    try:
        try:
            vw = page.evaluate("() => window.innerWidth || 0") or 0
            vh = page.evaluate("() => window.innerHeight || 0") or 0
            dpr = page.evaluate("() => window.devicePixelRatio || 1") or 1
        except Exception as e:
            log(f"cv2 扫描读取视口失败: {e}")
            return [], dpr, vw, vh
        im = _shot(page)
        W, H = im.size
        y0 = int(H * 0.78)
        strip = np.array(im.crop((0, y0, W, H)).convert("RGB"))
        gray = cv2.cvtColor(strip, cv2.COLOR_RGB2GRAY)
        edges = cv2.Canny(gray, 50, 150)
        edges = cv2.dilate(edges, np.ones((2, 2), np.uint8), iterations=1)
        cnts, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        boxes = []  # (cx, cy, w, h, 内部均值亮度)
        for c in cnts:
            x, y, w, h = cv2.boundingRect(c)
            if not (int(18 * dpr) <= w <= int(90 * dpr)):
                continue
            if not (int(14 * dpr) <= h <= int(60 * dpr)):
                continue
            if w / h < 0.6 or w / h > 3.5:
                continue
            inner = gray[y + h // 4:y + 3 * h // 4, x + w // 4:x + 3 * w // 4]
            if inner.size == 0:
                continue
            boxes.append((x + w / 2, y + h / 2 + y0, w, h, float(inner.mean())))
        if not boxes:
            log("cv2 扫描：底部条带未检测到任何分页块")
            return [], dpr, vw, vh
        ymed = sorted(b[1] for b in boxes)[len(boxes) // 2]
        row = [b for b in boxes if abs(b[1] - ymed) <= 6 * dpr]
        log(f"cv2 扫描：同行候选块 {len(row)} 个，内部亮度 {[round(b[4]) for b in row]}")
        return row, dpr, vw, vh
    except Exception as e:
        log(f"cv2 扫描异常: {e}")
    return [], dpr, vw, vh


def _cv2_next_page_point(page, shot_path=None):
    """OCR 失败时的回退：在分页器行里找「深色激活块」，取其右侧最近的「浅色页块」中心。
    仅用于「下一页」场景（当前只被 _turn_pages 调用）。任何校验不通过都返回 None 并记日志。"""
    row, dpr, vw, vh = _cv2_pager_row(page)
    if not row:
        return None
    dark = [b for b in row if b[4] < 120]
    if not dark:
        log("cv2 回退：未找到深色激活块，判定失败")
        return None
    if len(dark) > 1:
        log(f"cv2 回退：检测到 {len(dark)} 个深色块，无法确定激活块，判定失败")
        return None
    dx, dy = dark[0][0], dark[0][1]
    cand = []
    for b in row:
        if b[4] <= 200 or b[0] <= dx:
            continue
        gap_css = (b[0] - dx) / dpr
        if PAGER_MIN_GAP <= gap_css <= PAGER_MAX_GAP and abs(b[1] - dy) <= 5 * dpr:
            cand.append((gap_css, b))
    if not cand:
        log("cv2 回退：激活块右侧未找到间距 30-60px 的浅色页块，判定失败")
        return None
    cand.sort(key=lambda t: t[0])
    css_x = cand[0][1][0] / dpr
    css_y = cand[0][1][1] / dpr
    if vw and not (0 < css_x < vw * 0.995):
        log(f"cv2 回退：下一页坐标越界(x={css_x:.0f})，判定失败")
        return None
    if vh and not (0 < css_y < vh):
        log(f"cv2 回退：下一页坐标越界(y={css_y:.0f})，判定失败")
        return None
    log(f"cv2 回退命中：激活块 x={dx / dpr:.0f} → 下一页 x={css_x:.0f} y={css_y:.0f}")
    return (css_x, css_y)


def _pager_point_for(page, target, shot_path=None):
    """返回((x, y), "digit"/"cv2")；定位失败返回 (None, None)。

    分页器由 Flutter canvas 绘制，只能 OCR。数字等间距且位置固定，
    首屏 OCR 拟合「页码 → x」后按「任务ID+视口宽高+DPR」缓存，后续页直接算坐标。
    可用数字点少于 2 或间距不在 30–60px 时直接判定失败，不做外推。
    OCR 失败时回退到 cv2「深色激活块旁的浅色块」定位。
    """
    def _fallback():
        p = _cv2_next_page_point(page, shot_path)
        return (p, "cv2") if p else (None, None)

    try:
        from PIL import Image
        try:
            vw = page.evaluate("() => window.innerWidth || 0") or 0
            vh = page.evaluate("() => window.innerHeight || 0") or 0
            dpr = page.evaluate("() => window.devicePixelRatio || 1") or 1
        except Exception as e:
            log(f"读取视口尺寸失败，放弃分页缓存: {e}")
            vw = vh = 0
            dpr = 1
        key = _pager_key(vw, vh, dpr) if (vw and vh) else None
        # 1) 命中缓存：直接算坐标（需通过间距与视口合理性校验）
        if _pager_fit and _pager_fit_key == key and key:
            slope, intercept, fy = _pager_fit
            if not (PAGER_MIN_GAP <= slope <= PAGER_MAX_GAP):
                log(f"缓存间距异常({slope:.1f}px)，清缓存重新 OCR")
                _reset_pager_fit()
            else:
                fx = slope * target + intercept
                if 0 < fx < vw * 0.995 and 0 < fy < vh:
                    return (fx, fy), "digit"
                log(f"缓存坐标越界(页码{target} x={fx:.0f})，清缓存重新 OCR")
                _reset_pager_fit()
        # 2) 缓存未命中：OCR 一次并建立拟合
        im = _shot(page)
        W, H = im.size
        y0 = int(H * 0.65)
        crop = im.crop((0, y0, W, H))
        scale = 3
        crop = crop.resize((crop.width * scale, crop.height * scale), Image.LANCZOS)
        _save_debug(crop, shot_path)
        res, _ = _ocr_img(crop)
        pts = []
        if res:
            for it in res:
                box, text, conf = it
                t = (text or "").strip()
                if t.isdigit() and 1 <= int(t) <= 99:
                    xs = [p[0] for p in box]
                    ys = [p[1] for p in box]
                    pts.append((int(t), sum(xs) / len(xs), sum(ys) / len(ys)))
        if len(pts) < 2:
            log("分页器 OCR 数字不足（<2），判定定位失败，尝试 cv2 回退")
            return _fallback()
        ys = sorted(p[2] for p in pts)
        ymed = ys[len(ys) // 2]
        row = sorted(set((p[0], p[1], p[2]) for p in pts if abs(p[2] - ymed) < crop.height * 0.2))
        if len(row) < 2:
            log(f"分页行 OCR 数字不足（{len(row)}），判定定位失败，尝试 cv2 回退")
            return _fallback()
        rows_css = sorted((d, (x / scale) / dpr, (y0 + y / scale) / dpr) for d, x, y in row)
        run = _longest_run(rows_css)
        if len(run) < 2:
            log(f"可用连续数字点少于 2（{len(run)}），判定定位失败，尝试 cv2 回退")
            return _fallback()
        slope = _slope(run)
        if slope is None or not (PAGER_MIN_GAP <= slope <= PAGER_MAX_GAP):
            log(f"分页页码间距异常({slope}px)，判定定位失败，尝试 cv2 回退")
            return _fallback()
        cy = sum(p[2] for p in rows_css) / len(rows_css)
        hit = next((p for p in rows_css if p[0] == target), None)
        if hit:
            fx = hit[1]
            if vw and not (0 < fx < vw * 0.995):
                log(f"目标页码 {target} 坐标越界(x={fx:.0f})，判定定位失败，尝试 cv2 回退")
                return _fallback()
            if key:
                _cache_pager_fit(rows_css, key)
            return (fx, cy), "digit"
        # 目标页码不在可视窗口：按连续段斜率外推
        dm = sum(p[0] for p in run) / len(run)
        xm = sum(p[1] for p in run) / len(run)
        fx = xm + slope * (target - dm)
        if vw and not (0 < fx < vw * 0.995):
            log(f"外推页码 {target} 坐标越界(x={fx:.0f})，判定定位失败，尝试 cv2 回退")
            return _fallback()
        if key:
            _cache_pager_fit(rows_css, key)
        return (fx, cy), "digit"
    except Exception as e:
        log(f"定位翻页坐标失败: {e}，尝试 cv2 回退")
        return _fallback()


def _kill_port(port):
    """杀掉占用指定端口的进程（Windows）"""
    try:
        result = subprocess.run(
            ["netstat", "-ano"], capture_output=True, text=True, timeout=5
        )
        for line in result.stdout.splitlines():
            if f":{port}" in line and "LISTENING" in line:
                parts = line.split()
                pid = parts[-1]
                if pid and pid != "0":
                    subprocess.run(["taskkill", "/F", "/PID", pid],
                                   capture_output=True, timeout=5)
                    time.sleep(1)
    except:
        pass


def _try_connect():
    """尝试连接已有的 CDP，成功返回 browser，失败返回 None（短超时，失败快）"""
    global _playwright
    last = None
    for _ in range(2):
        try:
            if not _playwright:
                from playwright.sync_api import sync_playwright
                _playwright = sync_playwright().start()
            # 用 5 秒短超时，避免无端口时卡住 30 秒
            return _playwright.chromium.connect_over_cdp(
                f"http://127.0.0.1:{CDP_PORT}", timeout=5000
            )
        except Exception as e:
            last = e
            time.sleep(0.6)
    log(f"连接 CDP 失败: {last}")
    return None


def _connect_existing():
    """只复用/重连已运行的浏览器，绝不启动新窗口。成功返回 True。"""
    global _browser
    if _browser and _browser.is_connected():
        return True
    b = _try_connect()
    if b:
        _browser = b
        log("已重连到现有浏览器")
        return True
    return False


def _kill_our_browsers(profile_dir):
    """杀掉占用本自动化 profile 的 Edge/Chrome 进程（不误伤用户正常浏览器）"""
    try:
        ps = (
            "Get-CimInstance Win32_Process | "
            "Where-Object { ($_.Name -eq 'msedge.exe' -or $_.Name -eq 'chrome.exe') "
            "-and $_.CommandLine -like '*%s*' } | "
            "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
            % profile_dir.replace("\\", "\\\\")
        )
        subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps],
            capture_output=True, timeout=10,
        )
        time.sleep(1)
    except Exception as e:
        log(f"清理残留浏览器失败(跳过): {e}")


def launch_browser():
    """启动浏览器（优先 Edge，Chrome 备用）并通过 CDP 连接。会启动新窗口。"""
    global _chrome_proc, _browser, _playwright

    # 已经连接了，直接返回成功
    if _browser and _browser.is_connected():
        log("浏览器已连接")
        return True

    # 先尝试复用已运行的浏览器（可能上次没关），避免重复开窗口
    if _connect_existing():
        try:
            page = get_page()
            if page:
                page.goto(MEITUAN_LOGIN_URL, wait_until="domcontentloaded", timeout=15000)
        except:
            pass
        return True

    browser_name, browser_path = find_browser()
    if not browser_path:
        send({"type": "error", "message": "未找到 Edge 或 Chrome 浏览器，请先安装 Microsoft Edge"})
        return False
    log(f"使用浏览器: {browser_name} -> {browser_path}")

    # 不同浏览器用独立的用户数据目录，避免 profile 互相损坏
    profile_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", f"{browser_name}_profile")
    profile_dir = os.path.abspath(profile_dir)
    os.makedirs(profile_dir, exist_ok=True)

    # 没有可复用的浏览器：先清掉占用本 profile 的残留进程，再杀端口、启动新的
    _kill_our_browsers(profile_dir)
    _kill_port(CDP_PORT)
    time.sleep(1)

    try:
        _chrome_proc = subprocess.Popen([
            browser_path,
            f"--remote-debugging-port={CDP_PORT}",
            f"--user-data-dir={profile_dir}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--start-maximized",
            MEITUAN_LOGIN_URL,
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:
        send({"type": "error", "message": f"启动浏览器失败: {e}"})
        return False

    # 检测是否秒退（常见于 profile 被其它 Edge 占用）
    time.sleep(2)
    if _chrome_proc.poll() is not None:
        send({"type": "error",
              "message": "浏览器启动失败：可能有残留的自动化窗口占用了数据目录。请关闭所有弹出的 Edge/Chrome 窗口后重试。"})
        return False

    # 等待并连接（最多15秒）
    for i in range(15):
        time.sleep(1)
        b = _try_connect()
        if b:
            _browser = b
            log("浏览器连接成功")
            return True

    send({"type": "error",
          "message": "浏览器已打开但连接超时，请关闭所有 Edge/Chrome 窗口后重试"})
    return False


def get_page():
    """获取当前活动页面，没有则新建"""
    if not _browser:
        return None
    contexts = _browser.contexts
    if not contexts:
        return None
    pages = contexts[0].pages
    if pages:
        return pages[0]
    return contexts[0].new_page()


def check_login_status():
    """检查是否已登录美团"""
    page = get_page()
    if not page:
        return False
    try:
        url = page.url
        # 登录页特征：包含 login / passport / signin
        if any(k in url.lower() for k in ["login", "passport", "signin", "epassport"]):
            return False
        # 已进入商家后台（外卖商家域名）
        if "waimai.meituan.com" in url and not any(k in url.lower() for k in ["login", "passport"]):
            return True
        return False
    except:
        return False


def _setup_poilist_capture():
    """在当前页面设置响应监听，捕获 poiList 的完整 URL（含签名）"""
    global _poi_list_url, _poi_list_body
    try:
        page = get_page()
        if not page:
            return
        def on_resp(resp):
            global _poi_list_url
            try:
                url = resp.url
                if "poiList" in url and _poi_list_url is None:
                    _poi_list_url = url
                    log(f"记录 poiList URL: {url[:90]}")
                    try:
                        body = resp.text()
                        if body and len(body) > 20:
                            _poi_list_body = body
                            log(f"记录 poiList body, 长度={len(body)}")
                    except:
                        pass
            except:
                pass
        page.on("response", on_resp)
        # 已连接现有浏览器时，主动 reload 一次触发请求
        try:
            page.goto(MEITUAN_HOME_URL, wait_until="domcontentloaded", timeout=15000)
        except:
            pass
    except Exception as e:
        log(f"setup capture 异常: {e}")


def cmd_start_login(args):
    """启动浏览器并打开登录页"""
    global _poi_list_url, _poi_list_body
    _poi_list_url = None
    _poi_list_body = None
    ok = launch_browser()
    if ok:
        _setup_poilist_capture()
        send({"type": "login_started", "message": "浏览器已打开，请在浏览器中登录美团商家后台"})


def cmd_check_login(args):
    """检查登录状态（只复用已有浏览器，绝不主动开新窗口，避免轮询反复弹窗）"""
    if not _connect_existing():
        send({"type": "login_pending", "message": "浏览器未就绪，请稍候..."})
        return
    logged_in = check_login_status()
    if logged_in:
        send({"type": "login_success", "message": "登录成功"})
    else:
        send({"type": "login_pending", "message": "等待用户登录..."})


def _extract_shops_from_json(obj):
    """从 poiList 等接口 JSON 中递归提取店铺名（兼容多种字段命名）"""
    names = []
    seen = set()
    # 强标识字段：直接认作店铺名
    name_keys = ("wmPoiName", "poiName", "shopName", "poi_name", "shop_name", "app_poi_code")
    id_keys = ("poiId", "wmPoiId", "shopId", "app_poi_code", "poi_id", "id")
    # 裸 name 字段必须伴随这些 poi 特征字段之一，防止活动名/分类名/地址等嵌套污染
    poi_marker_keys = ("poiId", "wmPoiId", "poi_id", "openStatus", "isOpen", "is_open",
                       "appPoiCode", "wmPoiIdView", "poiStatus")

    def walk(o):
        if isinstance(o, dict):
            nm = None
            for k in name_keys:
                v = o.get(k)
                if isinstance(v, str) and v.strip():
                    nm = v.strip()
                    break
            if not nm:
                v = o.get("name")
                if isinstance(v, str) and v.strip() and any(m in o for m in poi_marker_keys):
                    nm = v.strip()
            has_id = any(k in o for k in id_keys)
            if nm and has_id and 2 <= len(nm) <= 40 and nm not in seen:
                seen.add(nm)
                names.append(nm)
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    walk(obj)
    return names


def _extract_account_from_page(page):
    """接口路径拿不到美团账号名时，从页面 DOM 顶层文本识别（如 fwsc_3187_001）"""
    try:
        return page.evaluate("""() => {
            const acctRe = /^[a-zA-Z][a-zA-Z0-9_]{2,}$/;
            const cands = [];
            document.querySelectorAll('*').forEach(el => {
                if (el.children.length > 0) return;
                const t = (el.textContent || '').trim();
                if (t && t.length >= 3 && t.length <= 40 && acctRe.test(t)) cands.push(t);
            });
            return cands.find(t => t.includes('_')) || cands[0] || null;
        }""")
    except Exception:
        return None


def _extract_top_shop_name_from_dom(page, require_anchored=False):
    """从顶栏店铺切换器读取当前店铺名（兼容单店账号）。

    服务商账号顶栏切换器显示「全部门店」，单店账号在同一个 span.txt 位置直接显示店名。
    class 中的哈希后缀（如 1pDiEh）每次发版都可能变化，这里只依赖语义类名 txt，
    并要求元素祖先同时包含「营业中/休息中/全部门店」状态文案，避免误抓导航菜单。
    require_anchored=True 时只接受带状态文案锚点的候选，用于切换后的严格校验。
    """
    js = """() => {
        const noise = new Set([
            '商家首页','订单管理','商品管理','顾客管理','财务管理','经营分析','活动中心',
            '体验中心','店铺设置','外卖课堂','服务市场','商家保险','帮扶动态','平台规则',
            '全部分类','向左收起','向右展开','门店','切换门店','选择门店',
            '营业中','停业中','门店上线中','仅接受预订','休息中','已下线','审核中',
            '商家综合体验分','门店外卖评分','品牌中心','查看规则','美团外卖'
        ]);
        const cands = [];
        document.querySelectorAll('span, div, a').forEach(el => {
            const cls = el.getAttribute('class') || '';
            const toks = cls.split(/\\s+/).filter(Boolean);
            if (!toks.some(k => k === 'txt' || k.startsWith('txt_'))) return;
            if (el.children.length > 0) return;
            const t = (el.textContent || '').trim();
            if (!t || t.length < 2 || t.length > 40) return;
            if (noise.has(t)) return;
            if (/^全部门店/.test(t)) return;
            if (/^[a-zA-Z][a-zA-Z0-9_]{2,}$/.test(t)) return;
            if (/^\\d+$/.test(t)) return;
            const r = el.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) return;
            if (r.top < 0 || r.top > 220) return;
            let anchored = false;
            let p = el;
            for (let i = 0; i < 5 && p; i++, p = p.parentElement) {
                const pt = (p.textContent || '').trim();
                if (pt.length < 240 &&
                    (pt.indexOf('营业中') >= 0 || pt.indexOf('休息中') >= 0 ||
                     pt.indexOf('停业中') >= 0 || pt.indexOf('全部门店') >= 0 ||
                     pt.indexOf('仅接受预订') >= 0 || pt.indexOf('门店上线中') >= 0 ||
                     pt.indexOf('已下线') >= 0)) {
                    anchored = true;
                    break;
                }
            }
            if (__REQUIRE_ANCHORED__ && !anchored) return;
            cands.push({ t: t, anchored: anchored, top: r.top, left: r.left });
        });
        if (!cands.length) return null;
        cands.sort((a, b) => (b.anchored - a.anchored) || (a.top - b.top) || (a.left - b.left));
        return cands[0].t;
    }"""
    js = js.replace("__REQUIRE_ANCHORED__", "true" if require_anchored else "false")
    frames = []
    try:
        frames = page.frames
    except Exception:
        frames = []
    for fr in frames:
        try:
            name = fr.evaluate(js)
            if name and str(name).strip():
                return str(name).strip()
        except Exception:
            continue
    return None


def _norm_shop_name(name):
    """店铺名归一化：去掉空格、括号、间隔号、符号，仅保留中文/字母/数字。"""
    if not name:
        return ""
    s = str(name).strip()
    return re.sub(r"[^\u4e00-\u9fa5a-zA-Z0-9]", "", s)


def _shop_names_match(actual, target):
    """模糊匹配店名：归一化后完全相等或互相包含。"""
    a = _norm_shop_name(actual)
    t = _norm_shop_name(target)
    if not a or not t:
        return False
    return a == t or a in t or t in a


def _click_shop_in_dropdown(page, shop_name):
    """下拉列表 OCR 模糊匹配店铺名并点击（兼容全/半角括号、间隔号差异）。"""
    try:
        boxes = _ocr_boxes(page, os.path.join(_REPLY_DBG_DIR, "ocr_dropdown.png"))
        tgt = _norm_shop_name(shop_name)
        if not tgt:
            return False
        best = None
        best_score = 0
        for item in boxes:
            text, bx, by = item[0], item[1], item[2]
            n = _norm_shop_name(text)
            if not n:
                continue
            if n == tgt:
                score = 1000
            elif n in tgt or tgt in n:
                score = len(n)
            else:
                continue
            if by < 80:  # 顶栏区域，不是下拉项
                continue
            if score > best_score:
                best_score = score
                best = (text, bx, by)
        if best:
            page.mouse.click(best[1], best[2])
            log(f"已按模糊匹配点击店铺: {best[0][:30]} (目标: {shop_name})")
            return True
    except Exception as e:
        log(f"下拉模糊匹配失败: {e}")
    return False


def _switch_and_verify(page, shop_name):
    """切换店铺并做二次校验，返回 (ok, actual_name, source)。

    ok=False 时调用方必须中止当前任务，不能继续操作页面（否则会误操作其他店铺）。
    """
    if not shop_name:
        return True, None, "SKIP"
    if not _switch_to_shop(page, shop_name):
        return False, None, "SWITCH_FAIL"
    return _verify_shop_after_switch(page, shop_name)


def _send_shop_not_found(shop_name, actual_name=None, source=None):
    """统一返回 SHOP_NOT_FOUND 错误，供 Java 中止整批任务并弹窗提示。"""
    log(f"店铺未找到或校验失败：目标={shop_name} 实际={actual_name or '未知'} 来源={source or 'NONE'}")
    send({"type": "error", "code": "SHOP_NOT_FOUND",
          "message": f"该店铺暂未寻到：{shop_name}（当前店铺：{actual_name or '未知'}），请重新执行"})


def _verify_shop_after_switch(page, shop_name, attempts=4):
    """切换后的二次校验：优先读取网页顶栏切换器 DOM（span.txt）中的店名。

    服务商账号与单店账号的店铺名都在同一个顶栏容器中显示（未切换时是「全部门店」），
    因此以 DOM 文本为唯一判定依据，不再让 OCR 结果覆盖 DOM 结论；
    仅当 DOM 完全读不到店名（页面变体/仍在加载）时，才用 OCR 兜底。

    返回 (matched, actual_name, source)。
    """
    actual = None
    source = "NONE"
    for _ in range(max(1, attempts)):
        try:
            dom_name = _extract_top_shop_name_from_dom(page, require_anchored=True)
        except Exception:
            dom_name = None
        if dom_name:
            actual = dom_name
            source = "DOM"
            if _shop_names_match(dom_name, shop_name):
                return True, dom_name, source
            # DOM 已明确读到别的店名（可能是页面还在刷新），继续等待下一轮
            page.wait_for_timeout(1200)
            continue

        ocr_name = None
        try:
            cands = _ocr_pick(page, ["营业中", "休息中", "停业中", "仅接受预订", "门店上线中", "已下线"])
            if cands:
                txt = cands[0][0] or ""
                for kw in ("营业中", "停业中", "休息中", "仅接受预订", "门店上线中", "已下线"):
                    if kw in txt:
                        txt = txt.split(kw)[0]
                ocr_name = txt.strip() or None
        except Exception:
            ocr_name = None
        if ocr_name:
            actual = ocr_name
            source = "OCR"
            if _shop_names_match(ocr_name, shop_name):
                return True, ocr_name, source
        page.wait_for_timeout(1500)
    return False, actual, source


def cmd_scrape_shops(args):
    """爬取店铺列表（服务商账号多个店铺；单店账号顶栏直接显示店名）"""
    if not _browser or not _browser.is_connected():
        if not launch_browser():
            send({"type": "error", "message": "浏览器连接失败，请重试"})
            return
    page = get_page()
    if not page:
        send({"type": "error", "message": "浏览器未连接"})
        return

    # 关键：先确认已登录，并等待商家后台真正渲染出「全部门店」切换器。
    # 避免登录刚跳转、页面还没加载完就抓取，导致误报“未找到店铺”。
    if not check_login_status():
        send({"type": "error", "message": "请先在浏览器中完成美团登录，登录成功后再读取店铺"})
        return
    try:
        page.goto(MEITUAN_HOME_URL, wait_until="domcontentloaded", timeout=20000)
    except:
        pass
    # 等待后台页面渲染（最多 10 秒），出现「全部门店」或单店顶栏店名即提前就绪；
    # 单店账号没有该切换器，因此不强制要求，超时也继续往下走接口抓取。
    for _ in range(10):
        try:
            if page.get_by_text("全部门店", exact=False).count() > 0:
                break
            if _extract_top_shop_name_from_dom(page):
                break
        except:
            pass
        time.sleep(1)

    shops = []
    account = None

    # 主方案 A：解析 poiList 接口返回（账号下店铺的权威数据源，最准确）
    try:
        captured = _poi_list_body
        if not captured:
            cap = []

            def _on_poi(resp, _c=cap):
                try:
                    if "poiList" in resp.url:
                        b = resp.text()
                        if b and len(b) > 20 and not b.lstrip().startswith("<"):
                            _c.append(b)
                except:
                    pass

            page.on("response", _on_poi)
            try:
                page.reload(wait_until="domcontentloaded", timeout=20000)
            except:
                pass
            time.sleep(3)
            try:
                page.remove_listener("response", _on_poi)
            except:
                pass
            if cap:
                captured = cap[-1]
        if captured:
            import json as _json
            names = _extract_shops_from_json(_json.loads(captured))
            log(f"poiList 接口解析到 {len(names)} 个店铺")
            for i, nm in enumerate(names[:50]):
                shops.append({"id": f"shop_{int(time.time())}_{i}", "name": nm, "source": "meituan"})
    except Exception as e:
        log(f"poiList 解析失败: {e}")

    if shops:
        if not account:
            account = _extract_account_from_page(page) or ""
        send({"type": "shops_fetched", "shops": shops, "count": len(shops), "account": account})
        return

    # 主方案 B：点击右上角"全部门店"切换器，从弹出的门店列表浮层提取
    try:
        switcher = page.get_by_text("全部门店", exact=False)
        if switcher.count() > 0:
            switcher.first.click(timeout=5000)
            time.sleep(2)
            extracted = page.evaluate("""() => {
                const noise = new Set([
                    '商家首页','订单管理','商品管理','顾客管理','财务管理','经营分析','活动中心',
                    '体验中心','店铺设置','外卖课堂','服务市场','商家保险','帮扶动态','平台规则',
                    '全部分类','向左收起','向右展开','全部门店','门店','切换门店','选择门店',
                    '营业中','停业中','门店上线中','仅接受预订','休息中','已下线','审核中'
                ]);
                const r = [];
                let acct = null;
                const seen = new Set();
                document.querySelectorAll('*').forEach(el => {
                    if (el.children.length > 0) return;
                    const t = (el.textContent || '').trim();
                    if (!t || t.length < 2 || t.length > 40 || seen.has(t)) return;
                    if (noise.has(t)) return;
                    // 美团账号名：纯字母数字下划线（如 fwsc_3187_001）
                    if (/^[a-zA-Z][a-zA-Z0-9_]{2,}$/.test(t)) {
                        if (!acct) acct = t;
                        return;
                    }
                    if (/^\\d+$/.test(t)) return;
                    if (/^全部门店（?共/.test(t)) return;
                    seen.add(t);
                    const z = parseInt(getComputedStyle(el).zIndex) || 0;
                    const inPop = !!el.closest('[class*="dropdown"],[class*="popover"],[class*="popup"],[class*="menu"],[class*="poi"],[class*="shop"]');
                    if (z > 50 || inPop) r.push(t);
                });
                return { shops: r.slice(0, 50), account: acct };
            }""")
            names = extracted.get("shops", []) if isinstance(extracted, dict) else (extracted or [])
            account = extracted.get("account") if isinstance(extracted, dict) else None
            log(f"浮层提取到 {len(names)} 个店铺，账号={account}")
            for i, nm in enumerate(names):
                shops.append({"id": f"shop_{int(time.time())}_{i}", "name": nm, "source": "meituan"})
            try:
                page.keyboard.press("Escape")
            except:
                pass
    except Exception as e:
        log(f"点击全部门店失败: {e}")

    # 主方案 C：单店账号没有「全部门店」切换器，顶栏同一位置的 span.txt 直接显示店铺名
    if not shops:
        try:
            current_name = _extract_top_shop_name_from_dom(page)
            if current_name:
                shops.append({"id": f"shop_{int(time.time())}_0", "name": current_name, "source": "meituan"})
                log(f"顶栏 DOM 提取到单店店铺名: {current_name}")
        except Exception as e:
            log(f"顶栏店铺名提取失败: {e}")

    # 兜底：API 响应解析（单店账号）
    if not shops:
        try:
            cap = []
            def on_resp(resp):
                if "homePage" in resp.url:
                    try:
                        b = resp.text()
                        if b and len(b) > 100 and not b.lstrip().startswith("<"):
                            cap.append(b)
                    except:
                        pass
            page.on("response", on_resp)
            try:
                page.reload(wait_until="domcontentloaded", timeout=15000)
            except:
                pass
            time.sleep(4)
            page.remove_listener("response", on_resp)
            for body in cap:
                import json as _json
                data = _json.loads(body)
                # 优先递归提取（兼容 wmPoiName / poiName / shopName 等字段）
                names = _extract_shops_from_json(data)
                name = names[0] if names else None
                if not name and isinstance(data.get("data"), dict):
                    name = data["data"].get("wmPoiName")
                if name and name != "全部门店":
                    shops.append({"id": "shop_0", "name": name, "source": "meituan"})
                    break
        except Exception as e:
            log(f"兜底解析失败: {e}")

    if not account:
        account = _extract_account_from_page(page) or ""
    send({"type": "shops_fetched", "shops": shops, "count": len(shops), "account": account})


def _ensure_logged_in_page():
    """确保浏览器已连接并已登录，返回 page，否则 None"""
    if not _browser or not _browser.is_connected():
        if not launch_browser():
            return None
    page = get_page()
    if not page:
        return None
    if not check_login_status():
        # 尝试打开美团首页触发登录态
        try:
            page.goto(MEITUAN_HOME_URL, wait_until="domcontentloaded", timeout=20000)
            time.sleep(3)
        except:
            pass
        if not check_login_status():
            return None
    return page


def _click_text(page, text, exact=False, timeout=4000):
    """页面内按文本点击，成功返回 True"""
    try:
        loc = page.get_by_text(text, exact=exact)
        if loc.count() > 0:
            loc.first.click(timeout=timeout)
            return True
    except Exception as e:
        log(f"点击文本[{text}]失败: {e}")
    return False


def _click_text_deep(page, text, timeout=3000):
    """深度文本点击：主 frame → 所有子 frame → OCR 定位点击。
    美团后台部分页面（hash 路由内嵌 iframe/Flutter canvas）主 frame 无该文本。"""
    try:
        loc = page.get_by_text(text, exact=False)
        if loc.count() > 0:
            loc.first.click(timeout=timeout)
            log(f"点击文本[{text}]命中(主frame)")
            return True
    except Exception as e:
        log(f"点击文本[{text}]主frame异常: {e}")
    for fr in page.frames:
        if fr == page.main_frame:
            continue
        try:
            loc = fr.get_by_text(text, exact=False)
            if loc.count() > 0:
                loc.first.click(timeout=timeout)
                log(f"点击文本[{text}]命中(子frame: {fr.url[:60]})")
                return True
        except Exception:
            continue
    try:
        cands = _ocr_pick(page, [text])
        if cands:
            _t, x, y = cands[0]
            page.mouse.click(x, y)
            log(f"点击文本[{text}]命中(OCR)")
            return True
    except Exception as e:
        log(f"点击文本[{text}]OCR异常: {e}")
    log(f"点击文本[{text}]未命中（frames={len(page.frames)}）")
    return False


def _click_text_deep_retry(page, text, tries=5, wait_ms=2000, timeout=3000):
    """带重试的深度文本点击。重连后页面常处于加载中（OCR 首帧为“加载中...”），
    且「顾客评价」藏在未展开的「顾客管理」子菜单里，需要等渲染完成后再点。"""
    for _ in range(tries):
        if _click_text_deep(page, text, timeout=timeout):
            return True
        page.wait_for_timeout(wait_ms)
    return False


def _open_review_list_tab(page, max_tries=5):
    """进入「顾客评价」后必须点击顶部「外卖评价列表」标签，comment/list 才会触发。
    新开窗口默认停在「综合评分明细」标签。标签栏可能位于 iframe/Flutter 内：
    先遍历所有 frame 按文本点，失败再 OCR 定位点击。"""
    for _ in range(max_tries):
        clicked = False
        for fr in page.frames:
            try:
                loc = fr.get_by_text("外卖评价列表", exact=False)
                if loc.count() > 0:
                    loc.first.click(timeout=3000)
                    clicked = True
                    break
            except Exception:
                continue
        if clicked:
            log("已点击「外卖评价列表」标签(DOM)")
            return True
        try:
            cands = _ocr_pick(page, ["外卖评价列表"])
            if cands:
                _t, x, y = cands[0]
                page.mouse.click(x, y)
                log("已点击「外卖评价列表」标签(OCR)")
                return True
        except Exception as e:
            log(f"OCR 定位标签失败: {e}")
        page.wait_for_timeout(1500)
    log("未找到「外卖评价列表」标签")
    return False


def _switch_to_shop(page, shop_name):
    """服务商账号：通过顶部店铺切换器切换到指定店铺。
    顶栏已是目标店铺时直接短路跳过（重试等待页面加载窗口期过去，
    重连后首帧 OCR 常只有“加载中...”）。
    入口优先「全部门店」文案；已选中具体店铺时顶栏显示店名，
    OCR 常将店名与「营业中」合并成一块（如“口水鸡·豆花鸡营业中fwSC_3187_001”），
    此时点击「营业中」命中块自身或其同行左侧块（即店铺选择器）。
    返回 True 表示已在目标店铺（或切换动作已执行），False 表示未能切换。"""
    if not shop_name:
        return True
    prefix = shop_name[:6]
    # 1) 短路：顶栏已是目标店铺。单店账号顶栏直接显示店名，先用 DOM 精确判断，
    #    避免 OCR 首帧只识别到「加载中...」时误判而继续走下拉切换。
    try:
        dom_name = _extract_top_shop_name_from_dom(page)
        if dom_name and _shop_names_match(dom_name, shop_name):
            log(f"顶栏已是目标店铺(DOM)，无需切换: {shop_name}")
            return True
    except Exception:
        pass
    try:
        # 2) 短路：顶栏已是目标店铺（OCR）
        for _ in range(3):
            cands = _ocr_pick(page, ["营业中", "休息中", "停业中", "全部门店", "仅接受预订", "门店上线中", "已下线"])
            if cands:
                if prefix in cands[0][0] or shop_name in cands[0][0]:
                    log(f"顶栏已是目标店铺，无需切换: {shop_name}")
                    return True
                break
            page.wait_for_timeout(2000)
        # 2) 展开店铺下拉：优先「全部门店」文案，其次顶栏容器，最后 OCR 店名块。
        #    不能点「营业中」状态标签；也不能用全局 chevron（页面上筛选框也有箭头，会点错）。
        opened = False
        sw = page.get_by_text("全部门店", exact=False)
        if sw.count() > 0:
            try:
                sw.first.click(timeout=4000)
                opened = True
                log("已点击「全部门店」展开店铺下拉(DOM)")
            except Exception:
                pass
        if not opened:
            try:
                cont = page.locator('div[class*="current-poi"]').first
                if cont.count() > 0:
                    cont.click(timeout=4000)
                    opened = True
                    log("已点击顶栏店铺选择器容器(current-poi)展开下拉")
            except Exception as e:
                log(f"点击顶栏店铺选择器容器失败: {e}")
        if not opened:
            cands2 = _ocr_pick(page, ["营业中", "休息中", "停业中", "仅接受预订", "门店上线中", "已下线"])
            if cands2:
                _t, yy_x, yy_y = cands2[0]
                boxes = _ocr_boxes(page, os.path.join(_REPLY_DBG_DIR, "ocr2.png"))
                same_row = [b for b in boxes if abs(b[2] - yy_y) < 18 and b[1] < yy_x - 10]
                if same_row:
                    # 状态标签左侧是店名块，点它才能展开店铺选择器
                    _t2, sx, sy = max(same_row, key=lambda c: c[1])
                    page.mouse.click(sx, sy)
                    opened = True
                    log(f"已点击顶栏店铺名块(状态标签左侧): {_t2[:20]}")
                else:
                    # 店名与状态合并成一块时，点合并块本身
                    page.mouse.click(yy_x, yy_y)
                    opened = True
                    log(f"已点击顶栏店铺名合并块(OCR): {_t[:20]}")
        if not opened:
            log(f"未能展开店铺下拉（顶栏状态未知），疑似店铺未找到: {shop_name}")
            return False
        time.sleep(1.5)
        # 3) 店铺多时下拉可能带搜索框，先输入店名过滤
        try:
            ph = page.get_by_placeholder(re.compile("搜索.*店|店名|门店"))
            if ph.count() > 0 and ph.first.is_visible():
                ph.first.fill(shop_name)
                page.wait_for_timeout(1500)
                log("已在下拉搜索框输入店名过滤")
        except Exception:
            pass
        # 3) 下拉中点目标店铺：DOM 重试 → OCR 店名前缀
        for _ in range(3):
            try:
                tgt = page.get_by_text(shop_name, exact=False)
                if tgt.count() > 0:
                    tgt.first.click(timeout=4000)
                    time.sleep(2)
                    log(f"已切换到店铺: {shop_name}")
                    return True
            except Exception:
                pass
            page.wait_for_timeout(1500)
        try:
            cands3 = _ocr_pick(page, [prefix])
            if cands3:
                page.mouse.click(cands3[0][1], cands3[0][2])
                time.sleep(2)
                log(f"已切换到店铺(OCR): {shop_name}")
                return True
        except Exception:
            pass
        if _click_shop_in_dropdown(page, shop_name):
            time.sleep(2)
            log(f"已切换到店铺(OCR模糊匹配): {shop_name}")
            return True
        page.keyboard.press("Escape")
        time.sleep(0.5)
        log(f"下拉中未找到目标店铺: {shop_name}（未切换，列表可能仍是原店铺）")
        return False
    except Exception as e:
        log(f"切换店铺失败: {e}")
        return False


def _norm_rating(v):
    """把各种评分归一到 1~5 整数，无法识别返回 0"""
    try:
        if v is None:
            return 0
        f = float(v)
    except Exception:
        return 0
    if f <= 0:
        return 0
    if f <= 5:
        return max(1, int(round(f)))
    # 美团常见 10/20/.../50 或 100 分制
    if f <= 50:
        return max(1, int(round(f / 10.0)))
    return max(1, min(5, int(round(f / 20.0))))


def _parse_time(v):
    """解析评价时间，失败返回当前时间（数据库该字段非空）"""
    import datetime as _dt
    if v is None:
        return _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    s = str(v).strip()
    # 纯数字：时间戳（秒或毫秒）
    if s.isdigit():
        ts = int(s)
        if ts > 10_000_000_000:
            ts = ts // 1000
        try:
            return _dt.datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S")
        except Exception:
            pass
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M",
                "%Y/%m/%d %H:%M:%S", "%Y-%m-%d", "%Y/%m/%d"):
        try:
            return _dt.datetime.strptime(s[:len(fmt) + 4] if "T" in fmt else s[:19], fmt).strftime("%Y-%m-%d %H:%M:%S")
        except Exception:
            pass
    try:
        return _dt.datetime.fromisoformat(s.replace("Z", "")).strftime("%Y-%m-%d %H:%M:%S")
    except Exception:
        return _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _extract_reviews_from_json(obj):
    """从评价接口 JSON 中递归提取评价（兼容多种字段命名）。
    返回 (有文字的评价列表, 评价对象总数)。无文字的评价不计入列表但计入总数。"""
    out = []
    total = 0
    seen = set()
    id_keys = ("commentId", "reviewId", "appraiseId", "comment_id", "review_id", "id")
    reviewer_keys = ("userName", "nickName", "nickname", "customerName",
                     "commentUser", "user_name", "name")
    rating_keys = ("orderCommentScore", "score", "star", "rating", "evaluateScore",
                   "commentScore", "grade", "starLevel", "comment_star", "starScore")
    content_keys = ("comment", "content", "commentContent", "text", "remark", "message")
    time_keys = ("commentTime", "createTime", "time", "ctime", "addTime",
                 "gmtCreate", "commentDate")
    reply_keys = ("replyContent", "reply", "answer", "merchantReply",
                  "replyComment", "replyInfo", "shopReply")
    reply_time_keys = ("replyTime", "replyDate", "merchantReplyTime")

    def pick(d, keys):
        for k in keys:
            v = d.get(k)
            if isinstance(v, str) and v.strip():
                return v.strip()
            if isinstance(v, (int, float)) and v not in (None, 0):
                return v
        return None

    def extract_reply(o):
        """提取商家回复：优先顶层字段，其次嵌套 eCommentList（美团实际结构）"""
        rc = pick(o, reply_keys)
        if rc:
            return rc, _parse_time(pick(o, reply_time_keys))
        for e in (o.get("eCommentList") or []):
            if not isinstance(e, dict):
                continue
            rc2 = pick(e, ("cleanComment", "comment", "content", "replyContent"))
            if rc2:
                # 真实回复时间取 ctime 秒级时间戳；"7天后"这类相对展示串不采用
                rt2 = e.get("ctime")
                if isinstance(rt2, (int, float)) and rt2 > 0:
                    rt2 = _parse_time(str(int(rt2)))
                else:
                    rt2 = ""
                return rc2, rt2
        return None, None

    def walk(o):
        nonlocal total
        if isinstance(o, dict):
            content = pick(o, content_keys)
            rid = pick(o, id_keys)
            rating_raw = pick(o, rating_keys)
            if rid is not None and (content or rating_raw is not None):
                total += 1
            if content and rid is not None:
                reviewer = pick(o, reviewer_keys) or "匿名用户"
                rc, rtime = extract_reply(o)
                key = (str(rid) if rid else None, reviewer, content)
                if key not in seen:
                    seen.add(key)
                    out.append({
                        "platformReviewId": str(rid) if rid else "",
                        "reviewer": str(reviewer),
                        "rating": _norm_rating(rating_raw),
                        "content": content[:1900],
                        "reviewTime": _parse_time(pick(o, time_keys)),
                        "replyStatus": "replied" if rc else "unreplied",
                        "replyContent": (str(rc)[:1900] if rc else ""),
                        "replyTime": (rtime or "") if rc else "",
                    })
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    walk(obj)
    return out, total


def _click_next_page(page):
    """点击「下一页」，成功返回 True"""
    for txt in ("下一页", "下页", "下一页›", "›"):
        try:
            loc = page.get_by_text(txt, exact=False)
            for i in range(loc.count()):
                el = loc.nth(i)
                try:
                    if el.is_visible(timeout=1000):
                        el.click(timeout=3000)
                        return True
                except Exception:
                    continue
        except Exception:
            continue
    return False


def _safe_url(url):
    """日志脱敏：去掉 query（token/签名）与 fragment"""
    try:
        from urllib.parse import urlparse, urlunparse
        p = urlparse(url or "")
        return urlunparse((p.scheme, p.netloc, p.path, "", "", ""))
    except Exception:
        return (url or "").split("?", 1)[0].split("#", 1)[0]


def _page_key(url, body):
    """评价页唯一标识：优先请求 URL 的 pageNum 原始字符串，其次响应 data.pageNum，最后 body md5。
    不做 offset/页序号换算。"""
    try:
        from urllib.parse import urlparse, parse_qs
        q = parse_qs(urlparse(url or "").query)
        v = q.get("pageNum")
        if v and str(v[0]).strip():
            return "url:" + str(v[0]).strip()
    except Exception as e:
        log(f"page_key 解析请求 URL 失败，改用响应字段: {e}")
    try:
        d = json.loads(body)
        data = d.get("data") if isinstance(d, dict) else None
        if isinstance(data, dict) and data.get("pageNum") is not None:
            return "body:" + str(data.get("pageNum")).strip()
    except Exception as e:
        log(f"page_key 解析响应失败，改用 md5 兜底: {e}")
    return "md5:" + hashlib.md5((body or "").encode("utf-8", "ignore")).hexdigest()


def _poi_id_from_url(url):
    """从请求 URL 取 wmPoiId（评价接口均携带）"""
    try:
        from urllib.parse import urlparse, parse_qs
        q = parse_qs(urlparse(url or "").query)
        for k in ("wmPoiId", "wmPoiIds", "poiId"):
            v = q.get(k)
            if v and str(v[0]).strip():
                return str(v[0]).strip().split(",")[0]
    except Exception as e:
        log(f"解析 URL 中的 wmPoiId 失败: {e}")
    return None


def _poi_id_from_data(d):
    """从响应 data 取 wmPoiId"""
    data = d.get("data") if isinstance(d, dict) else None
    if isinstance(data, dict):
        for k in ("wmPoiId", "poiId", "wm_poi_id"):
            v = data.get(k)
            if v not in (None, ""):
                return str(v)
    return None


def _pager_digits_visible(page):
    """检测底部分页器是否已渲染：OCR 命中 ≥2 个页码数字，或 cv2 扫描到深色激活块"""
    try:
        from PIL import Image
        im = _shot(page)
        W, H = im.size
        crop = im.crop((0, int(H * 0.72), W, H))
        crop = crop.resize((crop.width * 2, crop.height * 2), Image.LANCZOS)
        res, _ = _ocr_img(crop)
        n = 0
        if res:
            for it in res:
                t = (it[1] or "").strip()
                if t.isdigit() and 1 <= int(t) <= 99:
                    n += 1
        if n >= 2:
            return True
    except Exception as e:
        log(f"分页器 OCR 检测异常: {e}")
    row, _dpr, _vw, _vh = _cv2_pager_row(page)
    if any(b[4] < 120 for b in row):
        log("分页器 OCR 未命中，cv2 扫描到深色激活块，判定已渲染")
        return True
    return False


def _prepare_review_capture(page, shop_name):
    """注册响应监听 → reload → 打开外卖评价列表 → 等待分页器。
    返回 capture state；调用方负责 page.remove_listener("response", state["listener"])。"""
    state = {"captured": [], "urls": [], "keys": set(), "total_pages": 0,
             "expected_poi": None, "first_checked": False, "shop_checked": False,
             "abort": False, "listener": None, "failures": []}

    def _on_resp(resp):
        try:
            u = resp.url or ""
            if "comment/list" not in u.lower():
                return
            if resp.status != 200:
                log(f"丢弃响应：HTTP {resp.status}，URL={_safe_url(u)}")
                return
            b = resp.text()
            if not b:
                log(f"丢弃响应：空响应体，URL={_safe_url(u)}")
                return
            try:
                d = json.loads(b)
            except Exception as e:
                log(f"丢弃响应：JSON 解析异常({e})，URL={_safe_url(u)}")
                return
            if not isinstance(d, dict):
                log(f"丢弃响应：响应不是对象({type(d).__name__})，URL={_safe_url(u)}")
                return
            data = d.get("data")
            if not isinstance(data, dict):
                log(f"丢弃响应：data 不是对象(code={d.get('code')})，URL={_safe_url(u)}")
                return
            poi = _poi_id_from_url(u) or _poi_id_from_data(d)
            if not poi:
                log(f"丢弃响应：wmPoiId 缺失，URL={_safe_url(u)}")
                return
            if not state["first_checked"]:
                state["first_checked"] = True
                state["expected_poi"] = poi
                log(f"首条响应锁定期望 poi={poi}")
                try:
                    total = int(data.get("total") or 0)
                    ps = int(data.get("pageSize") or 0)
                    if total and ps:
                        state["total_pages"] = (total + ps - 1) // ps
                        log(f"总条数={total} 每页={ps} 总页数={state['total_pages']}")
                except Exception as e:
                    log(f"总页数计算失败: {e}")
            elif poi != state["expected_poi"]:
                log(f"丢弃响应：wmPoiId 不符 期望={state['expected_poi']} 实际={poi}，URL={_safe_url(u)}")
                return
            key = _page_key(u, b)
            if key not in state["keys"]:
                state["keys"].add(key)
                state["captured"].append(b)
                state["urls"].append(u)
                log(f"接受响应：key={key} 累计 {len(state['keys'])} 页")
            else:
                log(f"响应重复：key={key}，不重复计入")
        except Exception as e:
            log(f"处理评价响应异常: {e}")

    state["listener"] = _on_resp
    page.on("response", _on_resp)
    try:
        page.reload(wait_until="domcontentloaded", timeout=20000)
    except Exception as e:
        log(f"重新加载评价页失败: {e}")
        state["failures"].append("reload_failed")

    # reload 后先软等 3 秒；没有响应就先打开评价列表，再等 15 秒（超时计入失败）
    deadline = time.time() + 3
    while time.time() < deadline:
        if state["captured"] or state["abort"]:
            break
        page.wait_for_timeout(300)
    if not state["captured"] and not state["abort"]:
        log("reload 后 3 秒内未收到 comment/list 响应，先打开外卖评价列表继续等待")

    before = len(state["captured"])
    _open_review_list_tab(page)
    deadline = time.time() + 15
    while time.time() < deadline:
        if len(state["captured"]) > before or state["abort"]:
            break
        page.wait_for_timeout(300)
    if len(state["captured"]) <= before and not state["abort"]:
        log("打开外卖评价列表后 15 秒内未收到 comment/list 响应，计入本次失败")
        state["failures"].append("no_first_response")

    # DOM 店名校验：仅在拿到首条响应后执行一次，之后只用 wmPoiId 比对
    if state["expected_poi"] and not state["shop_checked"] and not state["abort"]:
        state["shop_checked"] = True
        dom_name = None
        try:
            dom_name = _extract_top_shop_name_from_dom(page, require_anchored=True)
        except Exception as e:
            log(f"首条响应读取 DOM 店名异常: {e}")
        if dom_name and shop_name and not _shop_names_match(dom_name, shop_name):
            log(f"首条响应 DOM 店名不匹配 目标={shop_name} 实际={dom_name}，终止本次抓取")
            state["abort"] = True
            state["captured"].clear()
            state["urls"].clear()
            state["keys"].clear()
        elif not dom_name:
            log("首条响应 DOM 店名不可读，沿用切换时的校验结果")
        else:
            log(f"首条响应 DOM 店名校验通过：{dom_name}")

    try:
        page.keyboard.press("End")
    except Exception as e:
        log(f"滚动到底部失败: {e}")
    deadline = time.time() + 15
    visible = False
    while time.time() < deadline:
        if _pager_digits_visible(page):
            visible = True
            break
        page.wait_for_timeout(300)
    if not visible:
        log("15 秒内未检测到分页器，翻页可能失败")
        state["failures"].append("pager_not_visible")
    if _DEBUG_SHOTS:
        try:
            _dbg_dir2 = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug", "review_dump")
            os.makedirs(_dbg_dir2, exist_ok=True)
            page.screenshot(path=os.path.join(_dbg_dir2, "reviews_page.png"), full_page=True)
            log(f"评价页当前 URL: {_safe_url(page.url)}")
        except Exception as e:
            log(f"截图调试失败: {e}")
    return state


def _wait_for_new_key(page, keys, before_count, timeout_ms=20000):
    """等待新页 key 出现，每 250ms 轮询，超时返回 False"""
    waited = 0
    while waited < timeout_ms:
        if len(keys) > before_count:
            return True
        page.wait_for_timeout(250)
        waited += 250
    return len(keys) > before_count


def _turn_pages(page, state, shop_name, shot_path):
    """翻页直到覆盖总页数（或到 200 页上限）。返回已捕获页数。"""
    keys = state["keys"]
    total = state["total_pages"]
    no_new = 0
    while len(keys) < total and len(keys) < 200:
        if state["abort"]:
            log("捕获已中止，停止翻页")
            break
        target = len(keys) + 1
        ok = False
        for attempt in range(5):
            if attempt:
                backoff = min(1.5 * attempt, 4.5)
                log(f"第 {target} 页重试 {attempt}/4，退避 {backoff}s")
                page.wait_for_timeout(int(backoff * 1000))
            before = len(keys)
            pt, _mode = _pager_point_for(page, target, shot_path)
            if not pt:
                log(f"未能定位第 {target} 页（尝试 {attempt + 1}/5），计入失败")
                no_new += 1
                if no_new >= 2:
                    log("连续 2 次无新页，清空分页缓存并重新 OCR")
                    _reset_pager_fit()
                    no_new = 0
                continue
            log(f"点击页码 {target} 坐标=({pt[0]:.0f},{pt[1]:.0f})")
            try:
                page.mouse.click(pt[0], pt[1])
            except Exception as e:
                log(f"点击第 {target} 页失败: {e}")
                no_new += 1
                continue
            if _wait_for_new_key(page, keys, before, 20000):
                ok = True
                no_new = 0
                log(f"翻页成功，已捕获 {len(keys)}/{total} 页")
                break
            no_new += 1
            log(f"点击后 20 秒内无新页（连续 {no_new} 次），已捕获 {len(keys)}/{total} 页")
            if no_new >= 2:
                log("连续 2 次点击无新页，清空分页缓存并重新 OCR")
                _reset_pager_fit()
                no_new = 0
        if not ok:
            log(f"第 {target} 页重试 4 次仍无新页，终止翻页")
            break
    return len(keys)


def cmd_scrape_reviews(args):
    """抓取指定店铺的全部评价（通过监听评价接口响应 + 翻页）"""
    page = _ensure_logged_in_page()
    if not page:
        send({"type": "error", "message": "浏览器未登录美团，请先登录"})
        return
    shop_name = args.get("shopName", "") or ""

    if shop_name:
        # 二次校验：确认顶栏实际店铺与目标一致，避免在「全部门店」视图下把
        # 服务商名下所有店铺的评价误当成目标店铺的数据。
        ok, actual_name, verify_source = _switch_and_verify(page, shop_name)
        if not ok:
            _send_shop_not_found(shop_name, actual_name, verify_source)
            return
        log(f"店铺二次校验通过({verify_source})：{actual_name}")

    # 进入 顾客管理 → 顾客评价
    _click_text_deep_retry(page, "顾客管理")
    time.sleep(1)
    if not _click_text_deep_retry(page, "顾客评价"):
        # 有的后台菜单文案不同，尝试「评价管理」
        _click_text_deep_retry(page, "评价管理")
    page.wait_for_timeout(300)

    task_id = f"{shop_name or 'current'}-{int(time.time())}"
    _begin_pager_task(task_id)
    shot_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug", "_ocr_shot.png")

    def _capture_once(attempt_no):
        log(f"开始第 {attempt_no + 1} 次评价抓取")
        state = _prepare_review_capture(page, shop_name)
        try:
            if state["abort"]:
                log("首条响应店名校验失败，跳过翻页")
                fetched = 0
            else:
                fetched = _turn_pages(page, state, shop_name, shot_path)
        finally:
            try:
                page.remove_listener("response", state["listener"])
            except Exception as e:
                log(f"摘除响应监听失败: {e}")
        return state, fetched

    state, pages_fetched = _capture_once(0)
    pages_expected = state["total_pages"]
    complete = pages_expected > 0 and pages_fetched >= pages_expected
    if state["abort"]:
        _send_shop_not_found(shop_name, shop_name, "CAPTURE_SHOP_MISMATCH")
        return
    if not complete:
        log(f"第 1 次抓取不完整（{pages_fetched}/{pages_expected or '未知'} 页），重新进入评价页重试 1 次")
        state, pages_fetched = _capture_once(1)
        pages_expected = state["total_pages"]
        complete = pages_expected > 0 and pages_fetched >= pages_expected
        if state["abort"]:
            _send_shop_not_found(shop_name, shop_name, "CAPTURE_SHOP_MISMATCH")
            return
    if not complete:
        log(f"重试后仍不完整：pagesFetched={pages_fetched} pagesExpected={pages_expected}，本次标记为部分数据")

    reviews = []
    seen = set()
    total_seen = 0
    for idx, body in enumerate(state["captured"]):
        n_here = 0
        try:
            data = json.loads(body)
        except Exception as e:
            log(f"  响应[{idx}] JSON 解析失败({e})，跳过该响应")
            continue
        rs, tot = _extract_reviews_from_json(data)
        total_seen += tot
        for r in rs:
            n_here += 1
            key = r["platformReviewId"] or (r["reviewer"], r["content"])
            if key in seen:
                log(f"  响应[{idx}] 跳过重复评价 key={key}")
                continue
            seen.add(key)
            reviews.append(r)
        url = state["urls"][idx] if idx < len(state["urls"]) else ""
        log(f"  响应[{idx}] 解析评价={n_here} 长度={len(body)} URL={_safe_url(url)}")

    if _DEBUG_SHOTS and state["captured"]:
        best_idx = max(range(len(state["captured"])), key=lambda i: len(state["captured"][i]))
        try:
            _dbg_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug", "review_dump")
            os.makedirs(_dbg_dir, exist_ok=True)
            with open(os.path.join(_dbg_dir, "list_sample.json"), "w", encoding="utf-8") as f:
                f.write(state["urls"][best_idx] + "\n" + state["captured"][best_idx])
        except Exception as e:
            log(f"保存评价列表样本失败: {e}")

    saved = len(reviews)
    skipped = max(0, total_seen - saved)
    log(f"评价抓取完成：店铺={shop_name or '(当前)'}，完整={complete} "
        f"页数={pages_fetched}/{pages_expected}，接口响应={len(state['captured'])} 条，"
        f"总评价={total_seen}，有文字已记录={saved}，无文字未记录={skipped}")
    send({"type": "reviews_fetched", "reviews": reviews, "count": saved,
          "total": total_seen, "skipped": skipped, "shopName": shop_name,
          "complete": complete, "pagesExpected": pages_expected, "pagesFetched": pages_fetched})


def cmd_run_appeal(args):
    """全自动执行申诉：定位评价→点申诉→填理由→传图→提交"""
    page = _ensure_logged_in_page()
    if not page:
        send({"type": "error", "message": "浏览器未登录美团，无法申诉"})
        return

    shop_name = args.get("shopName", "")
    reviewer = args.get("reviewer", "")
    content = args.get("content", "") or ""
    appeal_content = args.get("appealContent", "") or ""
    images = args.get("images", "") or ""

    try:
        # 1. 若指定了店铺，先切换并二次校验；失败则中止，避免操作到别的店铺
        if shop_name:
            ok, actual_name, verify_source = _switch_and_verify(page, shop_name)
            if not ok:
                _send_shop_not_found(shop_name, actual_name, verify_source)
                return
            log(f"店铺二次校验通过({verify_source})：{actual_name}")

        # 2. 导航到 顾客管理 → 顾客评价
        _click_text(page, "顾客管理")
        time.sleep(1)
        _click_text(page, "顾客评价")
        time.sleep(3)
        if _DEBUG_SHOTS:
            page.screenshot(path="data/debug/appeal_step_reviews.png")
        log("已进入评价列表页")

        # 3. 点击该条评价的"申诉"入口 → 打开「选择申诉类型」页
        appealed = False
        appeal_loc = page.get_by_text("申诉", exact=False)
        cnt = appeal_loc.count()
        log(f"页面找到 {cnt} 个'申诉'入口")
        if cnt > 0:
            try:
                appeal_loc.first.click(timeout=4000)
                appealed = True
                time.sleep(2)
                if _DEBUG_SHOTS:
                    page.screenshot(path="data/debug/appeal_step1_type.png")
                log("已点击申诉，进入选择申诉类型页")
            except Exception as e:
                log(f"点击申诉失败: {e}")
        if not appealed:
            send({"type": "error", "message": "未在页面找到申诉入口，请确认该评价可申诉"})
            return

        # 4. 选择申诉类型：点「其他」→ 点「下一步」
        try:
            other = page.get_by_text("其他", exact=True)
            if other.count() > 0:
                other.first.click(timeout=4000)
                log("已选择申诉类型：其他")
                time.sleep(1)
            else:
                log("未找到'其他'选项，尝试直接下一步")
            time.sleep(0.5)
            _click_text(page, "下一步", exact=False, timeout=4000)
            time.sleep(2)
            if _DEBUG_SHOTS:
                page.screenshot(path="data/debug/appeal_step2_detail.png")
            log("已进入评价申诉详情页")
        except Exception as e:
            log(f"选择类型/下一步失败: {e}")

        # 5. 填写「问题描述」（必填，至少5个字，否则提交按钮禁用）
        desc = appeal_content.strip()
        if not desc:
            desc = "该评价与实际情况不符，我方已按规范出餐与配送，不存在评价所述问题，恳请平台核实。"
        if len(desc) < 5:
            desc = desc + "，恳请平台核实处理。"
        try:
            ta = page.locator("textarea").first
            if ta.count() > 0:
                ta.click()
                ta.fill(desc)
                # 触发输入事件，确保字数校验生效、提交按钮解锁
                try:
                    ta.press(" ")
                    ta.press("Backspace")
                except:
                    pass
                log(f"已填写问题描述（{len(desc)}字）")
            else:
                log("未找到问题描述输入框")
        except Exception as e:
            log(f"填写问题描述失败: {e}")

        # 6. 上传证明图片（可选；有则传，没有也能提交）——核心：set_input_files，不走OCR
        img_paths = [p.strip() for p in images.split(",") if p.strip()]
        uploaded = 0
        if img_paths:
            try:
                file_input = page.locator("input[type=file]")
                if file_input.count() > 0:
                    existing = [p for p in img_paths if os.path.exists(p)]
                    if existing:
                        file_input.first.set_input_files(existing)
                        uploaded = len(existing)
                        log(f"已上传 {uploaded} 张证明图片")
                        time.sleep(2)
                    else:
                        log(f"图片文件不存在: {img_paths}")
                else:
                    log("未找到上传控件，跳过图片上传（不影响申诉）")
            except Exception as e:
                log(f"上传图片失败(跳过): {e}")

        if _DEBUG_SHOTS:
            page.screenshot(path="data/debug/appeal_step3_filled.png")

        # 7. 点击「提交」
        submitted = _click_text(page, "提交", exact=False, timeout=4000)
        if submitted:
            log("已点击提交")
            time.sleep(2)
        if _DEBUG_SHOTS:
            page.screenshot(path="data/debug/appeal_step4_done.png")

        send({
            "type": "appeal_done",
            "message": "申诉流程执行完成（类型:其他）",
            "reviewer": reviewer,
            "imagesUploaded": uploaded
        })

    except Exception as e:
        log(f"申诉执行异常: {e}\n{traceback.format_exc()}")
        send({"type": "error", "message": f"申诉执行异常: {e}"})


def _ocr_page_text(page, shot_path):
    """截图并 OCR 返回整页文本（美团页面为 Flutter canvas，DOM 无文本时兜底）"""
    try:
        im = _shot(page)
        _save_debug(im, shot_path)
        result, _ = _ocr_img(im)
        if not result:
            return ""
        return "\n".join(str(item[1]) for item in result)
    except Exception as e:
        log(f"OCR 失败: {e}")
        return ""


def _walk_collect(obj, pred, out, depth=0):
    """递归遍历 JSON，收集满足 pred 的 dict 节点"""
    if depth > 8:
        return
    if isinstance(obj, dict):
        try:
            if pred(obj):
                out.append(obj)
        except Exception:
            pass
        for v in obj.values():
            _walk_collect(v, pred, out, depth + 1)
    elif isinstance(obj, list):
        for v in obj:
            _walk_collect(v, pred, out, depth + 1)


_STATUS_TEXT_MAP = {
    "通过": "approved", "成功": "approved", "同意": "approved", "支持": "approved",
    "驳回": "rejected", "失败": "rejected", "拒绝": "rejected", "不支持": "rejected",
}


def _map_appeal_status(val):
    """中文申诉状态 → approved/rejected/submitted"""
    s = str(val or "")
    for k, v in _STATUS_TEXT_MAP.items():
        if k in s:
            return v
    return "submitted"


def _goto_appeal_page(page):
    """导航到美团申诉管理页（菜单文案兼容多种后台版本）"""
    _click_text(page, "顾客管理")
    time.sleep(1)
    for menu in ("申诉管理", "我的申诉", "评价申诉", "差评申诉"):
        if _click_text(page, menu):
            time.sleep(3)
            return True
    return False


def cmd_check_appeal(args):
    """回查申诉结果：优先监听申诉列表接口响应，OCR 兜底"""
    page = _ensure_logged_in_page()
    if not page:
        send({"type": "error", "message": "浏览器未登录"})
        return
    shop_name = args.get("shopName", "") or ""
    reviewer = args.get("reviewer", "") or ""
    content = args.get("content", "") or ""

    if shop_name:
        ok, actual_name, verify_source = _switch_and_verify(page, shop_name)
        if not ok:
            _send_shop_not_found(shop_name, actual_name, verify_source)
            return
    if not _goto_appeal_page(page):
        send({"type": "appeal_status", "status": "unknown",
              "message": "未找到申诉管理菜单，请确认美团后台页面"})
        return

    captured = []

    def _is_appeal_row(d):
        keys = set(d.keys())
        status_like = [k for k in ("appealStatus", "auditStatus", "checkStatus", "result", "status") if k in keys]
        text_like = [k for k in ("content", "comment", "commentTxt", "review") if k in keys]
        return bool(status_like) and (bool(text_like) or "reviewer" in keys or "nickname" in keys)

    def _on_resp(resp):
        try:
            u = resp.url.lower()
            if any(k in u for k in ("appeal", "comment", "audit")):
                ct = resp.headers.get("content-type", "")
                if "json" in ct:
                    b = resp.text()
                    if b and len(b) > 50:
                        captured.append(b)
        except Exception:
            pass

    page.on("response", _on_resp)
    try:
        page.reload(wait_until="domcontentloaded", timeout=20000)
    except Exception:
        pass
    time.sleep(4)

    rows = []
    try:
        page.remove_listener("response", _on_resp)
    except Exception:
        pass

    for body in captured:
        try:
            data = json.loads(body)
        except Exception:
            continue
        _walk_collect(data, _is_appeal_row, rows)

    log(f"申诉回查：接口响应={len(captured)} 条，候选行={len(rows)}")

    def _match(d):
        text = json.dumps(d, ensure_ascii=False)
        if reviewer and reviewer in text:
            return True
        if content and len(content) > 4 and content[:8] in text:
            return True
        return False

    for d in rows:
        if _match(d):
            status_val = d.get("appealStatus") or d.get("auditStatus") or d.get("checkStatus") \
                or d.get("result") or d.get("status") or ""
            status = _map_appeal_status(status_val)
            note = str(status_val)
            log(f"匹配到申诉记录: status={status_val} → {status}")
            send({"type": "appeal_status", "status": status, "note": note})
            return

    # 兜底：OCR 整页找状态关键词
    shot = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug", "_appeal_ocr.png")
    text = _ocr_page_text(page, shot)
    if text:
        hit_reviewer = reviewer and reviewer in text
        for k, v in _STATUS_TEXT_MAP.items():
            if k in text:
                if hit_reviewer or len(rows) == 0:
                    log(f"OCR 命中状态关键词: {k} → {v}")
                    send({"type": "appeal_status", "status": v, "note": f"OCR识别: {k}"})
                    return
    if "审核中" in text or "待审核" in text:
        send({"type": "appeal_status", "status": "submitted", "note": "OCR识别: 审核中"})
        return

    send({"type": "appeal_status", "status": "unknown",
          "message": f"已进入申诉页但未能读取状态（候选行={len(rows)}）"})


def cmd_scrape_negative(args):
    """抓取负反馈列表：进入负反馈页面，监听接口响应 + OCR 翻页"""
    page = _ensure_logged_in_page()
    if not page:
        send({"type": "error", "message": "浏览器未登录美团，请先登录"})
        return
    shop_name = args.get("shopName", "") or ""

    if shop_name:
        _switch_to_shop(page, shop_name)

    # 进入负反馈页（菜单文案兼容）
    _click_text(page, "顾客管理")
    time.sleep(1)
    entered = False
    for menu in ("负反馈", "顾客反馈", "投诉管理", "反馈管理"):
        if _click_text(page, menu):
            entered = True
            break
    time.sleep(3)

    captured = []
    captured_urls = []

    def _on_resp(resp):
        try:
            u = resp.url.lower()
            if any(k in u for k in ("negative", "feedback", "appeal", "complaint")):
                ct = resp.headers.get("content-type", "")
                if "json" in ct:
                    b = resp.text()
                    if b and len(b) > 50 and "403 Forbidden" not in b:
                        captured.append(b)
                        captured_urls.append(resp.url)
        except Exception:
            pass

    page.on("response", _on_resp)
    try:
        page.reload(wait_until="domcontentloaded", timeout=20000)
    except Exception:
        pass
    time.sleep(3)

    # 调试样本目录，便于按真实接口调整解析
    dbg_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug", "negative_dump")
    try:
        os.makedirs(dbg_dir, exist_ok=True)
        if _DEBUG_SHOTS:
            page.screenshot(path=os.path.join(dbg_dir, "negative_page.png"), full_page=True)
        log(f"负反馈页 URL: {page.url} 进入={entered}")
    except Exception as e:
        dbg_dir = None
        log(f"调试截图失败: {e}")

    # OCR 翻页抓前几页（负反馈通常量不大）
    shot_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug", "_neg_ocr.png")
    try:
        page.keyboard.press("End")
    except Exception:
        pass
    time.sleep(1)
    for expected in range(2, 8):
        pt = _find_pager_point(page, expected, shot_path)
        if not pt:
            break
        try:
            page.mouse.click(pt[0], pt[1])
        except Exception:
            break
        time.sleep(3)
        log(f"负反馈翻页 {expected}，已捕获 {len(captured)} 条响应")

    try:
        page.remove_listener("response", _on_resp)
    except Exception:
        pass

    # 解析：收集形如负反馈行的 dict（渠道/分类/订单号等特征）
    def _is_neg_row(d):
        keys = set(d.keys())
        has_channel = bool(keys & {"channelName", "channel", "feedbackChannel"})
        has_indicator = bool(keys & {"indicatorName", "indicator", "categoryName", "problemType"})
        has_order = bool(keys & {"orderNo", "orderId", "orderIdView"})
        return (has_channel and has_indicator) or (has_order and (has_channel or has_indicator))

    rows = []
    best_idx, best_len = -1, 0
    for idx, body in enumerate(captured):
        try:
            data = json.loads(body)
        except Exception:
            continue
        found = []
        _walk_collect(data, _is_neg_row, found)
        for d in found:
            rows.append(d)
        if len(body) > best_len:
            best_len = len(body)
            best_idx = idx
    if dbg_dir and best_idx >= 0:
        try:
            with open(os.path.join(dbg_dir, "list_sample.json"), "w", encoding="utf-8") as f:
                f.write(captured_urls[best_idx] + "\n" + captured[best_idx])
        except Exception:
            pass

    items = []
    seen = set()
    for d in rows:
        order_no = str(d.get("orderNo") or d.get("orderId") or d.get("orderIdView") or "")
        channel = str(d.get("channelName") or d.get("channel") or d.get("feedbackChannel") or "")
        key = (order_no, channel)
        if key in seen:
            continue
        seen.add(key)
        items.append({
            "orderNo": order_no,
            "channelName": channel,
            "indicatorName": str(d.get("indicatorName") or d.get("indicator") or d.get("categoryName") or d.get("problemType") or ""),
            "content": str(d.get("content") or d.get("feedbackContent") or d.get("description") or ""),
            "feedbackTime": str(d.get("feedbackTime") or d.get("createTime") or d.get("ctime") or ""),
            "status": str(d.get("appealStatus") or d.get("status") or d.get("auditStatus") or ""),
            "shopName": shop_name,
            "orderData": d,
        })

    log(f"负反馈抓取完成：响应={len(captured)}，解析行={len(items)}")
    if not items:
        send({"type": "negative_data", "rows": [], "count": 0,
              "message": "未解析到负反馈数据，已保存接口样本供分析" if dbg_dir else "未解析到负反馈数据"})
        return
    send({"type": "negative_data", "rows": items, "count": len(items), "shopName": shop_name})


def cmd_capture_reply(args):
    """抓包实测（Phase 0）：监听窗口期内人工在评价页回复一条评价，
    捕获回复请求 URL/method/postData + 响应体，并自动做重放测试 A（原样）/B（仅改文本），
    判定签名是否可复用、body 是否在签名内。"""
    page = _ensure_logged_in_page()
    if not page:
        send({"type": "error", "message": "浏览器未登录美团，无法抓包"})
        return

    wait_sec = int(args.get("waitSec", 150))
    reqs = []          # 捕获的所有候选请求（POST 或含 reply/comment）
    reply_resps = {}   # url -> response body（所有 POST 的响应都留底）
    hit = {"url": None}      # 首选目标：URL 含 reply 的 POST
    any_post = {"url": None, "t": 0}  # 兜底目标：任意 POST
    total_seen = {"n": 0}    # 诊断：窗口内收到的全部请求数

    def _on_req(req):
        try:
            total_seen["n"] += 1
            u = req.url
            ul = u.lower()
            interesting = (req.method == "POST"
                           or "reply" in ul or "comment" in ul)
            if interesting and ("meituan" in ul or "sankuai" in ul):
                reqs.append({
                    "url": u,
                    "method": req.method,
                    "postData": req.post_data or "",
                    "resourceType": req.resource_type,
                })
                if req.method == "POST":
                    if "reply" in ul and not hit["url"]:
                        hit["url"] = u
                    if not any_post["url"] or hit["url"] == u:
                        any_post["url"] = u
                        any_post["t"] = time.time()
                    elif not hit["url"]:
                        # 后续 POST 也更新兜底候选（回复提交通常是最后一次）
                        any_post["url"] = u
                        any_post["t"] = time.time()
        except Exception:
            pass

    def _on_resp(resp):
        try:
            u = resp.url
            if resp.request.method == "POST":
                b = resp.text()
                if b:
                    reply_resps[u] = b[:3000]
        except Exception:
            pass

    # 上下文级监听：覆盖所有窗口/标签页，避免用户在新标签回复时漏抓
    ctx = page.context
    ctx.on("request", _on_req)
    ctx.on("response", _on_resp)
    try:
        try:
            n_pages = len(ctx.pages)
        except Exception:
            n_pages = 1
        send({"type": "capture_waiting",
              "message": f"请在 {wait_sec} 秒内在任意美团商家后台窗口手动回复一条评价（正在监听全部 {n_pages} 个标签页）"})
        deadline = time.time() + wait_sec
        while time.time() < deadline:
            if hit["url"] and hit["url"] in reply_resps:
                time.sleep(3)  # 收尾：等可能的后续请求
                break
            # 兜底：无 reply 关键字时，任意 POST 拿到响应且过 5 秒静默期即收网
            if (not hit["url"] and any_post["url"]
                    and any_post["url"] in reply_resps
                    and time.time() - any_post["t"] > 5):
                time.sleep(3)
                break
            time.sleep(1)
    finally:
        try:
            ctx.remove_listener("request", _on_req)
            ctx.remove_listener("response", _on_resp)
        except Exception:
            pass

    if not reqs:
        send({"type": "capture_result", "ok": False,
              "message": "窗口期内未捕获到任何候选请求"})
        return

    # 找目标：优先 URL 含 reply 的 POST；否则取最后一个拿到响应的 POST（回复提交通常是最后一次）
    target = None
    for r in reqs:
        if "reply" in r["url"].lower() and r["method"] == "POST":
            target = r
    if target is None and any_post["url"]:
        for r in reversed(reqs):
            if r["url"] == any_post["url"] and r["method"] == "POST":
                target = r
                break

    result = {
        "type": "capture_result",
        "ok": target is not None,
        "target": target,
        "targetResponse": reply_resps.get(target["url"]) if target else None,
        "allPosts": [r for r in reqs if r["method"] == "POST"][:40],
        "totalSeen": total_seen["n"],
    }

    if target:
        ct = "application/x-www-form-urlencoded"
        body = target["postData"] or ""
        # 重放从与目标接口同源的页面发起，避免跨域干扰
        rp = page
        try:
            from urllib.parse import urlparse as _up
            _host = _up(target["url"]).netloc
            for _p in ctx.pages:
                try:
                    if _up(_p.url).netloc == _host:
                        rp = _p
                        break
                except Exception:
                    pass
        except Exception:
            pass
        # ---- 测试 A：原样重放（若已回复成功，预期业务错误"已回复"= 签名可复用的证据）----
        testA = _replay_fetch(rp, target["url"], target["method"], body, ct)
        result["testA_replay"] = testA
        # ---- 测试 B：仅改 body 中的文本值 ----
        new_body = _mutate_body_text(body)
        result["testB_body"] = new_body[:500]
        if new_body != body:
            result["testB_replay"] = _replay_fetch(rp, target["url"], target["method"], new_body, ct)
        # ---- 测试 C：替换 body 中的评论 ID（判定签名是否覆盖 ID）----
        mutated_id, new_body_c = _mutate_body_id(body)
        result["testC_id"] = mutated_id
        if mutated_id and new_body_c != body:
            result["testC_replay"] = _replay_fetch(rp, target["url"], target["method"], new_body_c, ct)
        result["verdict"] = _judge_signature(result)
        # ---- PASS 时保存接口模板供 reply_reviews 使用 ----
        if result["verdict"].startswith("PASS"):
            try:
                tpl_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug")
                os.makedirs(tpl_dir, exist_ok=True)
                sample_id, sample_text = _extract_id_and_text(body)
                with open(os.path.join(tpl_dir, "reply_api_template.json"), "w", encoding="utf-8") as f:
                    json.dump({
                        "url": target["url"], "method": target["method"], "contentType": ct,
                        "bodyTemplate": body, "sampleReviewId": sample_id, "sampleText": sample_text,
                        "capturedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
                    }, f, ensure_ascii=False, indent=2)
                result["templateSaved"] = True
            except Exception as e:
                result["templateSaved"] = False
                result["templateError"] = str(e)

    send(result)


def _replay_fetch(page, url, method, body, content_type):
    """页面上下文内 fetch 重放（同源自带 cookie），返回 {status, body}"""
    try:
        js = """
async (a) => {
  const opt = {method: a.method, credentials: 'include', headers: {}};
  if (a.body) {
    opt.headers['Content-Type'] = a.contentType;
    opt.body = a.body;
  }
  const r = await fetch(a.url, opt);
  const t = await r.text();
  return {status: r.status, body: t.substring(0, 2000)};
}
"""
        return page.evaluate(js, {"url": url, "method": method,
                                  "body": body, "contentType": content_type})
    except Exception as e:
        return {"error": str(e)}


def _mutate_body_text(body):
    """仅替换 body 中的文本值（form 或 JSON），保持字段与顺序不变，用于测试签名是否覆盖 body"""
    import re as _re
    if not body:
        return body
    # form: key=value&key2=value2 → 替换最长的 value
    if "=" in body and "{" not in body[:1]:
        pairs = body.split("&")
        best_i, best_len = -1, 0
        for i, p in enumerate(pairs):
            if "=" in p:
                v = p.split("=", 1)[1]
                if len(v) > best_len and not v.replace(".", "").replace("-", "").isdigit():
                    best_i, best_len = i, len(v)
        if best_i >= 0:
            from urllib.parse import quote
            k, _ = pairs[best_i].split("=", 1)
            pairs[best_i] = k + "=" + quote("接口直调测试回复-蓝星")
            return "&".join(pairs)
        return body
    # JSON: 正则替换最长字符串值，保持结构
    try:
        vals = list(_re.findall(r'"((?:[^"\\]|\\.){4,})"', body))
        if vals:
            best = max(vals, key=len)
            return body.replace('"%s"' % best, '"接口直调测试回复-蓝星"', 1)
    except Exception:
        pass
    return body


def _extract_id_and_text(body):
    """从 body 中提取疑似评论 ID（最长数字串）和疑似文本值（最长非数字字符串）"""
    import re as _re
    nums = _re.findall(r"\d{5,}", body)
    sample_id = max(nums, key=len) if nums else None
    sample_text = None
    if "=" in body and not body.startswith("{"):
        pairs = body.split("&")
        best_len = 0
        for p in pairs:
            if "=" in p:
                v = p.split("=", 1)[1]
                if len(v) > best_len and not v.replace(".", "").replace("-", "").isdigit():
                    best_len = len(v)
                    sample_text = v
    else:
        vals = _re.findall(r'"((?:[^"\\]|\\.){4,})"', body)
        if vals:
            sample_text = max(vals, key=len)
    return sample_id, sample_text


def _mutate_body_id(body):
    """把 body 中疑似评论 ID 的数字串 +1，用于测试签名是否覆盖 ID。返回 (旧ID, 新body)"""
    sample_id, _ = _extract_id_and_text(body)
    if not sample_id:
        return None, body
    new_id = str(int(sample_id) + 1)
    return sample_id, body.replace(sample_id, new_id)


def _judge_signature(result):
    """根据重放结果判定签名可复用性（A=原样重放，B=改文本，C=改评论ID）"""
    def _sig_err(r):
        if not isinstance(r, dict):
            return False
        b = (r.get("body") or "") + str(r.get("status") or "")
        for k in ("签名", "sign", "Sign", "401", "403", "token", "非法", "验证失败", "forbidden"):
            if k in b:
                return True
        return False

    a, b, c = result.get("testA_replay"), result.get("testB_replay"), result.get("testC_replay")
    if a is None:
        return "UNKNOWN: 测试A未执行"
    if _sig_err(a):
        return "FAIL: 原样重放即签名错误——签名一次性或绑上下文，接口直调不可行，走回退路线"
    if b is not None and _sig_err(b):
        return "PARTIAL: 签名可复用但覆盖body文本——仅能原样重放，不能自由组模板，走回退路线"
    if c is not None and _sig_err(c):
        return "PARTIAL: 签名覆盖评论ID——只能重放同一条，接口直调不可行，走回退路线"
    if c is None:
        return "PARTIAL: 文本可通过但未执行改ID测试，暂按回退路线处理"
    return "PASS: 签名可复用且不覆盖文本/ID——接口直调路线可行"


_REPLY_DBG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug", "reply_rpa")


def _ocr_boxes(page, shot_path):
    """截屏 OCR，返回 [(text, cx, cy)]（CSS 像素坐标）"""
    im = _shot(page)
    _save_debug(im, shot_path)
    res, _ = _ocr_img(im)
    out = []
    if not res:
        log(f"OCR无结果 shot={shot_path}")
        return out
    try:
        dpr = page.evaluate("() => window.devicePixelRatio || 1") or 1
    except Exception:
        dpr = 1
    _bad = 0
    for it in res:
        try:
            box, text, conf = it
            t = (text or "").strip()
            if not t:
                continue
            xs = [p[0] for p in box]
            ys = [p[1] for p in box]
            out.append((t, sum(xs) / len(xs) / dpr, sum(ys) / len(ys) / dpr))
        except Exception:
            _bad += 1
            continue
    if _bad:
        log(f"OCR解析失败条数={_bad}/{len(res)} 首条类型={type(res[0])} 首条={str(res[0])[:120]}")
    return out


def _ocr_pick(page, kws, exclude=(), min_x_frac=0.0):
    """OCR 定位包含 kws 关键字、不含 exclude 关键字的文字块，按 y 排序"""
    try:
        os.makedirs(_REPLY_DBG_DIR, exist_ok=True)
    except Exception:
        pass
    boxes = _ocr_boxes(page, os.path.join(_REPLY_DBG_DIR, "ocr.png"))
    log(f"OCR_pick kws={kws} boxes={len(boxes)} 样例={[t for t, _, _ in boxes[:6]]}")
    vp_w = 0
    try:
        vp_w = page.evaluate("() => window.innerWidth || 0")
    except Exception:
        pass
    out = []
    for t, x, y in boxes:
        if not any(k in t for k in kws):
            continue
        if any(e in t for e in exclude):
            continue
        if vp_w and x < vp_w * min_x_frac:
            continue
        out.append((t, x, y))
    out.sort(key=lambda c: c[2])
    return out


def _rpa_goto_page(page, page_no):
    """翻到指定期望页码。优先 DOM（美团翻页控件在 iframe 内，数字页码为 DOM 元素），
    OCR 仅作兜底（评价内容里的日期/满减数字会严重干扰 OCR 页码识别）。返回是否成功。"""
    try:
        # 1) DOM：底部「下一页」按钮
        for fr in page.frames:
            for txt in ("下一页", "下页"):
                try:
                    loc = fr.get_by_text(txt, exact=False)
                    if loc.count() > 0:
                        bb = loc.first.bounding_box()
                        if bb and bb["y"] > 300:
                            loc.first.click(timeout=3000)
                            page.wait_for_timeout(2500)
                            log(f"已点击「{txt}」翻页(DOM frame={fr.url[:40]})")
                            return True
                except Exception:
                    continue
        # 2) DOM：底部数字页码（文本为纯数字 + 视口下半部 + 小尺寸盒子 + 水平中带）
        try:
            vw = page.evaluate("() => window.innerWidth || 0") or 1920
        except Exception:
            vw = 1920
        for fr in page.frames:
            try:
                loc = fr.get_by_text(str(page_no), exact=True)
                cnt = loc.count()
            except Exception:
                continue
            for i in range(min(cnt, 6)):
                try:
                    bb = loc.nth(i).bounding_box()
                except Exception:
                    continue
                if not bb:
                    continue
                cx = bb["x"] + bb["width"] / 2
                cy = bb["y"] + bb["height"] / 2
                if cy > 400 and bb["width"] < 60 and bb["height"] < 50 and 0.15 < cx / vw < 0.92:
                    loc.nth(i).click(timeout=3000)
                    page.wait_for_timeout(2500)
                    log(f"已点击页码 {page_no}(DOM frame={fr.url[:40]})")
                    return True
        # 3) OCR 兜底（拟合算法）
        pt = _find_pager_point(page, page_no, os.path.join(_REPLY_DBG_DIR, f"pager_{page_no}.png"))
        if pt:
            page.mouse.click(pt[0], pt[1])
            page.wait_for_timeout(2500)
            log(f"已点击页码 {page_no}(OCR)")
            return True
        log(f"翻页失败：DOM 与 OCR 均未定位到页码 {page_no}")
        return False
    except Exception as e:
        log(f"翻页到第{page_no}页失败: {e}")
        return False


def _ocr_zone(page, tag, x_frac0=0.0, y_frac0=0.0, scale=3):
    """截屏并裁剪 (x_frac0,y_frac0)-右下角区域，LANCZOS 放大 scale 倍后 OCR。
    评价卡片由 Flutter canvas 绘制，12px 小字不放大识别不出。返回 [(text, cx, cy)]（页面 CSS 像素）"""
    try:
        from PIL import Image
        im = _shot(page)
        W, H = im.size
        x0, y0 = int(W * x_frac0), int(H * y_frac0)
        crop = im.crop((x0, y0, W, H))
        crop = crop.resize((crop.width * scale, crop.height * scale), Image.LANCZOS)
        _save_debug(crop, os.path.join(_REPLY_DBG_DIR, f"zone_{tag}.png"))
        res, _ = _ocr_img(crop)
    except Exception as e:
        log(f"OCR区域识别异常({tag}): {e}")
        return []
    try:
        dpr = page.evaluate("() => window.devicePixelRatio || 1") or 1
    except Exception:
        dpr = 1
    out = []
    for it in (res or []):
        try:
            box, text, conf = it
            t = (text or "").strip()
            if not t:
                continue
            xs = [p[0] for p in box]
            ys = [p[1] for p in box]
            cx = sum(xs) / len(xs) / scale / dpr + x0 / dpr
            cy = sum(ys) / len(ys) / scale / dpr + y0 / dpr
            out.append((t, round(cx), round(cy)))
        except Exception:
            continue
    return out


def _editor_open_pos(page, tag="edt"):
    """OCR 检测回复编辑器是否打开。编辑器同样由 canvas 绘制，DOM 扫描不可见，
    只能识别「取消」「公开展示」占位文案与「N/300」计数器。返回锚点字典或 None"""
    _re3 = __import__("re")
    boxes = _ocr_zone(page, tag, x_frac0=0.25, y_frac0=0.2)
    cancel = ph = counter = None
    for t, x, y in boxes:
        if t == "取消" and cancel is None:
            cancel = (x, y)
        elif ("公开展示" in t or "请注意措辞" in t) and ph is None:
            ph = (x, y)
        elif _re3.match(r"^\d+/300$", t) and counter is None:
            counter = (x, y)
    if cancel:
        return {"cancel": cancel, "ph": ph, "counter": counter}
    return None


def _flutter_editor_rect(page):
    """检测 Flutter 回复编辑器的输入区矩形。编辑器打开时引擎会在 glass pane
    shadowRoot 里放一个有实际尺寸的 flt-text-editing textarea（键盘输入宿主）。
    注意 activeElement 检测不可靠（实测聚焦的是 FLT-GLASS-PANE）。
    返回 (x, y, w, h)（页面 CSS 坐标）或 None（编辑器未打开）"""
    for fr in page.frames:
        try:
            r = fr.evaluate("""() => {
                const gp = document.querySelector('flt-glass-pane');
                const sr = gp && gp.shadowRoot;
                if (!sr) return null;
                for (const t of sr.querySelectorAll('textarea')) {
                    if (!/flt-text-editing/.test(t.className || '')) continue;
                    const r = t.getBoundingClientRect();
                    if (r.width > 50 && r.height > 15 && r.y >= 0 && r.y < window.innerHeight) {
                        return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)];
                    }
                }
                return null;
            }""")
            if r:
                return r
        except Exception:
            continue
    return None


def _editor_focus_ok(page):
    """确认键盘焦点已在编辑态 textarea 上。document.activeElement 实测返回的是
    FLT-GLASS-PANE（宿主元素），须用 shadowRoot.activeElement 才能拿到焦点真身"""
    for fr in page.frames:
        try:
            ok = fr.evaluate("""() => {
                const gp = document.querySelector('flt-glass-pane');
                const sr = gp && gp.shadowRoot;
                if (!sr) return false;
                const ae = sr.activeElement;
                return !!(ae && /flt-text-editing/.test(ae.className || ''));
            }""")
            if ok:
                return True
        except Exception:
            continue
    return False


def _click_editor_textarea(page):
    """用 Playwright locator 点击可见的编辑态 textarea 建立焦点。CSS 选择器自动
    穿透 open shadowRoot，点击前实时解析元素位置，规避编辑器开启动画导致的
    矩形过期（此前用旧矩形坐标点击会落空，Ctrl+A 触发页面级全选）"""
    try:
        vh = page.evaluate("() => window.innerHeight || 600")
    except Exception:
        vh = 600
    for fr in page.frames:
        try:
            loc = fr.locator("flt-glass-pane textarea.flt-text-editing")
            for i in range(loc.count()):
                el = loc.nth(i)
                try:
                    bb = el.bounding_box()
                except Exception:
                    continue
                if not bb or bb.get("width", 0) <= 50 or bb.get("height", 0) <= 15:
                    continue
                if bb.get("y", -1) < 0 or bb.get("y", 0) >= vh:
                    continue
                try:
                    el.click(timeout=3000)
                    return True
                except Exception:
                    continue
        except Exception:
            continue
    return False


def _list_btn_min_x(page):
    """「回复」按钮横向下限：过滤列表上部「是否回复/未回复」等筛选文字被 OCR 拆出的假按钮"""
    try:
        vw = page.evaluate("() => window.innerWidth || 1200")
    except Exception:
        vw = 1200
    return vw * 0.55


def _click_reply_button_for(page, review, k):
    """定位并点击目标评价的「回复」按钮，并确认编辑器展开。
    评价卡片由 Flutter canvas 绘制（DOM 里只有绘制产物、无事件处理器，元素点击无效），
    唯一可靠路径：OCR 放大定位按钮坐标 → CDP 可信鼠标点击（带抖动重试，OCR 确认编辑器出现）。
    定位优先按评价内容文本锚定（内容下方最近的「回复」按钮即同卡片按钮）；
    OCR 读不出内容时退化为第 k 个可见按钮（仅未滚动的首屏有效）。
    返回 (是否已点开编辑器, None)"""
    key = ((review.get("content") or "").strip())[:10]
    # 0) 清理可能残留的旧编辑器：先用 DOM 判断是否真的开着，避免每次都盲跑一次 OCR
    try:
        if _flutter_editor_rect(page):
            st = _editor_open_pos(page, "stale")
            if st:
                page.mouse.click(st["cancel"][0], st["cancel"][1])
                page.wait_for_timeout(800)
                log("已关闭残留编辑器")
    except Exception:
        pass
    btn = None
    for attempt in range(6):
        boxes = _ocr_zone(page, f"loc{attempt}", x_frac0=0.2, scale=2)
        min_x = _list_btn_min_x(page)
        btns = sorted([(x, y) for t, x, y in boxes if t == "回复" and x > min_x],
                      key=lambda c: c[1])
        anchor_y = None
        if len(key) >= 6:
            for t, x, y in boxes:
                if len(t) >= 4 and (key[:4] in t or t[:4] in key):
                    anchor_y = y
                    break
        if anchor_y is not None:
            below = [b for b in btns if b[1] > anchor_y]
            if below:
                btn = below[0]
                log(f"内容锚定目标卡片「回复」按钮(k={k} key={key[:8]}… 坐标=({btn[0]},{btn[1]}))")
                break
        if attempt == 0 and anchor_y is None and len(btns) > k:
            btn = btns[k]
            log(f"第{k}个可见「回复」按钮(坐标=({btn[0]},{btn[1]}))")
            break
        # 目标不在视口内：滚动评价列表后重试（滚轮必须落在卡片列表上，
        # 筛选面板区域不响应滚轮）
        try:
            vw = page.evaluate("() => window.innerWidth || 800")
            vh = page.evaluate("() => window.innerHeight || 600")
        except Exception:
            vw, vh = 800, 600
        page.mouse.move(vw * 0.6, vh * 0.8)
        page.wait_for_timeout(200)
        page.mouse.wheel(0, 600)
        page.wait_for_timeout(1000)
    if not btn:
        log(f"目标评价「回复」按钮未定位到(k={k} key={key[:8]}…)")
        return False, None
    # 归一化滚动：按钮太靠视口底部时，编辑器会向下展开超出视口（OCR 检测不到取消），
    # 先滚动列表让按钮上方留出 ≥300px 的编辑器空间，再按内容锚定/邻近关系重新定位按钮
    try:
        vw = page.evaluate("() => window.innerWidth || 800")
        vh = page.evaluate("() => window.innerHeight || 600")
    except Exception:
        vw, vh = 800, 600
    for _ in range(3):
        if btn[1] <= vh - 300:
            break
        page.mouse.move(vw * 0.6, vh * 0.8)
        page.wait_for_timeout(200)
        page.mouse.wheel(0, btn[1] - (vh - 300))
        page.wait_for_timeout(900)
        boxes = _ocr_zone(page, "locn", x_frac0=0.2, scale=2)
        min_x = _list_btn_min_x(page)
        btns = sorted([(x, y) for t, x, y in boxes if t == "回复" and x > min_x],
                      key=lambda c: c[1])
        nb = None
        if len(key) >= 6:
            for t, x, y in boxes:
                if len(t) >= 4 and (key[:4] in t or t[:4] in key):
                    below = [b for b in btns if b[1] > y]
                    if below:
                        nb = below[0]
                    break
        if nb is None:
            near = [b for b in btns if abs(b[1] - btn[1]) < 60]
            if near:
                nb = near[0]
        if not nb:
            log("归一化滚动后未重新定位到按钮，沿用原坐标")
            break
        btn = nb
    bx, by = btn
    for dx, dy in ((0, 0), (-4, 0), (4, 0), (0, -3), (-4, -3)):
        page.mouse.move(bx + dx, by + dy)
        page.wait_for_timeout(150)
        page.mouse.down()
        page.wait_for_timeout(80)
        page.mouse.up()
        for _ in range(4):
            page.wait_for_timeout(700)
            # DOM 优先：编辑器打开时引擎必有 flt-text-editing textarea，命中即成功，免 OCR
            if _flutter_editor_rect(page) or _editor_open_pos(page, "opened"):
                log(f"已点开「回复」编辑器(k={k} 点击=({bx + dx},{by + dy}))")
                return True, None
        page.keyboard.press("Escape")
        page.wait_for_timeout(400)
    log(f"「回复」按钮抖动点击均未展开编辑器(k={k} 坐标=({bx},{by}))")
    return False, None


def _rpa_reply_one(page, reply_events, text, card_eh=None, expect_pid=None):
    """对已点开的回复编辑器执行：输入→提交（编辑态 textarea 矩形 + OCR 混合定位）。
    编辑器由 Flutter canvas 绘制，DOM 文本不可见；但编辑器打开时引擎会在 glass pane
    shadowRoot 放一个有实际尺寸的 flt-text-editing textarea（键盘输入宿主），其矩形
    即输入区精确位置。键入成功以 N/300 计数器变化确认，提交按钮为输入区同排最右
    黄底「回复」。成败以页面真实发出的 gw/customer/comment/reply 响应为准，
    且 toCommentId 必须与目标评价一致（防点错卡片）。返回 (ok, msg)"""
    import re as _re3
    ev0 = len(reply_events)
    # 1) 等编辑态 textarea（编辑器已打开的确定性信号），超出视口底部时下滚露出完整编辑器
    ta = None
    for _ in range(6):
        ta = _flutter_editor_rect(page)
        if not ta:
            page.wait_for_timeout(1000)
            continue
        try:
            vh2 = page.evaluate("() => window.innerHeight || 600")
        except Exception:
            vh2 = 600
        if ta[1] + ta[3] <= vh2 - 20:
            break
        # 编辑器底部超出视口：在编辑器区域上滚轮下滚列表
        over = (ta[1] + ta[3]) - vh2 + 40
        page.mouse.move(ta[0] + ta[2] / 2, max(ta[1], 10))
        page.wait_for_timeout(200)
        page.mouse.wheel(0, max(over, 120))
        page.wait_for_timeout(900)
    if not ta:
        if _DEBUG_SHOTS:
            try:
                page.screenshot(path=os.path.join(_REPLY_DBG_DIR, "editor_fail.png"))
            except Exception:
                pass
        return False, "点击回复后编辑器未出现(未检到编辑态textarea)"
    # 2) 建立键盘焦点并键入。焦点优先用 Playwright locator 点击编辑态 textarea
    # （穿透 shadowRoot + 点击前实时解析位置），坐标点击兜底；焦点确认以
    # shadowRoot.activeElement 为准（document.activeElement 指向 glass pane 不可用）
    def _ensure_focus():
        for _ in range(3):
            if _editor_focus_ok(page):
                return True
            if not _click_editor_textarea(page):
                cur = _flutter_editor_rect(page)
                if not cur:
                    page.wait_for_timeout(600)
                    continue
                page.mouse.move(cur[0] + cur[2] / 2, cur[1] + cur[3] / 2)
                page.wait_for_timeout(150)
                page.mouse.down()
                page.wait_for_timeout(80)
                page.mouse.up()
            page.wait_for_timeout(600)
        return _editor_focus_ok(page)

    def _js_set_editor_text(txt):
        """兜底：直接写 textarea.value 并派发 input 事件（Flutter web 引擎监听该
        事件读取 textarea 值同步编辑状态，不校验 isTrusted）"""
        for fr in page.frames:
            try:
                ok = fr.evaluate("""(txt) => {
                    const gp = document.querySelector('flt-glass-pane');
                    const sr = gp && gp.shadowRoot;
                    if (!sr) return false;
                    for (const t of sr.querySelectorAll('textarea.flt-text-editing')) {
                        const r = t.getBoundingClientRect();
                        if (r.width > 50 && r.height > 15) {
                            t.focus();
                            t.value = txt;
                            t.setSelectionRange(txt.length, txt.length);
                            t.dispatchEvent(new Event('input', {bubbles: true}));
                            return true;
                        }
                    }
                    return false;
                }""", txt)
                if ok:
                    return True
            except Exception:
                continue
        return False

    ok_typed = False
    for rnd in range(3):
        if _ensure_focus():
            log(f"编辑态textarea焦点已建立(rnd={rnd})")
        else:
            log(f"编辑态textarea焦点未确认(rnd={rnd})，仍尝试键入")
        page.keyboard.press("Control+a")
        page.wait_for_timeout(150)
        page.keyboard.press("Delete")
        page.wait_for_timeout(250)
        if rnd == 0:
            # 中文必须走 IME 式插入：keyboard.type 会把 CJK 发成未知键，
            # Flutter 编辑态变成 '???'（计数器只验长度发现不了）
            page.keyboard.insert_text(text)
        else:
            _js_set_editor_text(text)
        page.wait_for_timeout(800)
        # 3) 键入校验：优先读编辑态 textarea 的 DOM 真实值（与 N/300 计数器等价，免一次 OCR）
        dv_val = None
        try:
            for fr in page.frames:
                dv = fr.evaluate("""() => {
                    const gp = document.querySelector('flt-glass-pane');
                    const sr = gp && gp.shadowRoot;
                    if (!sr) return null;
                    for (const t of sr.querySelectorAll('textarea.flt-text-editing')) {
                        const rc = t.getBoundingClientRect();
                        if (rc.width > 50 && rc.height > 15) return t.value;
                    }
                    return null;
                }""")
                if dv is not None:
                    dv_val = dv
                    break
        except Exception:
            pass
        if dv_val is not None:
            bad_chars = dv_val.count("?") >= 3
            ok_typed = dv_val.strip() == text.strip()
            has_cnt = ok_typed
            if _DEBUG_SHOTS:
                log("textarea值码点: " + " ".join(hex(ord(c)) for c in dv_val[:36]))
        else:
            # DOM 读不到时才退回 OCR 计数器校验
            boxes = _ocr_zone(page, f"cnt{rnd}", x_frac0=0.25, y_frac0=0.2)
            has_cnt = False
            bad_chars = False
            for t, x, y in boxes:
                if _re3.match(r"^\d+/300$", t) and not t.startswith("0/"):
                    has_cnt = True
                if t.count("?") >= 3:
                    bad_chars = True
            ok_typed = has_cnt and not bad_chars
        if ok_typed:
            break
        log(f"键入校验未通过(rnd={rnd} 计数器={has_cnt} 乱码={bad_chars})")
    if not ok_typed:
        if _DEBUG_SHOTS:
            try:
                page.screenshot(path=os.path.join(_REPLY_DBG_DIR, "typing_fail.png"))
            except Exception:
                pass
        return False, "回复内容键入失败（N/300 计数器未变化）"
    # 4) 提交：编辑器底部「取消+回复」按钮排（回复在最右）。用「取消」锚定同一排
    # （卡片列表没有「取消」，编辑器按钮排必有），避免误点卡片里同名的「回复」按钮
    ta2 = _flutter_editor_rect(page) or ta
    ty2 = ta2[1] + ta2[3] / 2
    boxes = _ocr_zone(page, "sub", x_frac0=0.25, y_frac0=0.2)
    cancels = [(x, y) for t, x, y in boxes if t == "取消"]
    subs = []
    for cx, cy in cancels:
        for rx, ry in [(x, y) for t, x, y in boxes if t == "回复"]:
            if abs(ry - cy) < 15 and rx > cx:
                subs.append((rx, ry))
    if not subs:
        # 兜底：输入区下缘~+160 范围内最右的「回复」（防「取消」被 OCR 漏识别）
        subs = [(x, y) for t, x, y in boxes
                if t == "回复" and x > ta2[0] + ta2[2] - 30
                and ta2[1] + ta2[3] - 20 < y < ta2[1] + ta2[3] + 160]
    if not subs:
        return False, "未找到提交按钮(取消同排无回复)"
    sx, sy = max(subs, key=lambda c: c[0])
    log(f"提交按钮定位: ta2={ta2} 候选={subs} 点击=({sx},{sy})")
    for attempt in range(3):
        if attempt == 2:
            # 兜底：坐标点击两连败时，点输入区建立焦点后按 Enter（部分编辑器支持回车提交）
            cur = _flutter_editor_rect(page)
            if cur:
                page.mouse.move(cur[0] + cur[2] / 2, cur[1] + cur[3] / 2)
                page.wait_for_timeout(150)
                page.mouse.down()
                page.wait_for_timeout(60)
                page.mouse.up()
                page.wait_for_timeout(300)
            page.keyboard.press("Enter")
            log("已尝试 Enter 提交(兜底)")
        else:
            page.mouse.move(sx, sy)
            page.wait_for_timeout(150)
            page.mouse.down()
            page.wait_for_timeout(80)
            page.mouse.up()
        # 5) 等页面发出 reply 请求并返回响应
        for _ in range(10):
            page.wait_for_timeout(1000)
            if len(reply_events) > ev0:
                ev = reply_events[-1]
                if expect_pid and ev.get("toCommentId") and str(ev["toCommentId"]) != str(expect_pid):
                    return False, f"回复接口评价ID不匹配(期望{expect_pid}/实际{ev['toCommentId']})"
                return ev["ok"], ev["message"] or ("成功" if ev["ok"] else "失败")
        if attempt == 0:
            log("提交后未捕获响应，微移重试一次")
            sx, sy = sx - 3, sy - 2
        elif attempt == 1:
            still = bool(_flutter_editor_rect(page)) or bool(_editor_open_pos(page, "subchk"))
            log(f"两次点击提交未捕获响应(编辑器仍在={still})，转 Enter 兜底")
    return False, "提交后未捕获到回复接口响应（超时）"


def cmd_reply_reviews(args):
    """批量回复评价（RPA 模式，对标西瓜本地客户端）：
    OCR 定位「回复/提交」按钮 + CDP 键鼠注入，全程不占用用户鼠标、无需窗口置顶。
    成败以页面真实请求 gw/customer/comment/reply 的响应为准（code=0 成功）。
    args: { shopName, items: [{platformReviewId, replyText}] }
    输出: { type: "reply_done", results: [{platformReviewId, success, message}] }
    防重复：以 comment/list 最新状态为准，已回复评价自动跳过。"""
    import re as _re2
    page = _ensure_logged_in_page()
    if not page:
        send({"type": "error", "message": "浏览器未登录美团，无法回复"})
        return

    shop_name = args.get("shopName", "") or ""
    items = args.get("items") or []
    if not items:
        send({"type": "reply_done", "results": [], "message": "无待回复项"})
        return
    text_map = {}
    for it in items:
        pid = str(it.get("platformReviewId", "") or "")
        txt = str(it.get("replyText", "") or "").strip()
        if pid and txt:
            text_map[pid] = txt[:300]
    if not text_map:
        send({"type": "reply_done", "results": [], "message": "待回复项缺少评价ID或回复文本"})
        return

    if shop_name:
        _switch_to_shop(page, shop_name)

    # 导航到评价页：优先直跳 hash 路由（页面常会自动跳回裸首页并卡在「加载中...」，
    # 菜单点击此时必然失败；直跳可恢复），失败再回退菜单点击
    _nav_ok = False
    try:
        page.goto(REVIEW_PAGE_URL, wait_until="domcontentloaded", timeout=20000)
        _nav_ok = True
        log(f"已直跳评价页: {page.url[:90]}")
    except Exception as e:
        log(f"直跳评价页失败，回退菜单点击: {e}")
    if not _nav_ok:
        _click_text_deep_retry(page, "顾客管理")
        time.sleep(1)
        if not _click_text_deep_retry(page, "顾客评价"):
            _click_text_deep_retry(page, "评价管理")
        time.sleep(3)
    log(f"回复导航后 URL: {page.url} frames={len(page.frames)}")
    latest = {}
    page_rows = []      # 最近一次 comment/list 的有序行（当前页）
    reply_events = []   # 页面自发回复请求的结果 {toCommentId, ok, message}

    def _on_resp(resp):
        try:
            u = resp.url.lower()
            if "comment" in u:
                try:
                    _db = resp.text() or ""
                except Exception:
                    _db = ""
                log(f"[dbg] {resp.request.method} {resp.url[:110]} status={resp.status} len={len(_db)} head={_db[:60]!r}")
            if "comment/list" in u:
                try:
                    b = resp.text()
                    # 注意：只拦 HTTP 403 或「403 Forbidden」字样，不能匹配裸「403」
                    # （正常响应体里可能恰好包含 403 子串，此前误杀过有效响应）
                    if resp.status == 200 and b and len(b) > 50 and "403 Forbidden" not in b:
                        rows, _t = _extract_reviews_from_json(json.loads(b))
                        page_rows[:] = rows
                        for r in rows:
                            latest[r["platformReviewId"]] = r
                        log(f"comment/list 已解析: rows={len(rows)} total标记={_t}")
                        try:
                            log(f"comment/list URL: {resp.url}")
                            log("  本页pids: " + ", ".join(
                                f"{r['platformReviewId']}({'已回' if r['replyStatus'] == 'replied' else '未回'})"
                                for r in rows))
                        except Exception:
                            pass
                    else:
                        log(f"comment/list 响应被过滤: status={resp.status} len={len(b or '')} forbidden={'403 Forbidden' in (b or '')}")
                        try:
                            with open(os.path.join(_REPLY_DBG_DIR, "list_dump.json"), "w", encoding="utf-8") as f:
                                f.write(b or "")
                        except Exception:
                            pass
                except Exception as e:
                    log(f"comment/list 解析异常: {e}")
                    try:
                        with open(os.path.join(_REPLY_DBG_DIR, "list_dump.json"), "w", encoding="utf-8") as f:
                            f.write(b if isinstance(b, str) else repr(b))
                    except Exception:
                        pass
            elif "comment/reply" in u and resp.request.method == "POST":
                body = resp.request.post_data or ""
                m = _re2.search(r"toCommentId=(\d+)", body)
                tid = m.group(1) if m else ""
                b = resp.text()
                ok = ('"code":0' in b.replace(" ", "")) or ('"success":true' in b.lower())
                msg = ""
                try:
                    d = json.loads(b)
                    msg = d.get("message") or ("成功" if ok else "失败")
                except Exception:
                    msg = (b or "")[:100]
                reply_events.append({"toCommentId": tid, "ok": bool(ok), "message": msg})
        except Exception:
            pass

    page.on("response", _on_resp)
    results = []
    done_ids = set()
    try:
        try:
            page.reload(wait_until="domcontentloaded", timeout=20000)
        except Exception:
            pass
        # reload 会回到默认「综合评分明细」标签，必须点「外卖评价列表」才有 comment/list
        _open_review_list_tab(page)
        # 等首轮评价列表
        for _ in range(10):
            page.wait_for_timeout(1000)
            if page_rows:
                break
        if not page_rows:
            # 兜底：可能标签点击与 reload 竞态，再点一次标签
            _open_review_list_tab(page, max_tries=3)
            for _ in range(5):
                page.wait_for_timeout(1000)
                if page_rows:
                    break
        if not page_rows:
            log("未监听到评价列表接口，放弃本轮回复")
            send({"type": "error", "message": "未监听到评价列表接口（comment/list），无法回复"})
            return

        MAX_PAGES = 5
        log(f"待回复目标: {list(text_map.keys())}")
        for page_no in range(1, MAX_PAGES + 1):
            if len(done_ids) >= len(text_map):
                break
            ordered = list(page_rows)
            unreplied_all = [r for r in ordered if r.get("replyStatus") != "replied"]
            targets = [r for r in unreplied_all
                       if r["platformReviewId"] in text_map
                       and r["platformReviewId"] not in done_ids]
            log(f"第{page_no}页: 本页rows={len(ordered)} 未回={len(unreplied_all)}"
                f" 目标命中={len(targets)}")
            if targets:
                for tgt in targets:
                    prid = tgt["platformReviewId"]
                    k = unreplied_all.index(tgt)
                    opened, card_eh = _click_reply_button_for(page, tgt, k)
                    if not opened:
                        log(f"目标 {prid} 未能定位「回复」按钮，本页放弃")
                        break
                    ok, msg = _rpa_reply_one(page, reply_events, text_map[prid],
                                             card_eh=card_eh, expect_pid=prid)
                    results.append({"platformReviewId": prid, "success": ok, "message": msg})
                    if ok:
                        done_ids.add(prid)
                        latest[prid]["replyStatus"] = "replied"
                    log(f"RPA回复 {prid}: {'成功' if ok else '失败'} - {msg}")
                    # 防频控间隔
                    page.wait_for_timeout(1500 + (len(done_ids) * 3 % 4) * 500)
            if len(done_ids) >= len(text_map):
                break
            if page_no < MAX_PAGES:
                if not _rpa_goto_page(page, page_no + 1):
                    break
    finally:
        try:
            page.remove_listener("response", _on_resp)
        except Exception:
            pass

    ok_n = sum(1 for x in results if x["success"])
    extra = ""
    missing = len(text_map) - len({r["platformReviewId"] for r in results})
    if missing > 0:
        extra = f"，{missing}条未找到或跳过"
    send({"type": "reply_done", "results": results,
          "message": f"回复完成: 成功{ok_n}/{len(results)}{extra}"})


def _fs_probe_poi_id(page, cap):
    """从页面请求中探测当前店铺 wmPoiId（食安接口的请求 URL/表单里都带）"""
    def _on_req(req):
        try:
            u = req.url
            if "waimaieapp.meituan.com" not in u:
                return
            m = re.search(r"[?&]wmPoiId(?:s)?=(\d+)", u)
            if m:
                cap.append(m.group(1))
                return
            body = req.post_data or ""
            m2 = re.search(r"(?:^|&)wmPoiId(?:s)?=(\d+)", body)
            if m2:
                cap.append(m2.group(1))
        except Exception:
            pass
    page.on("request", _on_req)
    return _on_req


def _fs_probe_poi_via_reviews(page, timeout_s=18):
    """导航评价页探测 wmPoiId（评价页接口请求均携带 wmPoiId，该页面加载方式已验证可靠；
    规则明细页直拼路径会 404，无法在其上探测）"""
    review_url = ("https://e.waimai.meituan.com/#https://waimaieapp.meituan.com"
                  "/frontweb/ffw/userComment_gw")
    cap = []
    un = _fs_probe_poi_id(page, cap)
    try:
        page.goto(review_url, wait_until="domcontentloaded", timeout=30000)
    except Exception:
        pass
    for _ in range(timeout_s):
        if cap:
            break
        page.wait_for_timeout(1000)
    try:
        page.remove_listener("request", un)
    except Exception:
        pass
    return cap[0] if cap else ""


def _fs_open_detail_page(page, poi_id):
    """打开食安明细页：壳先把 iframe 加载为裸 ruleCenter（地址栏二级 hash 不会被传给
    iframe，直拼 indicator_detail 路径服务端 404），Flutter 页面加载后需在 frame 内
    注入 hash 触发运行时路由（与手动点击后的真实行为一致），随后页面才发 indicator/detail。"""
    try:
        page.goto("https://e.waimai.meituan.com/#https://waimaieapp.meituan.com"
                  "/frontweb/ruleCenter",
                  wait_until="domcontentloaded", timeout=30000)
    except Exception:
        pass
    frame = None
    for _ in range(15):
        for fr in page.frames:
            if "frontweb/ruleCenter" in (fr.url or ""):
                frame = fr
                break
        if frame:
            break
        page.wait_for_timeout(1000)
    if not frame:
        return None
    h = "#/rule/indicator_detail?indicatorCode=food_safety_risk_rank"
    if poi_id:
        h += f"&wmPoiId={poi_id}"
    try:
        frame.evaluate(f"window.location.hash = {json.dumps(h)};")
    except Exception:
        pass
    return frame


def cmd_scrape_food_safety(args):
    """抓取食安负反馈明细：导航明细页，拦截 indicator/detail 响应"""
    page = _ensure_logged_in_page()
    if not page:
        send({"type": "error", "message": "浏览器未登录"})
        return
    shop_name = args.get("shopName", "") or ""
    if shop_name:
        ok, actual_name, verify_source = _switch_and_verify(page, shop_name)
        if not ok:
            _send_shop_not_found(shop_name, actual_name, verify_source)
            return

    detail_bodies = []

    def _on_resp(resp):
        try:
            if "indicator/detail" in resp.url and "waimaieapp" in resp.url:
                ct = resp.headers.get("content-type", "")
                if "json" in ct:
                    b = resp.text()
                    if b and b.lstrip().startswith("{"):
                        detail_bodies.append(b)
        except Exception:
            pass

    page.on("response", _on_resp)
    # 1) 评价页探测 poiId（明细页 hash 路由需要 wmPoiId，缺失时页面不发明细请求）
    poi_id = _fs_probe_poi_via_reviews(page)
    # 2) 打开明细页：壳加载裸 ruleCenter + frame 内注入 hash 路由
    _fs_open_detail_page(page, poi_id)
    # 3) 等明细接口响应（wait_for_timeout 泵事件，time.sleep 会导致回调不执行）
    for _ in range(24):
        if detail_bodies:
            break
        page.wait_for_timeout(1000)
    page.wait_for_timeout(2000)
    try:
        page.remove_listener("response", _on_resp)
    except Exception:
        pass

    items, total = [], 0
    for body in detail_bodies:
        try:
            data = json.loads(body)
        except Exception:
            continue
        d = data.get("data") or {}
        total = int(d.get("total") or 0)
        for row in (d.get("afterSaleDetail") or []):
            items.append({
                "orderNo": str(row.get("orderNo") or ""),
                "orderTime": str(row.get("orderTime") or ""),
                "feedbackChannel": str(row.get("feedbackChannel") or ""),
                "feedbackType": str(row.get("feedbackType") or ""),
                "content": str(row.get("content") or ""),
            })

    if not items:
        send({"type": "food_safety_scraped", "items": [], "total": 0, "poiId": poi_id,
              "message": "未抓到食安负反馈明细（可能无投诉记录或页面未加载完成）"})
        return

    # total 超过首屏条数时提示（明细接口分页由页面控制，Flutter 翻页难自动化，一般食安投诉量 < 10 条）
    note = ""
    if total > len(items):
        note = f"共{total}条，首屏抓到{len(items)}条，其余请稍后重试或联系管理员"
    send({"type": "food_safety_scraped", "items": items, "total": total, "poiId": poi_id,
          "message": note or f"抓取到 {len(items)} 条食安负反馈"})


def _fs_open_appeal_form(page, poi_id):
    """打开食安申诉表单页，返回表单所在 frame（React iframe）"""
    url = ("https://e.waimai.meituan.com/#https://waimaieapp.meituan.com/h5/gov/h5-home/indicatorAppeal"
           "?indicatorCode=food_safety_risk_rank&sourceType=20"
           + (f"&wmPoiIds={poi_id}" if poi_id else ""))
    try:
        page.goto(url, wait_until="domcontentloaded", timeout=30000)
    except Exception:
        pass
    # 等待期间需要泵事件（time.sleep 不泵，回调不执行）
    for _ in range(30):
        for fr in page.frames:
            if "indicatorAppeal" in fr.url:
                try:
                    if fr.locator("input[type=checkbox]").count() > 0:
                        return fr
                except Exception:
                    pass
        page.wait_for_timeout(1000)
    # 再宽松等一轮（只要有 iframe 就返回，可能暂无可申诉订单）
    for fr in page.frames:
        if "indicatorAppeal" in fr.url:
            return fr
    return None


def _fs_capture_orders(page, cap):
    """挂监听拦截 getOrdersWithStatus 响应"""
    def _on_resp(resp):
        try:
            if "getOrdersWithStatus" in resp.url:
                ct = resp.headers.get("content-type", "")
                if "json" in ct:
                    b = resp.text()
                    if b and b.lstrip().startswith("{"):
                        cap.append(b)
        except Exception:
            pass
    page.on("response", _on_resp)
    return _on_resp


def _fs_parse_orders(bodies):
    """解析 getOrdersWithStatus 响应 → [{orderNo,status,reason,auditRemark,type}]"""
    rows = []
    for body in bodies:
        try:
            data = json.loads(body)
        except Exception:
            continue
        if str(data.get("code")) != "0":
            continue
        for row in (data.get("data") or []):
            rows.append({
                "orderNo": str(row.get("orderNo") or ""),
                "status": row.get("status"),
                "reason": str(row.get("reason") or ""),
                "auditRemark": str(row.get("auditRemark") or ""),
                "type": str(row.get("indicatorName") or ""),
                "content": str(row.get("content") or ""),
            })
    return rows


def _fs_capture_tickets(page, cap):
    """挂监听拦截申诉记录页 queryAppealTicket 响应"""
    def _on_resp(resp):
        try:
            if "queryAppealTicket" in resp.url:
                ct = resp.headers.get("content-type", "")
                if "json" in ct:
                    b = resp.text()
                    if b and b.lstrip().startswith("{"):
                        cap.append(b)
        except Exception:
            pass
    page.on("response", _on_resp)
    return _on_resp


def _fs_parse_tickets(bodies):
    """解析 queryAppealTicket 响应 → [{orderNo,status,ticketId,cTime(ms)}]"""
    rows = []
    for body in bodies:
        try:
            data = json.loads(body)
        except Exception:
            continue
        if str(data.get("code")) != "0":
            continue
        for t in (data.get("data") or []):
            st = t.get("status")
            tid = t.get("appealTicketId")
            ct = t.get("cTime") or 0
            for ono in (t.get("orderList") or []):
                rows.append({"orderNo": str(ono or ""), "status": st,
                             "ticketId": tid, "cTime": ct})
    return rows


def _fs_open_record_page(page, poi_id):
    """打开申诉记录页（体验中心-指标监测-申诉记录）"""
    url = ("https://e.waimai.meituan.com/#https://waimaieapp.meituan.com/h5/gov/h5-home/indicatorAppeal/record"
           "?sourceType=20"
           + (f"&wmPoiIds={poi_id}" if poi_id else ""))
    try:
        page.goto(url, wait_until="domcontentloaded", timeout=30000)
    except Exception:
        pass


def cmd_appeal_food_safety(args):
    """食安险自动申诉：打开表单页 → 勾选目标订单 → 填理由 → 提交"""
    page = _ensure_logged_in_page()
    if not page:
        send({"type": "error", "message": "浏览器未登录"})
        return
    shop_name = args.get("shopName", "") or ""
    order_nos = [str(x) for x in (args.get("orderNos") or [])]
    reason = str(args.get("reason") or "")

    if shop_name:
        ok, actual_name, verify_source = _switch_and_verify(page, shop_name)
        if not ok:
            _send_shop_not_found(shop_name, actual_name, verify_source)
            return

    # poiId 优先用云端存储的（抓取时回填），为空才走评价页探测
    poi_id = str(args.get("poiId") or "")
    if not poi_id:
        poi_id = _fs_probe_poi_via_reviews(page)

    # 打开申诉表单 + 拦截订单状态
    order_cap = []
    commit_result = []

    def _on_commit(resp):
        try:
            u = resp.url
            # 食安表单提交接口名可能与通用申诉不同，放宽匹配并记录 URL 便于诊断
            if ("commit" in u or "/appeal" in u) and resp.request.method == "POST":
                ct = resp.headers.get("content-type", "")
                if "json" in ct:
                    b = resp.text()
                    if b:
                        commit_result.append(f"{u}||{b}")
        except Exception:
            pass

    un_orders = _fs_capture_orders(page, order_cap)
    page.on("response", _on_commit)
    try:
        fr = _fs_open_appeal_form(page, poi_id)
    finally:
        pass

    if not fr:
        try:
            page.remove_listener("response", un_orders)
            page.remove_listener("response", _on_commit)
        except Exception:
            pass
        send({"type": "food_safety_appeal_result", "success": False,
              "message": "申诉表单页加载失败，请手动确认页面"})
        return

    page.wait_for_timeout(3000)  # 泵事件等 getOrdersWithStatus 响应
    rows = _fs_parse_orders(order_cap)
    # 勾选目标订单 + 填理由 + 提交
    done, failed = [], []
    try:
        checkboxes = fr.locator("input[type=checkbox]")
        cnt = checkboxes.count()
        # 表格行：按 orderNo 文本匹配所在行
        for ono in order_nos:
            try:
                row = fr.locator(f"tr:has-text('{ono}')")
                if row.count() == 0:
                    # 非表格布局：找含订单号的容器再找内部 checkbox
                    row = fr.locator(f"div:has-text('{ono}')").last
                    cb = row.locator("input[type=checkbox], .ant-checkbox-input").first
                else:
                    cb = row.first.locator("input[type=checkbox]").first
                if cb.count() == 0:
                    failed.append(f"{ono}: 未找到勾选框")
                    continue
                if not cb.is_checked():
                    # antd 的原生 input 隐藏（视觉方框在 .ant-checkbox 上），check() 会因不可见超时
                    # 改为点击可视样式层，多候选兜底
                    clicked = False
                    for sel in (".ant-checkbox", "label.ant-checkbox-wrapper", ".ant-checkbox-inner"):
                        try:
                            box = row.first.locator(sel).first
                            if box.count() > 0:
                                box.click(timeout=4000)
                                clicked = True
                                break
                        except Exception:
                            continue
                    if not clicked:
                        try:
                            cb.evaluate("el => el.click()")  # JS 直点不受可见性限制
                            clicked = True
                        except Exception:
                            pass
                    if not clicked:
                        failed.append(f"{ono}: 勾选框点击失败")
                        continue
                    page.wait_for_timeout(800)  # 等 React 状态更新
                # 行内理由输入框
                ta = row.first.locator("textarea, input[type=text]").first
                if ta.count() > 0 and reason:
                    try:
                        ta.click(timeout=3000)
                        ta.fill("")
                        try:
                            page.keyboard.insert_text(reason)
                        except Exception:
                            ta.fill(reason)
                    except Exception:
                        pass
                done.append(ono)
            except Exception as e:
                failed.append(f"{ono}: {str(e)[:60]}")

        if not done:
            send({"type": "food_safety_appeal_result", "success": False,
                  "message": "未能勾选任何订单: " + ("; ".join(failed) or "表单无可申诉订单"),
                  "rows": rows})
            return

        # 点击提交按钮
        submitted = False
        for sel in (".appeal-submit-btn", "button:has-text('提交申诉')", "button:has-text('提交')"):
            try:
                btn = fr.locator(sel).first
                if btn.count() > 0 and btn.is_visible():
                    btn.click(timeout=5000)
                    submitted = True
                    break
            except Exception:
                continue
        if not submitted:
            send({"type": "food_safety_appeal_result", "success": False,
                  "message": "已勾选订单但未找到提交按钮", "rows": rows})
            return

        # 等 commit 响应（快速路径，泵事件）
        target_ono = order_nos[0] if order_nos else ""
        submit_ts = time.time()
        ok = False
        msg = ""
        for _ in range(6):
            if commit_result:
                break
            page.wait_for_timeout(1000)
        for body in commit_result:
            try:
                u, _, b = body.partition("||")
                r = json.loads(b)
                if str(r.get("code")) == "0":
                    ok = True
                    msg = str(r.get("message") or "")
                    break
                msg = str(r.get("message") or "")
            except Exception:
                continue
        if not ok:
            # 兜底：打开申诉记录页，确认出现包含目标订单的新工单（cTime晚于提交时刻）→ 已提交成功
            page.wait_for_timeout(2000)
            ticket_cap = []
            h2 = _fs_capture_tickets(page, ticket_cap)
            try:
                _fs_open_record_page(page, poi_id)
                for _ in range(12):
                    if ticket_cap:
                        break
                    page.wait_for_timeout(1000)
            finally:
                try:
                    page.remove_listener("response", h2)
                except Exception:
                    pass
            for r in _fs_parse_tickets(ticket_cap):
                if r.get("orderNo") == target_ono and (r.get("cTime") or 0) / 1000 > submit_ts - 120:
                    ok = True
                    msg = f"申诉工单已生成(ticket {r.get('ticketId')}, 状态{r.get('status')})"
                    break
        send({"type": "food_safety_appeal_result",
              "success": ok,
              "message": ("申诉提交成功" if ok else f"申诉提交失败: {msg or '无响应'}"),
              "orders": done, "failed": failed, "rows": rows})
    finally:
        try:
            page.remove_listener("response", un_orders)
            page.remove_listener("response", _on_commit)
        except Exception:
            pass


def cmd_check_food_safety(args):
    """食安申诉状态回查：打开申诉记录页拦截 queryAppealTicket，返回每笔工单状态"""
    page = _ensure_logged_in_page()
    if not page:
        send({"type": "error", "message": "浏览器未登录"})
        return
    shop_name = args.get("shopName", "") or ""
    if shop_name:
        ok, actual_name, verify_source = _switch_and_verify(page, shop_name)
        if not ok:
            _send_shop_not_found(shop_name, actual_name, verify_source)
            return

    # poiId 优先用云端存储的，为空才探测
    poi_id = str(args.get("poiId") or "")
    if not poi_id:
        poi_id = _fs_probe_poi_via_reviews(page)
    ticket_cap = []
    h = _fs_capture_tickets(page, ticket_cap)
    try:
        _fs_open_record_page(page, poi_id)
        # 等接口响应被捕获（页面加载后可能晚1-2秒才返回）
        for _ in range(15):
            if ticket_cap:
                break
            page.wait_for_timeout(1000)
    finally:
        try:
            page.remove_listener("response", h)
        except Exception:
            pass

    rows = _fs_parse_tickets(ticket_cap)
    send({"type": "food_safety_status", "items": rows,
          "message": f"回查到 {len(rows)} 条申诉记录"} if rows
         else {"type": "food_safety_status", "items": [],
               "message": "未回查到食安申诉记录"})


def cmd_close(args):
    """关闭浏览器"""
    global _chrome_proc, _browser, _playwright
    try:
        if _browser:
            _browser.close()
    except:
        pass
    try:
        if _chrome_proc:
            _chrome_proc.terminate()
            _chrome_proc.wait(timeout=5)
    except:
        try:
            _chrome_proc.kill()
        except:
            pass
    try:
        if _playwright:
            _playwright.stop()
    except:
        pass
    send({"type": "closed", "message": "浏览器已关闭"})


def main():
    # 强制 UTF-8 通信，避免中文 Windows 默认 GBK 编码导致中文乱码
    try:
        sys.stdin.reconfigure(encoding="utf-8")
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass
    send({"type": "ready", "message": "browser_automation 已启动"})

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            cmd = json.loads(line)
            action = cmd.get("action")
            args = cmd.get("args", {})

            if action == "start_login":
                cmd_start_login(args)
            elif action == "check_login":
                cmd_check_login(args)
            elif action == "scrape_shops":
                cmd_scrape_shops(args)
            elif action == "scrape_reviews":
                cmd_scrape_reviews(args)
            elif action == "run_appeal":
                cmd_run_appeal(args)
            elif action == "check_appeal":
                cmd_check_appeal(args)
            elif action == "scrape_negative":
                cmd_scrape_negative(args)
            elif action == "capture_reply":
                cmd_capture_reply(args)
            elif action == "reply_reviews":
                cmd_reply_reviews(args)
            elif action == "scrape_food_safety":
                cmd_scrape_food_safety(args)
            elif action == "appeal_food_safety":
                cmd_appeal_food_safety(args)
            elif action == "check_food_safety":
                cmd_check_food_safety(args)
            elif action == "close":
                cmd_close(args)
            elif action == "ping":
                send({"type": "pong"})
            else:
                send({"type": "error", "message": f"未知命令: {action}"})
        except Exception as e:
            send({"type": "error", "message": f"处理异常: {e}\n{traceback.format_exc()}"})


if __name__ == "__main__":
    main()
