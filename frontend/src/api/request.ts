import axios, { AxiosInstance, AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { track } from '../utils/tracker'

const instance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// Track backend connection state
let backendOffline = false
let lastErrorTime = 0

// URL → 用户动作名（写操作成功也记录，甲方的每个动作都可追溯）
const ACTION_MAP: Array<[string, string]> = [
  ['/auth/login', 'login'],
  ['/auth/register', 'register'],
  ['/shops/bind', 'bind_shop'],
  ['/shops/automation/start', 'start_automation'],
  ['/shops/automation/stop', 'stop_automation'],
  ['/shops/appeal-settings', 'appeal_settings_save'],
  ['/shops', 'shop_change'],
  ['/reviews/fetch-all', 'fetch_all_reviews'],
  ['/reviews/fetch', 'fetch_reviews'],
  ['/reviews/reply', 'reply_review'],
  ['/reviews/mark-replied', 'mark_replied'],
  ['/appeals/scan', 'appeal_scan'],
  ['/appeals/auto-run', 'appeal_auto_run'],
  ['/appeals/check-results', 'appeal_check_results'],
  ['/appeals', 'appeal_op'],
  ['/negative-feedbacks/sync', 'negative_sync'],
  ['/negative-feedbacks', 'negative_op'],
  ['/upload', 'upload_image'],
  ['/templates', 'template_change'],
  ['/users', 'user_change']
]

function matchAction(url = '', method = ''): string | undefined {
  for (const [prefix, name] of ACTION_MAP) {
    if (url.includes(prefix)) {
      if (prefix === '/shops' || prefix === '/appeals' || prefix === '/users' || prefix === '/templates' || prefix === '/negative-feedbacks') {
        // 通用前缀：GET 不记为操作
        if (method.toUpperCase() === 'GET') return undefined
      }
      return name
    }
  }
  return undefined
}

// 请求拦截器
instance.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    ;(config as any).__startAt = Date.now()
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器
instance.interceptors.response.use(
  (response) => {
    // Backend is online
    if (backendOffline) {
      backendOffline = false
      ElMessage.success('服务器连接已恢复')
    }
    // 审计：写操作成功记录 + 慢请求记录
    try {
      const cfg: any = response.config || {}
      const duration = cfg.__startAt ? Date.now() - cfg.__startAt : undefined
      const method = (cfg.method || '').toUpperCase()
      if (duration && duration > 3000) {
        track({
          type: 'slow_api', level: 'WARN', action: 'slow_request',
          result: 'slow', endpoint: `${method} ${cfg.url}`, durationMs: duration, httpStatus: response.status
        })
      }
      const action = matchAction(cfg.url, method)
      if (action) {
        track({
          type: 'user_action', level: 'INFO', action,
          result: 'success', endpoint: `${method} ${cfg.url}`, durationMs: duration, httpStatus: response.status
        })
      }
    } catch { /* 静默 */ }
    return response.data
  },
  (error: AxiosError) => {
    const now = Date.now()

    // 审计：所有接口失败都上报（含网络不通）
    try {
      const cfg: any = error.config || {}
      const duration = cfg.__startAt ? Date.now() - cfg.__startAt : undefined
      const method = (cfg.method || '').toUpperCase()
      const isNetErr = error.code === 'ECONNREFUSED' || error.code === 'ERR_NETWORK' || !error.response
      track({
        type: isNetErr ? 'api_error' : 'api_error',
        level: isNetErr ? 'ERROR' : error.response && error.response.status >= 500 ? 'ERROR' : 'WARN',
        action: 'api_fail',
        result: 'fail',
        detail: isNetErr
          ? '服务器无法连接（服务未启动或网络中断）'
          : `HTTP ${error.response?.status} ${(error.response?.data as any)?.message || error.message}`,
        endpoint: `${method} ${cfg.url}`,
        durationMs: duration,
        httpStatus: error.response?.status
      })
    } catch { /* 静默 */ }

    // Network error (backend offline)
    if (error.code === 'ECONNREFUSED' || error.code === 'ERR_NETWORK' || !error.response) {
      if (!backendOffline) {
        backendOffline = true
        ElMessage.error('服务器未连接')
      }
      // Don't show repeated errors for polling requests
      return Promise.reject(error)
    }

    // 店铺未找到由页面单独弹窗提示，这里不再重复弹出通用错误
    if ((error.response?.data as any)?.code === 'SHOP_NOT_FOUND') {
      return Promise.reject(error)
    }

    // Only show error messages once per second to avoid spam
    const shouldShowError = now - lastErrorTime > 1000

    if (error.response && shouldShowError) {
      lastErrorTime = now

      switch (error.response.status) {
        case 401:
          ElMessage.error('未登录或登录已过期')
          localStorage.removeItem('token')
          router.push('/login')
          break
        case 403:
          ElMessage.error('无权限访问')
          router.push('/forbidden')
          break
        case 404:
          ElMessage.error('请求的资源不存在')
          break
        case 500:
          ElMessage.error('服务器错误')
          break
        default:
          ElMessage.error(error.message || '请求失败')
      }
    }

    return Promise.reject(error)
  }
)

export default instance
