<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { api, perform, ui, session } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText } from '../lib/rules.js'
import Modal from '../components/Modal.vue'
import Icon from '../components/Icon.vue'
import Status from '../components/Status.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const route = useRoute(),
  manage = computed(() => route.path.startsWith('/manage')),
  rows = ref([]),
  detail = ref(null),
  dialog = ref(false),
  courses = ref([]),
  users = ref([]),
  exams = ref([]),
  ruleTarget = ref(null),
  rules = ref({}),
  form = ref({
    title: '',
    startAt: '',
    dueAt: '',
    courseIds: [],
    examIds: [],
    userIds: [],
    responsibleUserId: null,
    remindBeforeHours: 72,
    remindEveryHours: 24,
    pushResults: true,
  })
const { loading, error, reload } = useLoad(async () => {
  rows.value = await api('/plans')
  if (manage.value) {
    ;[courses.value, users.value] = await Promise.all([api('/courses'), api('/admin/users')])
    exams.value = await api('/teaching/exams')
  }
})
function canFollow(plan) {
  return (
    session.me?.roles.includes('ADMIN') ||
    (plan.responsible_user_id ?? plan.owner_id) === session.me?.user.id
  )
}
function openRules(plan) {
  ruleTarget.value = plan
  rules.value = {
    responsibleUserId: plan.responsible_user_id ?? plan.owner_id,
    remindBeforeHours: plan.remind_before_hours ?? 72,
    remindEveryHours: plan.remind_every_hours ?? 24,
    pushResults: plan.push_results ?? true,
  }
}
async function saveRules() {
  await perform(async () => {
    await api('/plans/' + ruleTarget.value.id + '/reminder-rules', 'PUT', rules.value)
    ruleTarget.value = null
    await reload()
  }, '负责人和提醒规则已保存')
}
async function remind(plan) {
  await perform(async () => {
    const result = await api('/plans/' + plan.id + '/remind', 'POST')
    return result
  }, '提醒与结果推送已检查，同一周期或结果不会重复通知')
}
async function create() {
  await perform(async () => {
    await api('/plans', 'POST', {
      ...form.value,
      startAt: new Date(form.value.startAt + '+08:00').toISOString(),
      dueAt: new Date(form.value.dueAt + '+08:00').toISOString(),
      examIds: form.value.examIds,
    })
    dialog.value = false
    await reload()
  }, '计划草稿已创建，请发布以分配任务')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">TRAINING CALENDAR</p>
      <h1>{{ manage ? '培训计划管理' : '培训计划' }}</h1>
      <p>看清培训目标、截止时间和需要完成的每一项任务。</p>
    </div>
    <button v-if="manage" class="btn" @click="dialog = true">
      <Icon name="plus" :size="17" />新建培训计划
    </button>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div v-if="rows.length" class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>培训计划</th>
            <th>开始时间</th>
            <th>截止时间</th>
            <th>负责人 / 提醒</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in rows" :key="p.id">
            <td>
              <strong>{{ p.title }}</strong>
            </td>
            <td>{{ dateText(p.start_at) }}</td>
            <td>{{ dateText(p.due_at) }}</td>
            <td>
              <span>{{
                p.responsible_name ||
                users.find((u) => u.id === (p.responsible_user_id ?? p.owner_id))?.display_name ||
                '员工 #' + (p.responsible_user_id ?? p.owner_id)
              }}</span
              ><small class="plan-rule-summary"
                >截止前 {{ p.remind_before_hours ?? 72 }} 小时 · 每
                {{ p.remind_every_hours ?? 24 }} 小时提醒 · 结果推送{{
                  p.push_results === false || p.push_results === 0 ? '关闭' : '开启'
                }}</small
              >
            </td>
            <td><Status :value="p.status" /></td>
            <td>
              <div class="actions">
                <button
                  class="text-link"
                  @click="perform(async () => (detail = await api('/plans/' + p.id)))"
                >
                  查看任务</button
                ><RouterLink
                  v-if="canFollow(p) || session.me?.roles.includes('INSTRUCTOR')"
                  :to="'/teaching/plans?plan=' + p.id"
                  class="text-link"
                  >计划跟进</RouterLink
                ><button v-if="canFollow(p)" class="text-link" @click="openRules(p)">
                  提醒规则</button
                ><button
                  v-if="canFollow(p) && p.status === 'PUBLISHED'"
                  class="text-link"
                  :disabled="ui.busy > 0"
                  @click="remind(p)"
                >
                  执行提醒</button
                ><button
                  v-if="manage && p.status === 'DRAFT'"
                  class="text-link"
                  @click="
                    perform(async () => {
                      await api('/plans/' + p.id + '/publish', 'POST')
                      await reload()
                    }, '计划已发布')
                  "
                >
                  发布计划
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Empty v-else title="暂无培训计划" text="新的培训任务发布后，会在这里展示。" /></LoadState
  ><Modal v-if="detail" :title="detail.plan.title" @close="detail = null"
    ><p class="notice">
      截止：{{ dateText(detail.plan.due_at) }} · 下方展示当前账号的课程与考试状态。
    </p>
    <h3>课程任务</h3>
    <div class="task-row" v-for="c in detail.progress" :key="c.id">
      <Icon :name="c.completed ? 'success' : 'book'" :size="19" />
      <div>
        {{ c.title }}<small>{{ c.completed ? '已完成' : '待完成' }}</small>
      </div>
      <RouterLink :to="'/courses/' + c.id" class="text-link" @click="detail = null"
        >前往学习</RouterLink
      >
    </div>
    <h3 class="section-spacer">关联考试</h3>
    <div class="task-row" v-for="e in detail.exams" :key="e.id">
      <Icon name="exam" :size="19" />
      <div>
        {{ e.title }}<small>{{ e.passed ? '已通过' : '待通过' }}</small>
      </div>
      <RouterLink to="/exams" class="text-link" @click="detail = null">考试中心</RouterLink>
    </div>
    <p v-if="!detail.exams?.length" class="caption section-spacer">该计划未关联考试。</p></Modal
  ><Modal v-if="dialog" title="新建培训计划" wide @close="dialog = false"
    ><form @submit.prevent="create">
      <div class="form-grid">
        <label class="field span-2"
          ><span>计划名称</span><input v-model="form.title" required maxlength="200" /></label
        ><label class="field"
          ><span>开始时间</span
          ><input type="datetime-local" v-model="form.startAt" required /></label
        ><label class="field"
          ><span>截止时间</span><input type="datetime-local" v-model="form.dueAt" required
        /></label>
        <div class="field span-2">
          <span>培训课程</span>
          <div class="check-list">
            <label v-for="c in courses" :key="c.id"
              ><input type="checkbox" v-model="form.courseIds" :value="c.id" />{{ c.title }}</label
            >
          </div>
        </div>
        <div class="field span-2">
          <span>参加员工（固定名单）</span>
          <div class="check-list">
            <label v-for="u in users.filter((x) => x.active)" :key="u.id"
              ><input type="checkbox" v-model="form.userIds" :value="u.id" />{{
                u.display_name
              }}</label
            >
          </div>
        </div>
        <div class="field span-2">
          <span>关联考试（可选）</span>
          <div class="check-list">
            <label v-for="e in exams" :key="e.id"
              ><input type="checkbox" v-model="form.examIds" :value="e.id" />{{ e.title }}</label
            >
          </div>
          <small>计划课程必须覆盖所选考试的前置课程。</small>
        </div>
        <label class="field span-2"
          ><span>计划负责人</span
          ><select v-model="form.responsibleUserId">
            <option :value="null">当前创建人负责</option>
            <option v-for="u in users.filter((x) => x.active)" :key="u.id" :value="u.id">
              {{ u.display_name }}
            </option></select
          ><small>负责人可以跟进计划、调整提醒规则并收到培训结果。</small></label
        >
        <label class="field"
          ><span>截止前开始提醒（小时）</span
          ><input
            v-model.number="form.remindBeforeHours"
            type="number"
            required
            min="0"
            max="8760"
            step="1"
        /></label>
        <label class="field"
          ><span>重复提醒间隔（小时）</span
          ><input
            v-model.number="form.remindEveryHours"
            type="number"
            required
            min="1"
            max="720"
            step="1"
        /></label>
        <label class="plan-checkbox span-2"
          ><input type="checkbox" v-model="form.pushResults" />向计划负责人推送培训结果</label
        >
        <p class="notice span-2">
          提醒通过站内消息发送给待完成人员；待阅卷会单独标记。相同提醒周期和相同结果不重复推送。
        </p>
      </div>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="dialog = false">取消</button
        ><button
          class="btn"
          :disabled="ui.busy > 0 || !form.courseIds.length || !form.userIds.length"
        >
          创建草稿
        </button>
      </footer>
    </form></Modal
  >
  <Modal v-if="ruleTarget" title="计划负责人和提醒规则" @close="ruleTarget = null"
    ><form @submit.prevent="saveRules">
      <p class="notice">{{ ruleTarget.title }} · 提醒发送到消息中心。</p>
      <div class="form-grid section-spacer">
        <label v-if="manage" class="field span-2"
          ><span>计划负责人</span
          ><select v-model="rules.responsibleUserId" required>
            <option v-for="u in users.filter((x) => x.active)" :key="u.id" :value="u.id">
              {{ u.display_name }}
            </option>
          </select></label
        ><label class="field"
          ><span>截止前开始提醒（小时）</span
          ><input
            v-model.number="rules.remindBeforeHours"
            type="number"
            required
            min="0"
            max="8760"
            step="1" /></label
        ><label class="field"
          ><span>重复提醒间隔（小时）</span
          ><input
            v-model.number="rules.remindEveryHours"
            type="number"
            required
            min="1"
            max="720"
            step="1" /></label
        ><label class="plan-checkbox span-2"
          ><input type="checkbox" v-model="rules.pushResults" />向负责人推送培训结果</label
        >
      </div>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="ruleTarget = null">取消</button
        ><button class="btn" :disabled="ui.busy > 0">保存规则</button>
      </footer>
    </form></Modal
  >
</template>
<style scoped>
.plan-rule-summary {
  display: block;
  font-size: 10px;
  color: #8c9eb7;
  margin-top: 7px;
  line-height: 1.7;
}
.plan-checkbox {
  display: flex;
  gap: 9px;
  align-items: center;
  color: #667f9e;
  font-size: 12px;
}
.task-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 17px 0;
  border-bottom: 1px solid #edf1f7;
  color: #7b94b3;
  font-size: 12px;
}
.task-row > div {
  flex: 1;
  color: #536e90;
}
.task-row small {
  display: block;
  font-size: 10px;
  color: #9badc3;
  margin-top: 5px;
}
</style>
