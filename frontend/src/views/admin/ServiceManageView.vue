<template>
  <div class="service-manage" v-loading="loading">
    <el-row :gutter="16">
      <!-- 左：服务列表与维护 -->
      <el-col :span="10">
        <el-card>
          <template #header>
            <div class="card-header">
              <span class="card-title">服务项目</span>
              <el-button link @click="openCreateItem">新建服务</el-button>
            </div>
          </template>

          <el-form inline @submit.prevent="loadItems(1)"><el-form-item><el-input v-model="keyword" clearable placeholder="查询服务名称"/></el-form-item><el-button native-type="submit" type="primary">查询</el-button></el-form>
          <el-alert v-if="itemError" :title="itemError" type="error" :closable="false"><el-button link @click="loadItems()">重试</el-button></el-alert>
          <el-empty v-else-if="!loading && items.length === 0" description="没有符合条件的服务项目" />

          <div
            v-for="item in items"
            :key="item.id"
            class="item-row"
            :class="{ active: current?.id === item.id, disabled: !item.enabled }"
            @click="selectItem(item)"
          >
            <div class="item-head">
              <span class="item-name">{{ item.name }}</span>
              <el-tag v-if="!item.enabled" type="info" size="small">已下架</el-tag>
              <span v-else class="item-price">{{ item.price }} 元</span>
            </div>
            <div class="item-provider">{{ item.provider }}</div>
            <!-- 编辑与下架是本行的操作入口，@click.stop 避免冒泡触发 selectItem 重新加载时段 -->
            <div class="item-actions" @click.stop>
              <el-button link type="primary" @click="openEditItem(item)">编辑</el-button>
              <el-button
                v-if="item.enabled"
                link
                type="warning"
                @click="disableItem(item)"
              >下架</el-button>
              <!-- 已下架时才给上架入口：管理双向操作，不是只能关不能开 -->
              <el-button
                v-else
                link
                type="success"
                @click="enableItem(item)"
              >上架</el-button>
            </div>
          </div>
          <ListPagination :page="itemPage" :size="itemSize" :total="itemTotal" :loading="loading" @page="loadItems" @size="changeItemSize"/>
        </el-card>
      </el-col>

      <!-- 右：时段维护 -->
      <el-col :span="14">
        <el-card>
          <template #header>
            <div class="card-header">
              <span class="card-title">
                {{ current ? `${current.name} — 预约时段` : '请先选择左侧服务' }}
              </span>
              <el-button v-if="current" link @click="openCreateSlot">新建时段</el-button>
            </div>
          </template>

          <el-empty v-if="!current" description="从左侧选择一个服务" />
          <el-alert v-else-if="slotError" :title="slotError" type="error" :closable="false"><el-button link @click="loadSlots()">重试</el-button></el-alert>
          <el-empty v-else-if="!loading && slots.length === 0" description="该服务暂无时段" />

          <el-table v-else :data="slots" size="small">
            <el-table-column label="时段" min-width="160">
              <template #default="{ row }">{{ formatTime(row) }}</template>
            </el-table-column>
            <el-table-column label="容量" width="70" prop="capacity" />
            <el-table-column label="已占用" width="80">
              <template #default="{ row }">
                <span :class="{ 'text-danger': row.occupiedCount >= row.capacity }">
                  {{ row.occupiedCount }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '开放' : '已关闭' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click.stop="openCapacity(row)">改容量</el-button>
                <el-button
                  v-if="row.enabled"
                  link
                  type="warning"
                  @click.stop="closeSlot(row)"
                >关闭</el-button>
                <!-- 已关闭时才给开放入口：误关的时段要能恢复 -->
                <el-button
                  v-else
                  link
                  type="success"
                  @click.stop="reopenSlotRow(row)"
                >开放</el-button>
              </template>
            </el-table-column>
          </el-table>
          <ListPagination v-if="current" :page="slotPage" :size="slotSize" :total="slotTotal" :loading="loading" @page="loadSlots" @size="changeSlotSize"/>
        </el-card>
      </el-col>
    </el-row>

    <!-- 新建/编辑服务 -->
    <el-dialog v-model="itemDialog" :title="itemForm.id ? '编辑服务' : '新建服务'" width="460px">
      <el-form ref="itemFormRef" :model="itemForm" :rules="itemRules" label-width="90px">
        <el-form-item label="服务名称" prop="name">
          <el-input v-model="itemForm.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="类别" prop="category">
          <el-select v-model="itemForm.category" style="width: 100%">
            <el-option label="家政保洁" value="HOUSEKEEPING" />
            <el-option label="医疗护理" value="MEDICAL" />
            <el-option label="生活配送" value="DELIVERY" />
            <el-option label="文化活动" value="CULTURE" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="itemForm.description" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
        <el-form-item label="参考价格" prop="price">
          <el-input-number v-model="itemForm.price" :min="0" :precision="2" :step="10" />
          <span class="field-hint">仅作参考，不代表收入</span>
        </el-form-item>
        <el-form-item label="提供方" prop="provider">
          <el-input v-model="itemForm.provider" maxlength="100" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="itemForm.phone" maxlength="20" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建时段 -->
    <el-dialog v-model="slotDialog" title="新建预约时段" width="440px">
      <p class="field-hint">新增时段必须从未来开始，结束时间晚于开始时间；同一服务的时段不能重叠。</p>
      <el-form ref="slotFormRef" :model="slotForm" :rules="slotRules" label-width="90px">
        <el-form-item label="开始时间" prop="startAt">
          <el-date-picker
            v-model="slotForm.startAt"
            type="datetime"
            placeholder="选择未来的开始时间"
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结束时间" prop="endAt">
          <el-date-picker
            v-model="slotForm.endAt"
            type="datetime"
            placeholder="选择结束时间"
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="容量" prop="capacity">
          <el-input-number v-model="slotForm.capacity" :min="1" :max="999" />
          <span class="field-hint">可接受的预约数，不等于人员排班</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="slotDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSlot">创建</el-button>
      </template>
    </el-dialog>

    <!-- 调整容量 -->
    <el-dialog v-model="capacityDialog" title="调整容量" width="400px">
      <p class="dialog-tip">
        已占用 {{ capacityForm.occupiedCount }} 个，容量不得低于该值。
      </p>
      <el-input-number v-model="capacityForm.capacity" :min="1" :max="999" />
      <template #footer>
        <el-button @click="capacityDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCapacity">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { listServicesPaged, listSlotsPaged } from '@/api/service'
import ListPagination from '@/components/ListPagination.vue'
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  closeSlot as closeSlotApi,
  createServiceItem,
  createSlot,
  disableService,
  enableService,
  listAllServices,
  listAllSlots,
  reopenSlot,
  updateServiceItem,
  updateSlotCapacity
} from '@/api/service'

const loading = ref(false)
const saving = ref(false)

const items = ref([])
const itemPage=ref(1),itemSize=ref(20),itemTotal=ref(0),keyword=ref(''),itemError=ref('')
const slotPage=ref(1),slotSize=ref(20),slotTotal=ref(0),slotError=ref('')
const slots = ref([])
const current = ref(null)

const itemDialog = ref(false)
const itemFormRef = ref(null)
const itemForm = ref({ id: null, name: '', category: 'HOUSEKEEPING', description: '', price: 0, provider: '', phone: '' })

const slotDialog = ref(false)
const slotFormRef = ref(null)
const slotForm = ref({ startAt: '', endAt: '', capacity: 1 })

const capacityDialog = ref(false)
const capacityForm = ref({ slotId: null, capacity: 1, occupiedCount: 0 })

const itemRules = {
  name: [{ required: true, message: '请填写服务名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择类别', trigger: 'change' }],
  provider: [{ required: true, message: '请填写提供方', trigger: 'blur' }]
}

const slotRules = {
  startAt: [{ required: true, message: '请选择开始时间', trigger: 'change' }, {
    trigger: 'change', validator: (_rule, value, done) => {
      done(!value || new Date(value).getTime() > Date.now() ? undefined : new Error('开始时间必须晚于当前时间'))
    }
  }],
  endAt: [{ required: true, message: '请选择结束时间', trigger: 'change' }, {
    trigger: 'change', validator: (_rule, value, done) => {
      done(!value || !slotForm.value.startAt || new Date(value) > new Date(slotForm.value.startAt)
        ? undefined : new Error('结束时间必须晚于开始时间'))
    }
  }],
  capacity: [{ required: true, message: '请填写容量', trigger: 'blur' }]
}

onMounted(loadItems)

async function loadItems(next=itemPage.value,recover=true) {
  loading.value = true
  itemError.value=''
  try {
    // 用 all=true 取全部（含已下架）：管理页必须能看到下架项，
    // 否则下架后就点不到「上架」把它恢复。
    // 老人端仍走默认的 listEnabled()，只看上架项。
    const res=await listServicesPaged({all:true,keyword:keyword.value,pageNum:Number.isSafeInteger(next)&&next>0?next:itemPage.value,pageSize:itemSize.value})
    items.value=res.data.records;itemTotal.value=res.data.total;itemPage.value=res.data.pageNum
    const last=Math.max(1,Math.ceil(itemTotal.value/itemSize.value));if(recover&&!items.value.length&&itemPage.value>last)return await loadItems(last,false)
  } catch (e) {
    itemError.value=e?.response?.data?.message||'服务列表读取失败，请重试'
  } finally {
    loading.value = false
  }
}

async function selectItem(item) {
  current.value = item
  slotPage.value=1;slotError.value='';slots.value=[]
  await loadSlots()
}

async function loadSlots(next=slotPage.value,recover=true) {
  if (!current.value) return
  loading.value = true
  slotError.value=''
  try {
    const res=await listSlotsPaged(current.value.id,{all:true,pageNum:Number.isSafeInteger(next)&&next>0?next:slotPage.value,pageSize:slotSize.value})
    slots.value=res.data.records;slotTotal.value=res.data.total;slotPage.value=res.data.pageNum
    const last=Math.max(1,Math.ceil(slotTotal.value/slotSize.value));if(recover&&!slots.value.length&&slotPage.value>last)return await loadSlots(last,false)
  } catch (e) {
    slotError.value=e?.response?.data?.message||'时段读取失败，请重试'
  } finally {
    loading.value = false
  }
}
function changeItemSize(value){itemSize.value=value;return loadItems(1)}
function changeSlotSize(value){slotSize.value=value;return loadSlots(1)}

function openCreateItem() {
  itemForm.value = { id: null, name: '', category: 'HOUSEKEEPING', description: '', price: 0, provider: '', phone: '' }
  itemDialog.value = true
}

/**
 * 打开编辑弹窗，回填现有值。
 *
 * <p>id 用字符串承载（后端 Long 序列化成字符串），
 * 提交时不再做数字转换——避免大 ID 在 JS 里丢精度。
 */
function openEditItem(item) {
  itemForm.value = {
    id: item.id,
    name: item.name,
    category: item.category,
    description: item.description || '',
    price: Number(item.price ?? 0),
    provider: item.provider || '',
    phone: item.phone || ''
  }
  itemDialog.value = true
}

async function saveItem() {
  const ok = await itemFormRef.value.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  // 有 id 走编辑、没有 id 走新建。这里不新增「保存服务」之类的通用接口：
  // 创建与更新是两个既有业务动作，各自对应一个后端端点。
  const isEdit = Boolean(itemForm.value.id)
  try {
    if (isEdit) {
      await updateServiceItem(itemForm.value.id, itemForm.value)
      ElMessage.success('已保存')
    } else {
      await createServiceItem(itemForm.value)
      ElMessage.success('已创建')
    }
    itemDialog.value = false
    await loadItems()
    // 编辑的可能是当前选中的服务，右侧时段标题要跟着更新
    if (isEdit && current.value?.id === itemForm.value.id) {
      current.value = items.value.find((i) => i.id === itemForm.value.id) || current.value
    }
  } catch (e) {
    ElMessage.error(e?.message || (isEdit ? '保存失败' : '创建失败'))
  } finally {
    saving.value = false
  }
}

/**
 * 下架服务。
 *
 * <p>提示语写明「不影响已有订单」——这是下架的既定语义，
 * 让操作者不必担心会误伤已预约的老人。
 */
async function disableItem(item) {
  try {
    await ElMessageBox.confirm(
      '下架后该服务不再受理新预约，已存在的订单不受影响。确认下架？',
      '下架服务',
      { type: 'warning' }
    )
  } catch {
    return // 用户取消
  }
  saving.value = true
  try {
    await disableService(item.id)
    ElMessage.success('已下架')
    await loadItems()
    if (current.value?.id === item.id) {
      current.value = items.value.find((i) => i.id === item.id) || current.value
    }
  } catch (e) {
    ElMessage.error(e?.message || '下架失败')
  } finally {
    saving.value = false
  }
}

function openCreateSlot() {
  slotForm.value = { startAt: '', endAt: '', capacity: 1 }
  slotDialog.value = true
}

async function saveSlot() {
  const ok = await slotFormRef.value.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    await createSlot(current.value.id, slotForm.value)
    ElMessage.success('时段已创建')
    slotDialog.value = false
    await loadSlots()
  } catch (e) {
    ElMessage.error(e?.message || '创建失败')
  } finally {
    saving.value = false
  }
}

function openCapacity(slot) {
  capacityForm.value = {
    slotId: slot.id,
    capacity: slot.capacity,
    occupiedCount: slot.occupiedCount
  }
  capacityDialog.value = true
}

async function saveCapacity() {
  saving.value = true
  try {
    await updateSlotCapacity(capacityForm.value.slotId, capacityForm.value.capacity)
    ElMessage.success('容量已调整')
    capacityDialog.value = false
    await loadSlots()
  } catch (e) {
    ElMessage.error(e?.message || '调整失败')
  } finally {
    saving.value = false
  }
}

/**
 * 上架服务（与下架对称）。
 *
 * <p>提示语写明「不影响已有订单」——这是上下架的既定语义，
 * 让操作者不必担心误伤已预约的老人。
 */
async function enableItem(item) {
  try {
    await ElMessageBox.confirm(
      '上架后该服务重新受理新预约，已存在的订单不受影响。确认上架？',
      '上架服务',
      { type: 'success' }
    )
  } catch {
    return // 用户取消
  }
  saving.value = true
  try {
    await enableService(item.id)
    ElMessage.success('已上架')
    await loadItems()
    if (current.value?.id === item.id) {
      current.value = items.value.find((i) => i.id === item.id) || current.value
    }
  } catch (e) {
    ElMessage.error(e?.message || '上架失败')
  } finally {
    saving.value = false
  }
}

/**
 * 重新开放已关闭的时段。
 *
 * <p>强调「不改动已约时间」：误关后最怕的是已预约的老人被改期，
 * 这个动作只翻转受理开关。
 */
async function reopenSlotRow(slot) {
  try {
    await ElMessageBox.confirm(
      '重新开放后该时段恢复受理新预约，已有的预约时间与名额不变。确认开放？',
      '重新开放时段',
      { type: 'success' }
    )
  } catch {
    return // 用户取消
  }
  saving.value = true
  try {
    await reopenSlot(slot.id)
    ElMessage.success('已重新开放')
    await loadSlots()
  } catch (e) {
    ElMessage.error(e?.message || '开放失败')
  } finally {
    saving.value = false
  }
}

async function closeSlot(slot) {
  try {
    await ElMessageBox.confirm(
      '关闭后不再受理新预约，但已存在的订单不受影响。确认关闭该时段？',
      '关闭时段',
      { type: 'warning' }
    )
  } catch {
    return // 用户取消
  }
  saving.value = true
  try {
    await closeSlotApi(slot.id)
    ElMessage.success('已关闭')
    await loadSlots()
  } catch (e) {
    ElMessage.error(e?.message || '关闭失败')
  } finally {
    saving.value = false
  }
}

function formatTime(slot) {
  const fmt = (v) => (v ? v.replace('T', ' ').slice(5, 16) : '')
  return `${fmt(slot.startAt)} — ${fmt(slot.endAt).slice(-5)}`
}
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 15px;
  font-weight: 500;
}

.item-row {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  margin-bottom: 10px;
  cursor: pointer;
}

.item-row:hover {
  border-color: var(--el-color-primary-light-5);
}

.item-row.active {
  border-color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
}

.item-row.disabled {
  opacity: 0.6;
}

.item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.item-name {
  font-size: 14px;
  font-weight: 500;
}

.item-price {
  color: var(--care-brand);
  font-size: 13px;
  margin-left: auto;
}

.item-provider {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.item-actions {
  margin-top: 4px;
}

.field-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dialog-tip {
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 12px;
}

.text-danger {
  color: var(--el-color-danger);
}
</style>
