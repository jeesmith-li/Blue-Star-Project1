import request from './request'

export interface FoodSafetyAppealItem {
  id: number
  shopId: number
  shopName: string
  orderNo: string
  orderTime: string
  feedbackChannel: string
  feedbackType: string
  content: string
  mtStatus: number
  mtStatusText: string
  appealReason: string
  appealTemplate: string
  hasUploadImages: boolean
  auditRemark: string
  appealTime: string
  lastCheckTime: string
  createdAt: string
}

export const foodSafetyApi = {
  list(params: { shopId?: number; mtStatus?: number }): Promise<FoodSafetyAppealItem[]> {
    return request.get('/food-safety/list', { params })
  },
  /** 按店铺抓取食安负反馈明细（RPA） */
  scrape(shopId: number): Promise<{ success: boolean; added?: number; skipped?: number; message: string }> {
    return request.post(`/food-safety/scrape/${shopId}`, {}, { timeout: 600000 })
  },
  /** 自动申诉：待申诉单按关键词模板提交（RPA，逐单耗时较长） */
  autoAppeal(shopId: number): Promise<{ success: boolean; submitted?: number; manualLeft?: number; failed?: number; message: string }> {
    return request.post(`/food-safety/appeal/${shopId}`, {}, { timeout: 1800000 })
  },
  /** 手动申诉指定订单 */
  manualAppeal(data: { shopId: number; orderNo: string; reason: string; templateMark?: string }): Promise<{ success: boolean; message: string }> {
    return request.post('/food-safety/appeal-manual', data, { timeout: 600000 })
  },
  /** 状态回查（RPA） */
  check(shopId: number): Promise<{ success: boolean; updated?: number; message: string }> {
    return request.post(`/food-safety/check/${shopId}`, {}, { timeout: 600000 })
  },
  /** 店铺开关：食安自动申诉 */
  toggleAuto(shopId: number, enabled: boolean): Promise<{ success: boolean; message?: string }> {
    return request.post(`/food-safety/toggle-auto/${shopId}`, { enabled })
  }
}
