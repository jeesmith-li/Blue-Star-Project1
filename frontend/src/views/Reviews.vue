<template>
  <div class="page-container">
    <!-- 筛选栏 -->
    <div class="card" style="margin-bottom: 16px;">
      <el-form :model="filters" inline>
        <el-form-item label="是否回复">
          <el-select v-model="filters.replyStatus" style="width: 120px;">
            <el-option label="全部" value="all" />
            <el-option label="未回复" value="unreplied" />
            <el-option label="已回复" value="replied" />
          </el-select>
        </el-form-item>
        <el-form-item label="评价星级">
          <el-select v-model="filters.rating" style="width: 120px;">
            <el-option label="全部" value="all" />
            <el-option label="差评" value="bad" />
            <el-option label="中评" value="medium" />
            <el-option label="好评" value="good" />
          </el-select>
        </el-form-item>
        <el-form-item label="有无内容">
          <el-select v-model="filters.hasContent" style="width: 120px;">
            <el-option label="全部" value="all" />
            <el-option label="有内容" value="yes" />
            <el-option label="无内容" value="no" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
          <el-button type="success" :loading="syncingAll" @click="handleSyncAll">
            抓取全部店铺评价
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 按店铺分组 -->
    <div v-if="shopGroups.length === 0" class="card empty-tip">
      <el-empty description="暂无评价数据" />
    </div>

    <el-collapse v-model="activeShopIds" class="shop-collapse">
      <el-collapse-item
        v-for="group in shopGroups"
        :key="group.shopId"
        :name="group.shopId"
      >
        <template #title>
          <div class="shop-title">
            <span class="shop-name">{{ group.shopName }}</span>
            <div class="shop-stats">
              <el-tag size="small" type="info">共 {{ group.reviews.length }} 条</el-tag>
              <el-tag size="small" type="success">已回复 {{ group.repliedCount }}</el-tag>
              <el-tag v-if="group.unrepliedCount > 0" size="small" type="warning">待回复 {{ group.unrepliedCount }}</el-tag>
              <el-button
                size="small"
                type="warning"
                plain
                :loading="markingShopId === group.shopId"
                :disabled="group.unrepliedCount === 0"
                @click.stop="handleMarkAllReplied(group)"
              >全部设为已回复</el-button>
              <el-button
                size="small"
                type="primary"
                :loading="syncingShopId === group.shopId"
                @click.stop="handleSyncOne(group)"
              >抓取评价</el-button>
            </div>
          </div>
        </template>

        <!-- 该店铺下的评价列表 -->
        <div class="reviews-in-shop">
          <!-- 分页：每页10条 -->
          <div class="shop-pagination">
            <el-pagination
              :current-page="shopPages[group.shopId] || 1"
              :page-size="PAGE_SIZE"
              :total="group.reviews.length"
              layout="prev, pager, next"
              background
              hide-on-single-page
              @current-change="(p: number) => { shopPages[group.shopId] = p }"
            />
          </div>
          <div
            v-for="review in pagedReviews(group)"
            :key="review.id"
            class="review-card"
          >
            <div class="review-header">
              <div class="review-rating">
                <span v-for="i in review.rating" :key="i" class="star">★</span>
              </div>
              <span class="review-user">{{ review.reviewer }}</span>
              <span class="review-time">{{ review.reviewTime }}</span>
              <el-tag
                v-if="review.replyStatus === 'unreplied'"
                type="warning"
                size="small"
                style="margin-left: auto"
              >待回复</el-tag>
              <el-tag v-else type="success" size="small" style="margin-left: auto">已回复</el-tag>
            </div>

            <div class="review-content">
              {{ review.content || '（无文字内容）' }}
            </div>

            <!-- 回复内容 -->
            <div v-if="review.replyStatus === 'replied'" class="review-reply">
              <div class="reply-label">回复内容：</div>
              <div class="reply-content">{{ review.replyContent || '（已回复）' }}</div>
              <div v-if="review.replyTemplate" class="reply-time">
                该评价由
                <el-link type="primary" style="font-size:12px;vertical-align:baseline;"
                  @click="goTemplate(review.replyTemplate, 'reply')">{{ tplName(review.replyTemplate) }}</el-link>
                提供回复内容<template v-if="hasKw(review.replyTemplate)">（命中关键词：{{ kwPart(review.replyTemplate) }}）</template>
              </div>
              <div v-if="review.replyTime" class="reply-time">回复于 {{ review.replyTime }}</div>
            </div>

            <div class="review-actions">
              <el-button
                v-if="review.replyStatus === 'unreplied'"
                type="primary"
                size="small"
                @click="handleReply(review)"
              >
                回复
              </el-button>
              <el-button
                v-if="review.replyStatus === 'unreplied'"
                size="small"
                @click="handleMarkReplied(review)"
              >
                设为已回复
              </el-button>
            </div>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <!-- 回复弹窗 -->
    <el-dialog v-model="replyDialogVisible" title="回复评价" width="600px">
      <div v-if="currentReview" class="reply-dialog">
        <div class="original-review">
          <div class="review-user">{{ currentReview.reviewer }}</div>
          <div class="review-content-preview">{{ currentReview.content }}</div>
        </div>

        <el-form :model="replyForm" style="margin-top: 20px;">
          <el-form-item label="选择模板">
            <el-select v-model="replyForm.templateId" placeholder="选择回复模板" style="width: 100%;">
              <el-option
                v-for="template in templates"
                :key="template.id"
                :label="template.name"
                :value="template.id"
                @click="applyTemplate(template)"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="回复内容">
            <el-input
              v-model="replyForm.content"
              type="textarea"
              :rows="6"
              placeholder="请输入回复内容"
            />
          </el-form-item>
          <el-form-item label="同步美团">
            <el-checkbox v-model="replyForm.sendToMeituan">
              同时发送到美团（浏览器自动操作，成功后本地才标记已回复）
            </el-checkbox>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="replyDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSendReply">发送回复</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alertIfShopNotFound } from '../utils/shopError'
import { confirmAction } from '../utils/confirmAction'
import { reviewsApi } from '../api/reviews'
import { shopsApi } from '../api/shops'
import { browserApi } from '../api/browser'
import type { Review, ReviewFilters } from '../types/review'
import type { ReplyTemplate } from '../types/template'
import type { Shop } from '../types/shop'

const router = useRouter()

// 模板标记解析与跳转：标记格式 "模板名(命中:关键词)"
const tplName = (mark?: string) => (mark || '').split('(命中')[0].trim()
const hasKw = (mark?: string) => (mark || '').includes('(命中:')
const kwPart = (mark?: string) => {
  const m = (mark || '').match(/\(命中:(.*?)\)/)
  return m ? m[1] : ''
}
const goTemplate = (mark: string, type: string) => {
  router.push({ path: '/templates', query: { highlight: tplName(mark), type } })
}

const route = useRoute()

interface ShopGroup {
  shopId: number
  shopName: string
  meituanAccount: string
  reviews: Review[]
  repliedCount: number
  unrepliedCount: number
}

const reviews = ref<Review[]>([])
const allShops = ref<Shop[]>([])
const templates = ref<ReplyTemplate[]>([])
const replyDialogVisible = ref(false)
const currentReview = ref<Review | null>(null)
const activeShopIds = ref<number[]>([])
const syncingAll = ref(false)
const syncingShopId = ref<number | null>(null)
const markingShopId = ref<number | null>(null)

const filters = reactive<ReviewFilters>({
  replyStatus: 'all',
  rating: 'all',
  hasContent: 'all'
})

const replyForm = reactive({
  templateId: undefined as number | undefined,
  content: '',
  sendToMeituan: false
})

// 每个店铺独立的分页状态：每页10条
const PAGE_SIZE = 10
const shopPages = reactive<Record<number, number>>({})

const pagedReviews = (group: ShopGroup) => {
  const totalPages = Math.max(1, Math.ceil(group.reviews.length / PAGE_SIZE))
  const page = Math.min(shopPages[group.shopId] || 1, totalPages)
  const start = (page - 1) * PAGE_SIZE
  return group.reviews.slice(start, start + PAGE_SIZE)
}

// 按店铺分组：以已绑定店铺为基础（无评价的店也显示），再挂上评价数据
const shopGroups = computed<ShopGroup[]>(() => {
  const qAccount = route.query.account as string | undefined
  const qShopId = route.query.shopId ? Number(route.query.shopId) : undefined

  const map = new Map<number, ShopGroup>()
  // 先放所有店铺（按账号/店铺过滤）
  for (const s of allShops.value) {
    if (qAccount && s.meituanAccount !== qAccount) continue
    if (qShopId && s.id !== qShopId) continue
    map.set(s.id, {
      shopId: s.id,
      shopName: s.name,
      meituanAccount: s.meituanAccount,
      reviews: [],
      repliedCount: 0,
      unrepliedCount: 0
    })
  }
  const filterActive = !!(qAccount || qShopId)
  // 再挂上评价
  for (const r of reviews.value) {
    if (filterActive && !map.has(r.shopId)) continue
    if (!map.has(r.shopId)) {
      map.set(r.shopId, {
        shopId: r.shopId,
        shopName: r.shopName || `店铺${r.shopId}`,
        meituanAccount: '',
        reviews: [],
        repliedCount: 0,
        unrepliedCount: 0
      })
    }
    const group = map.get(r.shopId)!
    group.reviews.push(r)
    if (r.replyStatus === 'replied') group.repliedCount++
    else group.unrepliedCount++
  }
  return Array.from(map.values())
})

const handleSearch = async () => {
  try {
    const res: any = await reviewsApi.getReviews({ ...filters, page: 1, pageSize: 10000 })
    // 兼容多种返回结构：数组 / {items,total} / {data:{items}} / {content}
    let list: any[] = []
    if (Array.isArray(res)) list = res
    else if (Array.isArray(res?.items)) list = res.items
    else if (Array.isArray(res?.data?.items)) list = res.data.items
    else if (Array.isArray(res?.data)) list = res.data
    else if (Array.isArray(res?.content)) list = res.content
    reviews.value = list
    // 默认展开所有有数据的店铺
    activeShopIds.value = shopGroups.value.map(g => g.shopId)
  } catch (error) {
    reviews.value = []
  }
}

const handleReset = () => {
  filters.replyStatus = 'all'
  filters.rating = 'all'
  filters.hasContent = 'all'
  handleSearch()
}

const handleSyncOne = async (group: ShopGroup) => {
  if (syncingAll.value) return
  const ok = await confirmAction(
    '抓取评价',
    `即将抓取店铺「${group.shopName}」的评价：系统会自动切换到该店铺并逐页翻取，期间请勿操作美团浏览器窗口。\n本次只抓取评价数据，不会自动回复或申诉；如需自动回复/申诉，请在「自动化任务」页执行流水线，或到「申诉管理」提交申诉。`
  )
  if (!ok) return
  syncingShopId.value = group.shopId
  try {
    const res: any = await browserApi.syncReviews(group.shopId)
    const data = res?.data || {}
    ElMessage.success(res?.message || `抓取完成：共 ${data.total || 0} 条，新增 ${data.added || 0} 条`)
    handleSearch()
  } catch (error: any) {
    if (alertIfShopNotFound(error)) {
      handleSearch()
    } else {
      ElMessage.warning(error?.response?.data?.message || '抓取失败，请确认浏览器已登录美团')
    }
  } finally {
    syncingShopId.value = null
  }
}

const handleSyncAll = async () => {
  if (syncingShopId.value !== null) return
  const ok = await confirmAction(
    '抓取全部店铺评价',
    `即将对当前账号下的所有店铺（共 ${shopGroups.value.length} 家，按店铺执行顺序）依次进行评价抓取。\n抓取过程中系统会自动逐店切换并翻页，请勿操作美团浏览器窗口，也不要重复点击。\n本次只抓取评价，抓取完成后不会自动回复或申诉；如需「抓取→回复→申诉」一并完成，请到「自动化任务」页点击「立即执行全部店铺」。`
  )
  if (!ok) return
  syncingAll.value = true
  try {
    const res: any = await browserApi.syncAllReviews()
    const data = res?.data || {}
    ElMessage.success(`全部完成：${data.shops || 0} 家店，共 ${data.total || 0} 条，新增 ${data.added || 0} 条`)
    handleSearch()
  } catch (error: any) {
    if (alertIfShopNotFound(error)) {
      handleSearch()
    } else {
      ElMessage.warning(error?.response?.data?.message || '抓取失败，请确认浏览器已登录美团')
    }
  } finally {
    syncingAll.value = false
  }
}

const handleReply = (review: Review) => {
  currentReview.value = review
  replyForm.content = ''
  replyDialogVisible.value = true
}

// 单条：手动设为已回复
const handleMarkReplied = async (review: Review) => {
  try {
    const res: any = await reviewsApi.markReplied({ id: review.id })
    ElMessage.success(res?.message || '已设为已回复')
    handleSearch()
  } catch {
    ElMessage.warning('操作失败，请重试')
  }
}

// 批量：该店铺下全部评价设为已回复
const handleMarkAllReplied = async (group: ShopGroup) => {
  try {
    await ElMessageBox.confirm(
      `将把「${group.shopName}」下全部 ${group.reviews.length} 条评价设为已回复（其中未回复 ${group.unrepliedCount} 条），是否继续？`,
      '批量设为已回复',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  markingShopId.value = group.shopId
  try {
    const res: any = await reviewsApi.markReplied({ shopId: group.shopId })
    ElMessage.success(res?.message || '批量设置完成')
    handleSearch()
  } catch {
    ElMessage.warning('操作失败，请重试')
  } finally {
    markingShopId.value = null
  }
}

const applyTemplate = (template: ReplyTemplate) => {
  replyForm.content = template.content
}

const handleSendReply = async () => {
  if (!currentReview.value) return
  if (replyForm.sendToMeituan) {
    const ok = await confirmAction(
      '提交回复到美团',
      `即将把回复内容提交到美团后台（店铺：${currentReview.value.shopName || '未知'}，评价人：${currentReview.value.reviewer || '匿名'}）。\n提交后无法撤回，请确认回复内容无误。`
    )
    if (!ok) return
  }
  try {
    await reviewsApi.replyReview(currentReview.value.id, {
      content: replyForm.content,
      templateId: replyForm.templateId,
      sendToMeituan: replyForm.sendToMeituan
    })
    ElMessage.success(replyForm.sendToMeituan ? '已回复并同步到美团' : '回复成功')
    replyDialogVisible.value = false
    handleSearch()
  } catch (error) {
    ElMessage.warning('回复失败，请重试')
  }
}

onMounted(async () => {
  // 先加载已绑定店铺（保证无评价的店也显示）
  try {
    allShops.value = await shopsApi.getShops()
  } catch {
    allShops.value = []
  }
  handleSearch()
  // 加载模板
  try {
    import('../api/templates').then(m => {
      m.templatesApi.getTemplates().then((list: any) => {
        templates.value = Array.isArray(list) ? list : (list.data || [])
      }).catch(() => {})
    })
  } catch (e) {}
})
</script>

<style scoped>
.empty-tip {
  padding: 40px 0;
}

.shop-collapse {
  display: flex;
  flex-direction: column;
  gap: 12px;
  border: none;
}

.shop-collapse :deep(.el-collapse-item) {
  border: 1px solid var(--color-border, #e4e7ed);
  border-radius: 8px;
  margin-bottom: 0;
  background: #fff;
  overflow: hidden;
}

.shop-collapse :deep(.el-collapse-item__header) {
  padding: 0 20px;
  height: 52px;
  font-size: 15px;
  font-weight: 600;
}

.shop-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}

.shop-collapse :deep(.el-collapse-item__content) {
  padding: 0 20px 20px;
}

.shop-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.shop-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text, #303133);
}

.shop-stats {
  display: flex;
  gap: 8px;
}

.reviews-in-shop {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.shop-pagination {
  display: flex;
  justify-content: center;
  padding: 4px 0;
}

.review-card {
  background: #f7f8fa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
}

.review-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.review-rating {
  color: #f56c6c;
  font-size: 14px;
}

.review-user {
  font-weight: 500;
  color: var(--color-text, #303133);
  font-size: 14px;
}

.review-time {
  font-size: 13px;
  color: var(--color-text-secondary, #909399);
}

.review-content {
  color: var(--color-text, #303133);
  line-height: 1.6;
  font-size: 14px;
  margin-bottom: 10px;
}

.review-reply {
  background: #fff;
  border-left: 3px solid #409eff;
  padding: 10px 12px;
  border-radius: 4px;
  margin-bottom: 10px;
}

.reply-label {
  font-size: 13px;
  color: var(--color-text-secondary, #909399);
  margin-bottom: 6px;
}

.reply-content {
  color: var(--color-text, #303133);
  line-height: 1.6;
  font-size: 14px;
}

.reply-time {
  font-size: 12px;
  color: var(--color-text-secondary, #909399);
  margin-top: 6px;
}

.review-actions {
  display: flex;
  gap: 8px;
}

.original-review {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 8px;
}

.review-content-preview {
  color: var(--color-text, #303133);
  line-height: 1.6;
  margin-top: 8px;
}
</style>
