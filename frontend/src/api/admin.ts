import request from './request'
import type { User, CreateUserRequest, UpdateUserRequest, SetValidityRequest } from '../types/user'

export interface AdminDashboardStats {
  totalUsers: number
  activeUsers: number
  expiredUsers: number
  expiringSoonUsers: number
  serviceProviders: number
  totalShops: number
  todayBadReviews: number
  unrepliedReviews: number
  runningTasks: number
  systemStatus: 'normal' | 'warning' | 'error'
}

export interface SystemLog {
  id: number
  timestamp: string
  username: string
  action: string
  type: string
  result: 'SUCCESS' | 'FAILED'
  ip?: string
}

export const adminApi = {
  // Dashboard
  getDashboard(): Promise<AdminDashboardStats> {
    return request.get('/admin/dashboard')
  },

  // Users
  getUsers(params?: any): Promise<User[]> {
    return request.get('/admin/users', { params })
  },

  getUser(id: number): Promise<User> {
    return request.get(`/admin/users/${id}`)
  },

  createUser(data: CreateUserRequest): Promise<User> {
    return request.post('/admin/users', data)
  },

  updateUser(id: number, data: UpdateUserRequest): Promise<User> {
    return request.put(`/admin/users/${id}`, data)
  },

  setUserValidity(id: number, data: SetValidityRequest): Promise<void> {
    return request.post(`/admin/users/${id}/validity`, data)
  },

  enableUser(id: number): Promise<void> {
    return request.put(`/admin/users/${id}/status`, { status: 'ACTIVE' })
  },

  disableUser(id: number): Promise<void> {
    return request.put(`/admin/users/${id}/status`, { status: 'DISABLED' })
  },

  deleteUser(id: number): Promise<void> {
    return request.delete(`/admin/users/${id}`)
  },

  // Shops
  getAdminShops(params?: any): Promise<any[]> {
    return request.get('/admin/shops', { params })
  },

  // Logs
  getSystemLogs(params?: any): Promise<SystemLog[]> {
    return request.get('/admin/logs', { params })
  }
}
