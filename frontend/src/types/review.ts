export interface Review {
  id: number
  shopId: number
  shopName: string
  reviewer: string
  rating: number
  content: string
  reviewTime: string
  replyStatus: 'unreplied' | 'replied'
  replyContent?: string
  replyTime?: string
  replyTemplate?: string
  appealTemplate?: string
  productRating?: number
  packagingRating?: number
  deliveryRating?: number
}

export interface ReviewFilters {
  shopId?: number
  replyStatus?: 'unreplied' | 'replied' | 'all'
  rating?: 'bad' | 'medium' | 'good' | 'all'
  hasContent?: 'yes' | 'no' | 'all'
  startDate?: string
  endDate?: string
  page?: number
  pageSize?: number
}

export interface ReplyRequest {
  content: string
  templateId?: number
  /** true 时通过浏览器 RPA 同步发送到美团，成功后本地才标记已回复 */
  sendToMeituan?: boolean
}
