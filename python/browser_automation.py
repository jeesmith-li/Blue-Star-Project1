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
_poi_capture_subscription = None

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


def _find_pager_point(page, expected, shot_path):
    """通过 OCR 定位底部分页器上指定期望页码的点击坐标(CSS 像素)。
    美团分页器数字等间距排列：用 OCR 认出的数字做线性拟合，推算目标页码的 x。
    找不到返回 None。"""
    try:
        page.screenshot(path=shot_path)
        from PIL import Image
        im = Image.open(shot_path)
        W, H = im.size
        y0 = int(H * 0.65)  # 只取底部 35%（分页器所在区域）
        crop = im.crop((0, y0, W, H))
        scale = 3
        crop = crop.resize((crop.width * scale, crop.height * scale), Image.LANCZOS)
        crop_path = shot_path + ".crop.png"
        crop.save(crop_path)
        res, _ = _get_ocr()(crop_path)
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


def _pager_point_for(page, target, shot_path):
    """返回(点击坐标, 方式)；方式 digit=直接点目标页码，arrow=目标页码不可见时点右侧箭头。"""
    try:
        page.screenshot(path=shot_path)
        from PIL import Image
        im = Image.open(shot_path)
        W, H = im.size
        # Flutter canvas page digits may be invisible to OCR. Identify the
        # active dark square and its adjacent light square in the footer.
        import cv2
        import numpy as np
        footer_y = max(0, H - 160)
        footer = np.array(im.convert("RGB"))[footer_y:]
        dark = cv2.inRange(footer, (25, 25, 25), (45, 45, 45))
        light = cv2.inRange(footer, (242, 242, 242), (244, 244, 244))
        def squares(mask):
            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            return [cv2.boundingRect(c) for c in contours
                    if 25 <= cv2.boundingRect(c)[2] <= 65
                    and 25 <= cv2.boundingRect(c)[3] <= 65
                    and abs(cv2.boundingRect(c)[2] - cv2.boundingRect(c)[3]) <= 5]
        for x, y, width, height in squares(dark):
            adjacent = sorted((r for r in squares(light)
                               if abs(r[1] - y) <= 4 and width + 2 <= r[0] - x <= width + 20),
                              key=lambda r: r[0])
            if adjacent:
                nx, ny, nw, nh = adjacent[0]
                # Derive screenshot-to-CSS scaling from viewport dimensions.
                viewport = page.evaluate("() => ({w: innerWidth, h: innerHeight})")
                sx, sy = W / viewport["w"], H / viewport["h"]
                return ((nx + nw / 2) / sx, (footer_y + ny + nh / 2) / sy), "adjacent"
        y0 = int(H * 0.65)
        crop = im.crop((0, y0, W, H))
        scale = 3
        crop = crop.resize((crop.width * scale, crop.height * scale), Image.LANCZOS)
        crop_path = shot_path + ".crop.png"
        crop.save(crop_path)
        res, _ = _get_ocr()(crop_path)
        pts = []
        if res:
            for it in res:
                box, text, conf = it
                t = (text or "").strip()
                if t.isdigit() and 1 <= int(t) <= 99:
                    xs = [p[0] for p in box]
                    ys = [p[1] for p in box]
                    pts.append((int(t), sum(xs) / len(xs), sum(ys) / len(ys)))
        # The active page has a dark background and is often missed by OCR.
        # A single visible target (e.g. page 2) is sufficient for a direct click.
        if not pts:
            log("分页器 OCR 未识别到数字")
            return None, None
        # Pagination is the bottom-most numeric row; review text above it can
        # contain dates, ratings and order counts which must not select the row.
        bottom = max(p[2] for p in pts)
        row = sorted(set(p for p in pts if abs(p[2] - bottom) < 24 * scale))
        hit = next((p for p in row if p[0] == target), None)
        try:
            dpr = page.evaluate("() => window.devicePixelRatio || 1")
        except Exception:
            dpr = 1
        if hit:
            return ((hit[1] / scale / dpr), ((y0 + hit[2] / scale) / dpr)), "digit"
        if len(row) < 2:
            return None, None
        # 目标页码未显示：按相邻数字间距外推箭头位置
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
        if len(best) < 2:
            best = row
        ds = [p[0] for p in best]
        xs = [p[1] for p in best]
        spacing = (xs[-1] - xs[0]) / (ds[-1] - ds[0]) if ds[-1] != ds[0] else 0.0
        if spacing <= 0:
            log("分页数字间距异常")
            return None, None
        cx = max(p[1] for p in row) + spacing
        cy = sum(p[2] for p in row) / len(row)
        if cx > crop.width * scale * 0.995:
            log("下一页箭头超出可视区域")
            return None, None
        return ((cx / scale / dpr), ((y0 + cy / scale) / dpr)), "arrow"
    except Exception as e:
        log(f"定位翻页坐标失败: {e}")
    return None, None


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
    global _poi_list_url, _poi_list_body, _poi_capture_subscription
    try:
        page = get_page()
        if not page:
            return
        if _poi_capture_subscription:
            previous_page, previous_handler = _poi_capture_subscription
            if previous_page is page:
                return
            try:
                previous_page.remove_listener("response", previous_handler)
            except Exception:
                pass
        def on_resp(resp):
            global _poi_list_url, _poi_list_body
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
        _poi_capture_subscription = (page, on_resp)
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


def _collect_shop_pages(page, read_rows):
    """Read all management pages; never return a silently truncated list."""
    all_rows = {}
    signatures = set()
    deadline = time.monotonic() + 15
    frame = None
    rows = []
    while time.monotonic() < deadline and not rows:
        for candidate in page.frames:
            try:
                rows = read_rows(candidate)
                if rows:
                    frame = candidate
                    break
            except Exception:
                continue
        if not rows:
            page.wait_for_timeout(200)
    if not rows:
        raise RuntimeError("未找到门店管理表")
    for index in range(100):
        signature = tuple(row["id"] for row in rows)
        if signature in signatures:
            raise RuntimeError("分页重复返回相同门店，已停止")
        signatures.add(signature)
        all_rows.update((row["id"], row) for row in rows)
        log(f"门店分页: 第{index + 1}页，{len(rows)}家，累计{len(all_rows)}家")
        next_button = frame.get_by_text("»", exact=True)
        if next_button.count() != 1:
            raise RuntimeError("无法唯一识别门店下一页按钮")
        disabled = next_button.evaluate("""el => !!el.closest('.disabled,[aria-disabled="true"]') || el.disabled === true""")
        if disabled:
            return list(all_rows.values())
        next_button.click(timeout=2000)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            page.wait_for_timeout(150)
            updated = read_rows(frame)
            if updated and tuple(row["id"] for row in updated) != signature:
                rows = updated
                break
        else:
            raise RuntimeError("点击下一页后门店数据未更新")
    raise RuntimeError("门店页数超过安全上限")


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

    if not check_login_status():
        send({"type": "error", "message": "请先登录美团"})
        return
    # Read the management table's ID and selected business-status option.
    # Dropdown status (e.g. preorder only) is not the management business status.
    # Open the platform's own all-stores entry first; route fallback is bounded.
    opened = False
    for frame in page.frames:
        try:
            entry = frame.get_by_text("全部门店", exact=True)
            if entry.count() and entry.first.is_visible():
                entry.first.click(timeout=1500)
            view_all = frame.get_by_text("查看全部门店", exact=False)
            if view_all.count() and view_all.first.is_visible():
                view_all.first.click(timeout=1500)
                opened = True
                break
        except Exception:
            continue
    if not opened:
        page.goto("https://e.waimai.meituan.com/#/v2/shop/manage",
                  wait_until="domcontentloaded", timeout=12000)
    def read_rows(frame):
        return frame.evaluate(r"""() => Array.from(
                document.querySelectorAll('tr[data-id]')
            ).flatMap(row => {
                const id = (row.getAttribute('data-id') || '').trim();
                const nameCell = row.querySelector('[data-name][data-id]');
                const selected = row.querySelector('select[data-id]');
                if (!nameCell || nameCell.dataset.id !== id || (selected && selected.dataset.id !== id)) return [];
                const name = (nameCell.dataset.name || '').trim();
                const status = (selected?.selectedOptions[0]?.textContent || '非营业中').trim();
                const open = selected?.value === '1' && status === '营业中';
                return /^\d+$/.test(id) && name ? [{id, poiId:id, name,
                    businessStatus: open ? '营业中' : status || '状态未知', source:'meituan'}] : [];
            })""")

    try:
        rows = _collect_shop_pages(page, read_rows)
    except Exception as exc:
        log(f"门店完整列表读取失败: {exc}")
        send({"type": "error", "message": f"门店列表读取不完整，未执行绑定：{exc}"})
        return
    shops = list({row["id"]: row for row in rows if row["businessStatus"] in ("营业中", "休息中")}.values())
    account = _extract_account_from_page(page) or ""
    log(f"门店管理读取 {len(rows)} 家，本次可绑定营业中/休息中 {len(shops)} 家")
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


def _dismiss_order_rule_notice(page):
    """Dismiss only the known informational notice, never arbitrary dialogs."""
    title = "订单自动完成规则说明"
    for frame in page.frames:
        try:
            titles = frame.get_by_text(title, exact=True)
            for index in range(titles.count()):
                heading = titles.nth(index)
                if not heading.is_visible():
                    continue
                # Scope the acknowledgement to the nearest enclosing modal.
                modal = heading.locator(
                    "xpath=ancestor::*[@role='dialog' or contains(@class,'roo-modal') "
                    "or contains(@class,'modal-content') or contains(@class,'dialog')][1]"
                )
                if modal.count() == 0:
                    log("规则提示存在，但无法确认弹窗边界，停止导航")
                    return False
                button = modal.get_by_text("我知道了", exact=True)
                if button.count() != 1 or not button.is_visible() or not button.is_enabled():
                    log("规则弹窗确认按钮不可用，停止导航")
                    return False
                button.click(timeout=1500)
                heading.wait_for(state="hidden", timeout=3000)
                log("已关闭「订单自动完成规则说明」，确认遮挡消失")
        except Exception as exc:
            log(f"规则弹窗处理失败: {type(exc).__name__}")
            return False
    return True


def _open_review_list_tab(page, max_tries=5):
    """进入「顾客评价」后必须点击顶部「外卖评价列表」标签，comment/list 才会触发。
    新开窗口默认停在「综合评分明细」标签。标签栏可能位于 iframe/Flutter 内：
    先遍历所有 frame 按文本点，失败再 OCR 定位点击。"""
    for _ in range(max_tries):
        if not _dismiss_order_rule_notice(page):
            return False
        clicked = False
        for fr in page.frames:
            try:
                loc = fr.get_by_text("外卖评价列表", exact=False)
                if loc.count() > 0 and loc.first.is_visible() and loc.first.is_enabled():
                    loc.first.click(timeout=1000)
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
        if max_tries > 1:
            page.wait_for_timeout(250)
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


def _prepare_review_capture(page, captured, shop_name):
    """Bounded navigation: reuse current view, direct route, one menu fallback."""
    def wait_first(timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for body in captured:
                data = json.loads(body).get("data", {})
                if data.get("pageNum", 1) in (0, 1):
                    return True
            page.wait_for_timeout(100)
        return False

    def verified():
        if not shop_name:
            return True
        ok, actual, source = _verify_shop_after_switch(page, shop_name, attempts=1)
        log(f"导航后店铺校验: {ok} ({source})")
        return ok

    if "userComment" in page.url:
        log("导航阶段: 复用当前评价页")
        _open_review_list_tab(page, max_tries=1)
        if wait_first(3) and verified():
            return True
    log("导航阶段: 直达评价路由")
    try:
        page.goto(REVIEW_PAGE_URL, wait_until="domcontentloaded", timeout=12000)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if _open_review_list_tab(page, max_tries=1) and wait_first(2):
                return verified()
    except Exception as exc:
        log(f"直达评价页未就绪: {type(exc).__name__}")
    # Same hash navigation does not reload the embedded application. If no
    # fresh list response arrived, perform one real reload with listeners live.
    log("导航阶段: 无新列表响应，执行一次受控刷新")
    try:
        page.reload(wait_until="domcontentloaded", timeout=15000)
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            if _open_review_list_tab(page, max_tries=1) and wait_first(2):
                return verified()
            page.wait_for_timeout(150)
    except Exception as exc:
        log(f"受控刷新未就绪: {type(exc).__name__}")
    log("导航阶段: 执行一次菜单回退")
    if not _dismiss_order_rule_notice(page):
        return False
    if not _click_text_deep_retry(page, "顾客管理", tries=1):
        return False
    if not _click_text_deep_retry(page, "顾客评价", tries=1):
        if not _click_text_deep_retry(page, "评价管理", tries=1):
            return False
    return _open_review_list_tab(page, max_tries=1) and wait_first(8) and verified()


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

    captured = []
    captured_urls = []
    list_url = None  # 评价列表接口的完整 URL（含签名 token）

    from urllib.parse import urlparse, parse_qs
    expected_poi = str(args.get("poiId") or "")
    accepting = False
    def _on_resp(resp):
        nonlocal list_url, expected_poi
        if not accepting:
            return
        try:
            if "comment/list" not in resp.url.lower() or resp.status != 200:
                return
            query = parse_qs(urlparse(resp.url).query)
            poi = (query.get("wmPoiId") or [""])[0]
            if not poi or (expected_poi and poi != expected_poi):
                return
            actual = _extract_top_shop_name_from_dom(page, require_anchored=True)
            if shop_name and (not actual or not _shop_names_match(actual, shop_name)):
                return
            body = resp.text()
            payload = json.loads(body)
            if not isinstance(payload, dict) or not isinstance(payload.get("data"), dict):
                return
            if not expected_poi:
                expected_poi = poi
            captured.append(body)
            captured_urls.append(resp.url)
            list_url = list_url or resp.url
        except Exception as exc:
            log(f"评价响应校验未通过: {type(exc).__name__}")

    page.on("response", _on_resp)
    capture_started = time.monotonic()
    try:
        accepting = True
        started = time.monotonic()
        try:
            ready = _prepare_review_capture(page, captured, shop_name)
        except Exception as exc:
            ready = False
            log(f"评价导航失败: {type(exc).__name__}: {exc}")
        if not ready:
            send({"type": "error", "message": "评价列表未就绪或未收到目标店铺首页响应，已停止抓取"})
            return
        log(f"评价导航就绪，耗时 {time.monotonic() - started:.2f}s")

        try:
            _dbg_dir2 = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug", "review_dump")
            os.makedirs(_dbg_dir2, exist_ok=True)
            page.screenshot(path=os.path.join(_dbg_dir2, "reviews_page.png"), full_page=True)
            log(f"评价页当前 URL: {page.url}")
            # 探测页码文本是否可被定位
            for _t in ("2", "下一页", "3"):
                try:
                    _n = page.get_by_text(_t, exact=True).count()
                    log(f"  定位文本 '{_t}' 命中数={_n}")
                except Exception as _e:
                    log(f"  定位文本 '{_t}' 异常: {_e}")
        except Exception as _e:
            log(f"截图调试失败: {_e}")

        # 美团评价页是 Flutter Web，文本画在 canvas 上，Playwright 定位不到页码。
        # 改用 OCR：截图 → 找到底部分页器上"下一页"数字的坐标 → 点击。
        # 用户实测规律：点完 2 后 3 会补到 2 的位置，故逐页 OCR 定位点击即可翻完全部页。
        shot_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug", "_ocr_shot.png")
        # 先滚到底，确保分页器在视口内
        try:
            page.keyboard.press("End")
        except Exception:
            pass
        page.wait_for_timeout(300)

        # 从第一页响应解析总条数 → 总页数
        total_pages = 0
        if captured:
            try:
                d0 = json.loads(captured[0])
                t = d0.get("data") if isinstance(d0, dict) else None
                if isinstance(t, dict):
                    total = t.get("total") or 0
                    ps = t.get("pageSize") or 10
                    if total and ps:
                        total_pages = (int(total) + int(ps) - 1) // int(ps)
            except Exception:
                pass
        log(f"评价总页数估算: {total_pages}")

        # 分页器会同时显示 1..12，固定坐标连点会重复命中旧页码，改为每页重新定位目标页码。
        def _req_page_no(u):
            try:
                from urllib.parse import urlparse, parse_qs
                q = parse_qs(urlparse(u).query)
                v = q.get("pageNum")
                if v:
                    return int(v[0])
            except Exception:
                pass
            return None

        def _body_page_no(body):
            try:
                d = json.loads(body)
                data = d.get("data") if isinstance(d, dict) else None
                if isinstance(data, dict):
                    pn = data.get("pageNum")
                    ps = data.get("pageSize") or 0
                    # 响应里的 pageNum 常是 offset（如 15 页时返回 140）
                    if isinstance(pn, int) and isinstance(ps, int) and ps > 0 and pn >= ps:
                        return pn // ps + 1
                    if isinstance(pn, int) and 0 < pn <= 999:
                        return pn
            except Exception:
                pass
            return None

        pages_seen = set()

        def _refresh_pages():
            for i, u in enumerate(captured_urls):
                p = _req_page_no(u)
                if p is None and i < len(captured):
                    p = _body_page_no(captured[i])
                if p:
                    if total_pages and p > total_pages:
                        continue
                    pages_seen.add(p)

        _refresh_pages()
        log(f"初始已捕获页码: {sorted(pages_seen)}")

        stalls = 0
        max_loops = (total_pages + 3) if total_pages else 30
        for step in range(max_loops):
            _refresh_pages()
            cur = max(pages_seen) if pages_seen else 1
            missing_pages = sorted(set(range(1, total_pages + 1)) - pages_seen) if total_pages else []
            if total_pages and not missing_pages:
                log(f"已逐页核对全部 {total_pages} 页，停止翻页")
                break
            target = missing_pages[0] if missing_pages else cur + 1
            before_pages = set(pages_seen)
            before_cnt = len(captured)
            pt, mode = _pager_point_for(page, target, shot_path)
            if not pt:
                log(f"未能定位第 {target} 页的点击位置，结束翻页")
                break
            label = f"页码 {target}" if mode == "digit" else f"下一页箭头(第{target}页不可见)"
            log(f"点击{label} 坐标=({pt[0]:.0f},{pt[1]:.0f})，当前已捕获页码={sorted(pages_seen)}")
            try:
                page.mouse.click(pt[0], pt[1])
            except Exception as e:
                log(f"点击失败: {e}")
                break
            waited = 0
            while waited < 12 and len(captured) == before_cnt:
                page.wait_for_timeout(500)
                waited += 1
            _refresh_pages()
            if len(captured) == before_cnt or not (pages_seen - before_pages):
                stalls += 1
                log(f"本次点击未出现新页码(连续 {stalls} 次)，已捕获={sorted(pages_seen)}")
                if stalls >= 2:
                    log("连续两次翻页无新数据，停止")
                    break
            else:
                stalls = 0
                log(f"翻页成功，已捕获页码={sorted(pages_seen)}，响应数={len(captured)}")

    finally:
        accepting = False
        page.remove_listener("response", _on_resp)
        log(f"抓取资源已释放，耗时={time.monotonic() - capture_started:.2f}s，响应数={len(captured)}")

    missing_pages = sorted(set(range(1, total_pages + 1)) - pages_seen) if total_pages else []
    if missing_pages:
        log(f"抓取不完整: 缺少页码={missing_pages}")
        send({"type": "error", "message": f"评价未抓全：预计{total_pages}页，缺少{missing_pages}。未将本次部分数据标记为成功，请检查浏览器和日志后重试"})
        return
    reviews = []
    seen = set()
    import json as _json
    # 调试目录
    try:
        _dbg_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "debug", "review_dump")
        os.makedirs(_dbg_dir, exist_ok=True)
    except Exception:
        _dbg_dir = None
    best_idx = -1
    best_len = 0
    total_seen = 0
    for idx, body in enumerate(captured):
        n_here = 0
        try:
            data = _json.loads(body)
        except Exception:
            data = None
        if data is not None:
            rs, tot = _extract_reviews_from_json(data)
            total_seen += tot
            for r in rs:
                n_here += 1
                key = r["platformReviewId"] or (r["reviewer"], r["content"])
                if key in seen:
                    continue
                seen.add(key)
                reviews.append(r)
        url = captured_urls[idx] if idx < len(captured_urls) else ""
        log(f"  响应[{idx}] 解析评价={n_here} 长度={len(body)} URL={url[:110]}")
        if len(body) > best_len:
            best_len = len(body)
            best_idx = idx
    # 保存最大的那条评价列表响应样本，供核对字段结构
    if _dbg_dir and best_idx >= 0:
        try:
            with open(os.path.join(_dbg_dir, "list_sample.json"), "w", encoding="utf-8") as f:
                f.write(captured_urls[best_idx] + "\n" + captured[best_idx])
        except Exception:
            pass

    saved = len(reviews)
    skipped = max(0, total_seen - saved)
    log(f"评价抓取完成：店铺={shop_name or '(当前)'}，接口响应={len(captured)} 条，"
        f"总评价={total_seen}，有文字已记录={saved}，无文字未记录={skipped}")
    send({"type": "reviews_fetched", "reviews": reviews, "count": saved,
          "total": total_seen, "skipped": skipped, "shopName": shop_name})


def _wait_appeal_type(page, timeout=4):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for frame in page.frames:
            try:
                heading = frame.get_by_text("选择申诉类型", exact=False)
                other = frame.get_by_text("其他", exact=True)
                if heading.count() and heading.first.is_visible() and other.count() and other.first.is_visible():
                    return True
            except Exception:
                continue
        page.wait_for_timeout(200)
    boxes = _ocr_pick(page, ["选择申诉类型", "其他", "下一步"])
    texts = [text.strip() for text, _, _ in boxes]
    return any("选择申诉类型" in text for text in texts) and "其他" in texts


def _click_target_appeal(page, content):
    """Only open an appeal associated with a uniquely matched review."""
    key = re.sub(r"\s+", "", content or "")[:12]
    if len(key) < 6:
        log("评价内容不足以唯一定位申诉入口")
        return False
    for frame in page.frames:
        try:
            anchor = frame.get_by_text(content, exact=True)
            if anchor.count() == 1:
                card = anchor.locator("xpath=ancestor::*[.//*[normalize-space(text())='申诉']][1]")
                button = card.get_by_text("申诉", exact=True)
                if button.count() == 1 and button.is_visible():
                    rect = button.bounding_box()
                    if rect:
                        page.mouse.click(rect["x"] + rect["width"] / 2, rect["y"] + rect["height"] / 2)
                        if _wait_appeal_type(page):
                            return True
                        log("DOM申诉点击未进入类型页，转为图像定位")
        except Exception:
            continue
    # Canvas fallback: match review content, then the first individual appeal
    # below it, before the next review's date. Never click a global first match.
    for attempt in range(8):
        log(f"申诉定位: 第{attempt + 1}轮，开始识别目标评价")
        boxes = _ocr_zone(page, f"appeal_loc_{attempt}", x_frac0=0.2, scale=2)
        anchors = [(x, y) for text, x, y in boxes
                   if key in re.sub(r"\s+", "", text)]
        if len(anchors) == 1:
            ax, ay = anchors[0]
            next_dates = [y for text, x, y in boxes
                          if re.search(r"20\d{2}[-/.]\d{1,2}[-/.]\d{1,2}", text) and y > ay]
            boundary = min(next_dates) if next_dates else float('inf')
            buttons = [(x, y) for text, x, y in boxes if text.strip() == "申诉"
                       and ay < y < boundary]
            if len(buttons) == 1:
                page.mouse.click(*buttons[0])
                if _wait_appeal_type(page):
                    log("目标申诉入口已打开，类型选择页已确认")
                    return True
                log("申诉按钮点击后页面未切换，停止以避免误操作")
                return False
        elif len(anchors) > 1:
            log("评价内容匹配不唯一，停止申诉")
            return False
        # CDP attaches to a real window; viewport_size can be None. Never use
        # a guessed 1200px viewport: it places the wheel outside the review pane.
        viewport = page.evaluate("() => ({width: innerWidth, height: innerHeight})")
        if not viewport or viewport["width"] <= 0 or viewport["height"] <= 0:
            log("无法读取真实窗口尺寸，停止定位")
            return False
        x, y = viewport["width"] * 0.6, viewport["height"] * 0.75
        log(f"申诉定位: 匹配数={len(anchors)}，窗口={viewport}，在评价区域滚动")
        page.mouse.move(x, y)
        page.mouse.wheel(0, viewport["height"] * 0.45)
        page.wait_for_timeout(400)
    return False


def _appeal_click_exact(page, text):
    for frame in page.frames:
        try:
            loc = frame.get_by_text(text, exact=True)
            visible = [loc.nth(i) for i in range(loc.count()) if loc.nth(i).is_visible()]
            if len(visible) == 1 and visible[0].is_enabled():
                rect = visible[0].bounding_box()
                if rect:
                    page.mouse.click(rect["x"] + rect["width"] / 2, rect["y"] + rect["height"] / 2)
                    log(f"申诉步骤点击: {text} (DOM矩形)")
                    return True
        except Exception:
            continue
    matches = [item for item in _ocr_pick(page, [text]) if item[0].strip() == text]
    if len(matches) == 1:
        _, x, y = matches[0]
        page.mouse.click(x, y)
        return True
    if text in ("下一步", "提交"):
        boxes = _ocr_zone(page, "appeal_action", x_frac0=0.25, y_frac0=0.6, scale=3)
        matches = [(x, y) for label, x, y in boxes if label.replace(" ", "") == text]
        if len(matches) == 1:
            page.mouse.click(*matches[0])
            log(f"申诉步骤点击: {text} (底部放大识别)")
            return True
    log(f"申诉步骤定位失败: {text}")
    return False


def _fill_appeal_description(page, description, timeout=8):
    # Flutter creates its text input host only after the canvas field is focused.
    activated = False
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for frame in page.frames:
            try:
                loc = frame.locator('textarea')
                visible = [loc.nth(i) for i in range(loc.count()) if loc.nth(i).is_visible()]
                if len(visible) == 1:
                    visible[0].fill(description, timeout=1500)
                    if visible[0].input_value() == description:
                        log("申诉问题描述已填入并逐字核对")
                        return True
            except Exception:
                continue
        if not activated:
            activated = True
            boxes = _ocr_pick(page, ["请描述问题详情"])
            matches = [(x, y) for label, x, y in boxes
                       if label.replace(" ", "") == "请描述问题详情"]
            if len(matches) == 1:
                page.mouse.click(*matches[0])
                log("申诉问题描述输入区已激活(OCR)")
                deadline = time.monotonic() + timeout
        page.wait_for_timeout(150)
    return False


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

        captured = []
        def capture_list(resp):
            if "comment/list" in resp.url and resp.status == 200:
                try:
                    captured.append(resp.text())
                except Exception:
                    pass
        page.on("response", capture_list)
        try:
            if not _prepare_review_capture(page, captured, shop_name):
                send({"type": "error", "message": "目标店铺评价列表未就绪，未执行申诉"})
                return
            if not _reply_rating(page, 1, captured):
                send({"type": "error", "message": "未确认差评筛选后的新列表，已停止申诉"})
                return
            rows, _ = _extract_reviews_from_json(json.loads(captured[-1]))
            if any(r.get("rating") not in (1, 2) for r in rows):
                send({"type": "error", "message": "筛选结果包含非差评，已停止申诉"})
                return
            log("申诉导航: 已切换差评筛选并收到新列表")
        finally:
            page.remove_listener("response", capture_list)
        if not _click_target_appeal(page, content):
            send({"type": "error", "message": "未能唯一定位目标评价的申诉入口，未点击其他评价"})
            return
        page.wait_for_timeout(500)

        # Each stage must succeed before the next one is attempted.
        if not _appeal_click_exact(page, "其他"):
            send({"type": "error", "message": "未能选择申诉类型「其他」，已停止"})
            return
        if not _appeal_click_exact(page, "下一步"):
            send({"type": "error", "message": "未找到可用的下一步按钮，已停止"})
            return
        desc = appeal_content.strip()
        if not 10 <= len(desc) <= 300:
            send({"type": "error", "message": "申诉理由须为10至300字，未提交"})
            return
        # The detail page shows the selected type and a problem-description field.
        deadline = time.monotonic() + 8
        type_confirmed = False
        while time.monotonic() < deadline:
            for frame in page.frames:
                try:
                    if (frame.get_by_text("其他", exact=True).first.is_visible()
                            and frame.get_by_text("问题描述", exact=False).first.is_visible()):
                        type_confirmed = True
                        break
                except Exception:
                    continue
            if type_confirmed:
                break
            page.wait_for_timeout(150)
        if not type_confirmed:
            boxes = _ocr_pick(page, ["申诉类型", "其他", "问题描述", "请描述问题详情"])
            labels = [label.replace(" ", "") for label, _, _ in boxes]
            type_confirmed = ("其他" in labels and "申诉类型" in labels
                              and any("问题描述" in label for label in labels))
        log(f"申诉详情页校验: {type_confirmed}")
        if not type_confirmed or not _fill_appeal_description(page, desc):
            send({"type": "error", "message": "未确认进入「其他」申诉详情页或问题描述未正确填入，未提交"})
            return
        img_paths = [path.strip() for path in images.split(",") if path.strip()]
        uploaded = 0
        if img_paths:
            if not all(os.path.isfile(path) for path in img_paths):
                send({"type": "error", "message": "申诉图片不存在，未提交"})
                return
            for frame in page.frames:
                inputs = frame.locator('input[type=file]')
                if inputs.count() == 1:
                    inputs.set_input_files(img_paths)
                    uploaded = len(img_paths)
                    break
            if uploaded != len(img_paths):
                send({"type": "error", "message": "未找到申诉图片上传控件，未提交"})
                return
        if not _appeal_click_exact(page, "提交"):
            send({"type": "error", "message": "未找到可用的提交按钮，未提交"})
            return
        # A click is not evidence of platform acceptance. Never auto-submit twice.
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            for frame in page.frames:
                try:
                    for label in ("申诉提交成功", "申诉成功", "提交成功"):
                        loc = frame.get_by_text(label, exact=True)
                        if loc.count() == 1 and loc.is_visible():
                            send({"type": "appeal_done", "message": "平台确认申诉提交成功（类型：其他）",
                                  "reviewer": reviewer, "imagesUploaded": uploaded})
                            return
                except Exception:
                    continue
            page.wait_for_timeout(200)
        send({"type": "error", "message": "已点击提交但未确认平台结果，请先检查申诉记录，勿重复提交"})

    except Exception as e:
        log(f"申诉执行异常: {e}\n{traceback.format_exc()}")
        send({"type": "error", "message": f"申诉执行异常: {e}"})


def _ocr_page_text(page, shot_path):
    """截图并 OCR 返回整页文本（美团页面为 Flutter canvas，DOM 无文本时兜底）"""
    try:
        page.screenshot(path=shot_path, full_page=False)
        result, _ = _get_ocr()(shot_path)
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
    page.screenshot(path=shot_path)
    res, _ = _get_ocr()(shot_path)
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
        os.makedirs(_REPLY_DBG_DIR, exist_ok=True)
    except Exception:
        pass
    shot = os.path.join(_REPLY_DBG_DIR, f"zone_{tag}.png")
    try:
        page.screenshot(path=shot)
        from PIL import Image
        im = Image.open(shot)
        W, H = im.size
        x0, y0 = int(W * x_frac0), int(H * y_frac0)
        crop = im.crop((x0, y0, W, H))
        crop = crop.resize((crop.width * scale, crop.height * scale), Image.LANCZOS)
        cp = os.path.join(_REPLY_DBG_DIR, f"zone_{tag}_crop.png")
        crop.save(cp)
        res, _ = _get_ocr()(cp)
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


def _click_reply_button_for(page, review, k, page_reviews=None):
    """定位并点击目标评价的「回复」按钮，并确认编辑器展开。
    评价卡片由 Flutter canvas 绘制（DOM 里只有绘制产物、无事件处理器，元素点击无效），
    唯一可靠路径：OCR 放大定位按钮坐标 → CDP 可信鼠标点击（带抖动重试，OCR 确认编辑器出现）。
    定位优先按评价内容文本锚定（内容下方最近的「回复」按钮即同卡片按钮）；
    根据同页文字间距小步滚动，文字不唯一时不按按钮序号猜测。
    返回 (是否已点开编辑器, None)"""
    key = re.sub(r"\s+", "", review.get("content") or "")[:16]
    # 0) 清理可能残留的旧编辑器，避免把旧编辑器误判为本目标已点开
    try:
        st = _editor_open_pos(page, "stale")
        if st:
            page.mouse.click(st["cancel"][0], st["cancel"][1])
            page.wait_for_timeout(800)
            log("已关闭残留编辑器")
    except Exception:
        pass
    btn = None
    for attempt in range(24):
        boxes = _ocr_zone(page, f"loc{attempt}", x_frac0=0.2, scale=2)
        min_x = _list_btn_min_x(page)
        anchors = [(x, y) for t, x, y in boxes
                   if len(key) >= 6 and key in re.sub(r"\s+", "", t)]
        anchor_y = anchors[0][1] if len(anchors) == 1 else None
        known_y = []
        for row in page_reviews or [review]:
            fragment = re.sub(r"\s+", "", row.get("content") or "")[:16]
            hits = [y for t, x, y in boxes if len(fragment) >= 6
                    and fragment in re.sub(r"\s+", "", t)]
            if len(hits) == 1:
                known_y.append(hits[0])
        known_y = sorted(set(known_y))
        gaps = [b - a for a, b in zip(known_y, known_y[1:]) if b - a > 40]
        if anchor_y is not None:
            boundaries = [y for y in known_y if y > anchor_y]
            boundaries += [y for t, x, y in boxes
                           if re.fullmatch(r"\d{4}-\d{2}-\d{2}", t.strip()) and y > anchor_y]
            bottom = min(boundaries) if boundaries else float("inf")
            candidates = [(x, y) for t, x, y in boxes
                          if t == "回复" and x > min_x and anchor_y < y < bottom]
            if len(candidates) == 1:
                btn = candidates[0]
                log(f"目标文字与卡片边界匹配，定位回复按钮: {review.get('platformReviewId')}")
                break
        viewport = page.evaluate("() => ({width:innerWidth,height:innerHeight})")
        step = min(220, viewport["height"] * 0.18, min(gaps) * 0.4 if gaps else 180)
        if anchor_y is not None:
            step = min(step, 80, max(20, anchor_y * 0.3))
        log(f"回复定位第{attempt + 1}轮: 目标文字={anchor_y is not None}, 间距={gaps}, 滚动={step:.0f}")
        page.mouse.move(viewport["width"] * 0.6, viewport["height"] * 0.8)
        page.mouse.wheel(0, step)
        page.wait_for_timeout(300)
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
            if _editor_open_pos(page, "opened") or _flutter_editor_rect(page):
                log(f"已点开「回复」编辑器(k={k} 点击=({bx + dx},{by + dy}))")
                return True, None
        page.keyboard.press("Escape")
        page.wait_for_timeout(400)
    log(f"「回复」按钮抖动点击均未展开编辑器(k={k} 坐标=({bx},{by}))")
    return False, None


def _read_reply_editor_values(page):
    """Read current visible Flutter editors, including open shadow roots."""
    values = []
    for frame in page.frames:
        try:
            values.extend(frame.evaluate("""() => {
                const values = [];
                function walk(root) {
                    for (const el of root.querySelectorAll('*')) {
                        if (el.matches('textarea.flt-text-editing')) {
                            const r = el.getBoundingClientRect();
                            if (r.width > 50 && r.height > 15 && getComputedStyle(el).visibility !== 'hidden') {
                                values.push(el.value);
                            }
                        }
                        if (el.shadowRoot) walk(el.shadowRoot);
                    }
                }
                walk(document);
                return values;
            }"""))
        except Exception:
            continue
    return values


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
        # Read fresh elements each time: Flutter may replace the textarea.
        deadline = time.monotonic() + 1.2
        dom_values = []
        while time.monotonic() < deadline:
            dom_values = _read_reply_editor_values(page)
            if len(dom_values) == 1 and dom_values[0].replace("\r\n", "\n") == text.replace("\r\n", "\n"):
                ok_typed = True
                break
            page.wait_for_timeout(100)
        if ok_typed:
            log("回复文字 DOM 精确校验通过，跳过输入校验 OCR")
            break
        if dom_values:
            # A readable mismatch must never be overridden by an OCR counter.
            log(f"回复文字 DOM 不匹配或编辑器不唯一，重新输入(rnd={rnd})")
            continue
        # 3) 键入校验：计数器 N/300（N>0）且编辑区无 '???' 乱码，失败重试
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
        try:
            page.screenshot(path=os.path.join(_REPLY_DBG_DIR, "typing_fail.png"))
        except Exception:
            pass
        return False, "回复内容校验失败，未提交"
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


def _reply_search(page, keyword, responses):
    """Change the actual search field and require a newly received list."""
    before = len(responses)
    for frame in page.frames:
        try:
            field = frame.get_by_placeholder("关键词搜索", exact=True)
            if field.count() == 1 and field.is_visible():
                field.fill(keyword, timeout=1500)
                field.press("Enter", timeout=1500)
                break
        except Exception:
            continue
    else:
        # Canvas field must first be focused; never type into an unknown editor.
        boxes = _ocr_pick(page, ["关键词搜索"])
        matches = [(x, y) for t, x, y in boxes if t.strip() == "关键词搜索"]
        if len(matches) != 1:
            return False
        page.mouse.click(*matches[0])
        for frame in page.frames:
            field = frame.locator("input:focus, textarea:focus")
            if field.count() == 1:
                field.fill(keyword, timeout=1500)
                field.press("Enter", timeout=1500)
                break
        else:
            return False
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if len(responses) > before:
            return True
        page.wait_for_timeout(100)
    return False


def _reply_rating(page, rating, responses):
    label = "差评" if rating in (1, 2) else "中评" if rating == 3 else "好评" if rating in (4, 5) else None
    if label is None:
        return False
    before = len(responses)
    clicked = False
    for frame in page.frames:
        try:
            loc = frame.get_by_text(re.compile("^" + label + r"[（(]"))
            if loc.count() == 1 and loc.is_visible():
                loc.click(timeout=1500)
                clicked = True
                break
        except Exception:
            continue
    if not clicked:
        matches = [(x, y) for t, x, y in _ocr_pick(page, [label])
                   if re.match("^" + label + r"[（(]", t)]
        if len(matches) != 1:
            return False
        page.mouse.click(*matches[0])
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if len(responses) > before:
            return True
        page.wait_for_timeout(100)
    return False


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

    started = time.monotonic()
    if shop_name:
        ok, actual, source = _switch_and_verify(page, shop_name)
        if not ok:
            _send_shop_not_found(shop_name, actual, source)
            return

    latest = {}
    page_rows = []      # 最近一次 comment/list 的有序行（当前页）
    navigation_responses = []
    reply_events = []   # 页面自发回复请求的结果 {toCommentId, ok, message}

    def _on_resp(resp):
        try:
            u = resp.url.lower()
            if "comment/list" not in u and "comment/reply" not in u:
                return
            if shop_name:
                actual = _extract_top_shop_name_from_dom(page, require_anchored=True)
                if not actual or not _shop_names_match(actual, shop_name):
                    return
            if "comment/list" in u:
                try:
                    b = resp.text()
                    # 注意：只拦 HTTP 403 或「403 Forbidden」字样，不能匹配裸「403」
                    # （正常响应体里可能恰好包含 403 子串，此前误杀过有效响应）
                    if resp.status == 200 and b and len(b) > 50 and "403 Forbidden" not in b:
                        rows, _t = _extract_reviews_from_json(json.loads(b))
                        navigation_responses.append(b)
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
        if not _prepare_review_capture(page, navigation_responses, shop_name):
            send({"type": "error", "message": "未收到目标店铺评价首页，已停止回复"})
            return
        log(f"回复导航就绪，耗时={time.monotonic() - started:.2f}s")

        # Keep the existing ID-based reply and submission confirmation for every target.
        for item in sorted(items, key=lambda it: int(it.get("rating") or 0)):
            prid = str(item.get("platformReviewId") or "")
            if prid not in text_map or prid in done_ids:
                continue
            rating = int(item.get("rating") or 0)
            fragments = re.findall(r"[\w\u4e00-\u9fff]{4,}", item.get("content") or "")
            keyword = max(fragments, key=len)[:12] if fragments else ""
            _reply_rating(page, rating, navigation_responses)
            found = None
            if len(keyword) >= 4 and _reply_search(page, keyword, navigation_responses):
                found = next((r for r in page_rows if r["platformReviewId"] == prid), None)
            if found is None:
                # Clearing must succeed before using any fallback page data.
                if not _reply_search(page, "", navigation_responses):
                    results.append({"platformReviewId": prid, "success": False,
                                    "message": "搜索条件未能清除或未收到新列表，已停止该条回复"})
                    continue
                for page_no in range(1, 101):
                    found = next((r for r in page_rows if r["platformReviewId"] == prid), None)
                    if found is not None:
                        break
                    before = len(navigation_responses)
                    if not _rpa_goto_page(page, page_no + 1):
                        break
                    deadline = time.monotonic() + 8
                    while len(navigation_responses) == before and time.monotonic() < deadline:
                        page.wait_for_timeout(100)
                    if len(navigation_responses) == before:
                        break
            if found is None or found.get("replyStatus") == "replied":
                continue
            opened, card_eh = _click_reply_button_for(page, found, 0, list(page_rows))
            if not opened:
                results.append({"platformReviewId": prid, "success": False, "message": "未定位目标回复按钮"})
                continue
            ok, msg = _rpa_reply_one(page, reply_events, text_map[prid], card_eh=card_eh, expect_pid=prid)
            results.append({"platformReviewId": prid, "success": ok, "message": msg})
            done_ids.add(prid)  # Never resubmit an uncertain result within the batch.
            page.wait_for_timeout(1500)
    finally:
        try:
            page.remove_listener("response", _on_resp)
        except Exception:
            pass

    log(f"回复流程结束，总耗时={time.monotonic() - started:.2f}s")
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


def _fs_detail_point(boxes):
    """Match the food-safety row only; never choose a global first detail link."""
    anchors = [(x, y) for text, x, y in boxes
               if "食品安全负反馈" in text.replace(" ", "")]
    if len(anchors) != 1:
        return None
    ax, ay = anchors[0]
    links = [(x, y) for text, x, y in boxes
             if text.replace(" ", "") == "查看明细"
             and x > ax and abs(y - ay) < 35]
    return links[0] if len(links) == 1 else None


def _fs_open_detail_page(page, poi_id):
    """Use the platform's food-safety row link instead of guessing its route."""
    if "userComment" not in page.url:
        page.goto(REVIEW_PAGE_URL, wait_until="domcontentloaded", timeout=30000)
    # Finish tab navigation before spending the separate detail-lookup budget.
    tab_clicked = False
    for attempt in range(3):
        _dismiss_order_rule_notice(page)
        for frame in page.frames:
            try:
                tab = frame.get_by_text("综合评分明细", exact=True)
                if tab.count() == 1 and tab.is_visible():
                    tab.scroll_into_view_if_needed(timeout=1500)
                    rect = tab.bounding_box()
                    if rect:
                        page.mouse.click(rect["x"] + rect["width"] / 2,
                                         rect["y"] + rect["height"] / 2)
                        tab_clicked = True
                        break
            except Exception:
                continue
        if not tab_clicked:
            tabs = [(x, y) for text, x, y in _ocr_pick(page, ["综合评分明细"])
                    if text.replace(" ", "") == "综合评分明细"]
            if len(tabs) == 1:
                page.mouse.click(*tabs[0])
                tab_clicked = True
        if tab_clicked:
            log("食安入口: 已点击综合评分明细，开始查找食品安全指标")
            break
        page.wait_for_timeout(300)
    if not tab_clicked:
        log("食安入口: 未找到综合评分明细标签")
        return False
    for attempt in range(3):
        _dismiss_order_rule_notice(page)
        for frame in page.frames:
            try:
                rows = frame.locator("tr").filter(has_text="食品安全负反馈")
                if rows.count() == 1:
                    link = rows.get_by_text("查看明细", exact=True)
                    if link.count() == 1 and link.is_visible():
                        link.click(timeout=1500)
                        log("食安入口: 已点击食品安全负反馈率所在行的查看明细(DOM)")
                        return True
            except Exception:
                continue
        boxes = _ocr_pick(page, ["食品安全负反馈", "查看明细", "综合评分明细"])
        point = _fs_detail_point(boxes)
        if point:
            page.mouse.click(*point)
            log("食安入口: 已点击食品安全负反馈率所在行的查看明细(OCR)")
            return True
        page.wait_for_timeout(300)
    log("食安入口: 未能唯一定位食品安全负反馈率的查看明细")
    return False


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
    # Capture the ID during real navigation; it is not needed to click the entry.
    poi_cap = []
    poi_listener = _fs_probe_poi_id(page, poi_cap)
    try:
        opened = _fs_open_detail_page(page, "")
    except Exception:
        page.remove_listener("response", _on_resp)
        raise
    finally:
        page.remove_listener("request", poi_listener)
    poi_id = poi_cap[-1] if poi_cap else ""
    if not opened:
        page.remove_listener("response", _on_resp)
        send({"type": "error", "message": "未找到食品安全负反馈率所在行的查看明细，已停止"})
        return
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
    reason = str(args.get("reason") or "").strip()
    if not 20 <= len(reason.encode("utf-16-le")) // 2 <= 300:
        send({"type": "food_safety_appeal_result", "success": False,
              "message": "食安申诉理由须为20至300字，请修改理由或模板后重试"})
        return

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
