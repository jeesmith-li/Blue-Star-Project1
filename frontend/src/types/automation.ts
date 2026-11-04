export type AutomationStage =
  | 'IDLE'
  | 'CUSTOMER_MANAGEMENT'
  | 'CUSTOMER_REVIEW'
  | 'REVIEW_LIST'
  | 'FILTERING'
  | 'FIND_REPLY'
  | 'INPUT_REPLY'
  | 'SUBMIT_REPLY'
  | 'VERIFY_REPLY'
  | 'SCROLL_RECOVERY'
  | 'COMPLETED'
  | 'FAILED'
  | 'PAUSED'

export interface AutomationStatus {
  shopId: number
  shopName: string
  status: 'running' | 'idle' | 'paused' | 'failed'
  stage: AutomationStage
  processedCount: number
  successCount: number
  failedCount: number
  scrollAttempts: number
  maxScrollAttempts: number
  currentAction?: string
}

export interface AutomationLog {
  timestamp: string
  level: 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR'
  message: string
}

export const STAGE_LABELS: Record<AutomationStage, string> = {
  IDLE: '空闲',
  CUSTOMER_MANAGEMENT: '进入顾客管理',
  CUSTOMER_REVIEW: '进入顾客评价',
  REVIEW_LIST: '进入评价列表',
  FILTERING: '正在筛选评价',
  FIND_REPLY: '寻找回复按钮',
  INPUT_REPLY: '正在输入回复',
  SUBMIT_REPLY: '正在提交回复',
  VERIFY_REPLY: '正在验证回复',
  SCROLL_RECOVERY: '正在翻动评论',
  COMPLETED: '已完成',
  FAILED: '执行失败',
  PAUSED: '已暂停'
}

/** 单条回复结果明细 */
export interface ReplyResultItem {
  platformReviewId: string
  reviewer?: string
  rating?: number
  success: boolean
  message: string
}

/** /automation/start 响应（同步执行完毕后返回） */
export interface AutomationStartResponse {
  success: boolean
  message: string
  status?: string
  detail?: {
    success?: boolean
    message?: string
    fetched?: number
    added?: number
    replied?: number
    failed?: number
    skipped?: number
    results?: ReplyResultItem[]
  }
}
