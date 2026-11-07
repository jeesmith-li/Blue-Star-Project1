import request from './request'
import type { Shop, ShopCreateRequest } from '../types/shop'

export const shopsApi = {
  getShops(): Promise<Shop[]> {
    return request.get('/shops')
  },

  getShop(id: number): Promise<Shop> {
    return request.get(`/shops/${id}`)
  },

  createShop(data: ShopCreateRequest): Promise<Shop> {
    return request.post('/shops', data)
  },

  updateShop(id: number, data: Partial<ShopCreateRequest>): Promise<Shop> {
    return request.put(`/shops/${id}`, data)
  },

  forceDeleteShop(id: number, password: string): Promise<void> {
    return request.post(`/shops/${id}/force-delete`, { password })
  },

  deleteShop(id: number): Promise<void> {
    return request.delete(`/shops/${id}`)
  },

  syncShopReviews(id: number): Promise<void> {
    return request.post(`/shops/${id}/sync`)
  },

  startAutomation(id: number): Promise<void> {
    return request.post(`/shops/${id}/automation/start`)
  },

  stopAutomation(id: number): Promise<void> {
    return request.post(`/shops/${id}/automation/stop`)
  },

  /** 自动申诉设定（对标西瓜 shop_set_appeal） */
  updateAppealSettings(id: number, data: { appealAutoEnabled: boolean; appealStars: string; appealRange: string }): Promise<{ success: boolean; message: string }> {
    return request.put(`/shops/${id}/appeal-settings`, data)
  },

  updateReplySettings(id: number, data: { autoReplyEnabled: boolean }): Promise<{ success: boolean; message: string }> {
    return request.put(`/shops/${id}/reply-settings`, data)
  },

  /** 保存店铺执行顺序（数组顺序即执行顺序） */
  reorderShops(ids: number[]): Promise<{ success: boolean; message: string }> {
    return request.post('/shops/reorder', { ids })
  }
}
