<template>
  <div class="page-container">
    <!-- 自动执行设置：用户自选执行间隔（几小时或几天） -->
    <div class="card" style="margin-bottom: 16px;">
      <h3 class="card-title">自动执行设置</h3>
      <div style="display:flex;align-items:center;flex-wrap:wrap;gap:14px;">
        <el-switch v-model="cfgEnabled" active-text="自动执行" />
        <span style="font-size:14px;">每</span>
        <el-input-number v-model="cfgValue" :min="1" :max="cfgUnit === 'day' ? 30 : 720"
          size="small" style="width:110px;" :disabled="!cfgEnabled" />
        <el-radio-group v-model="cfgUnit" size="small" :disabled="!cfgEnabled">
          <el-radio-button value="hour">小时</el-radio-button>
          <el-radio-button value="day">天</el-radio-button>
        </el-radio-group>
        <el-button type="primary" size="small" :loading="cfgSaving" @click="saveAutomationConfig">保存设置</el-button>
        <span style="color:#909399;font-size:12px;">开启后按间隔自动执行下方店铺顺序，逐店完成抓取/回复/申诉，需保持浏览器已登录美团</span>
      </div>
      <div v-if="cfg" style="margin-top:10px;font-size:13px;display:flex;align-items:center;gap:8px;">
        <el-tag size="small" :type="lastRunTagType">{{ lastRunText }}</el-tag>
        <span v-if="cfg.lastRunTime" style="color:#909399;font-size:12px;">{{ fmtTime(cfg.lastRunTime) }}</span>
        <span v-if="cfg.lastRunDetail" style="color:#909399;font-size:12px;max-width:560px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" :title="cfg.lastRunDetail">{{ cfg.lastRunDetail }}</span>
      </div>
    </div>

    <!-- 店铺执行顺序 -->
    <div class="card" style="margin-bottom: 16px;">
      <h3 class="card-title">店铺执行顺序</h3>
      <div class="order-hint">
        系统按此顺序逐店闭环执行：抓取评价 → 自动回复 → 差评申诉 → 申诉回查 → 食安抓取 → 食安自动申诉 → 食安回查；完成一家后再进入下一家。
      </div>
      <div v-if="orderList.length === 0" class="muted" style="padding:8px 0;">暂无绑定店铺，请先到「店铺管理」绑定店铺</div>
      <div v-for="(s, i) in orderList" :key="s.id" class="order-row">
        <div class="order-index">{{ i + 1 }}</div>
        <div class="order-name">{{ s.name }}</div>
        <div class="order-tags">
          <el-tag v-if="s.autoReplyEnabled" size="small" type="success" effect="plain">自动回复</el-tag>
          <el-tag v-if="s.appealAutoEnabled" size="small" type="warning" effect="plain">自动申诉</el-tag>
          <el-tag v-if="s.foodSafetyAutoAppeal" size="small" type="danger" effect="plain">食安自动</el-tag>
        </div>
        <div class="order-actions">
          <el-button size="small" :disabled="i === 0 || orderBusy || isRunning" @click="moveShop(i, -1)">上移</el-button>
          <el-button size="small" :disabled="i === orderList.length - 1 || orderBusy || isRunning" @click="moveShop(i, 1)">下移</el-button>
        </div>
      </div>
    </div>

    <div class="automation-container">
      <div class="automation-status-card card">
        <div class="status-header">
          <h3 class="card-title">自动流水线</h3>
          <el-tag :type="isRunning ? 'success' : ''" size="large">
            <span class="status-dot"></span>
            {{ isRunning ? '正在执行' : '空闲' }}
          </el-tag>
        </div>

        <div class="status-content">
          <div class="info-section">
            <div class="info-row">
              <span class="info-label">执行范围：</span>
              <span class="info-value">{{ scopeText }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">当前动作：</span>
              <span class="info-value highlight">{{ actionText }}</span>
            </div>
          </div>

          <div class="action-buttons">
            <el-button
              v-if="!isRunning"
              type="primary"
              size="large"
              :loading="starting"
              :disabled="orderList.length === 0"
              @click="handleStart"
            >
              立即执行全部店铺
            </el-button>
            <el-button v-else type="danger" size="large" @click="handleStop">
              停止
            </el-button>
          </div>
        </div>
      </div>

      <div class="automation-result-card card">
        <h3 class="card-title">最近一次执行结果</h3>
        <template v-if="lastResult">
          <el-alert
            :title="lastResult.message"
            :type="lastResult.success ? 'success' : 'warning'"
            :closable="false"
          />
          <div class="stats-section" style="margin-top: 12px;">
            <div class="stat-item success">
              <div class="stat-label">店铺成功</div>
              <div class="stat-value">{{ lastResult.shopsOk ?? 0 }}</div>
            </div>
            <div class="stat-item error">
              <div class="stat-label">店铺失败</div>
              <div class="stat-value">{{ lastResult.shopsFail ?? 0 }}</div>
            </div>
            <div class="stat-item">
              <div class="stat-label">抓到评价</div>
              <div class="stat-value">{{ lastResult.reviewsFetched ?? 0 }}</div>
            </div>
            <div class="stat-item">
              <div class="stat-label">新增入库</div>
              <div class="stat-value">{{ lastResult.reviewsAdded ?? 0 }}</div>
            </div>
            <div class="stat-item success">
              <div class="stat-label">回复成功</div>
              <div class="stat-value">{{ lastResult.replied ?? 0 }}</div>
            </div>
            <div class="stat-item success">
              <div class="stat-label">申诉提交</div>
              <div class="stat-value">{{ (lastResult.appealSubmitted ?? 0) + (lastResult.foodSafetySubmitted ?? 0) }}</div>
            </div>
          </div>
        </template>
        <el-empty v-else description="尚未执行" :image-size="80" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAutomationStore } from '../stores/automation'
import { useShopStore } from '../stores/shop'
import { automationApi, type AutomationConfigData } from '../api/automation'
import { shopsApi } from '../api/shops'
import { alertIfShopNotFound } from '../utils/shopError'
import type { Shop } from '../types/shop'

const automationStore = useAutomationStore()
const shopStore = useShopStore()

const starting = ref(false)
const orderBusy = ref(false)
const orderList = ref<Shop[]>([])
const lastResult = ref<any>(null)

const isRunning = computed(() => automationStore.status?.status === 'running')
const scopeText = computed(() =>
  orderList.value.length === 0 ? '暂无店铺' : `全部店铺（按上方顺序，共 ${orderList.value.length} 家）`)
const actionText = computed(() => {
  if (!isRunning.value) return '未执行'
  return automationStore.status?.currentAction || '正在按店铺顺序执行自动流水线'
})

// ==================== 自动执行设置 ====================
const cfg = ref<AutomationConfigData | null>(null)
const cfgEnabled = ref(true)
const cfgValue = ref(1)
const cfgUnit = ref<'hour' | 'day'>('day')
const cfgSaving = ref(false)

const loadAutomationConfig = async () => {
  try {
    const d = await automationApi.getAutomationConfig()
    cfg.value = d
    cfgEnabled.value = d.dailyCaptureEnabled
    if (d.intervalHours % 24 === 0) {
      cfgUnit.value = 'day'
      cfgValue.value = d.intervalHours / 24
    } else {
      cfgUnit.value = 'hour'
      cfgValue.value = d.intervalHours
    }
  } catch { /* 配置加载失败不阻塞页面 */ }
}

const saveAutomationConfig = async () => {
  cfgSaving.value = true
  try {
    const res: any = await automationApi.updateAutomationConfig({
      dailyCaptureEnabled: cfgEnabled.value,
      intervalValue: cfgValue.value,
      intervalUnit: cfgUnit.value
    })
    if (res?.success) {
      ElMessage.success('保存成功，系统将按新间隔自动执行')
      await loadAutomationConfig()
    } else {
      ElMessage.error(res?.message || '保存失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    cfgSaving.value = false
  }
}

const lastRunTagType = computed(() => {
  switch (cfg.value?.lastRunResult) {
    case 'success': return 'success'
    case 'partial': return 'warning'
    case 'skip': return 'danger'
    default: return 'info'
  }
})

const lastRunText = computed(() => {
  switch (cfg.value?.lastRunResult) {
    case 'success': return '上次自动执行成功'
    case 'partial': return '上次执行部分失败'
    case 'skip': return '上次执行被跳过：浏览器未连接或未登录美团'
    default: return '尚未执行过自动抓取'
  }
})

const fmtTime = (t?: string) => t ? t.replace('T', ' ').slice(0, 19) : ''

// ==================== 店铺执行顺序 ====================
const loadOrders = async () => {
  try {
    await shopStore.fetchShops()
    orderList.value = [...shopStore.shops]
  } catch {
    orderList.value = []
  }
}

const moveShop = async (index: number, delta: number) => {
  const target = index + delta
  if (target < 0 || target >= orderList.value.length) return
  const list = [...orderList.value]
  const tmp = list[index]
  list[index] = list[target]
  list[target] = tmp
  orderList.value = list
  orderBusy.value = true
  try {
    const res = await shopsApi.reorderShops(list.map(s => s.id))
    if (!res.success) ElMessage.error(res.message || '顺序保存失败')
    else ElMessage.success('店铺执行顺序已保存')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '顺序保存失败')
    await loadOrders()
  } finally {
    orderBusy.value = false
  }
}

// ==================== 执行流水线 ====================
let pollingTimer: number | null = null

const startPolling = () => {
  pollingTimer = window.setInterval(() => {
    automationStore.fetchStatus().catch(() => {})
  }, 2000)
}

const stopPolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

const handleStart = async () => {
  try {
    await ElMessageBox.confirm(
      `将按当前执行顺序逐店处理，共 ${orderList.value.length} 家店铺。每家店铺依次执行：抓取评价 → 自动回复 → 差评申诉 → 申诉回查 → 食安抓取 → 食安自动申诉 → 食安回查。执行期间请不要操作美团浏览器窗口。`,
      '启动确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )

    starting.value = true
    const resp = await automationApi.startFullPipeline()
    lastResult.value = resp?.detail || {
      success: resp?.success,
      message: resp?.message,
      shopsOk: 0,
      shopsFail: 0,
      reviewsFetched: 0,
      reviewsAdded: 0,
      replied: 0,
      appealSubmitted: 0,
      foodSafetySubmitted: 0
    }
    if (resp?.success) ElMessage.success(resp.message || '自动流水线执行完成')
    else ElMessage.warning(resp?.message || '执行完成（部分失败）')
  } catch (error: any) {
    if (error !== 'cancel' && !alertIfShopNotFound(error)) {
      ElMessage.error(error?.response?.data?.message || error?.message || '执行失败')
    }
  } finally {
    starting.value = false
    automationStore.fetchStatus().catch(() => {})
  }
}

const handleStop = async () => {
  try {
    await ElMessageBox.confirm(
      '确认停止当前自动流水线？正在进行的单步操作需要等其结束后才会中断。',
      '提示',
      { type: 'warning' }
    )
    await automationStore.stopAutomation()
    ElMessage.success('已发送停止请求')
  } catch { /* 用户取消 */ }
}

onMounted(async () => {
  await loadAutomationConfig()
  await loadOrders()
  await automationStore.fetchStatus().catch(() => {})
  startPolling()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.automation-container {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}

.automation-status-card,
.automation-result-card {
  padding: 24px;
}

.status-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  margin-right: 6px;
}

.status-content {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.info-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-row {
  display: flex;
  padding: 12px;
  background: var(--color-bg);
  border-radius: 8px;
}

.info-label {
  width: 100px;
  color: var(--color-text-secondary);
}

.info-value {
  flex: 1;
  color: var(--color-text);
  font-weight: 500;
}

.info-value.highlight {
  color: var(--color-primary);
  font-weight: 600;
}

.stats-section {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.stat-item {
  padding: 16px 8px;
  background: var(--color-bg);
  border-radius: 8px;
  text-align: center;
}

.stat-label {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin-bottom: 8px;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--color-text);
}

.stat-item.success .stat-value {
  color: var(--color-success);
}

.stat-item.error .stat-value {
  color: var(--color-error);
}

.action-buttons {
  display: flex;
  gap: 12px;
}

.action-buttons .el-button {
  flex: 1;
}

.order-hint {
  font-size: 12px;
  color: #909399;
  margin-bottom: 10px;
  line-height: 1.6;
}

.order-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--color-border, #ebeef5);
}

.order-row:last-child {
  border-bottom: none;
}

.order-index {
  width: 24px;
  height: 24px;
  line-height: 24px;
  text-align: center;
  border-radius: 50%;
  background: var(--color-bg);
  color: #606266;
  font-size: 12px;
  flex: none;
}

.order-name {
  flex: 1;
  min-width: 160px;
  font-size: 14px;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-tags {
  display: flex;
  gap: 6px;
  flex: none;
}

.order-actions {
  flex: none;
}
</style>
