import request from '@/utils/request'
export const listAccounts = params => request.get('/users/paged', { params })
export const createAccount = data => request.post('/users', data)
export const updateAccount = (id, data) => request.put(`/users/${id}/profile`, data)
export const setAccountEnabled = (id, enabled) => request.put(`/users/${id}/${enabled ? 'enable' : 'disable'}`)
export const listElders = params => request.get('/elder-profiles/paged', { params })
export const elderDetail = id => request.get(`/elder-profiles/${id}`)
export const updateElder = (id, data) => request.put(`/elder-profiles/${id}`, data)
export const getWorkbench = () => request.get('/workbench')
