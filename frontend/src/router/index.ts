import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { UserRole } from '../types/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/Login.vue')
    },
    {
      path: '/forbidden',
      name: 'Forbidden',
      component: () => import('../views/Forbidden.vue')
    },
    // Admin routes
    {
      path: '/admin',
      component: () => import('../layouts/AdminLayout.vue'),
      meta: { requiresAuth: true, requiresRole: UserRole.ADMIN },
      children: [
        {
          path: '',
          redirect: '/admin/dashboard'
        },
        {
          path: 'dashboard',
          name: 'AdminDashboard',
          component: () => import('../views/admin/AdminDashboard.vue')
        },
        {
          path: 'users',
          name: 'AdminUsers',
          component: () => import('../views/admin/Users.vue')
        },
        {
          path: 'shops',
          name: 'AdminShops',
          component: () => import('../views/admin/Shops.vue')
        },
        {
          path: 'logs',
          name: 'AdminLogs',
          component: () => import('../views/admin/Logs.vue')
        }
      ]
    },
    // User routes
    {
      path: '/',
      component: () => import('../layouts/MainLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          redirect: '/dashboard'
        },
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('../views/Dashboard.vue')
        },
        {
          path: 'users',
          name: 'UserManagement',
          component: () => import('../views/UserManagement.vue'),
          meta: { requiresAuth: true, requiresRole: UserRole.ADMIN }
        },
        {
          path: 'shops',
          name: 'Shops',
          component: () => import('../views/Shops.vue')
        },
        {
          path: 'reviews',
          name: 'Reviews',
          component: () => import('../views/Reviews.vue')
        },
        {
          path: 'templates',
          name: 'Templates',
          component: () => import('../views/Templates.vue')
        },
        {
          path: 'appeals',
          name: 'Appeals',
          component: () => import('../views/Appeals.vue')
        },
        {
          path: 'food-safety',
          name: 'FoodSafetyAppeals',
          component: () => import('../views/FoodSafetyAppeals.vue')
        },
        {
          path: 'automation',
          name: 'Automation',
          component: () => import('../views/Automation.vue')
        }
      ]
    }
  ]
})

router.beforeEach(async (to, _from, next) => {
  const authStore = useAuthStore()

  // Public routes - always allow
  if (to.path === '/login' || to.path === '/forbidden') {
    if (to.path === '/login' && authStore.token && authStore.user) {
      // Already logged in with user data, redirect to new dashboard
      next('/dashboard')
      return
    }
    next()
    return
  }

  // Protected routes - check token first
  if (to.meta.requiresAuth && !authStore.token) {
    next('/login')
    return
  }

  // Fetch user info if not loaded (only if we have a token)
  if (authStore.token && !authStore.user) {
    try {
      await authStore.fetchCurrentUser()
    } catch (error) {
      // Backend not available or token invalid
      console.error('Failed to fetch user:', error)
      authStore.logout()
      next('/login')
      return
    }
  }

  // Check role requirements
  if (to.meta.requiresRole && authStore.user) {
    const requiredRole = to.meta.requiresRole as UserRole
    if (authStore.user.role !== requiredRole) {
      next('/forbidden')
      return
    }
  }

  next()
})

export default router
