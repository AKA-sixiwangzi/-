<script setup>
import { computed, ref } from 'vue'
import { session, api } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText } from '../lib/rules.js'
import Icon from '../components/Icon.vue'
import CourseCard from '../components/CourseCard.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const info = ref({ summary: {}, courses: [] }),
  progress = ref({}),
  plans = ref([]),
  exams = ref([]),
  path = ref(null)
const { loading, error, reload } = useLoad(async () => {
  const [r, p, e, paths] = await Promise.all([
    api('/dashboards/personal'),
    api('/plans'),
    api('/exams'),
    api('/paths'),
  ])
  info.value = r
  plans.value = p
  exams.value = e
  progress.value = {}
  await Promise.all(
    r.courses.slice(0, 3).map(async (c) => {
      try {
        const ls = await api('/courses/' + c.id + '/progress')
        progress.value[c.id] =
          (ls.reduce((s, x) => s + x.covered_seconds, 0) /
            Math.max(
              1,
              ls.reduce((s, x) => s + x.duration_seconds, 0),
            )) *
          100
      } catch {
        progress.value[c.id] = c.completed ? 100 : 0
      }
    }),
  )
  if (paths.length) path.value = await api('/paths/' + paths[0].id)
})
const name = computed(() => session.me?.user.display_name),
  summary = computed(() => info.value.summary),
  completed = computed(() => Number(summary.value.completed_courses) || 0),
  pending = computed(() => Math.max(0, (summary.value.assigned_courses || 0) - completed.value)),
  week = ['一', '二', '三', '四', '五', '六', '日'],
  shanghai = new Date(Date.now() + 8 * 3600000),
  today = shanghai.getUTCDay() || 7
const greeting = computed(() =>
  shanghai.getUTCHours() < 12 ? '上午好' : shanghai.getUTCHours() < 18 ? '下午好' : '晚上好',
)
const stats = computed(() => [
  {
    label: '待完成课程',
    value: pending.value,
    unit: '门',
    icon: 'book',
    color: 'blue',
    note: '按照自己的节奏，持续向前',
  },
  {
    label: '已完成课程',
    value: completed.value,
    unit: '门',
    icon: 'checks',
    color: 'green',
    note: '每一门课程，都是新的积累',
  },
  {
    label: '有效学习时长',
    value: (Number(summary.value.effective_seconds || 0) / 60).toFixed(1),
    unit: '分钟',
    icon: 'clock',
    color: 'gold',
    note: '记录真实播放，见证每份投入',
  },
  {
    label: '考试通过',
    value: summary.value.passed_attempts || 0,
    unit: '次',
    icon: 'award',
    color: 'violet',
    note: '把所学，变成真正的能力',
  },
])
</script>
<template>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="page-heading">
      <div>
        <p class="eyebrow">MY LEARNING JOURNEY</p>
        <h1>学习工作台</h1>
        <p>清晰的目标，踏实的每一步。</p>
      </div>
      <RouterLink class="btn white" to="/plans"
        ><Icon name="calendar" :size="17" />查看培训计划</RouterLink
      >
    </div>
    <section class="welcome-hero">
      <div class="hero-copy">
        <span class="hero-tag"><i />成长，正在发生</span>
        <h2>{{ greeting }}，{{ name }}<span>每一次学习，都在靠近更好的自己。</span></h2>
        <p>
          你还有 <strong>{{ pending }}</strong> 门课程待完成。让今天的进步，从一小步开始。
        </p>
        <RouterLink
          class="btn yellow"
          :to="
            info.courses.length
              ? '/courses/' + (info.courses.find((c) => !c.completed)?.id || info.courses[0].id)
              : '/courses'
          "
          >继续我的学习<Icon name="arrow" :size="18" /></RouterLink
        ><span class="hero-footnote"><Icon name="shield" :size="13" />有效学习 · 持续成长</span>
      </div>
      <div class="hero-illustration" aria-hidden="true">
        <svg viewBox="0 0 500 290">
          <defs>
            <linearGradient id="stair" x2="0" y2="1">
              <stop stop-color="#fff" />
              <stop offset="1" stop-color="#a7cbff" />
            </linearGradient>
          </defs>
          <circle cx="330" cy="145" r="116" fill="none" stroke="#ffffff" opacity=".12" />
          <circle cx="330" cy="145" r="87" fill="none" stroke="#ffffff" opacity=".12" />
          <path d="M90 240 260 282 440 198 270 158z" fill="#001f57" opacity=".35" />
          <path d="m118 203 64-27 64 17-65 28z" fill="#c2d9ff" />
          <path d="m118 203 63 18v43l-63-18z" fill="#81a9ec" />
          <path d="m181 221 65-28v43l-65 28z" fill="#a8c7ff" />
          <path d="m206 154 64-29 65 19-65 29z" fill="#e3edff" />
          <path d="m206 154 64 19v81l-64-18z" fill="#a7c5f5" />
          <path d="m270 173 65-29v81l-65 29z" fill="#d0e1ff" />
          <path d="m294 94 65-30 65 19-65 30z" fill="#fff" />
          <path d="m294 94 65 19v113l-65-19z" fill="#c7dafa" />
          <path d="m359 113 65-30v113l-65 30z" fill="#edf4ff" />
          <path
            d="m132 147 83-44 53 10 93-59"
            stroke="#ffcb0b"
            stroke-width="4"
            fill="none"
            stroke-linecap="round"
          />
          <path d="m340 55 21-1-7 21" stroke="#ffcb0b" stroke-width="4" fill="none" />
          <rect
            x="332"
            y="9"
            width="46"
            height="44"
            rx="12"
            fill="#ffcb0b"
            transform="rotate(12 355 31)"
          />
          <path d="m343 30 8 8 15-17" stroke="#003f98" stroke-width="3" fill="none" />
          <circle cx="128" cy="81" r="5" fill="#ffcb0b" />
          <path d="M439 42v12m-6-6h12M237 40v9m-4-4h8" stroke="#bdd7ff" stroke-width="2" />
        </svg>
        <div class="hero-bubble">
          <Icon name="trend" :size="18" /><span>让成长<strong>看得见</strong></span>
        </div>
      </div>
    </section>
    <section class="kpi-grid">
      <article v-for="s in stats" :key="s.label" class="kpi">
        <div class="kpi-top">
          <span>{{ s.label }}</span
          ><i :class="s.color"><Icon :name="s.icon" :size="20" /></i>
        </div>
        <div class="kpi-value">
          {{ s.value }}<small>{{ s.unit }}</small>
        </div>
        <p>{{ s.note }}</p>
      </article>
    </section>
    <div class="dashboard-columns">
      <div class="dashboard-primary">
        <section>
          <header class="section-title">
            <div>
              <h2>
                接着上次，继续进步 <span class="count-pill">{{ pending }}</span>
              </h2>
              <p>为你安排的岗位学习，正在有序进行</p>
            </div>
            <RouterLink to="/courses" class="text-link"
              >全部课程<Icon name="right" :size="15"
            /></RouterLink>
          </header>
          <div v-if="info.courses.length" class="course-grid home-courses">
            <CourseCard
              v-for="c in info.courses.slice(0, 3)"
              :key="c.id"
              :course="c"
              :progress="progress[c.id] || 0"
            />
          </div>
          <Empty
            v-else
            title="新的成长，等待开启"
            text="管理员分配课程后，你的学习任务会出现在这里。"
          />
        </section>
        <section class="panel route-preview">
          <header class="section-title">
            <div>
              <span class="eyebrow">CAREER GROWTH</span>
              <h2>我的成长路线</h2>
            </div>
            <RouterLink to="/paths" class="text-link"
              >查看完整路线<Icon name="right" :size="15"
            /></RouterLink>
          </header>
          <template v-if="path"
            ><p class="route-preview-name">
              {{ path.path.title }} <span>按阶段学习，让成长更有方向</span>
            </p>
            <div class="mini-roadmap">
              <div
                v-for="(s, i) in path.stages"
                :key="s.stage.id"
                :class="{ done: s.passed, current: s.unlocked && !s.passed }"
              >
                <span class="road-node"
                  ><Icon v-if="s.passed" name="check" :size="18" /><Icon
                    v-else-if="!s.unlocked"
                    name="lock"
                    :size="16"
                  /><b v-else>{{ String(i + 1).padStart(2, '0') }}</b></span
                ><strong>{{ s.stage.title }}</strong
                ><small>{{ s.passed ? '已通关' : s.unlocked ? '当前阶段' : '待解锁' }}</small>
              </div>
            </div></template
          ><Empty v-else title="暂未分配成长路线" text="管理员将根据岗位，为你配置学习阶段。" />
        </section>
      </div>
      <aside class="dashboard-aside">
        <section class="panel calendar-panel">
          <header class="section-title">
            <h2>学习日历</h2>
            <Icon name="calendar" :size="18" />
          </header>
          <div class="calendar-month">
            {{ shanghai.getUTCFullYear() }} 年 {{ shanghai.getUTCMonth() + 1 }} 月 <span>本周</span>
          </div>
          <div class="week-strip">
            <div v-for="(d, i) in week" :key="d" :class="{ today: i + 1 === today }">
              <small>{{ d }}</small
              ><b>{{ new Date(shanghai.getTime() + (i + 1 - today) * 86400000).getUTCDate() }}</b
              ><i />
            </div>
          </div>
          <div class="calendar-divider" />
          <p class="small-heading">近期培训安排</p>
          <RouterLink v-for="p in plans.slice(0, 2)" :key="p.id" to="/plans" class="agenda"
            ><span class="agenda-dot" />
            <div>
              <b>{{ p.title }}</b
              ><small>{{ dateText(p.due_at) }} 截止</small>
            </div>
            <Icon name="right" :size="14"
          /></RouterLink>
          <p v-if="!plans.length" class="muted">暂无培训安排，保持学习的好习惯。</p>
        </section>
        <section class="panel exam-preview">
          <div class="exam-promo-icon"><Icon name="exam" :size="25" /></div>
          <h2>学以致用，检验所学</h2>
          <p>课程学完了吗？<br />用一次考核，确认自己的进步。</p>
          <div class="exam-count">
            <b>{{
              exams.filter((e) => ['READY', 'RETAKE', 'IN_PROGRESS'].includes(e.zone)).length
            }}</b
            ><span>场考试可参加</span>
          </div>
          <RouterLink to="/exams" class="btn outline full"
            >进入考试中心<Icon name="arrow" :size="16"
          /></RouterLink>
        </section>
        <div class="quote-note">
          <Icon name="bookmark" :size="18" />
          <p>“真正的成长，是把学到的知识<br />变成每天的行动。”</p>
          <span>LEARN · PRACTICE · GROW</span>
        </div>
      </aside>
    </div>
  </LoadState>
</template>
