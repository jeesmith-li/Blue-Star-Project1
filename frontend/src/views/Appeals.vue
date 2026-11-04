<template>
  <div class="page-container">
    <el-tabs v-model="activeTab">
      <!-- ============ Tab 1: 申诉查询 ============ -->
      <el-tab-pane label="申诉查询" name="query">
        <div class="page-actions">
          <el-input v-model="qShop" placeholder="门店名称" clearable style="width:180px;" @keyup.enter="fetchQuery" />
          <el-select v-model="qStatus" placeholder="申诉状态" clearable style="width:140px;margin-left:10px;">
            <el-option label="待申诉" value="pending" />
            <el-option label="审核中" value="submitted" />
            <el-option label="已通过" value="approved" />
            <el-option label="已驳回" value="rejected" />
          </el-select>
          <el-button type="primary" style="margin-left:10px;" @click="fetchQuery" :loading="qLoading">查询</el-button>
          <el-button type="warning" @click="handleAutoRun" :loading="autoRunning">自动申诉(按店铺设定)</el-button>
          <span class="muted">共 {{ qTotal }} 条</span>
        </div>

        <el-table :data="qList" style="width:100%;" size="small" border>
          <el-table-column label="序号" width="60">
            <template #default="{ $index }">{{ (qPage - 1) * qPageSize + $index + 1 }}</template>
          </el-table-column>
          <el-table-column prop="shopName" label="店名" min-width="150" show-overflow-tooltip />
          <el-table-column prop="reviewer" label="评价人" width="110" show-overflow-tooltip />
          <el-table-column label="星级" width="70">
            <template #default="{ row }">
              <el-tag size="small" type="danger">{{ row.rating }}星</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="content" label="内容" min-width="180" show-overflow-tooltip />
          <el-table-column label="评价时间" width="160">
            <template #default="{ row }">{{ fmt(row.reviewTime) }}</template>
          </el-table-column>
          <el-table-column label="申诉时间" width="160">
            <template #default="{ row }">{{ fmt(row.appealTime) }}</template>
          </el-table-column>
          <el-table-column label="申诉状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(row.appealStatus)">{{ statusText(row.appealStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="申诉模板" width="170" show-overflow-tooltip>
            <template #default="{ row }">
              <el-link v-if="row.appealTemplate" type="primary" style="font-size:12px;"
                @click="goTemplate(row.appealTemplate)">{{ tplName(row.appealTemplate) }}</el-link>
              <span v-else style="color:#909399;">手动</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.appealStatus === 'submitted'"
                size="small" type="primary" plain
                :loading="checkingId === row.id"
                @click="handleCheckStatus(row)">检测状态</el-button>
              <span v-else class="muted">{{ row.appealResultNote || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="qPage"
            :page-size="qPageSize"
            :total="qTotal"
            layout="prev, pager, next, total"
            @current-change="fetchQuery" />
        </div>
      </el-tab-pane>

      <!-- ============ Tab 2: 待申诉处理（原有流程） ============ -->
      <el-tab-pane label="待申诉处理" name="pending">
        <div class="page-actions">
          <el-button type="primary" @click="handleScan" :loading="scanning">
            <el-icon><Search /></el-icon>
            扫描待申诉评价
          </el-button>
          <span style="margin-left:16px;color:#909399;font-size:13px;">
            共 {{ list.length }} 条待申诉
          </span>
        </div>

        <div class="card" v-for="item in list" :key="item.id" style="margin-bottom:16px;">
          <div class="appeal-head">
            <span class="shop">{{ item.shopName }}</span>
            <el-tag size="small" type="danger">{{ item.rating }}星</el-tag>
            <span class="reviewer">评价人：{{ item.reviewer || '匿名' }}</span>
            <el-tag size="small" type="warning" effect="plain">待申诉</el-tag>
          </div>
          <div class="appeal-body">
            <div class="row"><b>评价内容：</b>{{ item.content }}</div>
            <div class="row"><b>已回复：</b>{{ item.replyContent }}</div>
          </div>

          <div class="appeal-action">
            <div class="left">
              <el-select v-model="tplSel[item.id]" placeholder="选择申诉模板(可选)" clearable style="width:220px;" @change="(v:any)=>onPickTpl(item, v)">
                <el-option v-for="t in appealTpls" :key="t.id" :label="t.name" :value="t.id" />
              </el-select>
              <el-upload
                :auto-upload="false" :show-file-list="true" :file-list="fileLists[item.id] || []"
                :on-change="(f:any)=>onUpload(item, f)" :on-remove="(f:any)=>onRemoveImg(item, f)"
                accept="image/*" multiple style="display:inline-block;margin-left:12px;">
                <el-button size="small" :icon="Upload">上传凭证图(可选)</el-button>
              </el-upload>
              <span v-if="imagesOf(item)" class="img-count">已传 {{ imagesOf(item).split(',').filter(Boolean).length }} 张</span>
            </div>
            <el-button type="primary" size="small" @click="handleSubmit(item)" :loading="submittingId===item.id">
              发起申诉
            </el-button>
          </div>
        </div>

        <div v-if="list.length === 0" class="empty">暂无待申诉评价，点击「扫描待申诉评价」从已回复差评中筛选</div>
      </el-tab-pane>

      <!-- ============ Tab 3: 负反馈 ============ -->
      <el-tab-pane label="负反馈" name="negative">
        <div class="page-actions">
          <el-input v-model="nShop" placeholder="门店名称" clearable style="width:180px;" @keyup.enter="fetchNegative" />
          <el-select v-model="nStatus" placeholder="状态" clearable style="width:130px;margin-left:10px;">
            <el-option label="待处理" value="pending" />
            <el-option label="已通过" value="success" />
            <el-option label="已失败" value="fail" />
          </el-select>
          <el-button style="margin-left:10px;" @click="fetchNegative">查询</el-button>
          <el-button type="primary" :loading="nSyncing" @click="handleSyncNegative">同步负反馈</el-button>
          <span class="muted">共 {{ nTotal }} 条</span>
        </div>

        <el-table :data="nList" style="width:100%;" size="small" border>
          <el-table-column label="序号" width="60">
            <template #default="{ $index }">{{ (nPage - 1) * nPageSize + $index + 1 }}</template>
          </el-table-column>
          <el-table-column prop="shopName" label="店名" min-width="140" show-overflow-tooltip />
          <el-table-column prop="orderNo" label="订单号" width="150" show-overflow-tooltip />
          <el-table-column prop="channelName" label="反馈渠道" width="110" />
          <el-table-column prop="indicatorName" label="问题分类" min-width="130" show-overflow-tooltip />
          <el-table-column prop="content" label="内容" min-width="180" show-overflow-tooltip />
          <el-table-column label="反馈时间" width="160">
            <template #default="{ row }">{{ fmt(row.feedbackTime) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="negTag(row.status)">{{ negText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'pending'" size="small" type="success" plain
                @click="handleNegStatus(row, 'success')">标记成功</el-button>
              <el-button v-else size="small" plain @click="handleNegStatus(row, 'pending')">重置</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="nPage"
            :page-size="nPageSize"
            :total="nTotal"
            layout="prev, pager, next, total"
            @current-change="fetchNegative" />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { alertIfShopNotFound } from '../utils/shopError'
import { confirmAction } from '../utils/confirmAction'
import { Search, Upload } from '@element-plus/icons-vue'
import { appealsApi, negativeApi, type PendingAppeal, type AppealRecord, type NegativeFeedback } from '../api/appeals'
import { templatesApi } from '../api/templates'
import { uploadApi } from '../api/upload'
import type { ReplyTemplate } from '../types/template'

const router = useRouter()

// 申诉模板标记解析与跳转：标记格式 "模板名(命中:关键词)"，"通用话术" 无对应模板不跳转
const tplName = (mark?: string) => (mark || '').split('(命中')[0].trim()
const goTemplate = (mark: string) => {
  const name = tplName(mark)
  if (name && name !== '通用话术') {
    router.push({ path: '/templates', query: { highlight: name, type: 'appeal' } })
  }
}

const activeTab = ref('query')

// ============ Tab1 申诉查询 ============
const qList = ref<AppealRecord[]>([])
const qTotal = ref(0)
const qPage = ref(1)
const qPageSize = 20
const qShop = ref('')
const qStatus = ref('')
const qLoading = ref(false)
const checkingId = ref<number | null>(null)
const autoRunning = ref(false)

const fetchQuery = async () => {
  qLoading.value = true
  try {
    const res = await appealsApi.query({
      shopName: qShop.value, status: qStatus.value,
      page: qPage.value, pageSize: qPageSize
    })
    qList.value = res.items || []
    qTotal.value = res.total || 0
  } catch { qList.value = [] }
  finally { qLoading.value = false }
}

const statusText = (s: string) => ({
  pending: '待申诉', submitted: '审核中', approved: '已通过', rejected: '已驳回'
} as Record<string, string>)[s] || s
const statusTag = (s: string) => ({
  pending: 'warning', submitted: 'primary', approved: 'success', rejected: 'danger'
} as Record<string, any>)[s] || 'info'

const handleCheckStatus = async (row: AppealRecord) => {
  const ok = await confirmAction(
    '检测申诉状态',
    `即将打开美团申诉记录页，回查店铺「${row.shopName}」该条申诉的审核结果；过程中会自动切换到该店铺，请勿操作美团浏览器窗口。`
  )
  if (!ok) return
  checkingId.value = row.id
  try {
    const res = await appealsApi.checkStatus(row.id)
    if (res.success) ElMessage.success(res.message)
    else ElMessage.warning(res.message)
    fetchQuery()
  } catch (e: any) { if (!alertIfShopNotFound(e)) ElMessage.error(e?.message || '检测失败') }
  finally { checkingId.value = null }
}

const handleAutoRun = async () => {
  const ok = await confirmAction(
    '自动申诉',
    '即将对所有已开启「自动申诉」的店铺按执行顺序逐店提交差评申诉：\n仅处理已回复的差评，且评价内容命中申诉模板关键词；未命中的评价将留待人工处理。\n执行期间会自动切换店铺并打开美团申诉页面，请勿操作浏览器窗口，提交后不可撤回。'
  )
  if (!ok) return
  autoRunning.value = true
  try {
    const res = await appealsApi.autoRun()
    ElMessage.success(res.message || '自动申诉完成')
    fetchQuery()
  } catch (e: any) { if (!alertIfShopNotFound(e)) ElMessage.error(e?.message || '自动申诉失败') }
  finally { autoRunning.value = false }
}

// ============ Tab2 待申诉处理（原有） ============
const list = ref<PendingAppeal[]>([])
const appealTpls = ref<ReplyTemplate[]>([])
const scanning = ref(false)
const submittingId = ref<number | null>(null)
const tplSel = reactive<Record<number, number>>({})
const imgMap = reactive<Record<number, string[]>>({})
const fileLists = reactive<Record<number, any[]>>({})

const imagesOf = (item: PendingAppeal) => (imgMap[item.id] || []).join(',')

const fetchList = async () => {
  try { list.value = await appealsApi.pendingList() } catch { list.value = [] }
}

const fetchTpls = async () => {
  try {
    const data: any = await templatesApi.getTemplates()
    const all = Array.isArray(data) ? data : (data?.data || [])
    appealTpls.value = all.filter((t: any) => (t.type || 'reply') === 'appeal')
  } catch { appealTpls.value = [] }
}

const handleScan = async () => {
  const ok = await confirmAction(
    '扫描待申诉评价',
    '即将扫描系统内「已回复」的差评，并按申诉模板规则生成待申诉列表。\n此操作只读取系统内数据，不会打开或操作美团页面。'
  )
  if (!ok) return
  scanning.value = true
  try {
    const res = await appealsApi.scan()
    ElMessage.success(res.message || `扫描完成，新增 ${res.flagged} 条`)
    fetchList()
  } catch (e: any) { ElMessage.error(e?.message || '扫描失败') }
  finally { scanning.value = false }
}

const onPickTpl = async (item: PendingAppeal, tplId: number | undefined) => {
  if (!tplId) return
  const tpl = appealTpls.value.find(t => t.id === tplId)
  if (!tpl) return
  try {
    await appealsApi.prepare(item.id, { templateId: tplId, content: tpl.content })
    if (tpl.images) {
      imgMap[item.id] = tpl.images.split(',').filter(Boolean)
      fileLists[item.id] = imgMap[item.id].map((u, i) => ({ name: `img${i}`, url: u }))
    }
    ElMessage.success('已套用申诉模板')
  } catch (e: any) { ElMessage.error(e?.message || '套用失败') }
}

const onUpload = async (item: PendingAppeal, file: any) => {
  if (!file.raw) return
  try {
    const res = await uploadApi.uploadImage(file.raw)
    if (res.success) {
      if (!imgMap[item.id]) imgMap[item.id] = []
      imgMap[item.id].push(res.url)
      fileLists[item.id] = [...(fileLists[item.id] || []), { name: file.name, url: res.url }]
      await appealsApi.prepare(item.id, { images: imagesOf(item) })
      ElMessage.success('图片已关联')
    } else ElMessage.error('上传失败')
  } catch { ElMessage.error('上传失败') }
}

const onRemoveImg = (item: PendingAppeal, file: any) => {
  const arr = imgMap[item.id] || []
  const i = arr.indexOf(file.url)
  if (i >= 0) arr.splice(i, 1)
  appealsApi.prepare(item.id, { images: imagesOf(item) }).catch(() => {})
}

const handleSubmit = async (item: PendingAppeal) => {
  const imgs = imagesOf(item)
  const ok = await confirmAction(
    '发起申诉',
    `即将对店铺「${item.shopName}」该条差评发起申诉（评价人：${item.reviewer || '匿名'}）。\n系统会自动切换到该店铺、打开美团申诉页面并提交理由${imgs ? '及凭证图' : ''}，提交后无法撤回。`
  )
  if (!ok) return
  submittingId.value = item.id
  try {
    const payload: any = {}
    if (imgs) payload.images = imgs
    const res: any = await appealsApi.submit(item.id, payload)
    if (res.success) {
      ElMessage.success(res.message || '申诉已提交')
      fetchList()
    } else {
      ElMessage.error(res.message || '申诉失败')
    }
  } catch (e: any) {
    if (!alertIfShopNotFound(e)) ElMessage.error(e?.message || '申诉失败')
  } finally { submittingId.value = null }
}

// ============ Tab3 负反馈 ============
const nList = ref<NegativeFeedback[]>([])
const nTotal = ref(0)
const nPage = ref(1)
const nPageSize = 20
const nShop = ref('')
const nStatus = ref('')
const nSyncing = ref(false)

const fetchNegative = async () => {
  try {
    const res = await negativeApi.list({
      shopName: nShop.value, status: nStatus.value,
      page: nPage.value, pageSize: nPageSize
    })
    nList.value = res.items || []
    nTotal.value = res.total || 0
  } catch { nList.value = [] }
}

const negText = (s: string) => ({ pending: '待处理', success: '已通过', fail: '已失败' } as Record<string, string>)[s] || s
const negTag = (s: string) => ({ pending: 'warning', success: 'success', fail: 'danger' } as Record<string, any>)[s] || 'info'

const handleSyncNegative = async () => {
  const ok = await confirmAction(
    '同步负反馈',
    '即将从美团同步顾客负反馈数据：系统会打开美团页面并按店铺切换读取反馈列表，过程中请勿操作美团浏览器窗口。'
  )
  if (!ok) return
  nSyncing.value = true
  try {
    const res = await negativeApi.sync({ shopName: nShop.value })
    if (res.success) ElMessage.success(res.message)
    else ElMessage.warning(res.message)
    fetchNegative()
  } catch (e: any) { ElMessage.error(e?.message || '同步失败') }
  finally { nSyncing.value = false }
}

const handleNegStatus = async (row: NegativeFeedback, status: string) => {
  try {
    const res = await negativeApi.setStatus(row.id, status)
    if (res.success) { ElMessage.success(res.message); fetchNegative() }
    else ElMessage.error(res.message)
  } catch (e: any) { ElMessage.error(e?.message || '更新失败') }
}

// ============ 通用 ============
const fmt = (s?: string) => (s ? String(s).replace('T', ' ').slice(0, 19) : '-')

watch(activeTab, (t) => {
  if (t === 'query') fetchQuery()
  else if (t === 'pending') fetchList()
  else if (t === 'negative') fetchNegative()
})

onMounted(() => {
  fetchQuery()
  fetchList()
  fetchTpls()
})
</script>

<style scoped>
.page-actions { margin-bottom: 16px; display: flex; align-items: center; }
.appeal-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.shop { font-weight: 600; }
.reviewer { color: #606266; font-size: 13px; }
.appeal-body { background: #fafafa; padding: 10px 14px; border-radius: 6px; margin-bottom: 12px; }
.row { margin: 4px 0; font-size: 13px; line-height: 1.6; }
.appeal-action { display: flex; justify-content: space-between; align-items: center; }
.left { display: flex; align-items: center; }
.img-count { margin-left: 12px; color: #67c23a; font-size: 12px; }
.empty { text-align: center; padding: 60px; color: #909399; }
.muted { margin-left: 16px; color: #909399; font-size: 13px; }
.pager { margin-top: 12px; display: flex; justify-content: flex-end; }
</style>
