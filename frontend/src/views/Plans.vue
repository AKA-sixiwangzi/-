<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { api, perform, ui } from '../lib/api.js'
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
  form = ref({ title: '', startAt: '', dueAt: '', courseIds: [], examIds: [], userIds: [] })
const { loading, error, reload } = useLoad(async () => {
  rows.value = await api('/plans')
  if (manage.value) {
    ;[courses.value, users.value] = await Promise.all([api('/courses'), api('/admin/users')])
    exams.value = await api('/teaching/exams')
  }
})
const examIds = ref('')
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
            <td><Status :value="p.status" /></td>
            <td>
              <div class="actions">
                <button
                  class="text-link"
                  @click="perform(async () => (detail = await api('/plans/' + p.id)))"
                >
                  查看任务</button
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
</template>
<style scoped>
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
