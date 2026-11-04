import { ElMessageBox } from 'element-plus'

/**
 * 操作前二次确认：向用户说明即将执行的动作及影响范围
 * @returns true 表示用户确认执行，false 表示取消
 */
export async function confirmAction(title: string, message: string): Promise<boolean> {
  const safe = message
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br/>')
  try {
    await ElMessageBox.confirm(safe, title, {
      confirmButtonText: '确认执行',
      cancelButtonText: '取消',
      type: 'warning',
      dangerouslyUseHTMLString: true
    })
    return true
  } catch {
    return false
  }
}
