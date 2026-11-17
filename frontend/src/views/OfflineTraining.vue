<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, perform, ui, session } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText } from '../lib/rules.js'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const route = useRoute(),
  rows = ref([]),
  personal = ref({ records: [], credited_hours: 0, attended_sessions: 0 }),
  options = ref({ users: [], instructors: [], courses: [], plans: [] }),
  filter = ref('all'),
  dialog = ref(false),
  detail = ref(null),
  attendance = ref([]),
  reason = ref(''),
  search = ref(''),
  form = ref({})
const admin = computed(() => session.me?.roles.includes('ADMIN')),
  canCreate = computed(() => admin.value || session.me?.roles.includes('INSTRUCTOR'))
const labels = { REGISTERED: '待签到', PRESENT: '出勤', ABSENT: '缺席', EXCUSED: '请假' }
const { loading, error, reload } = useLoad(async () => {
  const data = await Promise.all([
    api('/offline-sessions'),
    api('/me/offline-training'),
    ...(canCreate.value ? [api('/offline-sessions/options')] : []),
  ])
  rows.value = data[0]
  personal.value = data[1]
  if (data[2]) options.value = data[2]
  if (route.query.session && rows.value.some((row) => row.id === Number(route.query.session)))
    await loadDetail(Number(route.query.session))
})
const filtered = computed(() =>
  rows.value.filter(
    (row) =>
      filter.value === 'all' ||
      (filter.value === 'manage' ? row.can_manage : !!row.attendance_status),
  ),
)
const plan = computed(() => options.value.plans.find((item) => item.id === form.value.planId))
const courses = computed(() =>
  options.value.courses.filter(
    (item) => !form.value.planId || plan.value?.course_ids?.includes(item.id),
  ),
)
const users = computed(() =>
  options.value.users.filter(
    (user) =>
      (!form.value.planId || plan.value?.user_ids?.includes(user.id)) &&
      (admin.value || !form.value.courseId || user.course_ids?.includes(form.value.courseId)) &&
      user.display_name.includes(search.value.trim()),
  ),
)
watch(
  () => form.value.planId,
  () => {
    form.value.courseId = null
    form.value.userIds = []
  },
)
watch(
  () => form.value.courseId,
  () => {
    form.value.userIds = []
  },
)
const canRecord = computed(
  () => detail.value?.can_manage && new Date(detail.value.session.start_at).getTime() <= Date.now(),
)
const changes = computed(() =>
  attendance.value.filter(
    (row) =>
      ['PRESENT', 'ABSENT', 'EXCUSED'].includes(row.status) &&
      (row.status !== row.original.status ||
        normalizedScore(row.score) !== row.original.score ||
        row.note !== row.original.note),
  ),
)
const invalidScore = computed(() =>
  changes.value.some((row) => {
    const score = normalizedScore(row.score)
    return score != null && (!Number.isInteger(score) || score < 0 || score > 100)
  }),
)
function normalizedScore(value) {
  return value === '' || value == null ? null : Number(value)
}
function openCreate() {
  form.value = {
    title: '',
    planId: null,
    courseId: null,
    instructorId: session.me.roles.includes('INSTRUCTOR')
      ? session.me.user.id
      : (options.value.instructors[0]?.id ?? null),
    startAt: '',
    endAt: '',
    location: '',
    userIds: [],
  }
  search.value = ''
  dialog.value = true
}
async function create() {
  await perform(async () => {
    const start = new Date(form.value.startAt + '+08:00'),
      end = new Date(form.value.endAt + '+08:00')
    if (
      !Number.isFinite(start.getTime()) ||
      !Number.isFinite(end.getTime()) ||
      end <= start ||
      end - start > 86400000
    )
      throw new Error('请设置有效的起止时间，单场培训不超过24小时')
    await api('/offline-sessions', 'POST', {
      ...form.value,
      title: form.value.title.trim(),
      location: form.value.location.trim(),
      startAt: start.toISOString(),
      endAt: end.toISOString(),
    })
    dialog.value = false
    await reload()
  }, '线下培训已安排，参训员工将收到站内通知')
}
async function loadDetail(id) {
  const data = await api('/offline-sessions/' + id)
  detail.value = data
  reason.value = ''
  attendance.value = data.attendance.map((row) => ({
    ...row,
    score: row.score ?? null,
    note: row.note ?? '',
    original: { status: row.status, score: row.score ?? null, note: row.note ?? '' },
  }))
}
async function open(row) {
  await perform(() => loadDetail(row.id))
}
async function saveAttendance() {
  await perform(async () => {
    if (!changes.value.length || !reason.value.trim() || invalidScore.value)
      throw new Error('请修改出勤记录并填写登记原因，分数须为0至100的整数')
    const id = detail.value.session.id
    await api('/offline-sessions/' + id + '/attendance', 'PUT', {
      reason: reason.value.trim(),
      records: changes.value.map((row) => ({
        userId: row.user_id ?? row.id,
        status: row.status,
        score: normalizedScore(row.score),
        note: row.note.trim(),
      })),
    })
    await reload()
    await loadDetail(id)
  }, '出勤记录已保存并通知员工')
}
function phase(row) {
  const now = Date.now()
  return new Date(row.start_at).getTime() > now
    ? '未开始'
    : new Date(row.end_at).getTime() > now
      ? '进行中'
      : '已结束'
}
function hours(value) {
  return Number(value || 0).toFixed(1)
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">OFFLINE LEARNING</p>
      <h1>线下培训</h1>
      <p>记录面对面的交流、实践与成长，让线下参训同样有据可查。</p>
    </div>
    <button v-if="canCreate" class="btn" :disabled="loading || !!error" @click="openCreate">
      <Icon name="plus" :size="16" />安排线下培训
    </button>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload">
    <div class="offline-overview">
      <article class="panel">
        <small>我的已参训场次</small
        ><strong>{{ personal.attended_sessions }}<span>场</span></strong>
      </article>
      <article class="panel">
        <small>我的线下培训学时</small
        ><strong>{{ hours(personal.credited_hours) }}<span>小时</span></strong>
      </article>
      <div class="notice">
        线下出勤由授课内训师或组织发展部登记；已出勤且培训结束后计入线下学时，视频有效播放时长单独记录。
      </div>
    </div>
    <div class="panel filter-bar">
      <div class="tabs">
        <button
          v-for="[value, label] in [
            ['all', '全部可见培训'],
            ['mine', '我参加的培训'],
            ...(canCreate ? [['manage', '我可管理的培训']] : []),
          ]"
          :key="value"
          :class="{ active: filter === value }"
          @click="filter = value"
        >
          {{ label }}
        </button>
      </div>
      <button class="text-link" @click="reload"><Icon name="refresh" :size="15" />刷新</button>
    </div>
    <div v-if="filtered.length" class="offline-grid">
      <article v-for="row in filtered" :key="row.id" class="panel offline-card">
        <header>
          <span class="tag">{{ phase(row) }}</span
          ><span
            v-if="row.attendance_status"
            class="attendance-status"
            :class="row.attendance_status.toLowerCase()"
            >{{ labels[row.attendance_status] }}</span
          >
        </header>
        <h2>{{ row.title }}</h2>
        <p v-if="row.plan_title" class="offline-plan">{{ row.plan_title }}</p>
        <p>
          <Icon name="calendar" :size="15" /><span
            >{{ dateText(row.start_at) }}<br />至 {{ dateText(row.end_at) }}</span
          >
        </p>
        <p>
          <Icon name="building" :size="15" /><span>{{ row.location }}</span>
        </p>
        <p>
          <Icon name="teach" :size="15" /><span
            >{{ row.instructor_name
            }}<small v-if="row.course_title"> · {{ row.course_title }}</small></span
          >
        </p>
        <footer>
          <span>{{
            row.can_manage
              ? '已安排 ' + (row.member_count ?? 0) + ' 人'
              : '已计学时 ' + hours(row.credited_hours) + ' 小时'
          }}</span
          ><button class="text-link" @click="open(row)">
            {{ row.can_manage ? '出勤与成绩登记' : '查看参训记录' }}<Icon name="right" :size="14" />
          </button>
        </footer>
      </article>
    </div>
    <Empty
      v-else
      title="暂无线下培训安排"
      :text="
        canCreate
          ? '安排一场面对面培训，记录参训人员、出勤和培训成果。'
          : '收到线下培训安排后，可以在这里查看时间、地点和参训记录。'
      "
    />
  </LoadState>
  <Modal v-if="dialog" title="安排线下培训" wide @close="dialog = false"
    ><form @submit.prevent="create">
      <div class="form-grid">
        <label class="field span-2"
          ><span>培训名称</span><input v-model="form.title" required maxlength="200" /></label
        ><label class="field"
          ><span>关联培训计划（可选）</span
          ><select v-model="form.planId">
            <option :value="null">不关联计划</option>
            <option v-for="item in options.plans" :key="item.id" :value="item.id">
              {{ item.title }}
            </option>
          </select></label
        ><label class="field"
          ><span>关联课程（可选）</span
          ><select v-model="form.courseId">
            <option :value="null">不关联课程</option>
            <option v-for="item in courses" :key="item.id" :value="item.id">
              {{ item.title }}
            </option>
          </select></label
        ><label class="field"
          ><span>授课内训师</span
          ><select v-model="form.instructorId" required>
            <option :value="null" disabled>请选择内训师</option>
            <option v-for="item in options.instructors" :key="item.id" :value="item.id">
              {{ item.display_name }}
            </option>
          </select></label
        ><label class="field"
          ><span>培训地点</span><input v-model="form.location" required maxlength="200" /></label
        ><label class="field"
          ><span>开始时间（北京时间）</span
          ><input v-model="form.startAt" type="datetime-local" required /></label
        ><label class="field"
          ><span>结束时间（北京时间）</span
          ><input v-model="form.endAt" type="datetime-local" required
        /></label>
        <fieldset class="offline-members span-2">
          <legend>参加员工（可多选）</legend>
          <input
            class="offline-search"
            v-model="search"
            placeholder="搜索员工姓名"
            aria-label="搜索线下参训员工"
          />
          <div v-if="users.length" class="check-list">
            <label v-for="user in users" :key="user.id"
              ><input type="checkbox" v-model="form.userIds" :value="user.id" />{{
                user.display_name
              }}</label
            >
          </div>
          <p v-else class="caption">当前计划和课程下暂无可选的启用员工。</p>
          <small>已选 {{ form.userIds.length }} 人。更换计划或课程后需要重新勾选。</small>
        </fieldset>
        <p class="notice span-2">
          培训安排将发送到员工消息中心。内训师可安排自己课程的学员，关联计划时人员须已加入该计划。
        </p>
      </div>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="dialog = false">取消</button
        ><button
          class="btn"
          :disabled="
            ui.busy > 0 ||
            !form.userIds.length ||
            !form.instructorId ||
            !form.title.trim() ||
            !form.location.trim()
          "
        >
          确认安排
        </button>
      </footer>
    </form></Modal
  >
  <Modal v-if="detail" :title="detail.session.title + ' · 参训记录'" wide @close="detail = null"
    ><div class="offline-detail-info">
      <p>
        <strong>{{ detail.session.instructor_name }}</strong> · {{ detail.session.location }}
      </p>
      <p>{{ dateText(detail.session.start_at) }} 至 {{ dateText(detail.session.end_at) }}</p>
      <p v-if="detail.session.plan_title">关联计划：{{ detail.session.plan_title }}</p>
    </div>
    <p v-if="detail.can_manage" class="notice">
      {{
        canRecord
          ? '请按实际出勤情况登记，可补充成绩和备注。更正记录需填写原因并通知员工。'
          : '培训尚未开始，可以查看名单；开始后可登记出勤。'
      }}
    </p>
    <div class="attendance-list">
      <article v-for="row in attendance" :key="row.user_id ?? row.id" class="attendance-row">
        <header>
          <strong>{{ row.display_name }}</strong
          ><span class="attendance-status" :class="row.original.status.toLowerCase()">{{
            labels[row.original.status]
          }}</span
          ><small>{{ hours(row.credited_hours) }} 小时</small>
        </header>
        <div v-if="canRecord" class="form-grid">
          <label class="field"
            ><span>出勤状态 · {{ row.display_name }}</span
            ><select v-model="row.status">
              <option value="REGISTERED" disabled>待签到</option>
              <option value="PRESENT">出勤</option>
              <option value="ABSENT">缺席</option>
              <option value="EXCUSED">请假</option>
            </select></label
          ><label class="field"
            ><span>培训成绩 · {{ row.display_name }}（可选）</span
            ><input v-model="row.score" type="number" min="0" max="100" step="1" /></label
          ><label class="field span-2"
            ><span>记录备注 · {{ row.display_name }}（可选）</span
            ><input v-model="row.note" maxlength="1000"
          /></label>
        </div>
        <div v-else class="attendance-readonly">
          <p>成绩：{{ row.score == null ? '未评分' : row.score + ' 分' }}</p>
          <p>备注：{{ row.note || '—' }}</p>
        </div>
        <p v-if="row.recorded_at" class="caption">最近登记：{{ dateText(row.recorded_at) }}</p>
      </article>
    </div>
    <form v-if="canRecord" @submit.prevent="saveAttendance">
      <label class="field section-spacer"
        ><span>登记或更正原因</span
        ><textarea
          v-model="reason"
          required
          maxlength="1000"
          rows="2"
          placeholder="例如：根据现场签到表核实登记"
        />
      </label>
      <p v-if="invalidScore" class="score-error" role="status">成绩须为0至100的整数。</p>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="detail = null">取消</button
        ><button
          class="btn"
          :disabled="ui.busy > 0 || !changes.length || !reason.trim() || invalidScore"
        >
          保存 {{ changes.length }} 条记录
        </button>
      </footer>
    </form>
    <footer v-else class="form-actions">
      <button class="btn white" @click="detail = null">关闭</button>
    </footer></Modal
  >
</template>
<style scoped>
.offline-overview {
  display: grid;
  grid-template-columns: 180px 200px minmax(0, 1fr);
  gap: 18px;
  margin-bottom: 24px;
}
.offline-overview article {
  padding: 19px 22px;
}
.offline-overview article > small {
  font-size: 11px;
  color: #8ca0b9;
}
.offline-overview strong {
  display: block;
  font-size: 28px;
  color: #003f98;
  margin-top: 9px;
}
.offline-overview strong span {
  font-size: 10px;
  font-weight: 400;
  color: #a0afc3;
  margin-left: 9px;
}
.offline-overview .notice {
  margin: 0;
  display: flex;
  align-items: center;
  line-height: 1.9;
}
.offline-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  margin-top: 24px;
}
.offline-card {
  padding: 24px;
  min-width: 0;
}
.offline-card header {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}
.offline-card h2 {
  font-size: 18px;
  color: #28496f;
  margin: 19px 0 16px;
  overflow-wrap: anywhere;
}
.offline-card > p {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  font-size: 12px;
  color: #7892b0;
  line-height: 1.9;
  margin-top: 11px;
  overflow-wrap: anywhere;
}
.offline-card > p svg {
  flex-shrink: 0;
  margin-top: 4px;
}
.offline-card > p small {
  font-size: 10px;
  color: #9aacbf;
}
.offline-card .offline-plan {
  font-size: 10px;
  color: #9bacc0;
  margin-top: -8px;
}
.offline-card footer {
  border-top: 1px solid #eaf0f7;
  padding-top: 15px;
  margin-top: 21px;
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.offline-card footer > span {
  font-size: 10px;
  color: #95a6bc;
}
.attendance-status {
  display: inline-block;
  font-size: 10px;
  padding: 4px 9px;
  border-radius: 5px;
  background: #eef3fa;
  color: #8499b4;
  white-space: nowrap;
}
.attendance-status.present {
  background: #e8f6ef;
  color: #2f8a64;
}
.attendance-status.absent {
  background: #fbedef;
  color: #b36978;
}
.attendance-status.excused {
  background: #fff7dc;
  color: #a08436;
}
.offline-members {
  padding: 0;
  margin: 0;
  min-width: 0;
  border: 0;
}
.offline-members legend {
  font-size: 12px;
  color: #6b819d;
  margin-bottom: 10px;
}
.offline-search {
  width: 100%;
  margin-bottom: 10px;
}
.offline-members .check-list {
  max-height: 180px;
  overflow: auto;
  border: 1px solid #e2eaf5;
  background: #fafcff;
  border-radius: 7px;
  padding: 14px;
}
.offline-members > small {
  display: block;
  color: #97a9bf;
  font-size: 10px;
  line-height: 1.9;
  margin-top: 9px;
}
.offline-detail-info {
  font-size: 12px;
  color: #7f96b2;
  line-height: 2.1;
  margin-bottom: 19px;
  overflow-wrap: anywhere;
}
.offline-detail-info strong {
  color: #526f94;
}
.attendance-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 15px;
  margin-top: 20px;
}
.attendance-row {
  border: 1px solid #e4ebf5;
  border-radius: 8px;
  padding: 17px;
  min-width: 0;
}
.attendance-row header {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  margin-bottom: 17px;
}
.attendance-row strong {
  color: #48678f;
  font-size: 13px;
}
.attendance-row header > small {
  font-size: 10px;
  color: #98abc2;
  margin-left: auto;
}
.attendance-row .field span {
  font-size: 10px;
}
.attendance-row .form-grid {
  gap: 13px;
}
.attendance-row .caption {
  font-size: 9px;
  line-height: 1.9;
  margin-top: 14px;
}
.attendance-readonly p {
  color: #7891b0;
  font-size: 12px;
  line-height: 1.9;
  overflow-wrap: anywhere;
}
.score-error {
  font-size: 11px;
  color: #b66773;
  margin-top: 10px;
}
@media (max-width: 850px) {
  .offline-overview {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .offline-overview .notice {
    grid-column: span 2;
  }
  .offline-grid {
    grid-template-columns: 1fr;
  }
  .offline-card {
    padding: 20px;
  }
  .attendance-list {
    grid-template-columns: 1fr;
  }
}
</style>
