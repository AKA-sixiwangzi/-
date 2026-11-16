<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, perform, ui, session } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
import { positionLabel } from '../lib/organization.js'
import {
  departmentScopeLabel,
  isAllDepartments,
  userMatchesDepartmentScope,
} from '../lib/department-scope.js'
const route = useRoute(),
  manage = computed(() => route.path.startsWith('/manage')),
  rows = ref([]),
  detail = ref(null),
  courses = ref([]),
  users = ref([]),
  org = ref({ departments: [], positions: [], grades: [] }),
  dialog = ref(''),
  form = ref({}),
  selected = ref(null)
const selectedDepartments = computed(() =>
  org.value.departments.filter((department) => form.value.departmentIds?.includes(department.id)),
)
const eligibleUsers = computed(() =>
  users.value.filter(
    (user) =>
      user.active &&
      userMatchesDepartmentScope(user, selected.value) &&
      user.department_id === form.value.departmentId &&
      user.position_id === form.value.positionId &&
      (!selected.value?.position_id || user.position_id === selected.value.position_id),
  ),
)
const eligibleDepartments = computed(() =>
  org.value.departments.filter(
    (department) =>
      isAllDepartments(selected.value) || selected.value?.department_ids?.includes(department.id),
  ),
)
const eligiblePositions = computed(() =>
  org.value.positions.filter(
    (position) => !selected.value?.position_id || position.id === selected.value.position_id,
  ),
)
watch(
  () => [form.value.departmentId, form.value.positionId],
  () => {
    if (dialog.value === 'assign') form.value.userIds = []
  },
)
const invalidDepartmentSelection = computed(
  () => dialog.value === 'path' && !form.value.allDepartments && !form.value.departmentIds?.length,
)
function setDepartmentMode(all) {
  form.value.allDepartments = all
  if (all) form.value.departmentIds = []
}
const { loading, error, reload } = useLoad(async () => {
  ;[rows.value, org.value] = await Promise.all([api('/paths'), api('/organization')])
  if (rows.value.length) detail.value = await api('/paths/' + rows.value[0].id)
  if (manage.value)
    [courses.value, users.value] = await Promise.all([api('/courses'), api('/admin/users')])
})
async function choose(id) {
  await perform(async () => (detail.value = await api('/paths/' + id)))
}
function open(type, p) {
  selected.value = p
  form.value =
    type === 'path'
      ? { title: '', positionId: null, gradeId: null, allDepartments: true, departmentIds: [] }
      : type === 'stage'
        ? { title: '', sortOrder: (detail.value?.stages.length || 0) + 1, courseIds: [] }
        : { userIds: [], departmentId: null, positionId: p?.position_id || null }
  dialog.value = type
}
async function submit() {
  await perform(async () => {
    let pathId = selected.value?.id
    if (dialog.value === 'path') {
      if (invalidDepartmentSelection.value) throw new Error('请至少选择一个适用部门')
      const created = await api('/paths', 'POST', form.value)
      pathId = created.id
    }
    if (dialog.value === 'stage')
      await api('/paths/' + selected.value.id + '/stages', 'POST', form.value)
    if (dialog.value === 'assign')
      await api('/paths/' + selected.value.id + '/assign', 'POST', form.value)
    dialog.value = ''
    await reload()
    if (pathId) await choose(pathId)
  }, '操作已保存')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">CAREER DEVELOPMENT</p>
      <h1>{{ manage ? '成长路线管理' : '我的成长路线' }}</h1>
      <p>从岗位基础到专业进阶，清楚知道下一步往哪里走。</p>
    </div>
    <button v-if="manage" class="btn" @click="open('path')">
      <Icon name="plus" :size="16" />新建路线
    </button>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="tabs page-tabs">
      <button
        v-for="p in rows"
        :key="p.id"
        :class="{ active: detail?.path.id === p.id }"
        @click="choose(p.id)"
      >
        {{ p.title }} {{ manage && !p.published ? '· 草稿' : '' }}
      </button>
    </div>
    <template v-if="detail"
      ><section class="panel path-banner">
        <div>
          <span class="tag">岗位成长计划</span>
          <h2>{{ detail.path.title }}</h2>
          <p class="scope-summary">
            <Icon name="building" :size="14" />适用部门：{{
              departmentScopeLabel(detail.path, org.departments)
            }}
          </p>
          <p class="scope-summary">
            适用岗位：{{
              detail.path.position_id
                ? positionLabel(org.positions.find((p) => p.id === detail.path.position_id))
                : '不限定岗位'
            }}
          </p>
          <RouterLink
            v-if="session.me?.roles.includes('MANAGER')"
            to="/learning-requests"
            class="text-link"
            >申请补充课程<Icon name="right" :size="13"
          /></RouterLink>
          <p class="caption">
            完成阶段课程并通过关联考试，即可解锁下一阶段。已有有效学习结果可复用。
          </p>
        </div>
        <div class="btn-row" v-if="manage">
          <button
            v-if="!detail.path.published"
            class="btn outline"
            @click="open('stage', detail.path)"
          >
            增加阶段</button
          ><button
            v-if="!detail.path.published"
            class="btn"
            @click="
              perform(async () => {
                await api('/paths/' + detail.path.id + '/publish', 'POST')
                await choose(detail.path.id)
              }, '路线已发布')
            "
          >
            发布路线</button
          ><button v-else class="btn" @click="open('assign', detail.path)">分配员工</button>
        </div>
        <button
          v-else
          class="btn yellow"
          @click="
            perform(
              () => api('/paths/' + detail.path.id + '/certificate', 'POST'),
              '已领取路线证书',
            )
          "
        >
          <Icon name="award" :size="17" />领取通关证书
        </button>
      </section>
      <div class="path-stages">
        <article
          v-for="(item, i) in detail.stages"
          :key="item.stage.id"
          class="path-stage"
          :class="{ locked: !item.unlocked && !manage }"
        >
          <div class="stage-number">
            <Icon v-if="item.passed" name="check" :size="23" /><Icon
              v-else-if="!item.unlocked && !manage"
              name="lock"
              :size="20"
            /><span v-else>{{ String(i + 1).padStart(2, '0') }}</span>
          </div>
          <section class="panel">
            <header class="panel-head">
              <div>
                <small class="eyebrow">STAGE {{ String(i + 1).padStart(2, '0') }}</small>
                <h2>{{ item.stage.title }}</h2>
              </div>
              <span class="tag">{{
                manage
                  ? '阶段 ID ' + item.stage.id
                  : item.passed
                    ? '已通关'
                    : item.unlocked
                      ? '当前阶段'
                      : '完成前序阶段后解锁'
              }}</span>
            </header>
            <div class="stage-courses">
              <RouterLink v-for="c in item.courses" :key="c.id" :to="'/courses/' + c.id"
                ><span class="stage-course-icon"><Icon name="book" :size="20" /></span>
                <div>
                  <h3>{{ c.title }}</h3>
                  <small>{{ c.completed ? '课程已完成' : '完成必修课程' }}</small>
                </div>
                <Icon :name="c.completed ? 'success' : 'right'" :size="17"
              /></RouterLink>
            </div>
            <div class="stage-exams" v-for="e in item.exams" :key="e.id">
              <Icon name="exam" :size="17" /><span>{{ e.title }}</span
              ><RouterLink to="/exams" class="text-link"
                >{{ e.passed ? '已通过' : '前往考试中心' }}<Icon name="right" :size="13"
              /></RouterLink>
            </div>
          </section>
        </article></div></template
    ><Empty
      v-else
      title="成长路线尚未开启"
      text="管理员会根据岗位配置成长阶段，发布后分配给你。" /></LoadState
  ><Modal
    v-if="dialog"
    :title="
      dialog === 'path' ? '新建岗位成长路线' : dialog === 'stage' ? '配置学习阶段' : '分配成长路线'
    "
    @close="dialog = ''"
    ><form @submit.prevent="submit">
      <div class="form-grid">
        <template v-if="dialog === 'path'"
          ><label class="field span-2"
            ><span>路线名称</span><input v-model="form.title" required maxlength="200"
          /></label>
          <fieldset class="department-scope span-2">
            <legend>适用部门</legend>
            <div class="department-modes">
              <label :class="{ active: form.allDepartments }">
                <input
                  type="radio"
                  name="route-department-mode"
                  :checked="form.allDepartments"
                  @change="setDepartmentMode(true)"
                />所有部门
              </label>
              <label :class="{ active: !form.allDepartments }">
                <input
                  type="radio"
                  name="route-department-mode"
                  :checked="!form.allDepartments"
                  @change="setDepartmentMode(false)"
                />指定部门（可多选）
              </label>
            </div>
            <template v-if="!form.allDepartments">
              <div v-if="org.departments.length" class="department-options">
                <label v-for="department in org.departments" :key="department.id">
                  <input type="checkbox" v-model="form.departmentIds" :value="department.id" />
                  {{ department.name }}
                </label>
              </div>
              <p v-else class="scope-empty">暂无可选部门，请先在“组织与人员”中新增部门。</p>
              <div v-if="selectedDepartments.length" class="department-selected" aria-live="polite">
                <small>已选 {{ selectedDepartments.length }} 个部门</small>
                <span v-for="department in selectedDepartments" :key="department.id" class="tag">{{
                  department.name
                }}</span>
              </div>
              <p v-else class="scope-validation" role="status">请至少选择一个适用部门。</p>
            </template>
            <p class="scope-hint">
              所有部门不限制部门范围；指定部门后，分配员工时仅显示这些部门的员工。
            </p>
          </fieldset>
          <label class="field"
            ><span>适用岗位</span
            ><select v-model="form.positionId">
              <option :value="null">不限定</option>
              <option v-for="p in org.positions" :value="p.id" :key="p.id">{{ p.name }}</option>
            </select></label
          ><label class="field"
            ><span>适用职级</span
            ><select v-model="form.gradeId">
              <option :value="null">不限定</option>
              <option v-for="p in org.grades" :value="p.id" :key="p.id">{{ p.name }}</option>
            </select></label
          ></template
        ><template v-if="dialog === 'stage'"
          ><label class="field"><span>阶段名称</span><input v-model="form.title" required /></label
          ><label class="field"
            ><span>阶段顺序</span
            ><input v-model.number="form.sortOrder" type="number" min="1" required
          /></label>
          <div class="field span-2">
            <span>阶段课程</span>
            <div class="check-list">
              <label v-for="c in courses" :key="c.id"
                ><input type="checkbox" v-model="form.courseIds" :value="c.id" />{{
                  c.title
                }}</label
              >
            </div>
          </div>
          <p class="notice span-2">
            如需关联阶段考试，请在“题库与考试”中使用阶段 ID 配置，再发布路线。
          </p></template
        >
        <template v-if="dialog === 'assign'">
          <p class="notice span-2">
            先选择部门和岗位，再勾选参加人员。适用部门：{{
              departmentScopeLabel(selected, org.departments)
            }}。
          </p>
          <label class="field"
            ><span>部门</span
            ><select v-model="form.departmentId" required>
              <option :value="null" disabled>请选择部门</option>
              <option
                v-for="department in eligibleDepartments"
                :key="department.id"
                :value="department.id"
              >
                {{ department.name }}
              </option>
            </select></label
          >
          <label class="field"
            ><span>岗位</span
            ><select v-model="form.positionId" required>
              <option :value="null" disabled>请选择岗位</option>
              <option v-for="position in eligiblePositions" :key="position.id" :value="position.id">{{ position.name }}</option>
            </select></label
          >
          <div class="field span-2">
            <span
              >选择员工 <small class="muted">已选 {{ form.userIds.length }} 人</small></span
            >
            <div class="check-list">
              <label v-for="u in eligibleUsers" :key="u.id"
                ><input type="checkbox" v-model="form.userIds" :value="u.id" />{{
                  u.display_name
                }}</label
              >
            </div>
            <p v-if="!eligibleUsers.length" class="scope-empty">
              {{
                !form.departmentId || !form.positionId
                  ? '请选择部门和岗位后查看员工。'
                  : '该部门和岗位下暂无可分配的启用员工，请先维护员工的部门和岗位。'
              }}
            </p>
          </div></template
        >
      </div>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="dialog = ''">取消</button
        ><button
          class="btn"
          :disabled="
            ui.busy > 0 ||
            invalidDepartmentSelection ||
            (dialog === 'assign' &&
              (!form.departmentId || !form.positionId || !form.userIds?.length))
          "
        >
          保存
        </button>
      </footer>
    </form></Modal
  >
</template>
<style scoped>
.scope-summary {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 11px;
  color: #6c86a7;
  line-height: 1.8;
  margin: 9px 0;
}
.scope-summary svg {
  margin-top: 3px;
}
.department-scope {
  border: 0;
  margin: 0;
  padding: 0;
  min-width: 0;
}
.department-scope legend {
  font-size: 12px;
  font-weight: 500;
  color: #61748e;
  padding: 0;
  margin-bottom: 9px;
}
.department-modes {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
}
.department-modes label {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid #dce5f1;
  border-radius: 6px;
  padding: 10px 13px;
  font-size: 11px;
  color: #8093ad;
  cursor: pointer;
}
.department-modes label.active {
  border-color: #86a8d4;
  background: #f0f5fd;
  color: #003f98;
}
.department-modes input,
.department-options input {
  margin: 0;
}
.department-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 11px;
  border: 1px solid #e2eaf5;
  background: #fafcff;
  border-radius: 7px;
  padding: 14px;
  margin-top: 12px;
  max-height: 180px;
  overflow: auto;
}
.department-options label {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  color: #5d7799;
  line-height: 1.7;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.department-options input {
  margin-top: 4px;
  flex-shrink: 0;
}
.department-selected {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 10px;
}
.department-selected small {
  color: #8c9eb6;
  font-size: 10px;
}
.scope-hint,
.scope-empty {
  font-size: 10px;
  line-height: 1.9;
  color: #94a5bb;
  margin-top: 9px;
}
.scope-validation {
  font-size: 10px;
  color: #b18b39;
  line-height: 1.9;
  margin-top: 9px;
}
.path-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  padding: 26px;
  margin-bottom: 28px;
  background: linear-gradient(110deg, #fff, #f0f5ff);
}
.path-banner h2 {
  font-size: 21px;
  margin: 12px 0 9px;
}
.path-stages {
  margin-left: 20px;
}
.path-stage {
  position: relative;
  display: grid;
  grid-template-columns: 60px 1fr;
  gap: 20px;
  padding-bottom: 28px;
}
.path-stage:before {
  content: '';
  position: absolute;
  left: 21px;
  top: 45px;
  bottom: 0;
  border-left: 2px dashed #dae3ef;
}
.path-stage:last-child:before {
  display: none;
}
.stage-number {
  width: 44px;
  height: 44px;
  border: 5px solid #e4edf9;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #003f98;
  color: #fff;
  font-size: 14px;
  margin-top: 10px;
  z-index: 1;
}
.locked .stage-number {
  background: #eef2f7;
  color: #a8b5c7;
  border-color: #e9eef5;
}
.stage-courses {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.stage-courses > a {
  border: 1px solid #e8edf5;
  border-radius: 7px;
  padding: 15px;
  display: flex;
  gap: 12px;
  align-items: center;
  background: #fbfcff;
}
.stage-course-icon {
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #eaf1fb;
  color: #7595bd;
  border-radius: 8px;
}
.stage-courses > a > div {
  flex: 1;
}
.stage-courses h3 {
  font-size: 12px;
  font-weight: 500;
}
.stage-courses small {
  font-size: 10px;
  color: #9dabc0;
  display: block;
  margin-top: 5px;
}
.stage-courses > a > svg {
  color: #a3b5cc;
}
.stage-exams {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: #8399b5;
  margin-top: 17px;
}
.stage-exams .text-link {
  margin-left: auto;
}
@media (max-width: 1000px) {
  .stage-courses {
    grid-template-columns: 1fr;
  }
  .path-banner {
    align-items: flex-start;
    flex-direction: column;
  }
}
@media (max-width: 600px) {
  .department-options {
    grid-template-columns: 1fr;
  }
  .path-stages {
    margin-left: 0;
  }
  .path-stage {
    grid-template-columns: 35px 1fr;
    gap: 10px;
  }
  .stage-number {
    width: 34px;
    height: 34px;
    font-size: 11px;
  }
  .path-stage:before {
    left: 16px;
  }
  .path-stage .panel {
    padding: 14px;
  }
  .stage-courses > a {
    padding: 12px;
    gap: 9px;
  }
  .stage-course-icon {
    display: none;
  }
  .stage-courses h3 {
    font-size: 11px;
  }
  .path-stage .panel-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
