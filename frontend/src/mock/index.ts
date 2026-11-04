import { UserRole, UserType, AccountStatus } from '../types/user'
import type { User } from '../types/user'

// Mock users
export const mockUsers: User[] = [
  {
    id: 1,
    username: 'admin',
    role: UserRole.ADMIN,
    status: AccountStatus.ACTIVE,
    expireAt: '2027-12-31',
    createdAt: '2025-01-01',
    shopCount: 0
  },
  {
    id: 2,
    username: 'zhangsan',
    role: UserRole.USER,
    userType: UserType.NORMAL_USER,
    status: AccountStatus.ACTIVE,
    expireAt: '2027-03-15',
    createdAt: '2026-01-15',
    shopCount: 1
  },
  {
    id: 3,
    username: 'lisi',
    role: UserRole.USER,
    userType: UserType.SERVICE_PROVIDER,
    status: AccountStatus.ACTIVE,
    expireAt: '2027-06-20',
    createdAt: '2026-02-20',
    shopCount: 5
  }
]

// Mock login
export function mockLogin(username: string, _password: string): User | null {
  const user = mockUsers.find(u => u.username === username)
  return user || null
}
