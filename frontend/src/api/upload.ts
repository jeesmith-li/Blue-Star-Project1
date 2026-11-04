import request from './request'

export const uploadApi = {
  /** 上传图片，返回 { success, url, name } */
  uploadImage(file: File): Promise<{ success: boolean; url: string; name: string }> {
    const form = new FormData()
    form.append('file', file)
    return request.post('/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}
