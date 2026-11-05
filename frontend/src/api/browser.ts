import request from './request'

export interface ScrapedShop {
  id: string
  name: string
  source: string
  poiId: string
  businessStatus: string
}

export const browserApi = {
  /** 启动浏览器，打开美团登录页 */
  startLogin() {
    return request.post('/browser/start-login')
  },

  /** 检查登录状态（轮询） */
  checkLoginStatus(): Promise<{ loggedIn: boolean; message: string }> {
    return request.get('/browser/login-status')
  },

  /** 登录成功后爬取店铺列表 */
  scrapeShops(): Promise<{ success: boolean; message?: string; account: string; shops: ScrapedShop[]; count: number }> {
    return request.post('/browser/scrape-shops', undefined, { timeout: 60000 })
  },

  /** 批量绑定选中的店铺 */
  bindShop(data: { meituanAccount: string; shops: { name: string; poiId: string; businessStatus: string }[] }) {
    return request.post('/browser/bind-shop', data)
  },

  /** 抓取单个店铺的全部评价并入库（RPA 翻页耗时长，单独放宽超时） */
  syncReviews(shopId: number) {
    return request.post('/browser/sync-reviews', { shopId }, { timeout: 1800000 })
  },

  /** 抓取当前用户所有店铺的评价并入库（多店串行，单独放宽超时） */
  syncAllReviews() {
    return request.post('/browser/sync-all-reviews', undefined, { timeout: 1800000 })
  },

  /** 关闭浏览器 */
  close() {
    return request.post('/browser/close')
  }
}
