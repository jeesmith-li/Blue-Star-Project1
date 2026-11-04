import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { AutomationStatus, AutomationLog } from '../types/automation'
import { automationApi } from '../api/automation'

export const useAutomationStore = defineStore('automation', () => {
  const status = ref<AutomationStatus | null>(null)
  const logs = ref<AutomationLog[]>([])

  const fetchStatus = async () => {
    status.value = await automationApi.getStatus()
  }

  const fetchLogs = async () => {
    logs.value = await automationApi.getLogs()
  }

  const stopAutomation = async () => {
    await automationApi.stopAutomation()
    await fetchStatus()
  }

  return {
    status,
    logs,
    fetchStatus,
    fetchLogs,
    stopAutomation
  }
})
