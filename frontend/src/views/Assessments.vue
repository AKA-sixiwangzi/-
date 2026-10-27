<script setup>
import { ref, computed, watch } from 'vue'
import { api, download, session, perform, ui } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText } from '../lib/rules.js'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import Status from '../components/Status.vue'
import Empty from '../components/Empty.vue'
import LoadState from '../components/LoadState.vue'
const courses = ref([]),
  courseId = ref(null),
  questions = ref([]),
  papers = ref([]),
  exams = ref([]),
  grading = ref([]),
  learners = ref([]),
  tab = ref('questions'),
  selectedIds = ref([]),
  dialog = ref(''),
  form = ref({}),
  draftCount = ref(3),
  gradingDetail = ref(null),
  gradingScores = ref({})
const { loading, error, reload } = useLoad(async () => {
  courses.value = await api(session.me?.roles.includes('ADMIN') ? '/courses' : '/teaching/courses')
  courses.value = courses.value.filter((c) => c.status === 'PUBLISHED')
  if (!courseId.value && courses.value.length) courseId.value = courses.value[0].id
  ;[papers.value, exams.value, grading.value] = await Promise.all([
    api('/papers'),
    api('/teaching/exams'),
    api('/teaching/grading'),
  ])
  if (courseId.value) await loadCourse()
})
async function loadCourse() {
  ;[questions.value, learners.value] = await Promise.all([
    api('/courses/' + courseId.value + '/questions'),
    api('/courses/' + courseId.value + '/learners'),
  ])
  selectedIds.value = []
}
watch(courseId, (n, o) => {
  if (o && n !== o) void perform(loadCourse)
})
function open(type, p) {
  dialog.value = type
  if (type === 'question')
    form.value = {
      type: 'SINGLE',
      stem: '',
      a: '',
      b: '',
      c: '',
      d: '',
      answer: 'A',
      explanation: '',
    }
  if (type === 'paper') form.value = { title: '' }
  if (type === 'exam')
    form.value = {
      title: p?.title + ' · 考核',
      paperId: p?.id,
      stageId: null,
      startAt: '',
      endAt: '',
      durationMinutes: 30,
      passScore: 80,
      maxAttempts: 2,
      prerequisiteCourseIds: courseId.value ? [courseId.value] : [],
      userIds: [],
    }
}
async function submit() {
  await perform(async () => {
    if (dialog.value === 'question') {
      const f = form.value
      await api('/questions', 'POST', {
        courseId: courseId.value,
        type: f.type,
        stem: f.stem,
        options:
          f.type === 'TEXT'
            ? {}
            : Object.fromEntries(
                [
                  ['A', f.a],
                  ['B', f.b],
                  ['C', f.c],
                  ['D', f.d],
                ].filter(([, v]) => v.trim()),
              ),
        answer: f.answer,
        explanation: f.explanation,
      })
    }
    if (dialog.value === 'paper') {
      const n = selectedIds.value.length,
        scores = selectedIds.value.map((_, i) => Math.floor(100 / n) + (i === 0 ? 100 % n : 0))
      await api('/papers', 'POST', {
        title: form.value.title,
        questionIds: selectedIds.value,
        scores,
      })
    }
    if (dialog.value === 'exam')
      await api('/exams', 'POST', {
        ...form.value,
        startAt: new Date(form.value.startAt + '+08:00').toISOString(),
        endAt: new Date(form.value.endAt + '+08:00').toISOString(),
      })
    dialog.value = ''
    await reload()
  }, '已保存')
}
async function drafts() {
  await perform(async () => {
    await api('/courses/' + courseId.value + '/ai/question-drafts', 'POST', {
      count: draftCount.value,
    })
    await loadCourse()
  }, '题目草稿已生成，请核验题干、答案和解析后审核')
}
async function importPaper(e) {
  const file = e.target.files?.[0]
  if (!file) return
  await perform(async () => {
    if (!file.name.endsWith('.xlsx')) throw new Error('请选择标准xlsx模板')
    const form = new FormData()
    form.append('file', file)
    form.append('title', file.name.replace(/\.xlsx$/, ''))
    await api('/papers/import', 'POST', form)
    await reload()
  }, '试卷已导入，请发布后安排考试')
  e.target.value = ''
}
async function openGrading(a) {
  await perform(async () => {
    gradingDetail.value = await api('/teaching/attempts/' + a.id)
    gradingScores.value = {}
  })
}
async function grade(q) {
  await perform(async () => {
    await api('/teaching/attempts/' + gradingDetail.value.attempt.id + '/grade', 'PUT', {
      questionId: q.id,
      score: Number(gradingScores.value[q.id]),
    })
    await reload()
    gradingDetail.value = null
  }, '评分已保存，全部主观题完成后发布最终成绩')
}
const parsedQuestions = computed(() =>
  (gradingDetail.value?.questions || []).map((q) => ({
    ...q,
    snapshot: typeof q.snapshot_json === 'string' ? JSON.parse(q.snapshot_json) : q.snapshot,
  })),
)
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">TEACH · ASSESS · IMPROVE</p>
      <h1>题库与考试</h1>
      <p>课程、题目、试卷与考试安排相互关联，让考核更有依据。</p>
    </div>
    <RouterLink to="/creation" class="btn white"
      ><Icon name="plus" :size="16" />课程创作</RouterLink
    >
  </div>
  <div class="panel filter-bar">
    <div class="tabs">
      <button
        v-for="[key, label] in [
          ['questions', '课程题库'],
          ['papers', '试卷管理'],
          ['exams', '考试安排'],
          ['grading', '人工阅卷'],
        ]"
        :key="key"
        :class="{ active: tab === key }"
        @click="tab = key"
      >
        {{ label }}
      </button>
    </div>
    <select v-model="courseId" aria-label="关联课程">
      <option :value="null">选择关联课程</option>
      <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.title }}</option>
    </select>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><template v-if="tab === 'questions'"
      ><div class="workspace-banner section-spacer">
        <Icon name="ai" :size="27" />
        <div>
          <h3>从课程资料生成题目草稿</h3>
          <p>
            {{
              session.mode === 'demo'
                ? '演示模式生成固定示例题，未调用大模型。'
                : '使用课程文字资料生成题目，需配置后端模型服务。'
            }}所有草稿经人工审核后才能组卷。
          </p>
        </div>
        <div class="btn-row ai-draft-controls">
          <select v-model.number="draftCount" aria-label="生成题目数量">
            <option v-for="n in [1, 3, 5, 10]" :key="n" :value="n">{{ n }} 道题</option></select
          ><button class="btn" :disabled="!courseId || ui.busy > 0" @click="drafts">
            <Icon name="ai" :size="15" />生成草稿
          </button>
        </div>
      </div>
      <header class="section-title">
        <span class="caption"
          >当前课程 {{ questions.length }} 道题 · 选中 {{ selectedIds.length }} 道</span
        >
        <div class="btn-row">
          <button class="btn white small" :disabled="!courseId" @click="open('question')">
            <Icon name="plus" :size="14" />手动录题</button
          ><button
            class="btn small"
            :disabled="!selectedIds.length || selectedIds.length > 100"
            @click="open('paper')"
          >
            用选中题目组卷
          </button>
        </div>
      </header>
      <div class="table-wrap" v-if="questions.length">
        <table>
          <thead>
            <tr>
              <th>选择</th>
              <th>题目与答案</th>
              <th>题型</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="q in questions" :key="q.id">
              <td>
                <input
                  type="checkbox"
                  v-model="selectedIds"
                  :value="q.id"
                  :disabled="q.status !== 'APPROVED'"
                  :aria-label="'选择题目' + q.id"
                />
              </td>
              <td class="wrap-text">
                <strong>{{ q.stem }}</strong>
                <p class="caption">答案：{{ q.answer }} · {{ q.explanation }}</p>
              </td>
              <td>{{ q.type }}</td>
              <td><Status :value="q.status" /></td>
              <td>
                <button
                  v-if="q.status === 'DRAFT'"
                  class="text-link"
                  @click="
                    perform(async () => {
                      await api('/questions/' + q.id + '/approve', 'POST')
                      await loadCourse()
                    }, '题目已人工审核')
                  "
                >
                  确认审核通过
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Empty
        v-else
        title="为这门课程准备第一道题"
        text="可以手动录题，或根据已上传文字资料生成AI草稿。" /></template
    ><template v-if="tab === 'papers'"
      ><header class="section-title section-spacer">
        <p class="caption">已发布试卷冻结题目快照，修改时创建新试卷。</p>
        <div class="btn-row">
          <button
            class="btn white small"
            :disabled="session.mode === 'demo'"
            @click="perform(() => download('/papers/import-template', '试卷导入模板.xlsx'))"
          >
            <Icon name="download" :size="14" />标准模板</button
          ><label class="btn small" :class="{ disabled: session.mode === 'demo' }"
            ><Icon name="upload" :size="14" />导入试卷<input
              class="sr-only"
              type="file"
              accept=".xlsx"
              :disabled="session.mode === 'demo'"
              @change="importPaper"
          /></label>
        </div>
      </header>
      <p v-if="session.mode === 'demo'" class="notice gold">
        xlsx模板下载与实际文件解析需要后端模式。演示中可以在题库选题，创建并发布试卷。
      </p>
      <div class="table-wrap" v-if="papers.length">
        <table>
          <thead>
            <tr>
              <th>试卷 ID / 名称</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in papers" :key="p.id">
              <td>
                <strong>#{{ p.id }} {{ p.title }}</strong>
              </td>
              <td><Status :value="p.published ? 'PUBLISHED' : 'DRAFT'" /></td>
              <td>
                <div class="actions">
                  <button
                    v-if="!p.published"
                    class="text-link"
                    @click="
                      perform(async () => {
                        await api('/papers/' + p.id + '/publish', 'POST')
                        await reload()
                      }, '试卷已发布')
                    "
                  >
                    发布试卷</button
                  ><button v-else class="text-link" @click="open('exam', p)">安排关联考试</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Empty v-else title="暂无试卷" text="在课程题库选择审核后的题目，即可创建试卷。" /></template
    ><template v-if="tab === 'exams'"
      ><div class="notice section-spacer">
        考试分配不会自动完成课程学习。请由管理员通过培训计划或成长路线分配前置课程。
      </div>
      <div class="table-wrap" v-if="exams.length">
        <table>
          <thead>
            <tr>
              <th>考试 ID / 名称</th>
              <th>关联试卷</th>
              <th>时长 / 及格线</th>
              <th>开放时间</th>
              <th>最大次数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in exams" :key="e.id">
              <td>
                <strong>#{{ e.id }} {{ e.title }}</strong>
              </td>
              <td>#{{ e.paper_id }}</td>
              <td>{{ e.duration_minutes }} 分钟 / {{ e.pass_score }} 分</td>
              <td>{{ dateText(e.start_at) }} — {{ dateText(e.end_at) }}</td>
              <td>{{ e.max_attempts }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <Empty v-else title="暂无考试安排" text="发布试卷后，从试卷管理中安排考试。" /></template
    ><template v-if="tab === 'grading'"
      ><div class="table-wrap section-spacer" v-if="grading.length">
        <table>
          <thead>
            <tr>
              <th>考试 / 尝试 ID</th>
              <th>员工 ID</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in grading" :key="a.id">
              <td>{{ a.title || a.exam_id }} · {{ a.id }}</td>
              <td>{{ a.user_id }}</td>
              <td><Status :value="a.status" /></td>
              <td><button class="text-link" @click="openGrading(a)">进入阅卷</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <Empty
        v-else
        title="暂无待人工阅卷的试卷"
        text="含主观题的提交进入此队列，全部评分后才发布最终成绩。" /></template></LoadState
  ><Modal
    v-if="dialog"
    :title="
      dialog === 'question'
        ? '手动录入题目'
        : dialog === 'paper'
          ? '创建固定试卷'
          : '安排课程关联考试'
    "
    wide
    @close="dialog = ''"
    ><form @submit.prevent="submit">
      <div class="form-grid">
        <template v-if="dialog === 'question'"
          ><label class="field span-2"
            ><span>题型</span
            ><select v-model="form.type">
              <option value="SINGLE">单选题</option>
              <option value="MULTIPLE">多选题</option>
              <option value="TRUE_FALSE">判断题（填写A、B两个选项）</option>
              <option value="TEXT">主观简答题</option>
            </select></label
          ><label class="field span-2"
            ><span>题干</span><textarea v-model="form.stem" required maxlength="4000" /></label
          ><template v-if="form.type !== 'TEXT'"
            ><label
              v-for="key in ['a', 'b', ...(form.type === 'TRUE_FALSE' ? [] : ['c', 'd'])]"
              :key="key"
              class="field"
              ><span>选项 {{ key.toUpperCase() }}</span
              ><input v-model="form[key]" :required="['a', 'b'].includes(key)" /></label></template
          ><label class="field span-2"
            ><span>{{ form.type === 'TEXT' ? '参考答案' : '正确选项（多选用 A,C 格式）' }}</span
            ><input v-model="form.answer" required maxlength="2000" /></label
          ><label class="field span-2"
            ><span>解析</span
            ><textarea v-model="form.explanation" maxlength="4000" /></label></template
        ><template v-if="dialog === 'paper'"
          ><p class="notice span-2">
            选中
            {{
              selectedIds.length
            }}
            道已审核题目，试卷总分100，分值均分，余数计入首题。试卷创建时冻结题目快照。
          </p>
          <label class="field span-2"
            ><span>试卷名称</span
            ><input v-model="form.title" required maxlength="200" /></label></template
        ><template v-if="dialog === 'exam'"
          ><label class="field span-2"
            ><span>考试名称</span><input v-model="form.title" required maxlength="200" /></label
          ><label class="field"
            ><span>开放时间</span
            ><input v-model="form.startAt" type="datetime-local" required /></label
          ><label class="field"
            ><span>关闭时间</span
            ><input v-model="form.endAt" type="datetime-local" required /></label
          ><label class="field"
            ><span>考试时长（分钟）</span
            ><input
              v-model.number="form.durationMinutes"
              type="number"
              required
              min="1"
              max="480" /></label
          ><label class="field"
            ><span>合格分数</span
            ><input v-model.number="form.passScore" type="number" required min="1" /></label
          ><label class="field"
            ><span>最大尝试次数</span
            ><input
              v-model.number="form.maxAttempts"
              type="number"
              required
              min="1"
              max="10" /></label
          ><label class="field"
            ><span>关联阶段 ID（可选）</span
            ><input
              v-model.number="form.stageId"
              type="number"
              min="1"
              placeholder="非路线考试留空"
          /></label>
          <div class="field span-2">
            <span>必须先完成的课程</span>
            <div class="check-list">
              <label v-for="c in courses" :key="c.id"
                ><input v-model="form.prerequisiteCourseIds" type="checkbox" :value="c.id" />{{
                  c.title
                }}</label
              >
            </div>
          </div>
          <div class="field span-2">
            <span>参加考试的员工（当前课程学员）</span>
            <div class="check-list">
              <label v-for="u in learners" :key="u.id"
                ><input v-model="form.userIds" type="checkbox" :value="u.id" />{{
                  u.display_name
                }}</label
              >
            </div>
            <small v-if="!learners.length">本课程尚无学员，请先由管理员分配学习任务。</small>
          </div></template
        >
      </div>
      <footer class="form-actions">
        <button class="btn white" type="button" @click="dialog = ''">取消</button
        ><button class="btn" :disabled="ui.busy > 0">
          保存{{ dialog === 'paper' ? '试卷草稿' : '' }}
        </button>
      </footer>
    </form></Modal
  ><Modal v-if="gradingDetail" title="人工阅卷" wide @close="gradingDetail = null"
    ><article
      v-for="q in parsedQuestions.filter((q) => q.snapshot.type === 'TEXT')"
      :key="q.id"
      class="question-block"
    >
      <h3>{{ q.snapshot.stem }}（满分 {{ q.score }} 分）</h3>
      <p class="prose">
        作答：{{
          gradingDetail.answers.find((a) => a.paper_question_id === q.id)?.answer || '未作答'
        }}<br />参考：{{ q.snapshot.answer }}
      </p>
      <label class="field section-spacer"
        ><span>本题得分</span
        ><input type="number" v-model.number="gradingScores[q.id]" min="0" :max="q.score" /></label
      ><button
        class="btn small section-spacer"
        :disabled="
          ui.busy > 0 ||
          !Number.isInteger(gradingScores[q.id]) ||
          gradingScores[q.id] < 0 ||
          gradingScores[q.id] > q.score
        "
        @click="grade(q)"
      >
        保存本题评分
      </button>
    </article></Modal
  >
</template>
<style scoped>
.ai-draft-controls {
  margin-left: auto;
  flex-shrink: 0;
}
.ai-draft-controls select {
  font-size: 11px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
}
.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
@media (max-width: 650px) {
  .workspace-banner {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .ai-draft-controls {
    margin-left: 0;
  }
}
</style>
