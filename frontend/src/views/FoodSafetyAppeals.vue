<template>
  <div class="page-container">
    <div class="page-actions">
      <el-select v-model="shopId" placeholder="选择店铺" style="width:200px;" @change="fetchList">
        <el-option v-for="s in shops" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
      <el-select v-model="mtStatus" placeholder="美团状态" clearable style="width:140px;margin-left:10px;" @change="fetchList">
        <el-option v-for="(label, val) in STATUS_OPTIONS" :key="val" :label="label" :value="Number(val)" />
      </el-select>
      <el-button type="primary" style="margin-left:10px;" @click="fetchList" :loading="loading">查询</el-button>

      <el-button type="success" :disabled="!shopId" :loading="scraping" @click="handleScrape">抓取食安单</el-button>
      <el-button type="warning" :disabled="!shopId" :loading="autoRunning" @click="handleAutoAppeal">自动申诉</el-button>
      <el-button :disabled="!shopId" :loading="checking" @click="handleCheck">检测状态</el-button>

      <div class="auto-switch">
        <span>自动申诉</span>
        <el-switch :model-value="autoEnabled" :disabled="!shopId || toggling" @change="handleToggleAuto" />
      </div>
      <span class="muted">共 {{ total }} 条</span>
    </div>

    <el-table :data="list" style="width:100%;" size="small" border v-loading="loading">
      <el-table-column label="序号" width="60">
        <template #default="{ $index }">{{ $index + 1 }}</template>
      </el-table-column>
      <el-table-column prop="shopName" label="店名" min-width="130" show-overflow-tooltip />
      <el-table-column prop="orderNo" label="订单号" width="170" show-overflow-tooltip />
      <el-table-column label="订单时间" width="105">
        <template #default="{ row }">{{ fmt(row.orderTime) }}</template>
      </el-table-column>
      <el-table-column prop="feedbackChannel" label="反馈渠道" width="90" show-overflow-tooltip />
      <el-table-column prop="feedbackType" label="问题类型" width="110" show-overflow-tooltip />
      <el-table-column prop="content" label="顾客反馈" min-width="170" show-overflow-tooltip />
      <el-table-column label="美团状态" width="95">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.mtStatus)">{{ row.mtStatusText || statusTextOf(row.mtStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="appealReason" label="申诉理由" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.appealReason || '-' }}</template>
      </el-table-column>
      <el-table-column label="使用模板" width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link v-if="row.appealTemplate" type="primary" style="font-size:12px;"
            @click="goTemplate(row.appealTemplate)">{{ tplName(row.appealTemplate) }}</el-link>
          <span v-else style="color:#909399;">-</span>
        </template>
      </el-table-column>
      <el-table-column label="图片" width="60">
        <template #default="{ row }">
          <span v-if="row.hasUploadImages" style="color:#67c23a;">有</span>
          <span v-else style="color:#c0c4cc;">无</span>
        </template>
      </el-table-column>
      <el-table-column label="申诉时间" width="150">
        <template #default="{ row }">{{ fmt(row.appealTime) }}</template>
      </el-table-column>
      <el-table-column prop="auditRemark" label="审核意见" min-width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.auditRemark || '-' }}</template>
      </el-table-column>
      <el-table-column label="最后回查" width="150">
        <template #default="{ row }">{{ fmt(row.lastCheckTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canAppeal(row.mtStatus)" size="small" type="primary" plain
            @click="openManual(row)">手动申诉</el-button>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loading && list.length === 0" class="empty">
      暂无食安申诉记录，选择店铺后点击「抓取食安单」从美团拉取
    </div>

    <!-- 手动申诉弹窗 -->
    <el-dialog v-model="manualVisible" title="手动申诉" width="560px">
      <div class="manual-info">
        <div class="row"><b>店铺：</b>{{ manualRow?.shopName }}</div>
        <div class="row"><b>订单号：</b>{{ manualRow?.orderNo }}</div>
        <div class="row"><b>问题类型：</b>{{ manualRow?.feedbackType || '-' }}</div>
        <div class="row"><b>顾客反馈：</b>{{ manualRow?.content || '-' }}</div>
      </div>
      <el-select v-model="manualTplId" placeholder="套用申诉模板(可选)" clearable style="width:100%;margin-bottom:12px;"
        @change="onPickTpl">
        <el-option v-for="t in appealTpls" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      <el-input v-model="manualReason" type="textarea" :rows="5" maxlength="300" show-word-limit
        placeholder="填写申诉理由（20至300字）" />
      <div class="reason-hint" :class="{ ok: manualReason.trim().length >= 20 && manualReason.trim().length <= 300 }">
        已输入 {{ manualReason.trim().length }} 字{{ manualReason.trim().length >= 20 && manualReason.trim().length <= 300 ? '' : '（需20至300字）' }}
      </div>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button type="primary" :loading="manualSubmitting" @click="submitManual">提交申诉</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { alertIfShopNotFound } from '../utils/shopError'
import { confirmAction } from '../utils/confirmAction'
import { foodSafetyApi, type FoodSafetyAppealItem } from '../api/foodSafety'
import { shopsApi } from '../api/shops'
import { templatesApi } from '../api/templates'
import type { Shop } from '../types/shop'
import type { ReplyTemplate } from '../types/template'

const router = useRouter()

// ============ 筛选与列表 ============
const STATUS_OPTIONS: Record<number, string> = {
  10: '待申诉', 20: '申诉中', 30: '申诉成功', 3: '申诉驳回', 40: '申诉失败', 50: '申诉超时', 41: '可再次申诉'
}
const statusTextOf = (s: number) => STATUS_OPTIONS[s] || String(s)
const statusTag = (s: number): string => {
  if (s === 10 || s === 41) return 'warning'
  if (s === 20) return 'primary'
  if (s === 30) return 'success'
  if (s === 3 || s === 40 || s === 50) return 'danger'
  return 'info'
}
const canAppeal = (s: number) => s === 10 || s === 41

const shops = ref<Shop[]>([])
const shopId = ref<number | undefined>()
const mtStatus = ref<number | undefined>()
const list = ref<FoodSafetyAppealItem[]>([])
const total = computed(() => list.value.length)
const loading = ref(false)

const fetchList = async () => {
  loading.value = true
  try {
    list.value = await foodSafetyApi.list({
      shopId: shopId.value,
      mtStatus: mtStatus.value === undefined || mtStatus.value === null ? undefined : mtStatus.value
    })
  } catch { list.value = [] }
  finally { loading.value = false }
}

// ============ 抓取 / 自动申诉 / 回查 / 开关 ============
const scraping = ref(false)
const autoRunning = ref(false)
const checking = ref(false)
const toggling = ref(false)

const currentShop = computed(() => shops.value.find(s => s.id === shopId.value))
const autoEnabled = computed(() => !!currentShop.value?.foodSafetyAutoAppeal)

const handleScrape = async () => {
  if (!shopId.value) return ElMessage.warning('请先选择店铺')
  const ok = await confirmAction(
    '抓取食安单',
    `即将抓取店铺「${currentShop.value?.name || ''}」的食安负反馈单：系统会自动切换到该店铺并读取食安明细，过程中请勿操作美团浏览器窗口。\n本次只抓取数据，不会自动提交申诉。`
  )
  if (!ok) return
  scraping.value = true
  try {
    const res = await foodSafetyApi.scrape(shopId.value)
    if (res.success) ElMessage.success(res.message)
    else ElMessage.warning(res.message)
    fetchList()
  } catch (e: any) { if (!alertIfShopNotFound(e)) ElMessage.error(e?.message || '抓取失败') }
  finally { scraping.value = false }
}

const handleAutoAppeal = async () => {
  if (!shopId.value) return ElMessage.warning('请先选择店铺')
  const ok = await confirmAction(
    '食安自动申诉',
    `即将对店铺「${currentShop.value?.name || ''}」执行食安自动申诉：系统会打开食安申诉表单，勾选待申诉订单并自动填写、提交申诉理由，提交后无法撤回。\n执行期间请勿操作美团浏览器窗口。`
  )
  if (!ok) return
  autoRunning.value = true
  ElMessage.info('自动申诉执行中，请勿关闭浏览器窗口...')
  try {
    const res = await foodSafetyApi.autoAppeal(shopId.value)
    if (res.success) ElMessage.success(res.message)
    else ElMessage.warning(res.message)
    fetchList()
  } catch (e: any) { if (!alertIfShopNotFound(e)) ElMessage.error(e?.message || '自动申诉失败') }
  finally { autoRunning.value = false }
}

const handleCheck = async () => {
  if (!shopId.value) return ElMessage.warning('请先选择店铺')
  const ok = await confirmAction(
    '回查食安申诉状态',
    `即将回查店铺「${currentShop.value?.name || ''}」已提交食安申诉的审核结果：系统会切换到该店铺并打开申诉记录页，请勿操作美团浏览器窗口。`
  )
  if (!ok) return
  checking.value = true
  try {
    const res = await foodSafetyApi.check(shopId.value)
    if (res.success) ElMessage.success(res.message)
    else ElMessage.warning(res.message)
    fetchList()
  } catch (e: any) { if (!alertIfShopNotFound(e)) ElMessage.error(e?.message || '回查失败') }
  finally { checking.value = false }
}

const handleToggleAuto = async () => {
  if (!shopId.value || !currentShop.value) return
  toggling.value = true
  const target = !currentShop.value.foodSafetyAutoAppeal
  try {
    const res = await foodSafetyApi.toggleAuto(shopId.value, target)
    if (res.success) {
      currentShop.value.foodSafetyAutoAppeal = target
      ElMessage.success(`店铺[${currentShop.value.name}] 食安自动申诉已${target ? '开启' : '关闭'}`)
    } else ElMessage.error(res.message || '操作失败')
  } catch (e: any) { ElMessage.error(e?.message || '操作失败') }
  finally { toggling.value = false }
}

// ============ 手动申诉 ============
const manualVisible = ref(false)
const manualRow = ref<FoodSafetyAppealItem | null>(null)
const manualTplId = ref<number | undefined>()
const manualReason = ref('')
const manualSubmitting = ref(false)
const appealTpls = ref<ReplyTemplate[]>([])

const openManual = (row: FoodSafetyAppealItem) => {
  manualRow.value = row
  manualTplId.value = undefined
  manualReason.value = ''
  manualVisible.value = true
}

const onPickTpl = (tplId: number | undefined) => {
  if (!tplId) return
  const tpl = appealTpls.value.find(t => t.id === tplId)
  if (tpl) manualReason.value = tpl.content
}

const submitManual = async () => {
  if (!manualRow.value) return
  const reason = manualReason.value.trim()
  if (reason.length < 20 || reason.length > 300) return ElMessage.warning('食安申诉理由须为20至300字')
  const ok = await confirmAction(
    '提交食安申诉',
    `即将提交该条食安申诉（订单号：${manualRow.value.orderNo}），理由共 ${reason.length} 字。\n提交到美团后无法撤回，请确认理由与凭证无误。`
  )
  if (!ok) return
  manualSubmitting.value = true
  try {
    const tpl = appealTpls.value.find(t => t.id === manualTplId.value)
    const res = await foodSafetyApi.manualAppeal({
      shopId: manualRow.value.shopId,
      orderNo: manualRow.value.orderNo,
      reason,
      templateMark: tpl ? tpl.name : ''
    })
    if (res.success) {
      ElMessage.success(res.message || '申诉已提交')
      manualVisible.value = false
      fetchList()
    } else ElMessage.error(res.message || '申诉失败')
  } catch (e: any) { if (!alertIfShopNotFound(e)) ElMessage.error(e?.message || '申诉失败') }
  finally { manualSubmitting.value = false }
}

// ============ 模板跳转（与差评申诉一致） ============
const tplName = (mark?: string) => (mark || '').split('(命中')[0].trim()
const goTemplate = (mark: string) => {
  const name = tplName(mark)
  if (name && name !== '手动' && name !== '人工申诉' && name !== '通用话术') {
    router.push({ path: '/templates', query: { highlight: name, type: 'food_appeal' } })
  }
}

// ============ 通用 ============
const fmt = (s?: string) => (s ? String(s).replace('T', ' ').slice(0, 19) : '-')

const fetchTpls = async () => {
  try {
    const data: any = await templatesApi.getTemplates()
    const all = Array.isArray(data) ? data : (data?.data || [])
    appealTpls.value = all.filter((t: any) => (t.type || 'reply') === 'food_appeal')
  } catch { appealTpls.value = [] }
}

onMounted(async () => {
  try { shops.value = await shopsApi.getShops() } catch { shops.value = [] }
  fetchList()
  fetchTpls()
})
</script>

<style scoped>
.page-actions { margin-bottom: 16px; display: flex; align-items: center; flex-wrap: wrap; gap: 10px 0; }
.page-actions .el-button { margin-left: 10px; }
.auto-switch { display: flex; align-items: center; gap: 8px; margin-left: 16px; font-size: 13px; color: #606266; }
.muted { margin-left: 16px; color: #909399; font-size: 13px; }
.empty { text-align: center; padding: 60px; color: #909399; }
.manual-info { background: #fafafa; padding: 10px 14px; border-radius: 6px; margin-bottom: 14px; }
.manual-info .row { margin: 4px 0; font-size: 13px; line-height: 1.6; }
.reason-hint { margin-top: 6px; font-size: 12px; color: #e6a23c; text-align: right; }
.reason-hint.ok { color: #67c23a; }
</style>
