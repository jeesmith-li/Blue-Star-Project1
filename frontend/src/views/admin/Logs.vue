<template>
  <div class="page-container">
    <div class="filter-bar">
      <el-input v-model="f.username" placeholder="用户名" clearable style="width:130px;" @keyup.enter="fetch(1)" />
      <el-select v-model="f.level" placeholder="级别" clearable style="width:110px;margin-left:8px;">
        <el-option label="INFO" value="INFO" />
        <el-option label="WARN" value="WARN" />
        <el-option label="ERROR" value="ERROR" />
      </el-select>
      <el-select v-model="f.type" placeholder="类型" clearable style="width:140px;margin-left:8px;">
        <el-option label="用户操作" value="user_action" />
        <el-option label="接口报错" value="api_error" />
        <el-option label="慢请求" value="slow_api" />
        <el-option label="前端报错" value="js_error" />
        <el-option label="自动化执行" value="automation" />
        <el-option label="页面访问" value="page_view" />
      </el-select>
      <el-input v-model="f.keyword" placeholder="关键字(详情/动作)" clearable style="width:170px;margin-left:8px;" @keyup.enter="fetch(1)" />
      <el-date-picker v-model="range" type="datetimerange" start-placeholder="开始" end-placeholder="结束"
        value-format="YYYY-MM-DD HH:mm:ss" style="margin-left:8px;width:340px;" />
      <el-button type="primary" style="margin-left:8px;" @click="fetch(1)">查询</el-button>
      <el-checkbox v-model="onlyProblem" style="margin-left:8px;" @change="fetch(1)">只看问题(ERROR/WARN)</el-checkbox>
      <el-checkbox v-model="autoRefresh" style="margin-left:8px;" @change="toggleAuto">自动刷新</el-checkbox>
      <span class="total">共 {{ total }} 条</span>
    </div>

    <el-table :data="logs" size="small" border style="width:100%;">
      <el-table-column label="时间" width="160">
        <template #default="{ row }">{{ row.ts }}</template>
      </el-table-column>
      <el-table-column label="级别" width="70">
        <template #default="{ row }">
          <el-tag size="small" :type="row.level === 'ERROR' ? 'danger' : row.level === 'WARN' ? 'warning' : 'info'">
            {{ row.level }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="username" label="用户" width="90" />
      <el-table-column label="类型" width="95">
        <template #default="{ row }">{{ typeText(row.type) }}</template>
      </el-table-column>
      <el-table-column prop="action" label="动作" width="120" show-overflow-tooltip />
      <el-table-column label="结果" width="65">
        <template #default="{ row }">
          <el-tag v-if="row.result" size="small" :type="row.result === 'success' ? 'success' : 'danger'">{{ row.result }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="detail" label="详情" min-width="260" show-overflow-tooltip />
      <el-table-column prop="endpoint" label="接口" width="180" show-overflow-tooltip />
      <el-table-column label="耗时" width="75">
        <template #default="{ row }">{{ row.durationMs != null ? row.durationMs + 'ms' : '-' }}</template>
      </el-table-column>
      <el-table-column prop="page" label="页面" width="110" show-overflow-tooltip />
    </el-table>

    <div style="margin-top:12px;display:flex;justify-content:flex-end;">
      <el-pagination v-model:current-page="page" :page-size="pageSize" :total="total"
        layout="prev, pager, next, total" @current-change="fetch()" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import request from '../../api/request'

interface LogRow {
  id: number; ts: string; username: string; level: string; type: string
  action: string; result: string; detail: string; endpoint: string
  durationMs: number | null; page: string
}

const logs = ref<LogRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 50
const f = reactive({ username: '', level: '', type: '', keyword: '' })
const range = ref<[string, string] | null>(null)
const onlyProblem = ref(false)
const autoRefresh = ref(false)
let timer: number | null = null

const typeText = (t: string) => ({
  user_action: '用户操作', api_error: '接口报错', slow_api: '慢请求',
  js_error: '前端报错', automation: '自动化', page_view: '页面访问', system: '系统'
} as Record<string, string>)[t] || t

const fetch = async (p?: number) => {
  if (p) page.value = p
  try {
    const res: any = await request.get('/client-logs', {
      params: {
        username: f.username, level: onlyProblem.value && !f.level ? '' : f.level,
        type: f.type, keyword: f.keyword,
        start: range.value?.[0] || '', end: range.value?.[1] || '',
        page: page.value, pageSize
      }
    })
    let items: LogRow[] = res.items || []
    if (onlyProblem.value) items = items.filter(i => i.level === 'ERROR' || i.level === 'WARN')
    logs.value = items
    total.value = res.total || 0
  } catch {
    logs.value = []
  }
}

const toggleAuto = (v: boolean) => {
  if (timer !== null) { clearInterval(timer); timer = null }
  if (v) timer = window.setInterval(() => fetch(), 10000)
}

onMounted(() => fetch(1))
onUnmounted(() => { if (timer !== null) clearInterval(timer) })
</script>

<style scoped>
.filter-bar { margin-bottom: 12px; display: flex; align-items: center; flex-wrap: wrap; }
.total { margin-left: auto; color: #909399; font-size: 13px; }
</style>
