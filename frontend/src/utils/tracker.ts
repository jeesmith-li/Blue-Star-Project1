/**
 * 客户端审计日志：把甲方使用中的一切问题与操作上报给后端统一记录
 * - 所有接口失败/慢请求：在 request.ts 拦截器自动上报
 * - JS 运行时错误 / Promise 未捕获拒绝：window.onerror 自动上报
 * - 页面访问：router.afterEach 自动上报
 * - 上报本身失败时静默丢弃，绝不影响业务
 */

interface LogItem {
  type: string
  level: string
  action?: string
  result?: string
  detail?: string
  page?: string
  endpoint?: string
  durationMs?: number
  httpStatus?: number
}

const queue: LogItem[] = []
let timer: number | null = null
let sending = false

function screen(): string {
  try {
    return `${window.screen.width}x${window.screen.height}`
  } catch {
    return ''
  }
}

function page(): string {
  return location.hash ? location.hash.slice(1) : location.pathname
}

/** 加入上报队列（批量、防抖发送） */
export function track(item: LogItem) {
  try {
    queue.push({
      screen: screen(),
      page: page(),
      ts: new Date().toISOString().slice(0, 19).replace('T', ' '),
      ...item
    } as any)
    if (queue.length >= 20) {
      flush()
    } else if (timer === null) {
      timer = window.setTimeout(flush, 3000)
    }
  } catch {
    /* 静默 */
  }
}

/** 立即发送队列 */
export function flush() {
  if (timer !== null) {
    clearTimeout(timer)
    timer = null
  }
  if (sending || queue.length === 0) return
  const batch = queue.splice(0, queue.length)
  sending = true
  const token = localStorage.getItem('token')
  fetch('/api/client-logs', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(batch)
  })
    .then(() => { sending = false })
    .catch(() => { sending = false }) // 上报失败丢弃，避免雪球
}

/** 安装全局错误捕获，返回卸载函数 */
export function installErrorTracking() {
  window.addEventListener('error', (ev) => {
    track({
      type: 'js_error',
      level: 'ERROR',
      action: 'window_error',
      detail: `${ev.message} @ ${ev.filename}:${ev.lineno}:${ev.colno}`
    })
  })
  window.addEventListener('unhandledrejection', (ev) => {
    const reason = ev.reason
    let detail = 'Unhandled promise rejection'
    if (reason instanceof Error) detail = `${reason.message}\n${(reason.stack || '').slice(0, 500)}`
    else if (reason) detail = String(reason).slice(0, 500)
    track({
      type: 'js_error',
      level: 'ERROR',
      action: 'promise_rejection',
      detail
    })
  })
  // 页面关闭前把队列冲掉
  window.addEventListener('beforeunload', () => { flush() })
}
