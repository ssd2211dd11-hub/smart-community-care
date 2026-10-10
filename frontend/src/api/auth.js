import request from '@/utils/request'

/** 登录 */
export function login(data) {
  return request.post('/auth/login', data)
}

/** 公开注册，固定创建老人账号 */
export function register(data) {
  return request.post('/auth/register', data)
}

/** 退出，令牌立即失效 */
export function logout() {
  return request.post('/auth/logout')
}

/** 查询当前登录账号。刷新页面后用它确认身份，不信任本地存的 role */
export function fetchCurrentUser() {
  return request.get('/auth/me')
}

/** 修改本人密码 */
export function changePassword(data) {
  return request.put('/auth/password', data)
}

/**
 * 更新当前会话用户的资料，不提交用户ID、角色、启用状态或账号名。
 */
export function updateMyProfile(data) {
  return request.put('/auth/profile', data)
}
