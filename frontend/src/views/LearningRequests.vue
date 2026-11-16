<script setup>
import { ref, computed, watch } from 'vue'
import { api, perform, ui, session } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText } from '../lib/rules.js'
import { positionLabel as positionText } from '../lib/organization.js'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'

const admin = computed(() => session.me?.roles.includes('ADMIN')),
  rows = ref([]),
  options = ref({ users: [], courses: [], paths: [] }),
  org = ref({ departments: [], positions: [] }),
  filter = ref('all'),
  dialog = ref(''),
  selected = ref(null),
  departmentId = ref(null),
  positionId = ref(null),
  courseSearch = ref(''),
  form = ref({}),
  review = ref({ approve: true, note: '' })
const labels = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已驳回' }
const { loading, error, reload } = useLoad(async () => {
  const [requests, choices, organization] = await Promise.all([
    api('/learning-requests'),
    api('/learning-requests/options'),
    api('/organization'),
  ])
  rows.value = requests
  options.value = choices
  org.value = organization
})
const filtered = computed(() =>
  rows.value.filter((row) => filter.value === 'all' || row.status === filter.value),
)
const departments = computed(() =>
  org.value.departments.filter((department) =>
    options.value.users.some((user) => user.department_id === department.id),
  ),
)
const unassignedDepartment = computed(() =>
  options.value.users.some((user) => user.department_id == null),
)
function matchesDepartment(user) {
  return departmentId.value === 'unset'
    ? user.department_id == null
    : user.department_id === departmentId.value
}
const positions = computed(() =>
  org.value.positions.filter((position) =>
    options.value.users.some((user) => user.position_id === position.id && matchesDepartment(user)),
  ),
)
const unassignedPosition = computed(() =>
  options.value.users.some((user) => matchesDepartment(user) && user.position_id == null),
)
const path = computed(() => options.value.paths.find((item) => item.id === form.value.pathId))
const eligibleUsers = computed(() => {
  if (departmentId.value == null || positionId.value == null) return []
  return options.value.users.filter(
    (user) =>
      matchesDepartment(user) &&
      (positionId.value === 'unset'
        ? user.position_id == null
        : user.position_id === positionId.value) &&
      (!form.value.pathId || path.value?.user_ids?.includes(user.id)),
  )
})
const visibleCourses = computed(() =>
  options.value.courses.filter((course) => course.title.includes(courseSearch.value.trim())),
)
const selectedUsers = computed(() =>
  options.value.users.filter((user) => form.value.userIds?.includes(user.id)),
)
const invalidSubmission = computed(
  () =>
    !form.value.courseIds?.length ||
    !form.value.userIds?.length ||
    !form.value.reason?.trim() ||
    departmentId.value == null ||
    positionId.value == null,
)
watch(departmentId, () => {
  positionId.value = null
  form.value.userIds = []
})
watch([positionId, () => form.value.pathId], () => {
  form.value.userIds = []
})
function open(type) {
  departmentId.value = null
  positionId.value = null
  courseSearch.value = ''
  form.value = { pathId: null, courseIds: [], userIds: [], reason: '' }
  dialog.value = type
}
function inspect(row) {
  selected.value = row
  review.value = { approve: true, note: '' }
  dialog.value = 'detail'
}
async function submit() {
  await perform(
    async () => {
      if (invalidSubmission.value) throw new Error('请选择课程和员工，并填写说明')
      const body = {
        courseIds: form.value.courseIds,
        userIds: form.value.userIds,
        reason: form.value.reason.trim(),
        ...(dialog.value === 'request' ? { pathId: form.value.pathId } : {}),
      }
      await api(
        dialog.value === 'request' ? '/learning-requests' : '/admin/course-grants',
        'POST',
        body,
      )
      dialog.value = ''
      await reload()
    },
    dialog.value === 'request' ? '需求已提交，等待组织发展部审批' : '课程已授权给所选员工',
  )
}
async function decide() {
  await perform(async () => {
    if (!review.value.note.trim()) throw new Error('请填写审批意见')
    await api('/learning-requests/' + selected.value.id + '/review', 'POST', {
      approve: review.value.approve,
      note: review.value.note.trim(),
    })
    dialog.value = ''
    await reload()
  }, '审批结果已保存')
}
function employeeText(users) {
  return users?.map((user) => user.display_name).join('、') || '—'
}
</script>

<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">PERSONAL LEARNING SUPPORT</p>
      <h1>{{ admin ? '课程授权与需求' : '加课需求' }}</h1>
      <p>
        {{
          admin
            ? '审批部门需求，也可以将课程直接授权给指定员工。'
            : '为团队成员申请新课程，让岗位成长更贴近实际需要。'
        }}
      </p>
    </div>
    <div class="btn-row">
      <button class="btn white" @click="open('request')" :disabled="loading || !!error">
        <Icon name="plus" :size="16" />提交加课需求
      </button>
      <button v-if="admin" class="btn" @click="open('grant')" :disabled="loading || !!error">
        <Icon name="book" :size="16" />直接授权课程
      </button>
    </div>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload">
    <div class="request-stats">
      <article
        v-for="[state, label] in Object.entries(labels)"
        :key="state"
        class="panel request-stat"
      >
        <span>{{ label }}</span
        ><strong
          >{{ rows.filter((row) => row.status === state).length }}<small>条需求</small></strong
        >
      </article>
    </div>
    <div class="notice request-notice">
      {{
        admin
          ? '通过审批或直接授权后，指定员工即可在“我的课程”中学习。'
          : '只能为授权部门内的员工提交需求，审批通过前不会开通课程。'
      }}
      关联成长路线时，请选择已参加该路线的员工；新增课程只分配给所选人员。
    </div>
    <div class="panel filter-bar">
      <div class="tabs" aria-label="需求状态">
        <button :class="{ active: filter === 'all' }" @click="filter = 'all'">全部需求</button>
        <button
          v-for="[state, label] in Object.entries(labels)"
          :key="state"
          :class="{ active: filter === state }"
          @click="filter = state"
        >
          {{ label }}
        </button>
      </div>
      <button class="text-link" @click="reload"><Icon name="refresh" :size="15" />刷新</button>
    </div>
    <div v-if="filtered.length" class="request-list">
      <article v-for="row in filtered" :key="row.id" class="panel request-card">
        <div class="request-card-heading">
          <div>
            <span class="request-number">加课需求 #{{ row.id }}</span>
            <h2>{{ row.path_title || '个人专项学习' }}</h2>
          </div>
          <span class="request-status" :class="row.status.toLowerCase()">{{
            labels[row.status] || row.status
          }}</span>
        </div>
        <div class="request-course-tags">
          <span v-for="course in row.courses" :key="course.id" class="tag"
            ><Icon name="book" :size="12" />{{ course.title }}</span
          >
        </div>
        <p class="request-reason">{{ row.reason }}</p>
        <p class="request-people">
          <Icon name="users" :size="15" /><span>参训员工：{{ employeeText(row.users) }}</span>
        </p>
        <footer class="request-card-footer">
          <span>{{ row.requester_name }} · {{ dateText(row.created_at) }}</span>
          <button class="text-link" @click="inspect(row)">
            {{ admin && row.status === 'PENDING' ? '查看并审批' : '查看记录'
            }}<Icon name="right" :size="14" />
          </button>
        </footer>
      </article>
    </div>
    <Empty
      v-else
      title="暂无相关需求"
      :text="
        admin ? '部门提交的加课需求会显示在这里。' : '点击“提交加课需求”，为团队申请需要的课程。'
      "
    />
  </LoadState>

  <Modal
    v-if="dialog === 'request' || dialog === 'grant'"
    :title="dialog === 'request' ? '提交加课需求' : '直接授权课程'"
    wide
    @close="dialog = ''"
  >
    <form @submit.prevent="submit">
      <div class="form-grid">
        <label v-if="dialog === 'request'" class="field span-2"
          ><span>关联成长路线（可选）</span>
          <select v-model="form.pathId">
            <option :value="null">不关联，申请个人专项学习</option>
            <option v-for="item in options.paths" :key="item.id" :value="item.id">
              {{ item.title }}
            </option>
          </select>
        </label>
        <fieldset class="request-fieldset span-2">
          <legend>选择课程 <small>可多选</small></legend>
          <input
            class="request-search"
            v-model="courseSearch"
            aria-label="搜索可申请课程"
            placeholder="搜索已发布的课程"
          />
          <div v-if="visibleCourses.length" class="request-checks course-checks">
            <label v-for="course in visibleCourses" :key="course.id"
              ><input type="checkbox" v-model="form.courseIds" :value="course.id" /><span>{{
                course.title
              }}</span></label
            >
          </div>
          <p v-else class="request-empty">
            {{
              options.courses.length
                ? '没有匹配的课程，请调整关键词。'
                : '暂无已发布课程，请先完成课程审核发布。'
            }}
          </p>
          <p class="request-hint">已选 {{ form.courseIds.length }} 门课程</p>
        </fieldset>
        <label class="field"
          ><span>选择部门</span
          ><select v-model="departmentId" required>
            <option :value="null" disabled>请选择部门</option>
            <option v-for="department in departments" :key="department.id" :value="department.id">
              {{ department.name }}
            </option>
            <option v-if="unassignedDepartment" value="unset">未设置部门</option>
          </select></label
        >
        <label class="field"
          ><span>选择岗位</span
          ><select v-model="positionId" required :disabled="departmentId == null">
            <option :value="null" disabled>请选择岗位</option>
            <option v-for="position in positions" :key="position.id" :value="position.id">
              {{ positionText(position) }}
            </option>
            <option v-if="unassignedPosition" value="unset">未设置岗位</option>
          </select></label
        >
        <fieldset class="request-fieldset span-2">
          <legend>选择参训员工 <small>可多选</small></legend>
          <div v-if="eligibleUsers.length" class="request-checks">
            <label v-for="user in eligibleUsers" :key="user.id"
              ><input type="checkbox" v-model="form.userIds" :value="user.id" /><span
                >{{ user.display_name }}<small>{{ user.username }}</small></span
              ></label
            >
          </div>
          <p v-else class="request-empty">
            {{
              departmentId == null || positionId == null
                ? '请先选择部门和岗位。'
                : form.pathId
                  ? '该部门和岗位下，没有已参加所选路线的可选员工。'
                  : '该部门和岗位下暂无可选的启用员工。'
            }}
          </p>
          <p v-if="selectedUsers.length" class="request-hint" aria-live="polite">
            已选 {{ selectedUsers.length }} 人：{{ employeeText(selectedUsers) }}
          </p>
          <p class="request-hint">更换部门、岗位或关联路线后，需要重新勾选员工。</p>
        </fieldset>
        <label class="field span-2"
          ><span>{{ dialog === 'request' ? '加课需求说明' : '授权说明' }}</span
          ><textarea
            v-model="form.reason"
            required
            maxlength="1000"
            rows="3"
            :placeholder="
              dialog === 'request'
                ? '说明员工需要学习这些课程的原因'
                : '填写本次个人课程授权的原因，便于后续查询'
            "
          />
        </label>
        <p class="notice span-2">
          {{
            dialog === 'request'
              ? '提交后由组织发展部审批，通过后通知员工并开通课程。'
              : '确认后直接为所选员工开通课程，保留已有学习进度。'
          }}
        </p>
      </div>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="dialog = ''">取消</button
        ><button class="btn" :disabled="ui.busy > 0 || invalidSubmission">
          {{ dialog === 'request' ? '提交需求' : '确认授权' }}
        </button>
      </footer>
    </form>
  </Modal>
  <Modal v-else-if="dialog === 'detail' && selected" title="加课需求记录" @close="dialog = ''">
    <div class="request-detail">
      <div class="request-card-heading">
        <div>
          <span class="request-number">需求 #{{ selected.id }}</span>
          <h3>{{ selected.path_title || '个人专项学习' }}</h3>
        </div>
        <span class="request-status" :class="selected.status.toLowerCase()">{{
          labels[selected.status]
        }}</span>
      </div>
      <dl class="request-facts">
        <div>
          <dt>申请人</dt>
          <dd>{{ selected.requester_name }}</dd>
        </div>
        <div>
          <dt>提交时间</dt>
          <dd>{{ dateText(selected.created_at) }}</dd>
        </div>
        <div>
          <dt>参训员工</dt>
          <dd>{{ employeeText(selected.users) }}</dd>
        </div>
        <div>
          <dt>申请课程</dt>
          <dd>
            <p v-for="course in selected.courses" :key="course.id">{{ course.title }}</p>
          </dd>
        </div>
        <div>
          <dt>需求说明</dt>
          <dd>{{ selected.reason }}</dd>
        </div>
      </dl>
      <form v-if="admin && selected.status === 'PENDING'" @submit.prevent="decide">
        <div class="form-grid review-fields">
          <label class="field"
            ><span>审批结果</span
            ><select v-model="review.approve">
              <option :value="true">通过并开通课程</option>
              <option :value="false">驳回需求</option>
            </select></label
          ><label class="field span-2"
            ><span>审批意见</span
            ><textarea
              v-model="review.note"
              required
              maxlength="1000"
              rows="3"
              placeholder="填写处理意见，保留审批记录"
            />
          </label>
        </div>
        <footer class="form-actions">
          <button type="button" class="btn white" @click="dialog = ''">取消</button
          ><button class="btn" :disabled="ui.busy > 0 || !review.note.trim()">保存审批结果</button>
        </footer>
      </form>
      <template v-else
        ><div v-if="selected.status !== 'PENDING'" class="request-review-record">
          <strong
            >{{ selected.reviewer_name || '组织发展部' }} ·
            {{ dateText(selected.reviewed_at) }}</strong
          >
          <p>{{ selected.review_note || '—' }}</p>
        </div>
        <p v-else class="notice">等待组织发展部审批，审批通过后将通知申请人和参训员工。</p>
        <footer class="form-actions">
          <button class="btn white" @click="dialog = ''">关闭</button>
        </footer></template
      >
    </div>
  </Modal>
</template>

<style scoped>
.request-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin-bottom: 22px;
}
.request-stat {
  padding: 20px 24px;
}
.request-stat > span {
  color: #7b8fa9;
  font-size: 12px;
}
.request-stat strong {
  display: block;
  font-size: 28px;
  color: #003f98;
  margin-top: 9px;
}
.request-stat small {
  font-size: 10px;
  color: #9baabe;
  font-weight: 400;
  margin-left: 9px;
}
.request-notice {
  margin-bottom: 23px;
}
.request-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  margin-top: 22px;
}
.request-card {
  padding: 23px;
  min-width: 0;
}
.request-card-heading {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}
.request-number {
  font-size: 10px;
  color: #8ea2bd;
}
.request-card h2 {
  font-size: 17px;
  margin: 7px 0 15px;
}
.request-card-heading h3 {
  font-size: 17px;
  margin: 8px 0 0;
}
.request-status {
  display: inline-block;
  white-space: nowrap;
  font-size: 10px;
  border-radius: 4px;
  padding: 5px 9px;
}
.request-status.pending {
  background: #fff7d8;
  color: #9b7c12;
}
.request-status.approved {
  background: #e8f6ef;
  color: #278359;
}
.request-status.rejected {
  background: #f9eef0;
  color: #ad6571;
}
.request-course-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.request-course-tags .tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: normal;
  line-height: 1.6;
}
.request-reason {
  font-size: 12px;
  color: #617995;
  line-height: 1.9;
  margin-top: 15px;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.request-people {
  display: flex;
  gap: 8px;
  color: #8da0b9;
  font-size: 11px;
  line-height: 1.8;
  margin-top: 15px;
  overflow-wrap: anywhere;
}
.request-people svg {
  flex-shrink: 0;
  margin-top: 2px;
}
.request-card-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 20px;
  padding-top: 15px;
  border-top: 1px solid #edf1f7;
}
.request-card-footer > span {
  font-size: 10px;
  color: #94a5ba;
}
.request-fieldset {
  border: 0;
  margin: 0;
  padding: 0;
  min-width: 0;
}
.request-fieldset legend {
  font-size: 12px;
  color: #61748e;
  font-weight: 500;
  margin-bottom: 9px;
}
.request-fieldset legend small {
  font-size: 10px;
  color: #9aaac0;
  font-weight: 400;
  margin-left: 7px;
}
.request-search {
  width: 100%;
  margin-bottom: 9px;
}
.request-checks {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  max-height: 190px;
  overflow: auto;
  padding: 13px;
  border: 1px solid #e3eaf5;
  border-radius: 7px;
  background: #fafcff;
}
.request-checks label {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  line-height: 1.8;
  color: #5b7495;
  cursor: pointer;
  min-width: 0;
  overflow-wrap: anywhere;
}
.request-checks input {
  flex-shrink: 0;
  margin-top: 4px;
}
.request-checks small {
  display: block;
  font-size: 10px;
  color: #9aaac0;
}
.request-empty,
.request-hint {
  font-size: 11px;
  line-height: 1.8;
  color: #96a7bf;
  margin-top: 8px;
}
.request-empty {
  padding: 13px;
  background: #f7f9fc;
  border-radius: 6px;
}
.request-detail {
  min-width: 0;
}
.request-facts {
  margin-top: 22px;
}
.request-facts > div {
  display: grid;
  grid-template-columns: 75px minmax(0, 1fr);
  gap: 13px;
  padding: 10px 0;
  font-size: 12px;
  line-height: 1.8;
}
.request-facts dt {
  color: #8b9db5;
}
.request-facts dd {
  margin: 0;
  color: #536d90;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.request-facts dd p {
  margin: 0;
}
.request-review-record {
  padding: 16px;
  margin-top: 20px;
  background: #f3f7fd;
  border-radius: 7px;
  font-size: 12px;
  line-height: 1.9;
  color: #627e9f;
  overflow-wrap: anywhere;
}
.request-review-record strong {
  font-size: 11px;
  color: #7b91ad;
}
.request-review-record p {
  margin-top: 7px;
  white-space: pre-wrap;
}
.review-fields {
  border-top: 1px solid #e6edf6;
  margin-top: 18px;
  padding-top: 22px;
}
@media (max-width: 700px) {
  .request-stats {
    gap: 9px;
  }
  .request-stat {
    padding: 15px 12px;
  }
  .request-stat strong {
    font-size: 24px;
  }
  .request-stat small {
    display: block;
    margin: 3px 0 0;
  }
  .request-list {
    grid-template-columns: 1fr;
  }
  .request-card {
    padding: 18px;
  }
  .request-checks {
    grid-template-columns: 1fr;
  }
  .request-facts > div {
    grid-template-columns: 65px minmax(0, 1fr);
    gap: 8px;
  }
}
</style>
