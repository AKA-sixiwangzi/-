<script setup>
import { ref, computed } from 'vue'
import { api, session, perform } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText } from '../lib/rules.js'
import Icon from '../components/Icon.vue'
import Status from '../components/Status.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
import Modal from '../components/Modal.vue'
const data = ref({ summary: {}, courses: [] }),
  certificates = ref([]),
  exams = ref([]),
  wrong = ref([]),
  warnings = ref([]),
  offline = ref({ records: [], credited_hours: 0, attended_sessions: 0 }),
  tab = ref('courses'),
  appeal = ref(null),
  note = ref('')
const { loading, error, reload } = useLoad(
  async () =>
    ([data.value, certificates.value, exams.value, wrong.value, warnings.value, offline.value] =
      await Promise.all([
        api('/dashboards/personal'),
        api('/certificates'),
        api('/exams'),
        api('/learning/wrong-answers'),
        api('/warnings'),
        api('/me/offline-training'),
      ])),
)
const attempts = computed(() =>
  exams.value.flatMap((e) => e.attempts.map((a) => ({ ...a, title: e.exam.title }))),
)
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">MY ACHIEVEMENTS</p>
      <h1>我的培训成果</h1>
      <p>让学习有记录，让能力有积累。</p>
    </div>
    <span class="tag">{{ session.me?.user.display_name }} · 个人培训档案</span>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><section class="kpi-grid">
      <article
        class="kpi"
        v-for="s in [
          {
            label: '已完成课程',
            value: data.summary.completed_courses || 0,
            unit: '门',
            icon: 'book',
            color: 'blue',
          },
          {
            label: '视频有效学习时长',
            value: (Number(data.summary.effective_seconds || 0) / 60).toFixed(1),
            unit: '分钟',
            icon: 'clock',
            color: 'gold',
          },
          {
            label: '考试通过',
            value: data.summary.passed_attempts || 0,
            unit: '次',
            icon: 'exam',
            color: 'green',
          },
          {
            label: '培训证书',
            value: certificates.filter((c) => c.status !== 'REVOKED').length,
            unit: '张',
            icon: 'award',
            color: 'violet',
          },
        ]"
        :key="s.label"
      >
        <div class="kpi-top">
          {{ s.label }}<i :class="s.color"><Icon :name="s.icon" /></i>
        </div>
        <div class="kpi-value">
          {{ s.value }}<small>{{ s.unit }}</small>
        </div>
      </article>
    </section>
    <div class="tabs page-tabs">
      <button
        v-for="[key, label] in [
          ['courses', '课程记录'],
          ['exams', '考试成绩'],
          ['certificates', '培训证书'],
          ['wrong', '错题复习'],
          ['warnings', '我的异常记录'],
          ['offline', '线下参训'],
        ]"
        :key="key"
        :class="{ active: tab === key }"
        @click="tab = key"
      >
        {{ label }}
      </button>
    </div>
    <template v-if="tab === 'courses'"
      ><div v-if="data.courses.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>课程名称</th>
              <th>完成状态</th>
              <th>完成时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in data.courses" :key="c.id">
              <td>
                <strong>{{ c.title }}</strong>
              </td>
              <td><Status :value="c.completed ? 'COMPLETED' : '待完成'" /></td>
              <td>{{ dateText(c.completed_at) }}</td>
              <td>
                <RouterLink :to="'/courses/' + c.id" class="text-link">{{
                  c.completed ? '复习课程' : '继续学习'
                }}</RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Empty v-else title="你的学习记录，从这里开始" /></template
    ><template v-if="tab === 'exams'"
      ><div v-if="attempts.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>考试</th>
              <th>第几次</th>
              <th>得分</th>
              <th>结果</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in attempts" :key="a.id">
              <td>{{ a.title }}</td>
              <td>{{ a.attempt_no }}</td>
              <td>{{ a.score ?? '—' }}</td>
              <td>{{ a.status === 'GRADED' ? (a.passed ? '已通过' : '未通过') : '待判定' }}</td>
              <td><Status :value="a.status" /></td>
            </tr>
          </tbody>
        </table>
      </div>
      <Empty v-else title="还没有考试成绩" text="完成课程后，前往考试中心检验所学。" /></template
    ><template v-if="tab === 'certificates'"
      ><div v-if="certificates.length" class="cards-2">
        <article v-for="c in certificates" :key="c.id" class="panel certificate">
          <Icon name="award" :size="45" />
          <p class="eyebrow">CERTIFICATE OF COMPLETION</p>
          <h2>{{ c.title || '岗位成长路线培训证书' }}</h2>
          <p>{{ session.me?.user.display_name }}</p>
          <small>证书编号：{{ c.id }}</small
          ><small>颁发时间：{{ dateText(c.issued_at) }}</small
          ><Status :value="c.status || 'VALID'" />
        </article>
      </div>
      <Empty
        v-else
        title="第一张培训证书，值得期待"
        text="完成整条成长路线的课程和考试后，即可领取证书。" /></template
    ><template v-if="tab === 'wrong'"
      ><p class="notice">仅在考试整体关闭且最终阅卷后展示错题，避免影响后续补考。</p>
      <div v-for="(w, i) in wrong" :key="i" class="question-block">
        <h3>{{ w.stem }}</h3>
        <p class="prose">你的答案：{{ w.answer }}<br />解析：{{ w.explanation }}</p>
      </div>
      <Empty v-if="!wrong.length" title="暂无可复习的错题" /></template
    ><template v-if="tab === 'warnings'"
      ><div class="table-wrap" v-if="warnings.length">
        <table>
          <thead>
            <tr>
              <th>类型</th>
              <th>发生时间</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="w in warnings" :key="w.id">
              <td>{{ w.type }}</td>
              <td>{{ dateText(w.created_at) }}</td>
              <td><Status :value="w.status" /></td>
              <td>
                <button
                  class="text-link"
                  @click="perform(async () => (appeal = await api('/warnings/' + w.id)))"
                >
                  查看处理记录
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Empty v-else title="暂无异常记录" text="保持正常学习，按要求完成任务。"
    /></template>
    <template v-if="tab === 'offline'">
      <section class="panel offline-personal-summary">
        <div>
          <span>已参训场次</span><strong>{{ offline.attended_sessions }}<small>场</small></strong>
        </div>
        <div>
          <span>已计线下学时</span
          ><strong>{{ Number(offline.credited_hours || 0).toFixed(1) }}<small>小时</small></strong>
        </div>
        <p>
          出勤由授课内训师或组织发展部登记。已出勤且培训结束后计入线下学时；此处不计入视频有效播放时长。
        </p>
      </section>
      <div v-if="offline.records.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>培训名称</th>
              <th>时间 / 地点</th>
              <th>内训师</th>
              <th>出勤状态</th>
              <th>成绩 / 学时</th>
              <th>记录</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in offline.records" :key="record.id">
              <td>
                <strong>{{ record.title }}</strong
                ><small v-if="record.plan_title" class="offline-record-note">{{
                  record.plan_title
                }}</small>
              </td>
              <td>
                {{ dateText(record.start_at)
                }}<small class="offline-record-note">至 {{ dateText(record.end_at) }}</small
                ><small class="offline-record-note">{{ record.location }}</small>
              </td>
              <td>{{ record.instructor_name }}</td>
              <td>
                {{
                  { REGISTERED: '待签到', PRESENT: '出勤', ABSENT: '缺席', EXCUSED: '请假' }[
                    record.attendance_status ?? record.status
                  ] || '待签到'
                }}
              </td>
              <td>
                {{ record.score == null ? '未评分' : record.score + ' 分'
                }}<small class="offline-record-note"
                  >{{ Number(record.credited_hours || 0).toFixed(1) }} 小时</small
                >
              </td>
              <td>
                <p class="offline-record-remark">{{ record.note || '暂无备注' }}</p>
                <RouterLink :to="'/offline-training?session=' + record.id" class="text-link"
                  >查看参训记录</RouterLink
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Empty
        v-else
        title="暂无线下参训记录"
        text="参加线下培训后，可以在这里查看出勤、培训成绩和学时。"
      />
    </template> </LoadState
  ><Modal v-if="appeal" title="异常处理记录" @close="appeal = null"
    ><p class="notice">{{ appeal.warning.evidence }}</p>
    <div class="timeline">
      <article v-for="a in appeal.actions" :key="a.id || a.created_at">
        <h3>{{ a.action }} · {{ a.after_state }}</h3>
        <p>{{ a.note }}</p>
        <small>{{ dateText(a.created_at) }}</small>
      </article>
    </div>
    <form
      v-if="appeal.warning.status === 'CLOSED'"
      @submit.prevent="
        perform(async () => {
          await api('/warnings/' + appeal.warning.id + '/actions', 'POST', {
            action: 'APPEAL',
            note,
          })
          appeal = null
          await reload()
        }, '申诉已提交')
      "
    >
      <label class="field"
        ><span>申诉说明</span
        ><textarea v-model="note" required minlength="5" maxlength="1800" /></label
      ><button class="btn section-spacer">提交申诉</button>
    </form></Modal
  >
</template>
<style scoped>
.offline-personal-summary {
  display: flex;
  align-items: center;
  gap: 35px;
  padding: 22px 25px;
  margin-bottom: 22px;
}
.offline-personal-summary > div {
  flex-shrink: 0;
}
.offline-personal-summary span {
  display: block;
  font-size: 11px;
  color: #8fa0b9;
}
.offline-personal-summary strong {
  display: block;
  font-size: 26px;
  color: #003f98;
  margin-top: 8px;
}
.offline-personal-summary small {
  font-size: 10px;
  color: #9baac1;
  font-weight: 400;
  margin-left: 8px;
}
.offline-personal-summary p {
  font-size: 11px;
  line-height: 1.9;
  color: #95a5bc;
}
.offline-record-note {
  display: block;
  font-size: 10px;
  color: #9aabc2;
  margin-top: 6px;
  line-height: 1.8;
}
.offline-record-remark {
  max-width: 230px;
  color: #8b9db6;
  font-size: 11px;
  overflow-wrap: anywhere;
  line-height: 1.8;
  margin-bottom: 8px;
}
@media (max-width: 650px) {
  .offline-personal-summary {
    flex-wrap: wrap;
    gap: 22px;
  }
  .offline-personal-summary p {
    width: 100%;
  }
}
.certificate {
  text-align: center;
  background: linear-gradient(140deg, #fff, #fffdf5);
  border-color: #e9dfc5;
  padding: 32px;
}
.certificate > svg {
  color: #c4a151;
  margin: 10px;
}
.certificate h2 {
  margin: 20px 0;
  font-size: 18px;
}
.certificate > p:not(.eyebrow) {
  color: #53739c;
  font-size: 17px;
}
.certificate small {
  display: block;
  font-size: 10px;
  color: #a1abbb;
  margin: 12px 0;
}
.certificate .status {
  margin-top: 8px;
}
</style>
