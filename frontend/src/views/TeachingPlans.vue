<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, perform, ui, session } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText } from '../lib/rules.js'
import Icon from '../components/Icon.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
import Status from '../components/Status.vue'
const route = useRoute(),
  rows = ref([]),
  detail = ref(null),
  learnerId = ref(null)
const { loading, error, reload } = useLoad(async () => {
  rows.value = await api('/teaching/plans')
  const selected =
    rows.value.find((p) => p.id === detail.value?.plan.id) ||
    rows.value.find((p) => p.id === Number(route.query.plan)) ||
    rows.value[0]
  detail.value = selected ? await api('/teaching/plans/' + selected.id) : null
  learnerId.value = null
})
async function choose(id) {
  await perform(async () => {
    const next = await api('/teaching/plans/' + id)
    detail.value = next
    learnerId.value = null
  })
}
watch(
  () => route.query.plan,
  (id) => {
    if (id && rows.value.some((p) => p.id === Number(id))) choose(Number(id))
  },
)
const selectedPlan = computed(() => rows.value.find((p) => p.id === detail.value?.plan.id))
const visibleLearners = (course) =>
  course.learners.filter((u) => learnerId.value == null || u.id === learnerId.value)
const visibleExamResults = (exam) =>
  exam.results.filter((result) => learnerId.value == null || result.user_id === learnerId.value)
function gradeText(record) {
  if (record.status === 'GRADED')
    return `${record.score ?? '—'} 分${record.passed != null ? (record.passed ? ' · 已通过' : ' · 未通过') : ''}`
  return (
    {
      NOT_STARTED: '未参加',
      IN_PROGRESS: '考试中',
      PENDING_GRADE: '待人工阅卷',
      NOT_SUBMITTED: '未提交',
      SUBMITTED: '待评价',
    }[record.status] || record.status
  )
}
function canStudy(item) {
  return (
    session.me?.roles.includes('ADMIN') ||
    item.course.owner_id === session.me?.user.id ||
    item.learners.some((u) => u.id === session.me?.user.id)
  )
}
async function remind() {
  await perform(async () => {
    await api('/plans/' + detail.value.plan.id + '/remind', 'POST')
    await reload()
  }, '已检查学习提醒与结果推送，重复通知会自动合并')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">TRAINING PLAN FOLLOW-UP</p>
      <h1>计划跟进看板</h1>
      <p>按培训计划、参训人员和课程，查看教学任务与培训结果。</p>
    </div>
    <button class="btn white" @click="reload"><Icon name="refresh" :size="16" />刷新</button>
  </div>
  <p class="notice">
    计划负责人可查看整项计划；内训师查看自己课程的参训情况。成绩中的“待人工阅卷”和“待评价”会单独显示。
  </p>
  <LoadState :loading="loading" :error="error" @retry="reload">
    <div v-if="rows.length" class="tabs page-tabs">
      <button
        v-for="plan in rows"
        :key="plan.id"
        :class="{ active: detail?.plan.id === plan.id }"
        @click="choose(plan.id)"
      >
        {{ plan.title }}
      </button>
    </div>
    <template v-if="detail">
      <section class="panel follow-banner">
        <div>
          <span class="tag">{{ detail.plan.status === 'DRAFT' ? '计划草稿' : '培训计划' }}</span>
          <h2>{{ detail.plan.title }}</h2>
          <p>
            负责人：{{
              detail.plan.responsible_name ||
              selectedPlan?.responsible_name ||
              '员工 #' + (detail.plan.responsible_user_id ?? detail.plan.owner_id)
            }}
          </p>
          <p>{{ dateText(detail.plan.start_at) }} — {{ dateText(detail.plan.due_at) }}</p>
        </div>
        <div v-if="detail.can_manage_reminders" class="btn-row">
          <RouterLink
            :to="session.me?.roles.includes('ADMIN') ? '/manage/plans' : '/plans'"
            class="btn outline"
            >负责人及提醒规则</RouterLink
          ><button
            v-if="detail.plan.status === 'PUBLISHED'"
            class="btn"
            :disabled="ui.busy > 0"
            @click="remind"
          >
            执行提醒与推送
          </button>
        </div>
      </section>
      <div class="follow-metrics">
        <article class="panel">
          <small>可查看课程</small><strong>{{ detail.courses.length }}<span>门</span></strong>
        </article>
        <article class="panel">
          <small>参训人员</small><strong>{{ detail.members.length }}<span>人</span></strong>
        </article>
        <article class="panel">
          <small>已完成人课任务</small
          ><strong
            >{{
              selectedPlan?.completed_tasks ??
              detail.courses.reduce(
                (sum, c) => sum + c.learners.filter((u) => u.completed).length,
                0,
              )
            }}<span>项</span></strong
          >
        </article>
        <article class="panel">
          <small>待评阅任务</small
          ><strong>{{ selectedPlan?.pending_grade ?? 0 }}<span>项</span></strong>
        </article>
      </div>
      <div class="panel follow-filter">
        <label class="field"
          ><span>按参训人员查看</span
          ><select v-model="learnerId">
            <option :value="null">全部参训人员</option>
            <option v-for="person in detail.members" :key="person.id" :value="person.id">
              {{ person.display_name }}
            </option>
          </select></label
        >
        <p>选择员工后，下方课程、考试和作业结果会显示该员工的记录。</p>
      </div>
      <section v-for="item in detail.courses" :key="item.course.id" class="panel follow-course">
        <header class="panel-head">
          <div>
            <p class="eyebrow">COURSE {{ item.course.id }}</p>
            <h2>{{ item.course.title }}</h2>
          </div>
          <RouterLink v-if="canStudy(item)" :to="'/courses/' + item.course.id" class="text-link"
            >查看课程<Icon name="right" :size="14"
          /></RouterLink>
        </header>
        <div class="follow-content">
          <article>
            <h3>
              <Icon name="book" :size="15" />培训资料 <span>{{ item.materials.length }}</span>
            </h3>
            <p v-for="material in item.materials" :key="material.id">
              {{ material.title }}<small>{{ material.required ? '必修' : '选学' }}</small>
            </p>
            <p v-if="!item.materials.length" class="muted">暂无配套资料</p>
          </article>
          <article>
            <h3>
              <Icon name="exam" :size="15" />关联考试 <span>{{ item.exams.length }}</span>
            </h3>
            <p v-for="exam in item.exams" :key="exam.id">
              {{ exam.title }}<small>通过分数 {{ exam.pass_score }}</small>
            </p>
            <p v-if="!item.exams.length" class="muted">未关联考试</p>
          </article>
          <article>
            <h3>
              <Icon name="list" :size="15" />实践作业 <span>{{ item.assignments.length }}</span>
            </h3>
            <p v-for="assignment in item.assignments" :key="assignment.id">
              {{ assignment.title }}
            </p>
            <p v-if="!item.assignments.length" class="muted">未布置作业</p>
          </article>
        </div>
        <div v-if="visibleLearners(item).length" class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>参训员工</th>
                <th>课程状态</th>
                <th>考试结果</th>
                <th>作业结果</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="person in visibleLearners(item)" :key="person.id">
                <td>
                  <strong>{{ person.display_name }}</strong>
                </td>
                <td>
                  <Status :value="person.completed ? 'COMPLETED' : '待完成'" /><small
                    v-if="person.completed_at"
                    class="follow-cell-note"
                    >{{ dateText(person.completed_at) }}</small
                  >
                </td>
                <td>
                  <div
                    v-for="exam in person.exam_results"
                    :key="exam.exam_id"
                    class="follow-result"
                  >
                    <span>{{ exam.title }}</span
                    ><small :class="{ 'pending-mark': exam.status === 'PENDING_GRADE' }">{{
                      gradeText(exam)
                    }}</small>
                  </div>
                  <small v-if="!person.exam_results?.length" class="muted">无考试任务</small>
                </td>
                <td>
                  <div
                    v-for="assignment in person.assignment_results"
                    :key="assignment.assignment_id"
                    class="follow-result"
                  >
                    <span>{{ assignment.title }}</span
                    ><small>{{ gradeText(assignment) }}</small
                    ><small v-if="assignment.feedback">{{ assignment.feedback }}</small>
                  </div>
                  <small v-if="!person.assignment_results?.length" class="muted">无作业任务</small>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="caption">该员工没有可查看的课程任务。</p>
      </section>
      <section v-if="detail.plan_exams?.length" class="panel follow-course">
        <header class="panel-head">
          <div>
            <p class="eyebrow">PLAN ASSESSMENTS</p>
            <h2>计划关联考试汇总</h2>
          </div>
        </header>
        <article v-for="exam in detail.plan_exams" :key="exam.id" class="follow-plan-exam">
          <h3>{{ exam.title }}</h3>
          <div v-if="visibleExamResults(exam).length" class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>参训员工</th>
                  <th>考试进展与成绩</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="result in visibleExamResults(exam)" :key="result.user_id">
                  <td>
                    {{
                      detail.members.find((person) => person.id === result.user_id)?.display_name ||
                      '员工 #' + result.user_id
                    }}
                  </td>
                  <td>
                    <span :class="{ 'pending-mark': result.status === 'PENDING_GRADE' }">{{
                      gradeText(result)
                    }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="caption">暂无该员工的考试任务。</p>
        </article>
      </section>
      <section class="panel follow-course">
        <header class="panel-head">
          <div>
            <p class="eyebrow">OFFLINE TRAINING</p>
            <h2>关联线下培训</h2>
          </div>
        </header>
        <div v-for="item in detail.offline_sessions" :key="item.id" class="follow-offline">
          <Icon name="calendar" :size="18" />
          <div>
            <strong>{{ item.title }}</strong
            ><small>{{ dateText(item.start_at) }} — {{ dateText(item.end_at) }}</small
            ><small>{{ item.location }} · {{ item.instructor_name }}</small>
          </div>
          <RouterLink
            v-if="session.me?.roles.includes('ADMIN') || item.instructor_id === session.me?.user.id"
            :to="'/offline-training?session=' + item.id"
            class="text-link"
            >查看登记</RouterLink
          >
        </div>
        <p v-if="!detail.offline_sessions?.length" class="caption">本计划暂无线下培训安排。</p>
      </section> </template
    ><Empty
      v-else
      title="暂无需要跟进的培训计划"
      text="成为计划负责人或课程加入培训计划后，可在这里查看教学与培训结果。"
    />
  </LoadState>
</template>
<style scoped>
.follow-banner {
  padding: 25px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin: 24px 0 20px;
  background: linear-gradient(120deg, #fff, #f1f6ff);
}
.follow-plan-exam {
  padding: 16px 0;
  border-top: 1px solid #edf2f8;
}
.follow-plan-exam h3 {
  font-size: 13px;
  color: #607d9e;
  margin-bottom: 13px;
}
.follow-plan-exam .pending-mark {
  color: #ad8a27;
}
.follow-banner h2 {
  font-size: 21px;
  margin: 13px 0;
}
.follow-banner p {
  font-size: 11px;
  color: #8197b3;
  line-height: 1.9;
}
.follow-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 21px;
}
.follow-metrics article {
  padding: 18px 20px;
}
.follow-metrics small {
  font-size: 11px;
  color: #8d9fb8;
}
.follow-metrics strong {
  display: block;
  font-size: 27px;
  color: #003f98;
  margin-top: 9px;
}
.follow-metrics strong span {
  font-size: 10px;
  font-weight: 400;
  color: #9aacbf;
  margin-left: 8px;
}
.follow-filter {
  padding: 18px 22px;
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 22px;
}
.follow-filter label {
  width: 250px;
  max-width: 100%;
}
.follow-filter p {
  font-size: 11px;
  line-height: 1.8;
  color: #95a6bb;
}
.follow-course {
  padding: 24px;
  margin-bottom: 22px;
  min-width: 0;
}
.follow-course h2 {
  font-size: 17px;
}
.follow-content {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
  background: #f7f9fd;
  border: 1px solid #edf2f8;
  padding: 18px;
  border-radius: 8px;
  margin: 18px 0 22px;
}
.follow-content article {
  min-width: 0;
}
.follow-content h3 {
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 7px;
  color: #607e9f;
  margin-bottom: 12px;
}
.follow-content h3 span {
  font-size: 10px;
  color: #9bacc1;
}
.follow-content p {
  font-size: 11px;
  color: #718baa;
  line-height: 1.9;
  overflow-wrap: anywhere;
}
.follow-content small {
  display: block;
  color: #9caec5;
  font-size: 10px;
}
.follow-result {
  margin-bottom: 9px;
  font-size: 11px;
  line-height: 1.8;
  max-width: 240px;
  overflow-wrap: anywhere;
}
.follow-result small,
.follow-cell-note {
  display: block;
  font-size: 10px;
  color: #8ea3bd;
}
.follow-result .pending-mark {
  color: #ad8a27;
}
.follow-offline {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 0;
  border-top: 1px solid #edf2f8;
  color: #7892b3;
}
.follow-offline > div {
  flex: 1;
  min-width: 0;
}
.follow-offline strong {
  font-size: 12px;
  color: #587596;
}
.follow-offline small {
  display: block;
  font-size: 10px;
  line-height: 1.9;
  color: #98aac0;
}
.follow-course .muted {
  font-size: 10px;
}
@media (max-width: 800px) {
  .follow-banner,
  .follow-filter {
    align-items: flex-start;
    flex-direction: column;
  }
  .follow-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .follow-content {
    grid-template-columns: 1fr;
  }
  .follow-course {
    padding: 18px;
  }
  .follow-filter {
    gap: 12px;
  }
  .follow-offline {
    align-items: flex-start;
    flex-wrap: wrap;
  }
}
</style>
