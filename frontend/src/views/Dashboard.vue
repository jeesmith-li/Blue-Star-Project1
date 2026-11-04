<template>
  <div class="page-container">
    <!-- 自动抓取状态告警条：跳过/部分失败时醒目提醒 -->
    <el-alert
      v-if="autoCfg && autoCfg.lastRunResult !== 'success'"
      :title="autoAlertTitle"
      :description="autoCfg.lastRunDetail || undefined"
      :type="autoCfg.lastRunResult === 'skip' ? 'error' : 'warning'"
      show-icon :closable="false" style="margin-bottom: 16px;"
    >
      <template v-if="autoCfg.lastRunResult === 'skip'" #default>
        <div>上次自动抓取被跳过（{{ fmtTime(autoCfg.lastRunTime) }}），评价和差评未被同步。请打开美团商家后台并登录后，到「自动化」页点立即执行或等待下个周期。</div>
      </template>
    </el-alert>
    <el-alert
      v-else-if="autoCfg && autoCfg.lastRunResult === 'success'"
      :title="'自动抓取运行正常，上次执行：' + fmtTime(autoCfg.lastRunTime)"
      type="success" show-icon :closable="false" style="margin-bottom: 16px;"
    />

    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon" style="background: #ffe7e6;">
          <el-icon color="#f56c6c" :size="24"><ChatDotSquare /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.todayBadReviews }}</div>
          <div class="stat-label">今日差评</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon" style="background: #fff7e6;">
          <el-icon color="#ffc300" :size="24"><Warning /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.unreplied }}</div>
          <div class="stat-label">待回复</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon" style="background: #e6f7f0;">
          <el-icon color="#00b578" :size="24"><CircleCheck /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.replied }}</div>
          <div class="stat-label">已回复</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon" style="background: #e6f4f9;">
          <el-icon color="#99cdd8" :size="24"><Shop /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.shops }}</div>
          <div class="stat-label">店铺数量</div>
        </div>
      </div>
    </div>

    <div class="content-grid">
      <div class="card">
        <h3 class="card-title">最近差评</h3>
        <el-table :data="recentReviews" style="width: 100%">
          <el-table-column prop="reviewer" label="用户" width="120" />
          <el-table-column prop="rating" label="评分" width="80">
            <template #default="{ row }">
              <span style="color: #f56c6c;">{{ row.rating }}★</span>
            </template>
          </el-table-column>
          <el-table-column prop="content" label="内容" show-overflow-tooltip />
          <el-table-column prop="reviewTime" label="时间" width="180" />
        </el-table>
      </div>

      <div class="card">
        <h3 class="card-title">自动化状态</h3>
        <div v-if="automationStore.status" class="automation-info">
          <div class="info-row">
            <span class="info-label">状态：</span>
            <el-tag :type="statusType">{{ statusText }}</el-tag>
          </div>
          <div class="info-row">
            <span class="info-label">当前阶段：</span>
            <span>{{ stageText }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">已处理：</span>
            <span>{{ automationStore.status.processedCount }} 条</span>
          </div>
          <div class="info-row">
            <span class="info-label">成功：</span>
            <span style="color: var(--color-success);">{{ automationStore.status.successCount }} 条</span>
          </div>
        </div>
        <el-empty v-else description="暂无自动化任务" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAutomationStore } from '../stores/automation'
import { STAGE_LABELS } from '../types/automation'
import { automationApi, type AutomationConfigData } from '../api/automation'
import type { Review } from '../types/review'

const automationStore = useAutomationStore()

// 自动抓取执行状态（告警条数据源）
const autoCfg = ref<AutomationConfigData | null>(null)
const fmtTime = (t?: string) => t ? t.replace('T', ' ').slice(0, 16) : ''
const autoAlertTitle = computed(() => {
  switch (autoCfg.value?.lastRunResult) {
    case 'skip': return '自动抓取未执行：浏览器未连接或未登录美团'
    case 'partial': return '自动抓取部分失败'
    default: return '自动抓取尚未执行'
  }
})

const stats = ref({
  todayBadReviews: 0,
  unreplied: 0,
  replied: 0,
  shops: 0
})

const recentReviews = ref<Review[]>([])

const statusType = computed(() => {
  const status = automationStore.status?.status
  if (status === 'running') return 'success'
  if (status === 'paused') return 'warning'
  if (status === 'failed') return 'danger'
  return 'info'
})

const statusText = computed(() => {
  const status = automationStore.status?.status
  return status === 'running' ? '运行中' : status === 'paused' ? '已暂停' : status === 'failed' ? '失败' : '空闲'
})

const stageText = computed(() => {
  return automationStore.status?.stage ? STAGE_LABELS[automationStore.status.stage] : '-'
})

onMounted(async () => {
  // Initialize with empty production data
  stats.value = {
    todayBadReviews: 0,
    unreplied: 0,
    replied: 0,
    shops: 0
  }

  recentReviews.value = []

  // Silently try to fetch automation status
  try {
    await automationStore.fetchStatus()
  } catch (error) {
    // Backend not available, ignore
  }

  // 拉取自动抓取配置与最近执行状态（告警条）
  try {
    autoCfg.value = await automationApi.getAutomationConfig()
  } catch {
    autoCfg.value = null
  }
})
</script>

<style scoped>
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 24px;
  margin-bottom: 24px;
}

.stat-card {
  background: var(--color-card);
  border-radius: var(--border-radius);
  padding: 24px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-value {
  font-size: 32px;
  font-weight: 600;
  color: var(--color-text);
  line-height: 1;
  margin-bottom: 8px;
}

.stat-label {
  font-size: 14px;
  color: var(--color-text-secondary);
}

.content-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 24px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 16px;
}

.automation-info {
  padding: 16px 0;
}

.info-row {
  display: flex;
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  width: 100px;
  color: var(--color-text-secondary);
}
</style>
