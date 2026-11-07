<template>
  <div class="main-layout">
    <aside class="sidebar">
      <div class="sidebar-header">
        <img src="/logo/logo_9.jpg" alt="蓝星" class="sidebar-logo" />
        <h2 class="sidebar-title">蓝星</h2>
      </div>

      <el-menu
        :default-active="activeMenu"
        router
        class="sidebar-menu"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataLine /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item v-if="authStore.isAdmin" index="/users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/shops">
          <el-icon><Shop /></el-icon>
          <span>店铺管理</span>
        </el-menu-item>
        <el-menu-item index="/reviews">
          <el-icon><ChatDotSquare /></el-icon>
          <span>评价管理</span>
        </el-menu-item>
        <el-menu-item index="/appeals">
          <el-icon><Warning /></el-icon>
          <span>申诉管理</span>
        </el-menu-item>
        <el-menu-item index="/food-safety">
          <el-icon><CircleCheck /></el-icon>
          <span>食安申诉</span>
        </el-menu-item>
        <el-menu-item index="/templates">
          <el-icon><Document /></el-icon>
          <span>模板管理</span>
        </el-menu-item>
        <el-menu-item index="/automation">
          <el-icon><Setting /></el-icon>
          <span>自动化任务</span>
        </el-menu-item>
      </el-menu>

      <!-- 连接浏览器：执行自动化前提前登录美团 -->
      <div class="sidebar-browser">
        <el-button
          class="browser-btn"
          size="small"
          type="primary"
          plain
          :loading="browserConnecting"
          @click="connectBrowser"
        >
          连接浏览器
        </el-button>
        <div class="browser-state" :class="browserLoggedIn ? 'ok' : 'warn'">
          <span class="state-dot"></span>{{ browserLoggedIn ? '美团已登录' : '美团未登录' }}
        </div>
      </div>

      <div class="sidebar-footer">
        <div class="user-info">
          <div class="user-avatar">
            <el-icon><User /></el-icon>
          </div>
          <div class="user-details">
            <div class="user-name">{{ authStore.user?.username }}</div>
            <div class="user-status" :class="statusClass">
              <span class="status-dot"></span>
              {{ statusText }}
            </div>
          </div>
        </div>
      </div>
    </aside>

    <div class="main-container">
      <header class="main-header">
        <div class="header-left">
          <h3 class="page-title">{{ pageTitle }}</h3>
        </div>
        <div class="header-right">
          <el-select
            v-if="showShopSelector"
            v-model="shopStore.currentShopId"
            placeholder="选择店铺"
            style="width: 200px; margin-right: 16px"
            @change="shopStore.setCurrentShop"
          >
            <el-option
              v-for="shop in shopStore.shops"
              :key="shop.id"
              :label="shop.name"
              :value="shop.id"
            />
          </el-select>

          <el-button @click="handleLogout">退出</el-button>
        </div>
      </header>

      <main class="main-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { useShopStore } from '../stores/shop'
import { browserApi } from '../api/browser'
import { AccountStatus, ACCOUNT_STATUS_LABELS } from '../types/user'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const shopStore = useShopStore()

const activeMenu = computed(() => route.path)

const pageTitle = computed(() => {
  const titles: Record<string, string> = {
    '/dashboard': '仪表盘',
    '/users': '用户管理',
    '/shops': '店铺管理',
    '/reviews': '评价管理',
    '/appeals': '申诉管理',
    '/food-safety': '食安申诉',
    '/templates': '模板管理',
    '/automation': '自动化任务'
  }
  return titles[route.path] || ''
})

const showShopSelector = computed(() => {
  return authStore.isServiceProvider && shopStore.shops.length > 0
})

const statusClass = computed(() => {
  const status = authStore.user?.status
  return {
    'status-normal': status === AccountStatus.ACTIVE,
    'status-expired': status === AccountStatus.EXPIRED,
    'status-disabled': status === AccountStatus.DISABLED
  }
})

const statusText = computed(() => {
  const status = authStore.user?.status
  if (!status) return '未知'
  return ACCOUNT_STATUS_LABELS[status]
})

const handleLogout = () => {
  authStore.logout()
  router.push('/login')
}

// 浏览器连接状态：执行自动化前需确保已连接浏览器并登录美团
const browserLoggedIn = ref(false)
const browserConnecting = ref(false)
let browserTimer: number | undefined

const refreshBrowserState = async () => {
  try {
    const st = await browserApi.checkLoginStatus()
    browserLoggedIn.value = st.loggedIn
  } catch {
    browserLoggedIn.value = false
  }
}

const connectBrowser = async () => {
  browserConnecting.value = true
  try {
    await browserApi.startLogin()
    ElMessage.success('浏览器已打开，请在浏览器中完成美团登录')
    // 轮询最多 60 秒，登录成功后更新状态
    for (let i = 0; i < 10; i++) {
      await new Promise(resolve => setTimeout(resolve, 6000))
      try {
        const st = await browserApi.checkLoginStatus()
        if (st.loggedIn) {
          browserLoggedIn.value = true
          ElMessage.success('美团已登录，可以执行自动化了')
          break
        }
      } catch {
        // 轮询期间的网络抖动忽略，继续等待
      }
      if (i === 9) ElMessage.warning('尚未检测到登录，请在浏览器中完成美团登录后再执行自动化')
    }
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '启动浏览器失败，请确认程序与浏览器环境正常')
  } finally {
    browserConnecting.value = false
  }
}

onMounted(async () => {
  refreshBrowserState()
  browserTimer = window.setInterval(refreshBrowserState, 60000)
  // Don't fetch if already loaded
  if (!authStore.user && authStore.token) {
    try {
      await authStore.fetchCurrentUser()
    } catch (error) {
      console.error('Failed to fetch user:', error)
    }
  }

  try {
    await shopStore.fetchShops()
  } catch (error) {
    console.error('Failed to fetch shops:', error)
  }
})
onUnmounted(() => {
  if (browserTimer) window.clearInterval(browserTimer)
})
</script>

<style scoped>
.main-layout {
  display: flex;
  width: 100%;
  height: 100vh;
}

.sidebar {
  width: 240px;
  background: var(--color-card);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: 24px;
  border-bottom: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  gap: 12px;
}

.sidebar-logo {
  width: 40px;
  height: 40px;
  border-radius: 8px;
}

.sidebar-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-primary);
  margin: 0;
}

.sidebar-menu {
  flex: 1;
  border: none;
  padding: 12px 0;
}

/* 侧边栏：连接浏览器 */
.sidebar-browser {
  padding: 8px 16px 12px;
  border-top: 1px solid var(--color-border);
  margin-top: 4px;
}

.browser-btn {
  width: 100%;
}

.browser-state {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}

.browser-state.ok {
  color: #67c23a;
}

.browser-state.warn {
  color: #e6a23c;
}

.state-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.sidebar-footer {
  padding: 16px;
  border-top: 1px solid var(--color-border);
}

.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--color-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: var(--color-text-secondary);
}

.user-details {
  flex: 1;
}

.user-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: 4px;
}

.user-status {
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status-normal {
  color: var(--color-success);
}

.status-normal .status-dot {
  background: var(--color-success);
}

.status-expired {
  color: var(--color-error);
}

.status-expired .status-dot {
  background: var(--color-error);
}

.main-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.main-header {
  height: 64px;
  background: var(--color-card);
  border-bottom: 1px solid var(--color-border);
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0;
}

.header-right {
  display: flex;
  align-items: center;
}

.main-content {
  flex: 1;
  overflow-y: auto;
  background: var(--color-bg);
}
</style>
