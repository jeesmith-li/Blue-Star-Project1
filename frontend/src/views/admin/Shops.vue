<template>
  <div class="page-container">
    <div class="card">
      <el-table :data="shops" style="width: 100%">
        <el-table-column prop="name" label="店铺名称" width="200" />
        <el-table-column prop="ownerUsername" label="所属用户" width="150" />
        <el-table-column prop="userType" label="用户类型" width="120" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'info'">
              {{ row.status === 'active' ? '正常' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="todayBadReviews" label="今日差评" width="100" />
        <el-table-column prop="unrepliedCount" label="待回复" width="100" />
        <el-table-column prop="automationStatus" label="自动化" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.automationStatus === 'running'" type="success">运行中</el-tag>
            <el-tag v-else type="info">空闲</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastSyncTime" label="最后同步" width="180" />
        <el-table-column label="操作" width="200">
          <template #default>
            <el-button text type="primary" size="small">查看</el-button>
            <el-button text type="primary" size="small">查看评价</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api/admin'

const shops = ref<any[]>([])

onMounted(async () => {
  try {
    shops.value = await adminApi.getAdminShops()
  } catch (error) {
    // Mock
    shops.value = [
      {
        id: 1,
        name: 'XX餐饮店',
        ownerUsername: 'zhangsan',
        userType: '普通用户',
        status: 'active',
        todayBadReviews: 12,
        unrepliedCount: 8,
        automationStatus: 'idle',
        lastSyncTime: '2026-09-15 14:30'
      }
    ]
  }
})
</script>

<style scoped>
</style>
