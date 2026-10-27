<script setup>
import { ref, computed } from 'vue'
import { api, perform, ui, session } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText, statusText } from '../lib/rules.js'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import Status from '../components/Status.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const rows = ref([]),
  filter = ref('all'),
  detail = ref(null),
  action = ref('INVESTIGATE'),
  note = ref(''),
  adjust = ref(false)
const labels = {
  FREQUENT_SEEK: '频繁向前跳播',
  MULTI_PLAY: '多开播放尝试',
  POSITION_JUMP: '播放位置异常',
  EXAM_SWITCH: '考试切屏风险',
}
const { loading, error, reload } = useLoad(async () => (rows.value = await api('/warnings')))
const filtered = computed(() =>
  rows.value.filter((w) => filter.value === 'all' || w.status === filter.value),
)
const actions = computed(() =>
  detail.value?.warning.status === 'OPEN'
    ? [['INVESTIGATE', '开始核实']]
    : detail.value?.warning.status === 'INVESTIGATING'
      ? [
          ['WARN', '提醒并说明规则'],
          ['RELEARN', '要求重新学习'],
          ['FALSE_POSITIVE', '核实为误报'],
        ]
      : detail.value?.warning.status === 'REVIEW'
        ? [['CLOSE', '复核并关闭']]
        : [['REOPEN', '重新打开核实']],
)
async function open(w) {
  await perform(async () => {
    detail.value = await api('/warnings/' + w.id)
    action.value = actions.value[0][0]
    note.value = ''
    adjust.value = false
  })
}
async function submit() {
  await perform(async () => {
    await api(
      '/warnings/' + detail.value.warning.id + (adjust.value ? '/invalidate-session' : '/actions'),
      'POST',
      { action: adjust.value ? 'INVALIDATE_SESSION' : action.value, note: note.value },
    )
    detail.value = null
    await reload()
  }, '处理记录已保存')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">LEARNING INTEGRITY</p>
      <h1>预警中心</h1>
      <p>发现异常、核实证据、采取措施，每一步都有记录。</p>
    </div>
    <button class="btn white" @click="reload"><Icon name="refresh" :size="16" />刷新记录</button>
  </div>
  <div class="kpi-grid">
    <article
      class="kpi"
      v-for="[state, label, color] in [
        ['OPEN', '待处理预警', 'gold'],
        ['INVESTIGATING', '核实中', 'blue'],
        ['REVIEW', '待独立复核', 'violet'],
        ['CLOSED', '已关闭归档', 'green'],
      ]"
      :key="state"
    >
      <div class="kpi-top">
        {{ label
        }}<i :class="color"
          ><Icon :name="state === 'CLOSED' ? 'shield' : 'warning'" :size="19"
        /></i>
      </div>
      <div class="kpi-value">
        {{ rows.filter((w) => w.status === state).length }}<small>条</small>
      </div>
    </article>
  </div>
  <div class="notice">
    普通拖动只保留事件，不直接判违规。预警需结合证据核实；处理人和关闭复核人必须不同。演示可切换“复核管理员”完成关闭。
  </div>
  <div class="tabs page-tabs">
    <button
      v-for="[key, label] in [
        ['all', '全部记录'],
        ['OPEN', '待处理'],
        ['INVESTIGATING', '核实中'],
        ['REVIEW', '待复核'],
        ['CLOSED', '已归档'],
      ]"
      :key="key"
      :class="{ active: filter === key }"
      @click="filter = key"
    >
      {{ label }}
    </button>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="table-wrap" v-if="filtered.length">
      <table>
        <thead>
          <tr>
            <th>编号 / 类型</th>
            <th>员工 ID</th>
            <th>课程 ID</th>
            <th>触发次数</th>
            <th>发生时间</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="w in filtered" :key="w.id">
            <td>
              <strong>#{{ w.id }} {{ labels[w.type] || w.type }}</strong>
            </td>
            <td>{{ w.user_id }}</td>
            <td>{{ w.course_id || '—' }}</td>
            <td>{{ w.occurrences }}</td>
            <td>{{ dateText(w.created_at) }}</td>
            <td><Status :value="w.status" /></td>
            <td><button class="text-link" @click="open(w)">详情与处理</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <Empty
      v-else
      title="当前没有待关注的异常"
      text="系统会记录频繁跳播和并行播放等风险，供管理员核实。" /></LoadState
  ><Modal
    v-if="detail"
    :title="
      '预警 #' + detail.warning.id + ' · ' + (labels[detail.warning.type] || detail.warning.type)
    "
    wide
    @close="detail = null"
    ><dl class="detail-list">
      <dt>当前状态</dt>
      <dd><Status :value="detail.warning.status" /></dd>
      <dt>关联人员 / 课程</dt>
      <dd>员工 {{ detail.warning.user_id }} / 课程 {{ detail.warning.course_id }}</dd>
      <dt>风险证据</dt>
      <dd>{{ detail.warning.evidence }}</dd>
      <dt>关联会话</dt>
      <dd>{{ detail.warning.session_id || '未关联播放会话' }}</dd>
    </dl>
    <h3 class="section-spacer">处理时间线</h3>
    <div class="timeline section-spacer">
      <article>
        <h3>预警创建</h3>
        <p>{{ detail.warning.evidence }}</p>
        <small>{{ dateText(detail.warning.created_at) }}</small>
      </article>
      <article v-for="a in detail.actions" :key="a.id || a.created_at">
        <h3>{{ a.action }} · 操作人 {{ a.display_name || a.actor_id }}</h3>
        <p>{{ a.note }}</p>
        <small
          >{{ dateText(a.created_at) }} · {{ statusText[a.before_state] }} →
          {{ statusText[a.after_state] }}</small
        >
      </article>
    </div>
    <form @submit.prevent="submit">
      <div class="form-grid">
        <label class="field span-2"
          ><span>后续操作</span
          ><select v-model="action" :disabled="adjust">
            <option v-for="[value, label] in actions" :value="value" :key="value">
              {{ label }}
            </option>
          </select></label
        ><label
          v-if="detail.warning.status === 'INVESTIGATING' && detail.warning.session_id"
          class="field span-2"
          ><span
            ><input type="checkbox" v-model="adjust" />
            撤销该会话的有效学习区间（保留原记录并重新计算）</span
          ></label
        >
        <p
          v-if="
            detail.warning.status === 'REVIEW' && detail.warning.handler_id === session.me?.user.id
          "
          class="notice gold span-2"
        >
          你是该记录的处理人，须由另一管理员复核关闭。
        </p>
        <label class="field span-2"
          ><span>核实结论 / 措施说明</span
          ><textarea
            v-model="note"
            required
            minlength="5"
            maxlength="1800"
            placeholder="写清核实依据、已采取的措施及后续要求"
          />
        </label>
      </div>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="detail = null">取消</button
        ><button class="btn" :disabled="ui.busy > 0">
          {{ adjust ? '确认调整并留档' : '保存处理记录' }}
        </button>
      </footer>
    </form></Modal
  >
</template>
