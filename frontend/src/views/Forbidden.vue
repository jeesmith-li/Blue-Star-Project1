<template>
  <div class="forbidden-page">
    <div class="forbidden-card">
      <div class="forbidden-icon">
        <el-icon :size="80" color="#f56c6c"><CircleCloseFilled /></el-icon>
      </div>
      <h1 class="forbidden-title">403</h1>
      <p class="forbidden-text">无权限访问</p>
      <p class="forbidden-desc">您没有权限访问此页面</p>
      <el-button type="primary" size="large" @click="handleGoHome">
        返回首页
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const handleGoHome = () => {
  if (authStore.isAdmin) {
    router.push('/admin/dashboard')
  } else {
    router.push('/dashboard')
  }
}
</script>

<style scoped>
.forbidden-page {
  width: 100%;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg);
}

.forbidden-card {
  text-align: center;
  padding: 60px;
  background: var(--color-card);
  border-radius: var(--border-radius);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.forbidden-icon {
  margin-bottom: 24px;
}

.forbidden-title {
  font-size: 72px;
  font-weight: 700;
  color: var(--color-error);
  margin: 0 0 16px 0;
}

.forbidden-text {
  font-size: 24px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0 0 8px 0;
}

.forbidden-desc {
  font-size: 16px;
  color: var(--color-text-secondary);
  margin: 0 0 32px 0;
}
</style>
