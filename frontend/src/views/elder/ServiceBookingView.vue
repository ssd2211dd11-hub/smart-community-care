<template>
  <div class="service-booking" v-loading="loading">
    <el-alert type="info" :closable="false" class="page-note">
      预约提交后由社区安排服务人员，可在「我的订单」查看接单与服务进度。
    </el-alert>

    <el-row :gutter="16">
      <!-- 左：服务目录 -->
      <el-col :span="10">
        <el-card>
          <template #header>
            <div class="card-header">
              <span class="card-title">服务目录</span>
              <el-button link :loading="loading" :disabled="lockInteraction"
                @click="loadServices">刷新</el-button>
            </div>
          </template>

          <el-alert v-if="serviceError" :title="serviceError" type="error" :closable="false"><el-button link @click="loadServices()">重试</el-button></el-alert>
          <el-empty v-else-if="!loading && services.length === 0" description="暂无上架服务" />

          <div
            v-for="item in services"
            :key="item.id"
            class="service-item"
            :class="{ active: selected?.id === item.id, locked: lockInteraction }"
            @click="selectService(item)"
          >
            <div class="service-head">
              <span class="service-name">{{ item.name }}</span>
              <span class="service-price">{{ item.price }} 元</span>
            </div>
            <div class="service-desc">{{ item.description }}</div>
            <div class="service-provider">
              {{ item.provider }} · {{ item.phone }}
            </div>
          </div>
          <ListPagination :page="servicePage" :size="serviceSize" :total="serviceTotal" :loading="loading||lockInteraction" @page="loadServices" @size="changeServiceSize"/>
        </el-card>
      </el-col>

      <!-- 右：时段选择与下单 -->
      <el-col :span="14">
        <el-card>
          <template #header>
            <div class="card-header">
              <span class="card-title">
                {{ selected ? `${selected.name} — 可预约时段` : '请先选择左侧服务' }}
              </span>
              <el-button
                v-if="selected"
                link
                :loading="slotsLoading"
                :disabled="lockInteraction"
                @click="loadSlots"
              >刷新时段</el-button>
            </div>
          </template>

          <el-empty
            v-if="!selected"
            description="从左侧选择一个服务查看可预约时段"
          />

          <template v-else>
            <el-alert v-if="slotError" :title="slotError" type="error" :closable="false"><el-button link @click="loadSlots()">重试</el-button></el-alert>
            <el-empty
              v-if="!slotsLoading && slots.length === 0"
              description="该服务暂无可预约时段"
            />

            <el-radio-group v-else v-model="chosenSlotId" class="slot-list">
              <el-radio
                v-for="slot in slots"
                :key="slot.id"
                :value="slot.id"
                :disabled="remaining(slot) <= 0 || lockInteraction"
                class="slot-item"
                border
              >
                <div class="slot-time">{{ formatTime(slot) }}</div>
                <div class="slot-remaining" :class="{ full: remaining(slot) <= 0 }">
                  {{ remaining(slot) > 0 ? `剩余 ${remaining(slot)} 个名额` : '名额已满' }}
                </div>
              </el-radio>
            </el-radio-group>
            <ListPagination :page="slotPage" :size="slotSize" :total="slotTotal" :loading="slotsLoading||lockInteraction" @page="changeSlotPage" @size="changeSlotSize"/>

            <el-divider />

            <!--
              待确认提示：把「这笔请求可能已经提交」讲清楚，
              否则用户看到失败提示会直接改内容再提交，
              那正是旧实现产生「旧键 + 新内容」409 的路径。
            -->
            <el-alert
              v-if="pendingRequest"
              type="warning"
              :closable="false"
              show-icon
              class="pending-alert"
            >
              <template #title>
                上一次提交（时段 {{ slotLabelOf(pendingRequest.slotId) }}，请求标识
                {{ shortKey(pendingRequest.requestId) }}）结果未确认，订单可能已经创建。
                点「重试上一次提交」将按原内容重发，不会重复占用名额。
              </template>
            </el-alert>

            <el-form
              ref="formRef"
              :model="form"
              :rules="rules"
              label-width="90px"
              class="order-form"
            >
              <el-form-item label="联系人" prop="contactName">
                <el-input v-model="form.contactName" maxlength="50" :disabled="lockInteraction"
                  placeholder="请填写联系人姓名" />
              </el-form-item>
              <el-form-item label="联系电话" prop="contactPhone">
                <el-input v-model="form.contactPhone" maxlength="11" :disabled="lockInteraction"
                  placeholder="请填写手机号" />
              </el-form-item>
              <el-form-item label="服务地址">
                <el-input v-model="form.address" maxlength="255" :disabled="lockInteraction"
                  placeholder="选填，例如幸福社区1号楼101室" />
              </el-form-item>

              <el-form-item>
                <el-button
                  type="primary"
                  :loading="submitting"
                  :disabled="!chosenSlotId || lockInteraction"
                  @click="submit"
                >提交预约</el-button>
                <el-button :disabled="lockInteraction" @click="reset">重置</el-button>

                <!--
                  结果不确定时的重试入口。
                  明确用另一个按钮而不是复用「提交预约」：
                  重试发的是保存下来的原快照，原提交读的是实时表单，
                  两者是不同的语义，混在一个按钮里会让人以为改完表单再点也一样。
                -->
                <template v-if="pendingRequest">
                  <el-button type="warning" :disabled="submitting"
                    @click="retryPending">重试上一次提交</el-button>
                  <el-button :disabled="submitting"
                    @click="abandonPending">放弃这一笔</el-button>
                </template>
              </el-form-item>
            </el-form>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { listServicesPaged, listSlotsPaged } from '@/api/service'
import ListPagination from '@/components/ListPagination.vue'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { cancelOrder, createOrder, listMyOrders, listServices, listSlots, newRequestId } from '@/api/order'

const loading = ref(false)
const slotsLoading = ref(false)
const submitting = ref(false)

const services = ref([])
const slots = ref([])
const servicePage=ref(1),serviceSize=ref(20),serviceTotal=ref(0),serviceError=ref('')
const slotPage=ref(1),slotSize=ref(20),slotTotal=ref(0),slotError=ref('')
const selected = ref(null)
const chosenSlotId = ref(null)

const formRef = ref(null)
const form = ref({ contactName: '', contactPhone: '', address: '' })

const rules = {
  contactName: [{ required: true, message: '请填写联系人姓名', trigger: 'blur' }],
  contactPhone: [
    { required: true, message: '请填写联系电话', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ]
}

/**
 * 待确认的请求快照（规则 1、2）。
 *
 * <p>结构上它就是 POST 的完整请求体，<b>不存在第二个组装路径</b>：
 * 首次提交与重试都直接把这个对象交给 createOrder，
 * 所以重试不可能「读到被改过的表单」。
 *
 * <p>生命周期：
 * - 首次真实发送前建立（含 requestId）
 * - 结果不确定时保留，供重试
 * - 收到成功确认后清空（这一轮结束了）
 */
const pendingRequest = ref(null)

/**
 * 请求在途或结果待确认时锁定表单，重试保持原请求标识及完整内容。
 */
const lockInteraction = computed(() => submitting.value || pendingRequest.value !== null)

/**
 * 这次的失败是否意味着「这笔请求的结果仍然未知」。
 *
 * <p>分两种情况，结论完全相反：
 *
 * <p><b>结果未知 → 必须保留快照。</b>
 * 无响应（网络失败 / 超时）说明请求可能已经到达后端并建了单，只是答复没回来。
 * 5xx 同理：服务端在处理过程中出错，它**并不知道自己有没有建单**，
 * 这笔订单可能存在、也可能不存在——无法判定。两种情况下重试都必要，
 * 且重试带同一把键是安全的（后端同键同内容返回原订单）。
 *
 * <p><b>结果确定 → 可以清快照。</b>
 * 只有业务层的明确拒绝（4xx，如名额已满、内容不一致、未登录）
 * 才能断定「没有订单产生」。
 *
 * <p>关键点：<b>收到响应不等于没有订单产生。</b>
 * 503 / 500 这类服务端错误，重试一次仍然失败，
 * 但这不能证明最初那次请求没有建单——所以不能据此清掉快照。
 *
 * @param {object} e request.js 抛出的错误
 * @param {boolean} isRetry 本次是否为重试（重试失败时原请求状态仍需保留）
 */
function isOutcomeUncertain(e, isRetry) {
  if (!e?.response) return true
  const status = e.response.status
  if (status >= 500) return true
  // 4xx 是业务层的明确拒绝；若这笔请求此前已处于结果未知状态，
  // 拒绝的是「重试」而不是「最初那次」，原请求仍然没有定论。
  if (status < 400 && status < 500) return false
  return isRetry === true && previouslyUncertain.value === true
}

/** 这笔请求在本次尝试之前是否已经处于结果未知状态 */
const previouslyUncertain = ref(false)

/** 供提示条显示时段名 */
function slotLabelOf(slotId) {
  const slot = slots.value.find((x) => x.id === slotId)
  return slot ? formatTime(slot) : `时段 ${slotId}`
}

/** 只显示请求标识前 8 位，完整值是幂等键，不宜整段展示 */
function shortKey(requestId) {
  return String(requestId || '').slice(0, 8) + '…'
}

onMounted(loadServices)

async function loadServices(next=servicePage.value,recover=true) {
  if(lockInteraction.value)return
  loading.value = true
  serviceError.value=''
  try {
    const res=await listServicesPaged({pageNum:Number.isSafeInteger(next)&&next>0?next:servicePage.value,pageSize:serviceSize.value})
    services.value=res.data.records;servicePage.value=res.data.pageNum;serviceTotal.value=res.data.total
    const last=Math.max(1,Math.ceil(serviceTotal.value/serviceSize.value));if(recover&&!services.value.length&&servicePage.value>last)return await loadServices(last,false)
  } catch (e) {
    serviceError.value=e?.response?.data?.message||'服务目录读取失败，请重试'
  } finally {
    loading.value = false
  }
}

async function selectService(item) {
  // 待确认期间不允许换服务：换了服务再看时段，很容易忘了手上还有一笔未确认的请求。
  // 这不是「顺手禁用」，而是要求用户先处理完那一笔。
  if (lockInteraction.value) {
    ElMessage.warning(
      pendingRequest.value
        ? '请先重试或放弃上一次未确认的提交，再切换服务'
        : '提交进行中，请稍候'
    )
    return
  }
  selected.value = item
  slotPage.value=1;slots.value=[];slotError.value=''
  chosenSlotId.value = null
  pendingRequest.value = null
  await loadSlots()
}

async function loadSlots(next=slotPage.value,recover=true) {
  if (!selected.value) return
  slotsLoading.value = true
  slotError.value=''
  try {
    const res=await listSlotsPaged(selected.value.id,{pageNum:Number.isSafeInteger(next)&&next>0?next:slotPage.value,pageSize:slotSize.value})
    slots.value=res.data.records;slotPage.value=res.data.pageNum;slotTotal.value=res.data.total
    const last=Math.max(1,Math.ceil(slotTotal.value/slotSize.value));if(recover&&!slots.value.length&&slotPage.value>last)return await loadSlots(last,false)
  } catch (e) {
    slotError.value=e?.response?.data?.message||'预约时段读取失败，请重试'
  } finally {
    slotsLoading.value = false
  }
}
function changeServiceSize(value){serviceSize.value=value;return loadServices(1)}
function changeSlotPage(value){if(lockInteraction.value)return;chosenSlotId.value=null;return loadSlots(value)}
function changeSlotSize(value){if(lockInteraction.value)return;slotSize.value=value;return changeSlotPage(1)}

/** 剩余名额 = 容量 - 已占用 */
function remaining(slot) {
  return (slot.capacity ?? 0) - (slot.occupiedCount ?? 0)
}

function formatTime(slot) {
  const fmt = (v) => (v ? v.replace('T', ' ').slice(5, 16) : '')
  return `${fmt(slot.startAt)} — ${fmt(slot.endAt).slice(-5)}`
}

async function submit() {
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  if (!chosenSlotId.value) {
    ElMessage.warning('请先选择时段')
    return
  }
  if (lockInteraction.value) {
    ElMessage.warning(pendingRequest.value ? '请先处理上一次未确认的提交' : '提交进行中，请勿重复点击')
    return
  }

  // 规则 1：首次真实发送前，把完整请求体一次成型地存下来。
  // 之后无论表单怎么变、时段怎么换，重试发的都是这份快照。
  const snapshot = {
    slotId: chosenSlotId.value,
    contactName: form.value.contactName,
    contactPhone: form.value.contactPhone,
    address: form.value.address,
    requestId: newRequestId()
  }
  pendingRequest.value = snapshot

  await sendSnapshot(snapshot)
}

/**
 * 发送一份快照，并根据结果决定这一轮是否结束。
 *
 * <p>首次提交与重试走的是<b>同一个函数、同一个对象</b>，
 * 差别只有是谁调用的——这是「重试不可能带新内容」的结构性保证，
 * 而不是靠调用方记得别改表单。
 */
/**
 * @param {object} snapshot 要发送的完整请求体
 * @param {boolean} isRetry 本次是否为重试。重试失败时不能因为「又失败了」
 *   就断定最初那次没建单，因此这个标记要传进失败判定。
 */
async function sendSnapshot(snapshot, isRetry = false) {
  submitting.value = true
  try {
    const res = await createOrder({ ...snapshot })
    ElMessage.success(
      res?.data?.orderNo
        ? `预约成功，订单号 ${res.data.orderNo}`
        : '预约成功，可在「我的订单」查看'
    )
    // 收到成功确认：这一轮结束，清空快照并重置表单。
    // 换新键发生在「结束一轮」而不是「开始一轮」，避免在途换键。
    pendingRequest.value = null
    previouslyUncertain.value = false
    chosenSlotId.value = null
    form.value = { contactName: '', contactPhone: '', address: '' }
    formRef.value?.clearValidate()
    await loadSlots()
  } catch (e) {
    if (isOutcomeUncertain(e, isRetry)) {
      // 结果不确定：请求可能已经建单。保留完整快照，等用户决定重试或放弃。
      // 不清 chosenSlotId、不清表单——保持现场，重试才不必重新填。
      // 注意 5xx 也走这里：服务端出错不代表订单没建出来。
      previouslyUncertain.value = true
      ElMessage.error('提交结果未确认，订单可能已创建。可点「重试上一次提交」确认，不会重复占用名额')
    } else {
      // 业务层的明确拒绝（4xx）：这一次确定没有订单产生。
      // 清掉快照，让用户改完内容后作为一次新提交。
      pendingRequest.value = null
      previouslyUncertain.value = false
      ElMessage.error(e?.message || '预约失败')
    }
  } finally {
    submitting.value = false
  }
}

/** 重试：直接重发保存下来的快照，不读表单、不换键（规则 2） */
async function retryPending() {
  if (!pendingRequest.value) return
  if (submitting.value) return
  await sendSnapshot(pendingRequest.value, true)
}

/**
 * 放弃本地跟踪前必须确认请求已结束；在途时保留快照。
 * 本地丢弃不代表业务取消，已经创建的订单仍可从我的订单查看。
 */
function abandonPending() {
  if (submitting.value) {
    ElMessage.warning('提交进行中，请等这次请求返回后再决定是否放弃')
    return
  }
  pendingRequest.value = null
  ElMessage.info('已不再跟踪上一次提交。若该订单确实已创建，仍会显示在「我的订单」中')
}

function reset() {
  // 重置会换键并清空全部现场。在途或待确认时做这件事，
  // 等于把一笔可能已经落库的请求变成一笔「新」请求——必须先明确处理。
  if (lockInteraction.value) {
    ElMessage.warning(
      pendingRequest.value
        ? '请先重试或放弃上一次未确认的提交，再重置'
        : '提交进行中，请稍候'
    )
    return
  }
  form.value = { contactName: '', contactPhone: '', address: '' }
  chosenSlotId.value = null
  formRef.value?.clearValidate()
}
</script>

<style scoped>
.page-note {
  margin-bottom: 16px;
}

/* 待确认提示与表单之间留出呼吸空间 */
.pending-alert {
  margin-bottom: 14px;
}

/* 在途或待确认时，服务卡片不响应点击（脚本里也会拦一道，双保险） */
.service-item.locked {
  cursor: not-allowed;
  opacity: 0.6;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 15px;
  font-weight: 500;
}

.service-item {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.service-item:hover {
  border-color: var(--el-color-primary-light-5);
}

.service-item.active {
  border-color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
}

.service-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 6px;
}

.service-name {
  font-size: 14px;
  font-weight: 500;
}

.service-price {
  color: var(--el-color-danger);
  font-size: 14px;
}

.service-desc {
  font-size: 13px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
  margin-bottom: 4px;
}

.service-provider {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.slot-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.slot-item {
  width: 100%;
  height: auto;
  margin-right: 0;
  padding: 10px 12px;
}

.slot-item :deep(.el-radio__label) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.slot-time {
  font-size: 13px;
}

.slot-remaining {
  font-size: 12px;
  color: var(--el-color-success);
}

.slot-remaining.full {
  color: var(--el-text-color-secondary);
}

.order-form {
  max-width: 420px;
}
</style>
