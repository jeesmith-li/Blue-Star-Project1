<template>
  <div class="page-container">
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon" style="background: #e6f4f9;">
          <el-icon color="#99cdd8" :size="24"><User /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.totalUsers }}</div>
          <div class="stat-label">用户总数</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon" style="background: #e6f7f0;">
          <el-icon color="#00b578" :size="24"><CircleCheck /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.activeUsers }}</div>
          <div class="stat-label">正常用户</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon" style="background: #fff7e6;">
          <el-icon color="#ffc300" :size="24"><Warning /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.expiringSoonUsers }}</div>
          <div class="stat-label">即将过期</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon" style="background: #ffe7e6;">
          <el-icon color="#f56c6c" :size="24"><Close /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.expiredUsers }}</div>
          <div class="stat-label">已过期</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon" style="background: #fde8e6;">
          <el-icon color="#ff7875" :size="24"><Shop /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.serviceProviders }}</div>
          <div class="stat-label">服务商数量</div>
        </div>
      </div>

      <div class="stat-card">
        <div class="stat-icon" style="background: #e6f4f9;">
          <el-icon color="#99cdd8" :size="24"><Shop /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.totalShops }}</div>
          <div class="stat-label">店铺总数</div>
        </div>
      </div>

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
          <el-icon color="#ffc300" :size="24"><ChatLineSquare /></el-icon>
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats.unrepliedReviews }}</div>
          <div class="stat-label">待回复</div>
        </div>
      </div>
    </div>

    <div class="content-grid">
      <div class="card">
        <h3 class="card-title">系统状态</h3>
        <div class="system-status">
          <div class="status-row">
            <span class="status-label">运行中任务：</span>
            <span class="status-value">{{ stats.runningTasks }} 个</span>
          </div>
          <div class="status-row">
            <span class="status-label">系统状态：</span>
            <el-tag :type="systemStatusType">{{ systemStatusText }}</el-tag>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { adminApi, type AdminDashboardStats } from '../../api/admin'

const stats = ref<AdminDashboardStats>({
  totalUsers: 0,
  activeUsers: 0,
  expiredUsers: 0,
  expiringSoonUsers: 0,
  serviceProviders: 0,
  totalShops: 0,
  todayBadReviews: 0,
  unrepliedReviews: 0,
  runningTasks: 0,
  systemStatus: 'normal'
})

const systemStatusType = computed(() => {
  if (stats.value.systemStatus === 'normal') return 'success'
  if (stats.value.systemStatus === 'warning') return 'warning'
  return 'danger'
})

const systemStatusText = computed(() => {
  if (stats.value.systemStatus === 'normal') return '正常'
  if (stats.value.systemStatus === 'warning') return '警告'
  return '异常'
})

onMounted(async () => {
  try {
    stats.value = await adminApi.getDashboard()
  } catch (error) {
    // Mock data for demo
    stats.value = {
      totalUsers: 128,
      activeUsers: 116,
      expiredUsers: 4,
      expiringSoonUsers: 8,
      serviceProviders: 15,
      totalShops: 89,
      todayBadReviews: 156,
      unrepliedReviews: 68,
      runningTasks: 3,
      systemStatus: 'normal'
    }
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
  gap: 24px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 16px;
}

.system-status {
  padding: 16px 0;
}

.status-row {
  display: flex;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
}

.status-row:last-child {
  border-bottom: none;
}

.status-label {
  width: 120px;
  color: var(--color-text-secondary);
}

.status-value {
  color: var(--color-text);
  font-weight: 500;
}
</style>
