<template>
  <div class="page-container">
    <div class="page-actions">
      <el-button type="primary" @click="handleCreate">
        <el-icon><Plus /></el-icon>
        新增用户
      </el-button>
    </div>

    <div class="card" style="margin-bottom: 16px;">
      <el-form :model="filters" inline>
        <el-form-item label="用户名">
          <el-input v-model="filters.username" placeholder="请输入用户名" clearable style="width: 200px;" />
        </el-form-item>
        <el-form-item label="账号状态">
          <el-select v-model="filters.status" placeholder="全部" clearable style="width: 150px;">
            <el-option label="全部" value="" />
            <el-option label="正常" :value="AccountStatus.ACTIVE" />
            <el-option label="已欠费" :value="AccountStatus.EXPIRED" />
            <el-option label="已禁用" :value="AccountStatus.DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="card">
      <el-table :data="users" style="width: 100%">
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="userType" label="类型" width="110">
          <template #default="{ row }">
            {{ USER_TYPE_LABELS[row.userType as UserType] || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ ACCOUNT_STATUS_LABELS[row.status as AccountStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="shopCount" label="店铺数" width="90" />
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status !== AccountStatus.DISABLED"
              text
              type="danger"
              size="small"
              @click="handleDisable(row)"
            >
              禁用
            </el-button>
            <el-button
              v-else
              text
              type="success"
              size="small"
              @click="handleEnable(row)"
            >
              启用
            </el-button>
            <el-button text type="danger" size="small" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增用户弹窗 -->
    <el-dialog v-model="createDialogVisible" title="新增用户" width="460px">
      <el-form :model="createForm" label-width="90px">
        <el-form-item label="用户名">
          <el-input v-model="createForm.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="createForm.password" type="password" placeholder="请输入密码" />
        </el-form-item>
        <el-form-item label="用户类型">
          <el-select v-model="createForm.userType" placeholder="请选择" style="width: 100%;">
            <el-option label="普通用户" :value="UserType.NORMAL_USER" />
            <el-option label="服务商" :value="UserType.SERVICE_PROVIDER" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreateSave">保存</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { adminApi } from '../../api/admin'
import type { User } from '../../types/user'
import { UserType, AccountStatus, ACCOUNT_STATUS_LABELS, USER_TYPE_LABELS } from '../../types/user'

const users = ref<User[]>([])
const createDialogVisible = ref(false)

const filters = reactive({
  username: '',
  status: ''
})

const createForm = reactive({
  username: '',
  password: '',
  userType: UserType.NORMAL_USER
})

const getStatusType = (status: AccountStatus) => {
  if (status === AccountStatus.ACTIVE) return 'success'
  if (status === AccountStatus.EXPIRED) return 'warning'
  return 'danger'
}

const fetchUsers = async () => {
  try {
    const res: any = await adminApi.getUsers(filters)
    users.value = Array.isArray(res) ? res : (res.data || res.content || [])
  } catch (error) {
    users.value = []
  }
}

const handleSearch = () => fetchUsers()
const handleReset = () => {
  filters.username = ''
  filters.status = ''
  fetchUsers()
}

const handleCreate = () => {
  createForm.username = ''
  createForm.password = ''
  createForm.userType = UserType.NORMAL_USER
  createDialogVisible.value = true
}

const handleCreateSave = async () => {
  try {
    await adminApi.createUser({
      username: createForm.username,
      password: createForm.password,
      userType: createForm.userType,
      durationMonths: 1
    })
    ElMessage.success('创建成功')
    createDialogVisible.value = false
    fetchUsers()
  } catch (error) {
    ElMessage.warning('创建失败，请重试')
  }
}

const handleDisable = async (user: User) => {
  try {
    await ElMessageBox.confirm('确认禁用该用户？', '提示', { type: 'warning' })
    await adminApi.disableUser(user.id)
    ElMessage.success('已禁用')
    fetchUsers()
  } catch (e) {}
}

const handleEnable = async (user: User) => {
  try {
    await adminApi.enableUser(user.id)
    ElMessage.success('已启用')
    fetchUsers()
  } catch (e) {
    ElMessage.warning('操作失败')
  }
}

const handleDelete = async (user: User) => {
  try {
    await ElMessageBox.confirm(`确认删除用户「${user.username}」？删除后不可恢复。`, '警告', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消'
    })
    await adminApi.deleteUser(user.id)
    ElMessage.success('已删除')
    fetchUsers()
  } catch (e) {}
}

onMounted(() => {
  fetchUsers()
})
</script>

<style scoped>
.page-actions {
  margin-bottom: 16px;
}
</style>
