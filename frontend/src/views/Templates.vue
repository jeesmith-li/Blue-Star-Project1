<template>
  <div class="page-container">
    <div class="page-actions">
      <el-radio-group v-model="activeTab" @change="fetchTemplates">
        <el-radio-button value="reply">回复模板</el-radio-button>
        <el-radio-button value="appeal">申诉模板</el-radio-button>
        <el-radio-button value="food_appeal">食安申诉模板</el-radio-button>
      </el-radio-group>
      <el-button type="primary" @click="handleCreate" style="margin-left: 16px;">
        <el-icon><Plus /></el-icon>
        新增{{ tabLabel }}模板
      </el-button>
      <el-button @click="downloadSample">下载导入模板</el-button>
      <el-button type="success" @click="importDialogVisible = true">Excel 批量导入</el-button>
    </div>

    <div class="card">
      <el-table :data="filteredTemplates" style="width: 100%" :row-class-name="rowClassName">
        <el-table-column prop="name" label="模板名称" width="200" />
        <el-table-column prop="content" label="模板内容" show-overflow-tooltip />
        <el-table-column label="触发关键词" width="220">
          <template #default="{ row }">
            <template v-if="row.keywords">
              <el-tag v-for="kw in row.keywords.split(/[,，]/).filter(Boolean)" :key="kw" size="small" style="margin-right:4px;">{{ kw }}</el-tag>
            </template>
            <span v-else style="color:#909399;">无（走兜底）</span>
          </template>
        </el-table-column>
        <el-table-column v-if="activeTab === 'reply'" label="适用档位" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.starLevel === 'good'" type="success">好评(4-5星)</el-tag>
            <el-tag v-else-if="row.starLevel === 'middle'" type="warning">中评(3星)</el-tag>
            <el-tag v-else-if="row.starLevel === 'bad'" type="danger">差评(1-2星)</el-tag>
            <el-tag v-else type="info">通用</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="activeTab === 'appeal'" label="凭证图片" width="120">
          <template #default="{ row }">
            <span v-if="row.images">{{ (row.images.split(',')).length }} 张</span>
            <span v-else style="color:#909399;">无</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button text type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="(isEdit ? '编辑' : '新增') + tabLabel + '模板'" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="模板名称">
          <el-input v-model="form.name" placeholder="请输入模板名称" />
        </el-form-item>
        <el-form-item :label="activeTab === 'reply' ? '模板内容' : '申诉理由'">
          <el-input v-model="form.content" type="textarea" :rows="8"
            :placeholder="activeTab === 'reply' ? '请输入回复模板内容' : '请输入申诉理由内容'" />
        </el-form-item>
        <el-form-item label="触发关键词">
          <el-input v-model="form.keywords" placeholder="多个关键词用逗号分隔，如：难吃,包装撒了,太辣" />
          <div v-if="kwDupName" style="color:#f56c6c;font-size:12px;margin-top:4px;font-weight:bold;">
            ⚠ 关键词与已有模板【{{ kwDupName }}】完全一致，后加的模板可能无法生效
          </div>
          <div style="color:#909399;font-size:12px;margin-top:4px;">
            评价内容包含任一关键词即自动套用本模板，多个模板命中时取命中关键词最多的；
            配置了关键词模板后，未命中关键词的评价将留待人工处理；全部不填则按原有档位/通用逻辑兜底
          </div>
        </el-form-item>
        <el-form-item v-if="activeTab === 'reply'" label="适用档位">
          <el-radio-group v-model="form.starLevel">
            <el-radio value="">通用（兜底）</el-radio>
            <el-radio value="good">好评(4-5星)</el-radio>
            <el-radio value="middle">中评(3星)</el-radio>
            <el-radio value="bad">差评(1-2星)</el-radio>
          </el-radio-group>
          <div style="color:#909399;font-size:12px;margin-top:4px;">
            开启自动回复后，系统按评价星级自动套用对应档位模板；该档位无模板时使用"通用"模板
          </div>
        </el-form-item>
        <el-form-item v-if="activeTab === 'appeal'" label="凭证图片">
          <el-upload
            :auto-upload="false"
            :show-file-list="true"
            list-type="picture-card"
            :file-list="imgFileList"
            :on-change="handleImgChange"
            :on-remove="handleImgRemove"
            accept="image/*"
            multiple>
            <el-icon><Plus /></el-icon>
          </el-upload>
          <div style="color:#909399;font-size:12px;margin-top:4px;">支持 jpg/png 等，申诉时自动作为凭证上传</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importDialogVisible" title="Excel 批量导入模板" width="580px">
      <el-alert type="info" :closable="false" style="margin-bottom: 12px;">
        列顺序：模板名称 | 模板内容 | 类型(回复/申诉/食安申诉) | 触发关键词(逗号分隔) | 星级档位(好评/中评/差评，仅回复模板)。
        首行为表头自动跳过；与已有模板同名的自动跳过防重复；申诉凭证图片请导入后在本页编辑上传。
      </el-alert>
      <el-upload drag :auto-upload="false" :limit="1" accept=".xlsx,.xls"
        :on-change="handleImportChange" :on-remove="handleImportRemove">
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处或 <em>点击选择 Excel 文件</em></div>
        <template #tip>
          <div class="el-upload__tip">仅支持 .xlsx / .xls，可在示例模板基础上填写</div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="downloadSample">下载示例模板</el-button>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!importFile" :loading="importing" @click="handleImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, UploadFilled } from '@element-plus/icons-vue'
import { templatesApi } from '../api/templates'
import { uploadApi } from '../api/upload'
import type { ReplyTemplate } from '../types/template'

const templates = ref<ReplyTemplate[]>([])
const activeTab = ref<'reply' | 'appeal' | 'food_appeal'>('reply')
const tabLabel = computed(() => ({ reply: '回复', appeal: '申诉', food_appeal: '食安申诉' } as Record<string, string>)[activeTab.value] || '回复')
const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref<number | null>(null)
const imgUrls = ref<string[]>([])
const imgFileList = ref<any[]>([])
const importDialogVisible = ref(false)
const importFile = ref<any>(null)
const importing = ref(false)

const form = reactive({ name: '', content: '', starLevel: '', keywords: '' })

// 关键词防呆：与已有模板完全一致时弹窗提醒 + 红色提示 + 保存前二次确认
const kwDupName = ref<string | null>(null)
const kwAlerted = ref<string | null>(null)
let kwCheckTimer: ReturnType<typeof setTimeout> | null = null

const doKeywordCheck = async () => {
  const kws = form.keywords.trim()
  if (!kws) { kwDupName.value = null; kwAlerted.value = null; return }
  try {
    const res: any = await templatesApi.checkKeyWords(kws, activeTab.value, isEdit.value && currentId.value ? currentId.value : undefined)
    if (res?.duplicate) {
      kwDupName.value = res.duplicateName
      // 同一关键词组合只弹一次窗，避免打字过程反复打扰；换关键词组合会重新提醒
      if (kwAlerted.value !== kws) {
        kwAlerted.value = kws
        ElMessageBox.alert(
          `<span style="color:#f56c6c;font-weight:bold;">模板关键词与【${res.duplicateName}】完全一致，可能会导致后加的模板无法生效，请调整关键词或确认后再保存！</span>`,
          '关键词重复提醒',
          { type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
        ).catch(() => {})
      }
    } else {
      kwDupName.value = null
      kwAlerted.value = null
    }
  } catch { kwDupName.value = null }
}

watch(() => form.keywords, () => {
  if (kwCheckTimer) clearTimeout(kwCheckTimer)
  kwCheckTimer = setTimeout(doKeywordCheck, 400)
})

const filteredTemplates = computed(() =>
  templates.value.filter(t => (t.type || 'reply') === activeTab.value)
)

const fetchTemplates = async () => {
  try {
    const data: any = await templatesApi.getTemplates()
    templates.value = Array.isArray(data) ? data : (data?.data || [])
  } catch {
    templates.value = []
  }
}

const resetForm = () => {
  form.name = ''
  form.content = ''
  form.starLevel = ''
  form.keywords = ''
  kwDupName.value = null
  kwAlerted.value = null
  imgUrls.value = []
  imgFileList.value = []
}

const handleCreate = () => {
  isEdit.value = false
  currentId.value = null
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (tpl: ReplyTemplate) => {
  isEdit.value = true
  currentId.value = tpl.id
  form.name = tpl.name
  form.content = tpl.content
  form.starLevel = (tpl as any).starLevel || ''
  form.keywords = (tpl as any).keywords || ''
  kwDupName.value = null
  imgUrls.value = tpl.images ? tpl.images.split(',').filter(Boolean) : []
  imgFileList.value = imgUrls.value.map((u, i) => ({ name: `img${i}`, url: u }))
  dialogVisible.value = true
}

const handleImgChange = async (file: any) => {
  if (!file.raw) return
  try {
    const res = await uploadApi.uploadImage(file.raw)
    if (res.success && res.url) {
      imgUrls.value.push(res.url)
      imgFileList.value.push({ name: file.name, url: res.url })
      ElMessage.success('图片已上传')
    } else {
      ElMessage.error('上传失败')
    }
  } catch {
    ElMessage.error('上传失败')
  }
}

const handleImgRemove = (file: any) => {
  const idx = imgUrls.value.indexOf(file.url)
  if (idx >= 0) imgUrls.value.splice(idx, 1)
}

const handleSave = async () => {
  if (!form.name.trim()) { ElMessage.warning('请输入模板名称'); return }
  if (!form.content.trim()) { ElMessage.warning('请输入模板内容'); return }
  // 关键词完全一致防呆：保存前实时再查一次（覆盖防抖未完成的场景），命中时红色弹窗二次确认
  if (form.keywords.trim()) {
    try {
      const res: any = await templatesApi.checkKeyWords(
        form.keywords.trim(), activeTab.value, isEdit.value && currentId.value ? currentId.value : undefined)
      if (res?.duplicate) {
        kwDupName.value = res.duplicateName
        try {
          await ElMessageBox.confirm(
            `<span style="color:#f56c6c;font-weight:bold;">模板关键词与【${res.duplicateName}】完全一致，可能会导致后加的模板无法生效，请确认是否添加该模板！</span>`,
            '关键词重复提醒',
            { type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '确认添加', cancelButtonText: '取消' }
          )
        } catch { return }
      } else {
        kwDupName.value = null
      }
    } catch { /* 检查接口失败不阻断保存 */ }
  }
  try {
    const payload: any = {
      name: form.name,
      content: form.content,
      type: activeTab.value,
      images: imgUrls.value.join(','),
      keywords: form.keywords.trim(),
      starLevel: activeTab.value === 'reply' ? form.starLevel : undefined
    }
    if (isEdit.value && currentId.value) {
      await templatesApi.updateTemplate(currentId.value, payload)
      ElMessage.success('更新成功')
    } else {
      await templatesApi.createTemplate(payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    fetchTemplates()
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  }
}

const handleDelete = async (tpl: ReplyTemplate) => {
  try {
    await ElMessageBox.confirm('确认删除该模板？', '提示', { type: 'warning' })
    await templatesApi.deleteTemplate(tpl.id)
    ElMessage.success('删除成功')
    fetchTemplates()
  } catch { /* cancel */ }
}

// ==================== Excel 批量导入 ====================

const handleImportChange = (file: any) => {
  importFile.value = file
}

const handleImportRemove = () => {
  importFile.value = null
}

const downloadSample = async () => {
  try {
    const blob = await templatesApi.downloadImportSample()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = '模板批量导入示例.xlsx'
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('下载失败')
  }
}

const handleImport = async () => {
  const raw = importFile.value?.raw as File | undefined
  if (!raw) return
  importing.value = true
  try {
    const res: any = await templatesApi.importExcel(raw)
    if (res?.success) {
      if (res.errors?.length) {
        ElMessageBox.alert(res.errors.join('<br/>'), res.message, {
          dangerouslyUseHTMLString: true, type: 'warning', confirmButtonText: '知道了'
        }).catch(() => {})
      } else {
        ElMessage.success(res.message || '导入成功')
      }
      importDialogVisible.value = false
      importFile.value = null
      fetchTemplates()
    } else {
      ElMessage.error(res?.message || '导入失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '导入失败')
  } finally {
    importing.value = false
  }
}

// ============ 跳转高亮定位（从评价/申诉页点模板链接跳转而来） ============
const route = useRoute()
const highlightName = ref('')
let highlightTimer: ReturnType<typeof setTimeout> | null = null

const rowClassName = ({ row }: any) => (highlightName.value && row.name === highlightName.value ? 'tpl-highlight-row' : '')

onMounted(() => {
  fetchTemplates().then(() => {
    const hl = route.query.highlight as string
    const type = route.query.type as string
    if (!hl) return
    if (type === 'appeal' || type === 'reply' || type === 'food_appeal') activeTab.value = type
    highlightName.value = hl
    nextTick(() => {
      const el = document.querySelector('.tpl-highlight-row')
      el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      if (highlightTimer) clearTimeout(highlightTimer)
      // 高亮动画结束后清除标记，避免之后编辑同名模板仍闪烁
      highlightTimer = setTimeout(() => { highlightName.value = '' }, 6000)
    })
  })
})

onBeforeUnmount(() => { if (highlightTimer) clearTimeout(highlightTimer) })
</script>

<style scoped>
.page-actions { margin-bottom: 16px; display: flex; align-items: center; }

/* 模板跳转高亮：黄色背景闪烁数秒定位目标行 */
:deep(.tpl-highlight-row td) {
  animation: tpl-flash 1s ease-in-out 5;
}
@keyframes tpl-flash {
  0%, 100% { background: transparent; }
  50% { background: #faecd8; }
}
</style>
