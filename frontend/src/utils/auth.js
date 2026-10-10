/**
 * 登录态存储。
 *
 * 关键约定：
 * 1. 存储键统一加 `scc:` 前缀，退出时只删自己的键，
 *    不清理整个浏览器存储，保留其他项目的数据。
 * 2. sessionStorage 隔离不同标签页；保存的 role 只是展示用，**不是权限事实**。
 *    刷新后页面应调用 /api/auth/me 重新确认，前端存的 role 可被篡改。
 */

export const TOKEN_KEY = 'scc:token'
export const USER_KEY = 'scc:user'

// 升级时不继承旧的共享身份，避免把最后登录者带入所有标签。
localStorage.removeItem(TOKEN_KEY)
localStorage.removeItem(USER_KEY)

export function saveLogin({ token, user }) {
  sessionStorage.setItem(TOKEN_KEY, token)
  sessionStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function getStoredUser() {
  const raw = sessionStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    // 数据损坏时当作未登录，重新走登录流程
    sessionStorage.removeItem(USER_KEY)
    return null
  }
}

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function clearLogin() {
  // 只删本项目的键，不动其他项目的数据
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(USER_KEY)
}

/** 角色对应的首页路径 */
export function homePathFor(role) {
  return {
    COMMUNITY_ADMIN: '/admin',
    ELDER: '/elder',
    SERVICE_STAFF: '/staff'
  }[role] || '/403'
}

/** 角色中文名 */
export const ROLE_LABELS = {
  COMMUNITY_ADMIN: '社区管理员',
  ELDER: '老人用户',
  SERVICE_STAFF: '服务人员'
}
