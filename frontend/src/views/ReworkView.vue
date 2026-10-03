<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import router from '../router'

// ---------------- 状态字典 ----------------
const COLUMNS = [
  { key: 'QUARANTINED', label: '隔离中', hint: '判返修后先隔离，待开工', color: '#e4573d' },
  { key: 'PROCESSING', label: '处理中', hint: '正在返修处置', color: '#ffb74d' },
  { key: 'INSPECTING', label: '待复检', hint: '处置完成，等复检结论', color: '#4fc3f7' },
  { key: 'RELEASED', label: '已放行', hint: '复检合格，可上架发运', color: '#4dd6a8' }
]

const MERGE_REASON_BADGE = {
  NEW_BATCH: 'primary',
  SAME_BATCH: 'success',
  ORDER_BRIDGE: 'warning',
  ORDER_MATCH: 'warning',
  ORDER_ONLY: 'info',
  REFUSE_ORDER_AMBIGUOUS: 'danger',
  REFUSE_BATCH_CONFLICT: 'danger',
  ISOLATED_NO_KEY: 'info'
}

const EVENT_STYLE = {
  ISOLATE: { color: '#e4573d', icon: '⛔' },
  MERGE: { color: '#b08df0', icon: '🔗' },
  START: { color: '#ffb74d', icon: '🛠' },
  DISPOSE: { color: '#ffd180', icon: '📝' },
  RELEASE: { color: '#4dd6a8', icon: '✅' },
  REWORK_BACK: { color: '#e4573d', icon: '↩️' },
  REOPEN: { color: '#ff8a65', icon: '🔁' },
  CONFLICT: { color: '#ef5350', icon: '⚡' }
}

const activeColumn = ref('')
const board = ref([])
const loading = ref(false)
const detail = ref(null)
const drawer = ref(false)

// 处置表单
const dlgDispose = ref(false)
const disposeMode = ref('start') // start / progress / finish
const disposeForm = ref({ method: '', progress: '', operator: '许静', finish: false })

// 复检表单
const dlgReinspect = ref(false)
const inspectForm = ref({ verdict: 'PASS', inspector: '何斌', note: '', defectType: '', defectSizeMm: 2, affectedSheets: 1 })

const DEFECTS = [
  { key: 'SCRATCH', label: '划伤' }, { key: 'BUBBLE', label: '气泡' }, { key: 'STONE', label: '结石' },
  { key: 'CHIP', label: '崩边' }, { key: 'WAVE', label: '波形' }, { key: 'STRESS', label: '应力斑' }
]

const grouped = computed(() => {
  const map = {}
  for (const c of COLUMNS) map[c.key] = []
  for (const b of board.value) {
    if (map[b.status]) map[b.status].push(b)
  }
  return map
})

function processLabel(key) {
  const map = { CUTTING: '切割', GRINDING: '磨边', TEMPERING: '钢化', COATING: '镀膜', INSULATING: '中空', LAMINATING: '夹胶', REINSPECT: '复检' }
  return map[key] || key
}
function defectLabel(key) {
  return (DEFECTS.find((d) => d.key === key) || {}).label || key
}

async function load() {
  loading.value = true
  try {
    board.value = await api.rework.board(activeColumn.value || undefined)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function openTimeline(row) {
  try {
    detail.value = await api.rework.timeline(row.id)
    drawer.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function refreshDetail() {
  if (!detail.value) return
  try {
    detail.value = await api.rework.timeline(detail.value.batch.id)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------------- 处置流转 ----------------
function openStart(row) {
  disposeMode.value = 'start'
  disposeForm.value = { method: '', progress: '', operator: row.disposedBy || '许静', finish: false }
  dlgDispose.value = true
}
function openDispose(row, finish) {
  disposeMode.value = finish ? 'finish' : 'progress'
  disposeForm.value = {
    method: row.dispositionMethod || '',
    progress: finish ? '处置完成，待复检' : (row.dispositionProgress || ''),
    operator: row.disposedBy || '许静',
    finish
  }
  dlgDispose.value = true
}

async function submitDispose() {
  const batch = detail.value?.batch
  if (!batch) return
  const f = disposeForm.value
  if (disposeMode.value === 'start' && !f.method.trim()) {
    ElMessage.warning('请填写处置方式')
    return
  }
  try {
    if (disposeMode.value === 'start') {
      await api.rework.start(batch.id, f)
    } else {
      await api.rework.dispose(batch.id, f)
    }
    ElMessage.success(disposeMode.value === 'finish' ? '已流转待复检' : '处置已登记')
    dlgDispose.value = false
    await refreshDetail()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ---------------- 复检 ----------------
function openReinspect(verdict) {
  inspectForm.value = {
    verdict,
    inspector: verdict === 'PASS' ? '何斌' : '周梅',
    note: '', defectType: '', defectSizeMm: 2, affectedSheets: detail.value.batch.totalSheets || 1
  }
  dlgReinspect.value = true
}

async function submitReinspect() {
  const batch = detail.value?.batch
  if (!batch) return
  try {
    await api.rework.reinspect(batch.id, { ...inspectForm.value })
    ElMessage.success(inspectForm.value.verdict === 'PASS' ? '复检合格，批次已放行' : '再判返修，批次已回隔离')
    dlgReinspect.value = false
    await refreshDetail()
  } catch (e) {
    await onReinspectError(e)
  }
}

async function onReinspectError(e) {
  dlgReinspect.value = false
  await refreshDetail()
  if (e.conflict) {
    const c = e.conflict
    ElMessageBox.alert(
      `你的结论「${c.conflictVerdict === 'PASS' ? '合格放行' : '再判返修'}」未生效。` +
      `另一检验员的结论「${c.winningVerdict === 'PASS' ? '合格放行' : '再判返修'}」已先一步成为唯一有效最终状态。` +
      `该次冲突已以 CONFLICT 事件记入批次时间线，可在下方审计流中核对。`,
      '并发结论冲突', { type: 'error', confirmButtonText: '查看时间线' })
  } else {
    ElMessage.error(e.message)
  }
}

/**
 * 双检验员几乎同时提交相反结论：合格 vs 返修并发发出。
 * 服务端行锁串行化，只产生一个最终状态，另一方收到 409 冲突并留 CONFLICT 审计。
 */
async function simulateConcurrentVerdict() {
  const batch = detail.value?.batch
  if (!batch || batch.status !== 'INSPECTING') return
  try {
    await ElMessageBox.confirm(
      '将由检验员「何斌=合格放行」与「周梅=再判返修」几乎同时并发提交，系统只保留一个最终状态，另一方会看到明确冲突。是否演示？',
      '并发复检对拍', { confirmButtonText: '并发提交', cancelButtonText: '取消' })
  } catch {
    return
  }
  const payloadA = { verdict: 'PASS', inspector: '何斌', note: '并发对拍：判定合格' }
  const payloadB = { verdict: 'REWORK', inspector: '周梅', note: '并发对拍：判定返修', defectType: 'CHIP', defectSizeMm: 3.2, affectedSheets: 1 }
  const results = await Promise.allSettled([
    api.rework.reinspect(batch.id, payloadA),
    api.rework.reinspect(batch.id, payloadB)
  ])
  const rejected = results.filter((r) => r.status === 'rejected').map((r) => r.reason)
  const accepted = results.filter((r) => r.status === 'fulfilled').length
  await refreshDetail()
  if (rejected.some((e) => e.conflict)) {
    const c = rejected.find((e) => e.conflict).conflict
    ElMessage.warning(`并发完成：1 个结论生效，检验员 ${c.conflictInspector} 的相反结论被拒绝并记入冲突审计`)
  } else if (accepted === 1) {
    ElMessage.success('并发完成：仅一个结论生效')
  } else {
    ElMessage.info('并发完成，详见时间线')
  }
}

onMounted(async () => {
  await load()
  // 从成品架“去处理”跳转：按返修批次 id 直达时间线
  const qid = router.currentRoute.value?.query?.batchId
  if (qid) {
    const target = board.value.find((b) => String(b.id) === String(qid))
    if (target) await openTimeline(target)
  }
})
</script>

<template>
  <div v-loading="loading">
    <div class="view-head">
      <div class="view-title">返修批次工作区 · 隔离 → 处置 → 复检放行</div>
      <div class="view-tip">检验、深加工、成品架共享同一份批次质量状态；隔离 / 待复检批次在成品架自动拦截发运</div>
    </div>

    <!-- 状态看板 -->
    <div class="glass-bar">
      <span class="glass-bar-label">待办状态</span>
      <span class="chip" :class="{ on: activeColumn === '' }" @click="activeColumn = ''; load()">全部</span>
      <span v-for="c in COLUMNS" :key="c.key" class="chip"
            :class="{ on: activeColumn === c.key }" @click="activeColumn = c.key; load()">
        {{ c.label }}
        <b class="chip-count" :style="{ color: c.color }">{{ grouped[c.key]?.length || 0 }}</b>
      </span>
      <el-button size="small" style="margin-left: auto" @click="load">刷新</el-button>
    </div>

    <div class="board">
      <div v-for="c in COLUMNS" :key="c.key" class="board-col" v-show="!activeColumn || activeColumn === c.key">
        <div class="col-head" :style="{ borderColor: c.color }">
          <span :style="{ color: c.color }">● {{ c.label }}</span>
          <span class="col-hint">{{ c.hint }}</span>
          <span class="col-count">{{ grouped[c.key]?.length || 0 }}</span>
        </div>
        <div class="card-list">
          <div v-for="b in grouped[c.key]" :key="b.id" class="rw-card glass"
               :class="{ blocked: b.status !== 'RELEASED' }" @click="openTimeline(b)">
            <div class="rw-card-top">
              <span class="rw-no">{{ b.reworkNo }}</span>
              <el-tag size="small" :color="c.color" effect="dark" disable-transitions>{{ b.statusName }}</el-tag>
            </div>
            <div class="rw-line">
              <span class="rw-k">实物批次</span>
              <b>{{ b.keyBatchNo || '— 待核实 —' }}</b>
            </div>
            <div class="rw-line">
              <span class="rw-k">订单/单号</span>
              <span class="ellipsis">{{ b.orderNos || '—' }}</span>
            </div>
            <div class="rw-line">
              <span class="rw-k">涉及片数</span><b>{{ b.totalSheets }}</b>
              <span class="rw-k" style="margin-left: 10px">第 {{ b.currentRound }} 轮</span>
            </div>
            <div class="rw-tags">
              <el-tag v-for="p in b.sourceProcesses" :key="p" size="small" type="info" effect="plain">
                {{ processLabel(p) }}
              </el-tag>
              <el-tag v-for="d in b.defects" :key="d" size="small" type="danger" effect="plain">
                {{ defectLabel(d) }}
              </el-tag>
            </div>
            <div v-if="b.status === 'PROCESSING' || b.status === 'INSPECTING'" class="rw-progress">
              {{ b.dispositionProgress || b.dispositionMethod }}
            </div>
          </div>
          <div v-if="!grouped[c.key]?.length" class="col-empty">暂无{{ c.label }}批次</div>
        </div>
      </div>
    </div>

    <!-- 批次时间线抽屉 -->
    <el-drawer v-model="drawer" size="56%" :title="detail ? `批次时间线 · ${detail.batch.reworkNo}` : ''">
      <div v-if="detail" class="tl-wrap">
        <!-- 批次头 -->
        <div class="tl-head glass glass-pad">
          <div class="tl-status-row">
            <el-tag size="large" effect="dark"
                    :color="{ QUARANTINED: '#e4573d', PROCESSING: '#ffb74d', INSPECTING: '#4fc3f7', RELEASED: '#4dd6a8', MERGED: '#8c98cc' }[detail.batch.status]">
              {{ detail.batch.statusName }}
            </el-tag>
            <span class="tl-round">第 {{ detail.batch.currentRound }} 轮返修 · 共 {{ detail.batch.totalSheets }} 片</span>
            <span class="tl-ver" v-if="detail.batch.version != null">版本 v{{ detail.batch.version }}</span>
          </div>
          <div class="tl-grid">
            <div><span>实物批次</span><b>{{ detail.batch.keyBatchNo || '待核实' }}</b></div>
            <div><span>订单/单号</span><b>{{ detail.batch.orderNos || '—' }}</b></div>
            <div><span>隔离库位</span><b>{{ detail.batch.isolationLocation }}</b></div>
            <div><span>隔离时间</span><b>{{ detail.batch.isolatedAt }}</b></div>
            <div><span>处置方式</span><b>{{ detail.batch.dispositionMethod || '—' }}</b></div>
            <div><span>处置人</span><b>{{ detail.batch.disposedBy || '—' }}</b></div>
            <div v-if="detail.batch.releasedAt"><span>放行时间</span><b>{{ detail.batch.releasedAt }}</b></div>
          </div>
          <div class="tl-actions">
            <el-button v-if="detail.batch.status === 'QUARANTINED'" type="warning" @click="openStart(detail.batch)">登记处置并开工</el-button>
            <template v-if="detail.batch.status === 'PROCESSING'">
              <el-button @click="openDispose(detail.batch, false)">追加处置进度</el-button>
              <el-button type="primary" @click="openDispose(detail.batch, true)">处置完成，发起复检</el-button>
            </template>
            <template v-if="detail.batch.status === 'INSPECTING'">
              <el-button type="success" @click="openReinspect('PASS')">复检合格 · 放行</el-button>
              <el-button type="danger" plain @click="openReinspect('REWORK')">复检再判返修 · 回隔离</el-button>
              <el-button type="warning" plain @click="simulateConcurrentVerdict">⚡ 双检验员并发对拍</el-button>
            </template>
            <el-tag v-if="detail.batch.status === 'RELEASED'" type="success" effect="dark">已放行，成品架可上架 / 发运</el-tag>
          </div>
        </div>

        <!-- 来源记录（可追溯：来源工序、片数、缺陷、归并理由） -->
        <div class="glass glass-pad tl-block">
          <div class="section-title">返修来源（{{ detail.sources.length }}）· 保留来源工序 / 片数 / 缺陷</div>
          <el-table :data="detail.sources" size="small" stripe>
            <el-table-column prop="inspectNo" label="检验单" width="150" />
            <el-table-column label="来源工序" width="90">
              <template #default="{ row }">{{ row.sourceProcessName }}</template>
            </el-table-column>
            <el-table-column prop="sourceBatchNo" label="原批次号" width="120" />
            <el-table-column prop="sourceOrderNo" label="原订单号" width="140" />
            <el-table-column label="缺陷" width="80">
              <template #default="{ row }">{{ row.defectTypeName || '—' }}</template>
            </el-table-column>
            <el-table-column prop="sheetCount" label="片数" width="60" />
            <el-table-column prop="inspector" label="检验员" width="80" />
            <el-table-column label="归并取舍" min-width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="MERGE_REASON_BADGE[row.mergeReason] || 'info'">
                  {{ row.mergeReasonName }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 归并解释 -->
        <div v-if="detail.mergeLogs.length" class="glass glass-pad tl-block">
          <div class="section-title">归并取舍说明（历史残缺数据为何这样并）</div>
          <el-timeline>
            <el-timeline-item v-for="m in detail.mergeLogs" :key="m.id" :timestamp="m.createdAt"
                              :type="MERGE_REASON_BADGE[m.reason] === 'danger' ? 'danger' : 'primary'">
              <el-tag size="small" :type="MERGE_REASON_BADGE[m.reason] || 'info'">{{ m.reasonName }}</el-tag>
              <span class="merge-inspect">{{ m.inspectNo }}</span>
              <div class="merge-detail">{{ m.detail }}</div>
            </el-timeline-item>
          </el-timeline>
        </div>

        <!-- 事件审计流 -->
        <div class="glass glass-pad tl-block">
          <div class="section-title">受控流转与冲突审计（{{ detail.events.length }}）</div>
          <el-timeline>
            <el-timeline-item v-for="e in detail.events" :key="e.id" :timestamp="e.eventAt"
                              :color="(EVENT_STYLE[e.eventType] || {}).color || '#8c98cc'">
              <div class="ev-head">
                <span class="ev-icon">{{ (EVENT_STYLE[e.eventType] || {}).icon || '•' }}</span>
                <b>{{ e.eventTypeName }}</b>
                <span class="ev-round" v-if="e.roundNo">第 {{ e.roundNo }} 轮</span>
                <el-tag v-if="e.fromStatus && e.toStatus" size="small" effect="plain" class="ev-transition">
                  {{ e.fromStatusName }} → {{ e.toStatusName }}
                </el-tag>
                <span class="ev-actor" v-if="e.actor">{{ e.actor }}</span>
              </div>
              <div class="ev-detail">{{ e.detail }}</div>
              <div v-if="e.eventType === 'CONFLICT'" class="conflict-box">
                <div>落败结论：检验员 <b>{{ e.conflictInspector }}</b> 提交「{{ e.conflictVerdictName }}」</div>
                <div>唯一生效结论：<b>{{ e.winningVerdictName }}</b></div>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
    </el-drawer>

    <!-- 处置对话框 -->
    <el-dialog v-model="dlgDispose"
               :title="disposeMode === 'start' ? '登记处置并开工' : (disposeMode === 'finish' ? '处置完成，发起复检' : '追加处置进度')"
               width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="处置方式">
          <el-input v-model="disposeForm.method" placeholder="如：复磨 / 返钢化 / 重新干燥合片 / 重镀" />
        </el-form-item>
        <el-form-item label="处置进度">
          <el-input v-model="disposeForm.progress" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="处置人">
          <el-input v-model="disposeForm.operator" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dlgDispose = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitDispose">
          {{ disposeMode === 'start' ? '开工' : (disposeMode === 'finish' ? '提交复检' : '保存进度') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 复检对话框 -->
    <el-dialog v-model="dlgReinspect"
               :title="inspectForm.verdict === 'PASS' ? '复检合格 · 放行' : '复检再判返修 · 回隔离'"
               width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="复检员">
          <el-input v-model="inspectForm.inspector" />
        </el-form-item>
        <el-form-item label="结论">
          <el-radio-group v-model="inspectForm.verdict">
            <el-radio-button label="PASS">合格放行</el-radio-button>
            <el-radio-button label="REWORK">再判返修</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="inspectForm.verdict === 'REWORK'">
          <el-form-item label="缺陷类型">
            <el-select v-model="inspectForm.defectType" clearable style="width: 100%">
              <el-option v-for="d in DEFECTS" :key="d.key" :label="d.label" :value="d.key" />
            </el-select>
          </el-form-item>
          <el-form-item label="缺陷尺寸mm">
            <el-input-number v-model="inspectForm.defectSizeMm" :min="0.1" :max="100" :step="0.1" />
          </el-form-item>
          <el-form-item label="涉及片数">
            <el-input-number v-model="inspectForm.affectedSheets" :min="1" :max="999" />
          </el-form-item>
        </template>
        <el-form-item label="备注">
          <el-input v-model="inspectForm.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dlgReinspect = false">取消</el-button>
        <el-button size="small" :type="inspectForm.verdict === 'PASS' ? 'success' : 'danger'" @click="submitReinspect">
          提交复检结论
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.board { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-top: 4px; }
.board-col { background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.1); border-radius: 12px; padding: 10px; min-height: 300px; }
.col-head { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 700; padding-bottom: 8px; border-bottom: 2px solid; margin-bottom: 10px; }
.col-hint { font-size: 10px; color: var(--ink-dim); font-weight: 400; flex: 1; }
.col-count { background: rgba(255,255,255,0.12); border-radius: 10px; padding: 0 8px; font-size: 12px; }
.card-list { display: flex; flex-direction: column; gap: 10px; }
.rw-card { padding: 10px 12px; border-radius: 10px; cursor: pointer; transition: transform .15s ease, border-color .15s; }
.rw-card:hover { transform: translateY(-2px); border-color: rgba(255,255,255,0.4); }
.rw-card.blocked { border-left: 3px solid #e4573d; }
.rw-card-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.rw-no { font-weight: 800; color: #fff; font-size: 13px; }
.rw-line { font-size: 11px; color: var(--ink-soft); display: flex; align-items: center; gap: 6px; margin: 3px 0; }
.rw-k { color: var(--ink-dim); min-width: 56px; }
.ellipsis { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rw-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.rw-progress { margin-top: 7px; font-size: 10px; color: #ffd180; background: rgba(255,183,77,0.1); padding: 4px 6px; border-radius: 6px; }
.col-empty { font-size: 11px; color: var(--ink-dim); text-align: center; padding: 24px 0; }
.chip-count { margin-left: 4px; }

.tl-wrap { padding: 0 18px 24px; }
.tl-block { margin-top: 14px; }
.tl-status-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.tl-round { color: #fff; font-weight: 700; font-size: 13px; }
.tl-ver { margin-left: auto; font-size: 11px; color: var(--ink-dim); }
.tl-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 16px; font-size: 12px; }
.tl-grid div { display: flex; gap: 8px; }
.tl-grid span { color: var(--ink-dim); min-width: 64px; }
.tl-grid b { color: var(--ink-soft); font-weight: 600; }
.tl-actions { margin-top: 14px; display: flex; gap: 8px; flex-wrap: wrap; }
.merge-inspect { margin-left: 8px; font-size: 12px; color: #fff; font-weight: 700; }
.merge-detail { font-size: 11px; color: var(--ink-soft); margin-top: 3px; }
.ev-head { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.ev-icon { font-size: 14px; }
.ev-round { font-size: 10px; color: var(--ink-dim); }
.ev-transition { margin-left: auto; }
.ev-actor { font-size: 11px; color: var(--ink-dim); }
.ev-detail { font-size: 12px; color: var(--ink-soft); margin-top: 4px; }
.conflict-box { margin-top: 6px; padding: 8px 10px; border: 1px dashed #ef5350; border-radius: 8px; background: rgba(239,83,80,0.08); font-size: 12px; color: #ffcdd2; }
</style>
