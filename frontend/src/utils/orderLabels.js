const STATUS_LABELS = {
  PENDING_ASSIGNMENT: '待领取 / 待分派', ASSIGNED: '待接单', ACCEPTED: '待服务',
  IN_SERVICE: '服务中', PENDING_CONFIRMATION: '待确认', COMPLETED: '已完成',
  CANCELLED: '已取消', TERMINATED: '已终止'
}

const ACTION_LABELS = {
  CREATE: '创建预约', CLAIM: '主动领取', ASSIGN: '分派', TRANSFER: '转派',
  ACCEPT: '接单', REJECT: '拒接', START: '开始服务', SUBMIT: '提交结果',
  CONFIRM: '确认完成', RETURN: '退回处理',
  CANCEL: '取消预约', TERMINATE: '异常终止'
}

export function orderStatusLabel(status) {
  return STATUS_LABELS[status] || status || '—'
}

export function orderActionLabel(action) {
  return ACTION_LABELS[action] || action || '—'
}

export function formatOrderActions(actions) {
  return actions ? actions.split(/\s*\/\s*/).map(orderActionLabel).join(' / ') : '—'
}
