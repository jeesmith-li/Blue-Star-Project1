import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '../types/user'
import { UserRole, UserType } from '../types/user'
import { authApi } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const token = ref<string | null>(localStorage.getItem('token'))

  const isAdmin = computed(() => user.value?.role === UserRole.ADMIN)
  const isUser = computed(() => user.value?.role === UserRole.USER)
  const isServiceProvider = computed(() => user.value?.userType === UserType.SERVICE_PROVIDER)
  const isNormalUser = computed(() => user.value?.userType === UserType.NORMAL_USER)

  const login = async (username: string, password: string) => {
    const response = await authApi.login({ username, password })
    token.value = response.token
    user.value = response.user
    localStorage.setItem('token', response.token)
  }

  const logout = () => {
    user.value = null
    token.value = null
    localStorage.removeItem('token')
  }

  const fetchCurrentUser = async () => {
    if (token.value) {
      try {
        user.value = await authApi.getCurrentUser()
      } catch (error) {
        // Silent fail for getCurrentUser
        console.error('Failed to fetch current user:', error)
      }
    }
  }

  const hasRole = (role: UserRole): boolean => {
    return user.value?.role === role
  }

  const hasPermission = (_permission: string): boolean => {
    // Can be extended with more complex permission logic
    if (isAdmin.value) return true
    return false
  }

  return {
    user,
    token,
    isAdmin,
    isUser,
    isServiceProvider,
    isNormalUser,
    login,
    logout,
    fetchCurrentUser,
    hasRole,
    hasPermission
  }
})
