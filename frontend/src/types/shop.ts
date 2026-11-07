export interface Shop {
  id: number
  poiId?: string
  name: string
  meituanAccount: string
  shopType: string
  status: 'active' | 'inactive'
  lastSyncTime?: string
  todayBadReviews: number
  unrepliedCount: number
  foodSafetyAutoAppeal?: boolean
  autoReplyEnabled?: boolean
  appealAutoEnabled?: boolean
  appealStars?: string
  appealRange?: string
  sortOrder?: number
}

export interface ShopCreateRequest {
  name: string
  meituanAccount: string
  shopType: string
}
