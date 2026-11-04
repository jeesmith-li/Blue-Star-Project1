import request from './request'
import type { Review, ReviewFilters, ReplyRequest } from '../types/review'

export const reviewsApi = {
  getReviews(filters: ReviewFilters): Promise<Review[]> {
    return request.get('/reviews', { params: filters })
  },

  getReview(id: number): Promise<Review> {
    return request.get(`/reviews/${id}`)
  },

  // sendToMeituan=true 时后端走 RPA（切店铺+抓列表+OCR定位回复），可能超过默认30秒
  replyReview(id: number, data: ReplyRequest): Promise<void> {
    return request.post(`/reviews/${id}/reply`, data, { timeout: 180000 })
  },

  markReplied(data: { id?: number; ids?: number[]; shopId?: number }): Promise<{ success: boolean; updated: number; message: string }> {
    return request.post('/reviews/mark-replied', data)
  }
}
