export enum UserRole {
  ADMIN = 'ADMIN',
  USER = 'USER'
}

export enum UserType {
  NORMAL_USER = 'NORMAL_USER',
  SERVICE_PROVIDER = 'SERVICE_PROVIDER'
}

export enum AccountStatus {
  ACTIVE = 'ACTIVE',
  EXPIRED = 'EXPIRED',
  DISABLED = 'DISABLED'
}

export interface User {
  id: number
  username: string
  role: UserRole
  userType?: UserType
  status: AccountStatus
  expireAt: string
  createdAt: string
  lastLoginAt?: string
  shopCount: number
  avatar?: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  user: User
}

export interface CreateUserRequest {
  username: string
  password: string
  userType: UserType
  durationMonths: number
}

export interface UpdateUserRequest {
  username?: string
  userType?: UserType
}

export interface SetValidityRequest {
  durationMonths: number
}

export const ACCOUNT_STATUS_LABELS: Record<AccountStatus, string> = {
  [AccountStatus.ACTIVE]: '正常',
  [AccountStatus.EXPIRED]: '已欠费',
  [AccountStatus.DISABLED]: '已禁用'
}

export const USER_TYPE_LABELS: Record<UserType, string> = {
  [UserType.NORMAL_USER]: '普通用户',
  [UserType.SERVICE_PROVIDER]: '服务商'
}

export const DURATION_OPTIONS = [
  { value: 1, label: '1个月' },
  { value: 3, label: '3个月' },
  { value: 6, label: '6个月' },
  { value: 12, label: '12个月' },
  { value: 24, label: '24个月' }
]
