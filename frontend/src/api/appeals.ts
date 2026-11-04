import request from './request'

export interface PendingAppeal {
  id: number
  shopId: number
  shopName: string
  reviewer: string
  rating: number
  content: string
  replyContent: string
  appealStatus: string
  appealImages: string
}

export const appealsApi = {
  scan(): Promise<{ success: boolean; flagged: number; message: string }> {
    return request.post('/appeals/scan')
  },
  pendingList(): Promise<PendingAppeal[]> {
    return request.get('/appeals/pending')
  },
  prepare(id: number, data: { images?: string; templateId?: number; content?: string }) {
    return request.post(`/appeals/${id}/prepare`, data)
  },
  submit(id: number, data?: { content?: string; images?: string }) {
    return request.post(`/appeals/${id}/submit`, data || {}, { timeout: 600000 })
  },
  checkResults(): Promise<{ success: boolean; updated: number; message: string }> {
    return request.post('/appeals/check-results', undefined, { timeout: 1800000 })
  },
  /** 申诉查询（对标西瓜 comment_list） */
  query(params: { shopName?: string; status?: string; page?: number; pageSize?: number }): Promise<{
    total: number
    page: number
    pageSize: number
    items: AppealRecord[]
  }> {
    return request.get('/appeals/query', { params })
  },
  /** 单条检测申诉状态（对标西瓜 comment_status） */
  checkStatus(id: number): Promise<{ success: boolean; appealStatus?: string; message: string }> {
    return request.post(`/appeals/${id}/check-status`, undefined, { timeout: 600000 })
  },
  /** 自动申诉（按店铺设定批量提交） */
  autoRun(): Promise<{ success: boolean; candidates: number; submitted: number; message: string }> {
    return request.post('/appeals/auto-run', undefined, { timeout: 1800000 })
  }
}

export interface AppealRecord {
  id: number
  shopId: number
  shopName: string
  reviewer: string
  rating: number
  content: string
  reviewTime: string
  appealStatus: string
  appealTime: string
  appealResultTime: string
  appealResultNote: string
}

export interface NegativeFeedback {
  id: number
  shopId: number
  shopName: string
  orderNo: string
  channelName: string
  indicatorName: string
  content: string
  feedbackTime: string
  status: string
  orderData: string
}

export const negativeApi = {
  list(params: { shopName?: string; status?: string; page?: number; pageSize?: number }): Promise<{
    total: number
    page: number
    pageSize: number
    items: NegativeFeedback[]
  }> {
    return request.get('/negative-feedbacks', { params })
  },
  sync(data?: { shopName?: string }): Promise<{ success: boolean; saved: number; message: string }> {
    return request.post('/negative-feedbacks/sync', data || {})
  },
  setStatus(id: number, status: string): Promise<{ success: boolean; message: string }> {
    return request.post(`/negative-feedbacks/${id}/status`, { status })
  }
}
