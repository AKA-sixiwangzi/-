<script setup>
import { ref } from 'vue'
import { api, perform, ui } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import Status from '../components/Status.vue'
import Empty from '../components/Empty.vue'
import LoadState from '../components/LoadState.vue'
const rows = ref([]),
  selected = ref(null),
  learners = ref([]),
  work = ref(null),
  assignment = ref({ title: '', instructions: '' }),
  score = ref({}),
  feedback = ref({})
const { loading, error, reload } = useLoad(
  async () => (rows.value = await api('/dashboards/teaching')),
)
async function open(c) {
  await perform(async () => {
    selected.value = c
    learners.value = await api('/courses/' + c.id + '/learners')
    work.value = await api('/courses/' + c.id + '/coursework')
  })
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">INSTRUCTOR WORKSPACE</p>
      <h1>授课工作台</h1>
      <p>关注每一位学员的进展，让好内容带来真实成长。</p>
    </div>
    <RouterLink to="/creation" class="btn"><Icon name="plus" :size="16" />创作课程</RouterLink>
  </div>
  <div class="notice">
    此工作区只展示归属于你的课程及相关学习记录。你的个人学习情况仍在“学习工作台”。
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="kpi-grid">
      <article class="kpi">
        <div class="kpi-top">
          我的课程<i class="blue"><Icon name="book" /></i>
        </div>
        <div class="kpi-value">{{ rows.length }}<small>门</small></div>
      </article>
      <article class="kpi">
        <div class="kpi-top">
          已发布课程<i class="green"><Icon name="checks" /></i>
        </div>
        <div class="kpi-value">
          {{ rows.filter((c) => c.status === 'PUBLISHED').length }}<small>门</small>
        </div>
      </article>
      <article class="kpi">
        <div class="kpi-top">
          参训人课任务<i class="gold"><Icon name="users" /></i>
        </div>
        <div class="kpi-value">
          {{ rows.reduce((s, c) => s + Number(c.learner_count || 0), 0) }}<small>项</small>
        </div>
      </article>
      <article class="kpi">
        <div class="kpi-top">
          完成任务<i class="violet"><Icon name="award" /></i>
        </div>
        <div class="kpi-value">
          {{ rows.reduce((s, c) => s + Number(c.completed_count || 0), 0) }}<small>项</small>
        </div>
      </article>
    </div>
    <div v-if="rows.length" class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>课程</th>
            <th>发布状态</th>
            <th>参训人数</th>
            <th>完成人数</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in rows" :key="c.id">
            <td>
              <strong>{{ c.title }}</strong>
            </td>
            <td><Status :value="c.status" /></td>
            <td>{{ c.learner_count }}</td>
            <td>{{ c.completed_count }}</td>
            <td><button class="text-link" @click="open(c)">学习情况与作业</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <Empty
      v-else
      title="你的授课故事，即将开始"
      text="课程审核发布后，就能在这里查看学员进展。" /></LoadState
  ><Modal
    v-if="selected && work"
    :title="selected.title + ' · 教学管理'"
    wide
    @close="selected = null"
    ><h3>学员学习情况</h3>
    <div class="table-wrap section-spacer">
      <table>
        <thead>
          <tr>
            <th>员工</th>
            <th>有效时长</th>
            <th>课程状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in learners" :key="u.id">
            <td>{{ u.display_name }}</td>
            <td>{{ Math.floor(u.effective_seconds || 0) }} 秒</td>
            <td>{{ u.completed ? '已完成' : '待完成' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <Empty v-if="!learners.length" title="尚未有员工分配到本课程" />
    <h3 class="section-spacer">作业提交与反馈</h3>
    <article v-for="s in work.submissions" :key="s.id" class="question-block section-spacer">
      <p class="caption">员工 ID {{ s.user_id }} · 提交 #{{ s.id }}</p>
      <p class="prose section-spacer">{{ s.content }}</p>
      <div class="form-grid section-spacer">
        <label class="field"
          ><span>评分（0—100）</span
          ><input type="number" min="0" max="100" v-model.number="score[s.id]" /></label
        ><label class="field"><span>评价反馈</span><input v-model="feedback[s.id]" /></label>
      </div>
      <button
        class="btn small section-spacer"
        :disabled="
          ui.busy > 0 ||
          !Number.isInteger(score[s.id]) ||
          score[s.id] < 0 ||
          score[s.id] > 100 ||
          !feedback[s.id]?.trim()
        "
        @click="
          perform(async () => {
            await api('/submissions/' + s.id + '/grade', 'PUT', {
              score: Number(score[s.id]),
              feedback: feedback[s.id] || '',
            })
            await open(selected)
          }, '作业评价已保存')
        "
      >
        保存评价
      </button>
      <p class="caption section-spacer">
        现有结果：{{ s.score == null ? '待评价' : s.score + '分 · ' + s.feedback }}
      </p>
    </article>
    <p v-if="!work.submissions.length" class="caption section-spacer">暂无作业提交。</p>
    <form
      class="section-spacer"
      @submit.prevent="
        perform(async () => {
          await api('/courses/' + selected.id + '/assignments', 'POST', assignment)
          assignment = { title: '', instructions: '' }
          await open(selected)
        }, '作业已发布')
      "
    >
      <h3>布置新作业</h3>
      <div class="form-grid section-spacer">
        <label class="field span-2"
          ><span>作业标题</span><input v-model="assignment.title" required maxlength="200" /></label
        ><label class="field span-2"
          ><span>实践要求</span
          ><textarea v-model="assignment.instructions" required maxlength="3000" />
        </label>
      </div>
      <button class="btn section-spacer" :disabled="ui.busy > 0">发布作业</button>
    </form></Modal
  >
</template>
