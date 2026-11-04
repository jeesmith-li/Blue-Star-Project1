<template>
  <div class="page-container">
    <div class="page-actions">
      <el-button type="primary" @click="showCreateDialog">
        <el-icon><Plus /></el-icon>
        添加用户
      </el-button>
    </div>

    <div class="card">
      <el-table :data="users" style="width: 100%">
        <el-table-column prop="username" label="用户名" width="150" />
        <el-table-column prop="role" label="角色" width="100">
          <template #default="{ row }">
            <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'primary'">
              {{ row.role === 'ADMIN' ? '管理员' : (row.role === 'SERVICE_PROVIDER' ? '服务商' : '普通用户') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320">
          <template #default="{ row }">
            <el-button
              v-if="row.role !== 'ADMIN'"
              text
              type="warning"
              size="small"
              @click="toggleStatus(row)">
              {{ row.status === 'DISABLED' ? '启用' : '禁用' }}
            </el-button>
            <el-button
              v-if="row.role !== 'ADMIN'"
              text
              type="danger"
              size="small"
              @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 创建用户对话框 -->
    <el-dialog v-model="createDialogVisible" title="添加用户" width="500px">
      <el-form :model="createForm" label-width="120px">
        <el-form-item label="用户名">
          <el-input v-model="createForm.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="createForm.password" type="password" placeholder="请输入密码" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="createForm.phone" placeholder="请输入手机号（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import request from '../api/request'

interface User {
  id: number
  username: string
  role: string
  createdAt: string
  status: string
}

const users = ref<User[]>([])
const createDialogVisible = ref(false)

const createForm = ref({
  username: '',
  password: '',
  phone: ''
})

const fetchUsers = async () => {
  try {
    const response = await request.get('/users')
    users.value = response.data || []
  } catch (error) {
    ElMessage.error('加载用户列表失败')
  }
}

const showCreateDialog = () => {
  createForm.value = { username: '', password: '', phone: '' }
  createDialogVisible.value = true
}

const handleCreate = async () => {
  try {
    if (!createForm.value.username || !createForm.value.password) {
      ElMessage.warning('请填写用户名和密码')
      return
    }
    await request.post('/users', createForm.value)
    ElMessage.success('用户创建成功')
    createDialogVisible.value = false
    fetchUsers()
  } catch (error: any) {
    ElMessage.error(error?.message || '创建用户失败')
  }
}

const toggleStatus = async (user: User) => {
  try {
    const newStatus = user.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
    await request.put(`/users/${user.id}/status`, { status: newStatus })
    ElMessage.success(newStatus === 'ACTIVE' ? '已启用' : '已禁用')
    fetchUsers()
  } catch (error: any) {
    ElMessage.error(error?.message || '操作失败')
  }
}

const handleDelete = async (user: User) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除用户"${user.username}"吗？`,
      '删除确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    await request.delete(`/users/${user.id}`)
    ElMessage.success('删除成功')
    fetchUsers()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(error?.message || '删除失败')
  }
}

const formatDateTime = (dateTime: string) => {
  if (!dateTime) return '-'
  return dateTime.replace('T', ' ').substring(0, 19)
}

const getStatusType = (status: string) => {
  switch (status) {
    case 'ACTIVE': return 'success'
    case 'EXPIRED': return 'warning'
    case 'DISABLED': return 'info'
    default: return 'info'
  }
}

const getStatusText = (status: string) => {
  switch (status) {
    case 'ACTIVE': return '正常'
    case 'EXPIRED': return '已欠费'
    case 'DISABLED': return '已禁用'
    default: return status
  }
}

onMounted(() => { fetchUsers() })
</script>

<style scoped>
.page-actions { margin-bottom: 16px; }
</style>
