<template>
  <div class="page-container">
    <div class="page-actions">
      <el-button type="primary" @click="openBindDialog">
        <el-icon><Plus /></el-icon>
        添加店铺
      </el-button>
      <el-button type="danger" plain :disabled="shops.length === 0 || deleting" :loading="deleting" @click="deleteStores([...shops], '全部店铺')">
        删除全部店铺
      </el-button>
    </div>

    <div class="card">
      <el-table :data="accountGroups" style="width: 100%" row-key="account">
        <el-table-column label="美团账号" min-width="200">
          <template #default="{ row }">
            <div class="account-cell">
              <el-icon color="#409EFF"><Avatar /></el-icon>
              <span class="account-name">{{ row.account }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="店铺类型" width="120">
          <template #default="{ row }">
            <el-tag :type="row.shopType === '服务商用户' ? 'warning' : 'success'" effect="light">
              {{ row.shopType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="店铺数量" width="100">
          <template #default="{ row }">
            <span class="count-num">{{ row.shops.length }}</span> 家
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default>
            <el-tag type="success" effect="plain">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后同步" width="170">
          <template #default="{ row }">{{ row.lastSyncTime || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(cmd: any) => handleViewReviews(row, cmd)">
              <el-button text type="primary" size="small">
                查看差评（账户下共{{ row.shops.length }}个店铺）<el-icon><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="__all__">显示全部店铺</el-dropdown-item>
                  <el-dropdown-item v-for="s in row.shops" :key="s.id" :command="s.id">
                    {{ s.name }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-dropdown trigger="click" @command="(cmd: any) => openAppealDialog(cmd)">
              <el-button text type="warning" size="small">
                自动化设定<el-icon><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="s in row.shops" :key="s.id" :command="s.id">
                    {{ s.name }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-dropdown trigger="click" :disabled="deleting" @command="(cmd: number | string) => handleDeleteCommand(row, cmd)">
              <el-button text type="danger" size="small" :disabled="deleting">
                删除店铺<el-icon><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="__all__">删除此账号全部店铺（{{ row.shops.length }}家）</el-dropdown-item>
                  <el-dropdown-item v-for="s in row.shops" :key="s.id" :command="s.id" divided>
                    删除：{{ s.name }}（店铺ID: {{ s.poiId || '未获取' }}）
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="accountGroups.length === 0" style="text-align: center; padding: 40px; color: #909399;">
        暂无店铺，点击右上角"添加店铺"开始绑定
      </div>
    </div>

    <!-- 添加店铺 - 登录绑定对话框 -->
    <el-dialog v-model="dialogVisible" title="添加店铺 - 登录美团商家后台" width="600px" :close-on-click-modal="false" @close="handleDialogClose">
      <!-- 步骤1：启动浏览器 -->
      <div v-if="step === 'start'" style="text-align: center; padding: 30px 0;">
        <el-icon :size="48" color="#409EFF"><Monitor /></el-icon>
        <p style="margin: 16px 0; color: #606266;">点击下方按钮，系统将打开浏览器并跳转至美团商家登录页</p>
        <p style="margin: 8px 0; color: #909399; font-size: 13px;">请在弹出的浏览器中手动登录你的美团商家账号</p>
        <el-button type="primary" size="large" :loading="starting" @click="handleStartLogin" style="margin-top: 16px;">
          启动浏览器登录
        </el-button>
      </div>

      <!-- 步骤2：等待登录 -->
      <div v-if="step === 'waiting'" style="text-align: center; padding: 30px 0;">
        <el-icon class="is-loading" :size="40" color="#E6A23C"><Loading /></el-icon>
        <p style="margin: 16px 0; color: #606266;">浏览器已打开，请在浏览器中完成美团登录</p>
        <p style="margin: 8px 0; color: #909399; font-size: 13px;">登录成功后将自动获取你的店铺列表</p>
        <el-button @click="handleCancel" style="margin-top: 12px;">取消</el-button>
      </div>

      <!-- 步骤3：选择店铺 -->
      <div v-if="step === 'select'">
        <div class="account-banner" v-if="scrapedAccount">
          <el-icon color="#409EFF"><Avatar /></el-icon>
          <span>美团账号：<b>{{ scrapedAccount }}</b></span>
          <el-tag size="small" :type="scrapedShops.length >= 2 ? 'warning' : 'success'" style="margin-left: 12px;">
            {{ scrapedShops.length >= 2 ? '服务商用户' : '美团单店' }}
          </el-tag>
        </div>
        <p style="margin: 12px 0; color: #606266;">
          登录成功！账户下共 <b>{{ scrapedShops.length }}</b> 个店铺，请选择要绑定的店铺：
        </p>
        <el-table :data="scrapedShops" max-height="300" size="small" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="50" />
          <el-table-column prop="poiId" label="美团店铺ID" width="130" />
          <el-table-column prop="name" label="店铺名称" />
          <el-table-column prop="businessStatus" label="营业状态" width="100" />
        </el-table>
        <div v-if="scrapedShops.length === 0" style="text-align: center; padding: 20px; color: #909399;">
          未获取到店铺，请确认已登录正确的美团商家账号
        </div>
      </div>

      <template #footer>
        <el-button @click="handleCancel">取消</el-button>
        <el-button
          v-if="step === 'select'"
          type="primary"
          :disabled="selectedShops.length === 0"
          :loading="binding"
          @click="handleBind">
          确认绑定 ({{ selectedShops.length }})
        </el-button>
      </template>
    </el-dialog>

    <!-- 自动化设定对话框（自动申诉 + 自动回复） -->
    <el-dialog v-model="appealDialogVisible" :title="`自动化设定 - ${appealShopName}`" width="440px">
      <div class="appeal-setting-row">
        <span>自动回复评价</span>
        <el-switch v-model="appealForm.autoReplyEnabled" active-text="开" inactive-text="关" />
      </div>
      <p class="appeal-tip" style="margin-top:0;">开启后，每日自动抓取和「启动自动化」时，系统将按好/中/差评档位模板自动回复未回复评价（模板在"模板管理"中配置）。</p>
      <el-divider style="margin:12px 0;" />
      <div class="appeal-setting-row">
        <span>自动申诉</span>
        <el-switch v-model="appealForm.appealAutoEnabled" active-text="开" inactive-text="关" />
      </div>
      <div class="appeal-setting-row">
        <span>仅申诉星级</span>
        <el-checkbox-group v-model="appealStarsArr">
          <el-checkbox :value="1">1星</el-checkbox>
          <el-checkbox :value="2">2星</el-checkbox>
        </el-checkbox-group>
      </div>
      <div class="appeal-setting-row">
        <span>申诉范围</span>
        <el-radio-group v-model="appealForm.appealRange">
          <el-radio value="today">今天的评价</el-radio>
          <el-radio value="yesterday">昨天的评价</el-radio>
        </el-radio-group>
      </div>
      <p class="appeal-tip">开启后，点击申诉管理页的「自动申诉」按钮时，系统将按以上规则筛选已回复差评并自动提交申诉（无需上传凭证图）。</p>
      <template #footer>
        <el-button @click="appealDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="appealSaving" @click="saveAppealSettings">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Monitor, Loading, Avatar, ArrowDown } from '@element-plus/icons-vue'
import { shopsApi } from '../api/shops'
import { browserApi } from '../api/browser'
import type { Shop } from '../types/shop'
import type { ScrapedShop } from '../api/browser'

const router = useRouter()
const shops = ref<Shop[]>([])
const dialogVisible = ref(false)

const step = ref<'start' | 'waiting' | 'select'>('start')
const starting = ref(false)
const binding = ref(false)
const scrapedShops = ref<ScrapedShop[]>([])
const scrapedAccount = ref('')
const selectedShops = ref<ScrapedShop[]>([])

let pollTimer: ReturnType<typeof setInterval> | null = null

// 按美团账号分组
interface AccountGroup {
  account: string
  shopType: string
  shops: Shop[]
  lastSyncTime?: string
}
const accountGroups = computed<AccountGroup[]>(() => {
  const map = new Map<string, AccountGroup>()
  for (const s of shops.value) {
    const key = s.meituanAccount || '未知账号'
    if (!map.has(key)) {
      map.set(key, {
        account: key,
        shopType: s.shopType || '美团单店',
        shops: [],
        lastSyncTime: s.lastSyncTime
      })
    }
    map.get(key)!.shops.push(s)
  }
  // 同组店铺数>=2 标记为服务商用户
  for (const g of map.values()) {
    if (g.shops.length >= 2 && g.shopType !== '服务商用户') g.shopType = '服务商用户'
  }
  return Array.from(map.values())
})

const fetchShops = async () => {
  try {
    shops.value = await shopsApi.getShops()
  } catch {
    shops.value = []
  }
}

// ============ 自动申诉设定 ============
const appealDialogVisible = ref(false)
const appealSaving = ref(false)
const appealShopId = ref<number | null>(null)
const appealShopName = ref('')
const appealForm = reactive({ appealAutoEnabled: false, appealRange: 'today', autoReplyEnabled: false })
const appealStarsArr = ref<number[]>([1, 2])

const openAppealDialog = (shopId: number) => {
  const shop = shops.value.find(s => s.id === shopId)
  if (!shop) return
  appealShopId.value = shopId
  appealShopName.value = shop.name
  const anyShop: any = shop as any
  appealForm.appealAutoEnabled = !!anyShop.appealAutoEnabled
  appealForm.appealRange = anyShop.appealRange || 'today'
  appealForm.autoReplyEnabled = !!anyShop.autoReplyEnabled
  const stars = String(anyShop.appealStars || '1,2')
  appealStarsArr.value = stars.split(',').map(Number).filter(n => n === 1 || n === 2)
  appealDialogVisible.value = true
}

const saveAppealSettings = async () => {
  if (!appealShopId.value) return
  if (appealStarsArr.value.length === 0) {
    ElMessage.warning('请至少选择一个星级')
    return
  }
  appealSaving.value = true
  try {
    await shopsApi.updateAppealSettings(appealShopId.value, {
      appealAutoEnabled: appealForm.appealAutoEnabled,
      appealStars: appealStarsArr.value.sort().join(','),
      appealRange: appealForm.appealRange
    })
    await shopsApi.updateReplySettings(appealShopId.value, {
      autoReplyEnabled: appealForm.autoReplyEnabled
    })
    ElMessage.success('自动化设定已保存')
    appealDialogVisible.value = false
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    appealSaving.value = false
  }
}

const openBindDialog = () => {
  step.value = 'start'
  scrapedShops.value = []
  scrapedAccount.value = ''
  selectedShops.value = []
  dialogVisible.value = true
}

const handleStartLogin = async () => {
  starting.value = true
  try {
    await browserApi.startLogin()
    step.value = 'waiting'
    startPolling()
  } catch (e: any) {
    ElMessage.error(e?.message || '启动浏览器失败')
  } finally {
    starting.value = false
  }
}

const startPolling = () => {
  pollTimer = setInterval(async () => {
    try {
      const res = await browserApi.checkLoginStatus()
      if (res.loggedIn) {
        stopPolling()
        await fetchScrapedShops()
      }
    } catch {
      // ignore poll errors
    }
  }, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const fetchScrapedShops = async () => {
  try {
    const res = await browserApi.scrapeShops()
    if (res.success) {
      scrapedShops.value = (res.shops || []).filter(s => ['营业中', '休息中'].includes(s.businessStatus) && /^\d+$/.test(s.poiId))
      scrapedAccount.value = res.account || ''
      step.value = 'select'
      if (scrapedShops.value.length === 0) {
        ElMessage.warning('未获取到店铺数据，你也可以取消后手动添加')
      }
    } else {
      step.value = 'start'
      ElMessage.error(res.message || '获取店铺列表失败')
    }
  } catch (e: any) {
    step.value = 'start'
    ElMessage.error(e?.response?.data?.message || e?.message || '获取店铺失败')
  }
}

const handleSelectionChange = (rows: ScrapedShop[]) => {
  selectedShops.value = rows
}

const handleBind = async () => {
  binding.value = true
  try {
    const result: any = await browserApi.bindShop({
      meituanAccount: scrapedAccount.value || '美团账号',
      shops: selectedShops.value.map(s => ({ name: s.name, poiId: s.poiId, businessStatus: s.businessStatus }))
    })
    if (!result.success) {
      ElMessage.error(result.message || '绑定失败，未写入店铺')
      return
    }
    if (result.data?.failed > 0) {
      ElMessage.warning(result.message)
    } else {
      ElMessage.success(result.message || `成功绑定 ${result.data?.bound || 0} 个店铺`)
      dialogVisible.value = false
    }
    await fetchShops()
  } catch (e: any) {
    ElMessage.error(e?.message || '绑定失败')
  } finally {
    binding.value = false
  }
}

const handleCancel = () => {
  stopPolling()
  browserApi.close().catch(() => {})
  dialogVisible.value = false
}

const handleDialogClose = () => {
  stopPolling()
  browserApi.close().catch(() => {})
}

// 查看差评：cmd 为店铺id 或 '__all__'
const handleViewReviews = (group: AccountGroup, cmd: any) => {
  if (cmd === '__all__') {
    // 全部店铺：不传 shopId，差评页显示该账号所有店
    router.push({ path: '/reviews', query: { account: group.account } })
  } else {
    router.push({ path: '/reviews', query: { shopId: cmd } })
  }
}

const deleting = ref(false)

const handleDeleteCommand = (group: AccountGroup, command: number | string) => {
  const targets = command === '__all__' ? [...group.shops] : group.shops.filter(s => s.id === Number(command))
  if (targets.length) void deleteStores(targets, command === '__all__' ? `账号「${group.account}」下全部店铺` : `店铺「${targets[0].name}」`)
}

const deleteStores = async (targets: Shop[], label: string) => {
  if (deleting.value || targets.length === 0) return
  deleting.value = true
  try {
    await ElMessageBox.confirm(
      `确定删除${label}，共 ${targets.length} 家吗？此操作不可撤销。有历史评价时，可验证当前登录用户密码后强制删除。`,
      '删除确认', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
    let deleted = 0
    const failures: string[] = []
    for (const shop of targets) {
      try {
        await shopsApi.deleteShop(shop.id)
        shops.value = shops.value.filter(s => s.id !== shop.id)
        deleted++
      } catch (error: any) {
        if (error?.response?.status === 400 && error?.response?.data?.message?.includes('历史评价')) {
          try {
            const { value: password } = await ElMessageBox.prompt(
              `「${shop.name}」存在历史评价。强制删除将永久删除店铺及关联评价、申诉和反馈。请输入当前登录用户的密码验证：`,
              '强制删除验证', {
                inputType: 'password', inputValidator: value => !!value?.trim() || '请输入密码',
                confirmButtonText: '验证并强制删除', cancelButtonText: '保留店铺',
                closeOnClickModal: false
              }
            )
            await shopsApi.forceDeleteShop(shop.id, password)
            shops.value = shops.value.filter(s => s.id !== shop.id)
            deleted++
            continue
          } catch (forceError: any) {
            failures.push(`${shop.name}：${forceError === 'cancel' || forceError === 'close' ? '已保留' : forceError?.response?.data?.message || '强制删除失败'}`)
            continue
          }
        }
        failures.push(`${shop.name}（ID: ${shop.poiId || '未获取'}）：${error?.response?.data?.message || error?.message || '删除失败'}`)
      }
    }
    if (failures.length) {
      await ElMessageBox.alert(`成功删除 ${deleted} 家，失败 ${failures.length} 家。\n${failures.join('\n')}`, '删除结果', { type: 'warning' })
    } else {
      ElMessage.success(`成功删除 ${deleted} 家店铺`)
    }
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error?.message || '删除失败')
  } finally {
    deleting.value = false
  }
}

onMounted(() => { fetchShops() })
onUnmounted(() => { stopPolling() })
</script>

<style scoped>
.page-actions { margin-bottom: 16px; }
.account-cell { display: flex; align-items: center; gap: 8px; }
.account-name { font-weight: 600; color: #303133; }
.count-num { font-weight: 600; color: #409EFF; }
.account-banner {
  display: flex; align-items: center; gap: 6px;
  background: #ecf5ff; padding: 10px 14px; border-radius: 6px; color: #409EFF;
}
.appeal-setting-row {
  display: flex; align-items: center; margin-bottom: 18px; gap: 12px;
}
.appeal-setting-row > span:first-child { width: 80px; color: #606266; }
.appeal-tip { color: #909399; font-size: 12px; line-height: 1.6; margin: 8px 0 0; }
</style>
