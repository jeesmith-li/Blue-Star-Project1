import { ElMessageBox } from 'element-plus'

/**
 * 统一处理后端 SHOP_NOT_FOUND：
 * 店铺切换失败或二次校验不通过时，弹窗提示用户重新执行。
 * 返回 true 表示已处理（调用方不要再弹通用错误提示）。
 */
export function alertIfShopNotFound(error: any): boolean {
  const data = error?.response?.data
  if (!data || data.code !== 'SHOP_NOT_FOUND') return false
  ElMessageBox.alert(
    data.message || '该店铺暂未寻到，请重新执行',
    '店铺未找到',
    { type: 'warning', confirmButtonText: '知道了' }
  )
  return true
}
