import request from './request'
import type { LoginRequest, LoginResponse, User } from '../types/user'

export const authApi = {
  async login(data: LoginRequest): Promise<LoginResponse> {
    return await request.post('/auth/login', data)
  },

  async getCurrentUser(): Promise<User> {
    return await request.get('/auth/user')
  },

  logout(): Promise<void> {
    return request.post('/auth/logout')
  }
}
