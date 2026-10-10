import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearLogin } from '@/utils/auth'

/**
 * 统一请求实例。
 *
 * 约定：
 * - 业务成功码固定 200（与后端 ApiResponse 一致）
 * - 令牌通过请求头传递，不放 URL
 * - 401 清理本项目登录态并跳登录，只清 scc: 前缀的键，不影响其他项目
 * - 公共错误只提示一次，页面不再重复 catch 弹窗
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      // 与后端 application.yml 的 sa-token.token-prefix 保持一致，
      // 少了 Bearer 前缀后端会认不出令牌，直接返回 401
      config.headers['scc-token'] = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

request.interceptors.response.use(
  (response) => {
    const body = response.data
    // HTTP 2xx 但业务码非 200 也算失败
    if (body && body.code !== undefined && body.code !== 200) {
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message
    // 保留HTTP状态/响应用于重试判定，同时让页面能展示后端的具体业务原因。
    if (message) error.message = message

    if (status === 401) {
      clearLogin()
      // 动态导入避免与 router 形成循环依赖
      import('@/router').then(({ default: router }) => {
        if (router.currentRoute.value.path !== '/login') {
          router.replace('/login')
        }
      })
      ElMessage.error(message || '登录已失效，请重新登录')
    } else if (status === 403) {
      ElMessage.error(message || '当前角色无权执行此操作')
    } else if (status === 409) {
      ElMessage.warning(message || '状态已变化，请刷新后重试')
    } else if (status === 404) {
      ElMessage.error(message || '对象不存在或无权访问')
    } else if (status >= 500) {
      ElMessage.error('服务器异常，请稍后重试')
    } else if (!error.response) {
      ElMessage.error('网络连接失败，请检查后端是否启动')
    }

    return Promise.reject(error)
  }
)

export default request
