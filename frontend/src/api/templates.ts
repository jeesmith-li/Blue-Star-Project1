import request from './request'
import type { ReplyTemplate, TemplateCreateRequest } from '../types/template'

export const templatesApi = {
  getTemplates(): Promise<ReplyTemplate[]> {
    return request.get('/templates')
  },

  createTemplate(data: TemplateCreateRequest): Promise<ReplyTemplate> {
    return request.post('/templates', data)
  },

  updateTemplate(id: number, data: Partial<TemplateCreateRequest>): Promise<ReplyTemplate> {
    return request.put(`/templates/${id}`, data)
  },

  deleteTemplate(id: number): Promise<void> {
    return request.delete(`/templates/${id}`)
  },

  /** 检查关键词组合是否与已有模板完全一致（防呆） */
  checkKeyWords(keywords: string, type: string, excludeId?: number): Promise<any> {
    return request.get('/templates/check-keywords', { params: { keywords, type, excludeId } })
  },

  /** Excel 批量导入模板 */
  importExcel(file: File): Promise<any> {
    const fd = new FormData()
    fd.append('file', file)
    return request.post('/templates/import-excel', fd)
  },

  /** 下载 Excel 导入示例模板 */
  async downloadImportSample(): Promise<Blob> {
    return await request.get('/templates/import-sample', { responseType: 'blob' }) as any
  }
}
