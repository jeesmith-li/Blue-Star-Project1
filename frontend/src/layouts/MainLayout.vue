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
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useShopStore } from '../stores/shop'
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

onMounted(async () => {
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
