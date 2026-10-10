import { createRouter, createWebHistory } from 'vue-router'
import { getStoredUser, getToken } from '@/utils/auth'

/**
 * 路由表。
 *
 * 三角色按 meta.role 区分，meta.requiresAuth 标记需要登录。
 * 路由守卫只负责页面体验，真正的权限判定由后端强制执行——
 * 前端隐藏菜单不等于后端拒绝请求。
 */
const routes = [
  { path: '/', redirect: '/login' },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { public: true }
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { public: true }
  },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    children: [
      { path: 'users', name: 'AdminUsers', component: () => import('@/views/admin/AdminUsersView.vue'), meta: { role: 'COMMUNITY_ADMIN' } },
      { path: 'elders', name: 'AdminElders', component: () => import('@/views/admin/AdminEldersView.vue'), meta: { role: 'COMMUNITY_ADMIN' } },
      { path: 'elders/:elderId', name: 'AdminElderDetail', component: () => import('@/views/admin/AdminElderDetailView.vue'), meta: { role: 'COMMUNITY_ADMIN' } },
      {
        path: '',
        name: 'AdminHome',
        component: () => import('@/views/admin/AdminHomeView.vue'),
        meta: { role: 'COMMUNITY_ADMIN' }
      },
      {
        path: 'alerts',
        name: 'AdminAlerts',
        component: () => import('@/views/admin/AlertCenterView.vue'),
        meta: { role: 'COMMUNITY_ADMIN' }
      },
      {
        path: 'services',
        name: 'AdminServices',
        component: () => import('@/views/admin/ServiceManageView.vue'),
        meta: { role: 'COMMUNITY_ADMIN' }
      },
      {
        path: 'orders',
        name: 'AdminOrders',
        component: () => import('@/views/admin/AdminOrdersView.vue'),
        meta: { role: 'COMMUNITY_ADMIN' }
      },
      {
        path: 'devices',
        name: 'AdminDevices',
        component: () => import('@/views/admin/AdminDevicesView.vue'),
        meta: { role: 'COMMUNITY_ADMIN' }
      },
      {
        // 共用个人中心组件，页面字段及路由权限按会话角色确定。
        path: 'profile',
        name: 'AdminProfile',
        component: () => import('@/views/elder/ProfileView.vue'),
        meta: { role: 'COMMUNITY_ADMIN' }
      }
    ]
  },
  {
    path: '/elder',
    component: () => import('@/layouts/ElderLayout.vue'),
    children: [
      {
        path: '',
        name: 'ElderHome',
        component: () => import('@/views/elder/ElderHomeView.vue'),
        meta: { role: 'ELDER' }
      },
      {
        path: 'health-input',
        name: 'ElderHealthInput',
        component: () => import('@/views/elder/HealthInputView.vue'),
        meta: { role: 'ELDER' }
      },
      {
        path: 'health-summary',
        name: 'ElderHealthSummary',
        component: () => import('@/views/elder/HealthSummaryView.vue'),
        meta: { role: 'ELDER' }
      },
      {
        path: 'my-alerts',
        name: 'ElderMyAlerts',
        component: () => import('@/views/elder/MyAlertsView.vue'),
        meta: { role: 'ELDER' }
      },
      {
        path: 'help-call',
        name: 'ElderHelpCall',
        component: () => import('@/views/elder/HelpCallView.vue'),
        meta: { role: 'ELDER' }
      },
      {
        path: 'service-booking',
        name: 'ElderServiceBooking',
        component: () => import('@/views/elder/ServiceBookingView.vue'),
        meta: { role: 'ELDER' }
      },
      {
        path: 'my-devices',
        name: 'ElderMyDevices',
        component: () => import('@/views/elder/MyDevicesView.vue'),
        meta: { role: 'ELDER' }
      },
      {
        path: 'my-orders',
        name: 'ElderMyOrders',
        component: () => import('@/views/elder/MyOrdersView.vue'),
        meta: { role: 'ELDER' }
      },
      {
        path: 'profile',
        name: 'ElderProfile',
        component: () => import('@/views/elder/ProfileView.vue'),
        meta: { role: 'ELDER' }
      }
    ]
  },
  {
    path: '/staff',
    component: () => import('@/layouts/StaffLayout.vue'),
    children: [
      {
        path: '',
        name: 'StaffHome',
        component: () => import('@/views/staff/StaffHomeView.vue'),
        meta: { role: 'SERVICE_STAFF' }
      },
      {
        path: 'alerts',
        name: 'StaffAlerts',
        component: () => import('@/views/staff/StaffAlertsView.vue'),
        meta: { role: 'SERVICE_STAFF' }
      },
      {
        path: 'tasks',
        name: 'StaffTasks',
        component: () => import('@/views/staff/MyTasksView.vue'),
        meta: { role: 'SERVICE_STAFF' }
      },
      {
        // 人员查看本人收到的评价。
        path: 'reviews',
        name: 'StaffReviews',
        component: () => import('@/views/staff/MyReviewsView.vue'),
        meta: { role: 'SERVICE_STAFF' }
      },
      {
        // 共用个人中心组件，页面字段及路由权限按会话角色确定。
        path: 'profile',
        name: 'StaffProfile',
        component: () => import('@/views/elder/ProfileView.vue'),
        meta: { role: 'SERVICE_STAFF' }
      }
    ]
  },
  {
    path: '/alerts/:eventId',
    name: 'AlertDetail',
    component: () => import('@/views/shared/AlertDetailView.vue')
  },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('@/views/NotFoundView.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/** 角色对应的首页路径 */
const HOME_BY_ROLE = {
  COMMUNITY_ADMIN: '/admin',
  ELDER: '/elder',
  SERVICE_STAFF: '/staff'
}

router.beforeEach((to) => {
  const token = getToken()
  const user = getStoredUser()

  if (to.meta.public) {
    // 已登录时不再回登录页，直接进对应首页
    if (to.path === '/login' && token && user) {
      return HOME_BY_ROLE[user.role] || '/403'
    }
    return true
  }

  if (!token) {
    return '/login'
  }

  // 角色不符则拒绝。前端拦只是体验，后端同样会拒绝。
  const requiredRole = to.matched.find((r) => r.meta.role)?.meta.role
  if (requiredRole && user?.role !== requiredRole) {
    return '/403'
  }

  return true
})

export { HOME_BY_ROLE }
export default router
