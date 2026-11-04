export interface ReplyTemplate {
  id: number
  name: string
  content: string
  type: 'reply' | 'appeal' | 'food_appeal'
  images?: string
  keywords?: string
  createdAt: string
  status: 'active' | 'inactive'
}

export interface TemplateCreateRequest {
  name: string
  content: string
  type?: 'reply' | 'appeal' | 'food_appeal'
  images?: string
  keywords?: string
}
