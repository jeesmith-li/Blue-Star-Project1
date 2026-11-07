import request from './request'
import type { AutomationStatus, AutomationLog } from '../types/automation'

export interface AutomationConfigData {
  id: number
  dailyCaptureEnabled: boolean
  intervalHours: number
  lastRunTime?: string
  lastRunResult?: 'success' | 'partial' | 'skip' | 'never'
  lastRunDetail?: string
}

export const automationApi = {
  /** 获取自动执行配置与最近执行状态 */
  getAutomationConfig(): Promise<AutomationConfigData> {
    return request.get('/automation-config').then((r: any) => r.data)
  },

  /** 更新自动执行配置：intervalValue + intervalUnit(hour/day) */
  updateAutomationConfig(payload: {
    dailyCaptureEnabled?: boolean
    intervalValue?: number
    intervalUnit?: 'hour' | 'day'
  }): Promise<any> {
    return request.put('/automation-config', payload)
  },

  getStatus(): Promise<AutomationStatus> {
    return request.get('/automation/status')
  },

  getLogs(): Promise<AutomationLog[]> {
    return request.get('/automation/logs')
  },

  // 同步执行：按店铺执行顺序逐店完成抓取/回复/申诉/食安，可能耗时很长，单独放宽超时
  startFullPipeline(): Promise<any> {
    return request.post('/automation/start', null, { timeout: 1800000 })
  },

  // 单店执行：仅对指定店铺跑一次完整流水线（2 小时内已抓取/已执行过自动化则跳过抓取评价）
  runShopPipeline(shopId: number): Promise<any> {
    return request.post(`/automation/shop/${shopId}`, null, { timeout: 1800000 })
  },

  pauseAutomation(): Promise<void> {
    return request.post('/automation/pause')
  },

  resumeAutomation(): Promise<void> {
    return request.post('/automation/resume')
  },

  stopAutomation(): Promise<void> {
    return request.post('/automation/stop', null, { timeout: 10000 })
  }
}
