<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { api, perform, ui } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText } from '../lib/rules.js'
import Icon from '../components/Icon.vue'
import Status from '../components/Status.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const router = useRouter(),
  rows = ref([]),
  filter = ref('all'),
  tabs = [
    ['all', '全部考试'],
    ['READY', '可参加'],
    ['LEARNING_REQUIRED', '待完成学习'],
    ['COMPLETED', '已通过'],
  ]
const { loading, error, reload } = useLoad(async () => (rows.value = await api('/exams')))
const filtered = computed(() =>
  rows.value.filter(
    (e) =>
      filter.value === 'all' ||
      (filter.value === 'READY'
        ? ['READY', 'RETAKE', 'IN_PROGRESS'].includes(e.zone)
        : e.zone === filter.value),
  ),
)
async function start(e) {
  await perform(async () => {
    const r = await api('/exams/' + e.exam.id + '/start', 'POST')
    await router.push('/attempt/' + r.attempt.id)
  })
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">ASSESSMENT CENTER</p>
      <h1>考试中心</h1>
      <p>先学后考，让每一次考核都有明确的学习依据。</p>
    </div>
    <RouterLink to="/results" class="btn white"
      ><Icon name="award" :size="17" />查看培训成果</RouterLink
    >
  </div>
  <div class="panel filter-bar">
    <div class="tabs">
      <button
        v-for="[key, title] in tabs"
        :key="key"
        :class="{ active: filter === key }"
        @click="filter = key"
      >
        {{ title }}
      </button>
    </div>
    <span class="caption">前置课程、阶段和开放时间由后端核验</span>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="exam-grid section-spacer" v-if="filtered.length">
      <article v-for="e in filtered" :key="e.exam.id" class="panel exam-card">
        <header>
          <span class="exam-icon"><Icon name="exam" :size="25" /></span><Status :value="e.zone" />
        </header>
        <h2>{{ e.exam.title }}</h2>
        <div class="inline-meta">
          <span><Icon name="clock" :size="14" />{{ e.exam.duration_minutes }} 分钟</span
          ><span><Icon name="target" :size="14" />{{ e.exam.pass_score }} 分及格</span>
        </div>
        <p class="caption">
          开放时间：{{ dateText(e.exam.start_at) }} — {{ dateText(e.exam.end_at) }}
        </p>
        <div class="exam-prerequisites">
          <h3>关联学习课程</h3>
          <div v-for="c in e.prerequisites" :key="c.id">
            <Icon :name="c.completed ? 'success' : 'lock'" :size="15" /><span>{{ c.title }}</span
            ><RouterLink v-if="!c.completed" :to="'/courses/' + c.id"
              >去学习<Icon name="right" :size="12" /></RouterLink
            ><small v-else>已完成</small>
          </div>
        </div>
        <footer>
          <span>剩余 {{ Math.max(0, e.exam.max_attempts - e.attempts.length) }} 次机会</span
          ><button
            class="btn small"
            :disabled="ui.busy > 0 || !['READY', 'RETAKE', 'IN_PROGRESS'].includes(e.zone)"
            @click="start(e)"
          >
            {{
              e.zone === 'IN_PROGRESS'
                ? '继续考试'
                : e.zone === 'RETAKE'
                  ? '参加补考'
                  : e.zone === 'COMPLETED'
                    ? '已通过'
                    : e.zone === 'LEARNING_REQUIRED'
                      ? '请先完成学习'
                      : '开始考试'
            }}<Icon name="arrow" :size="14" />
          </button>
        </footer>
      </article>
    </div>
    <Empty v-else title="暂无符合条件的考试" text="考试发布后，将按学习条件分区展示。"
  /></LoadState>
</template>
<style scoped>
.exam-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}
.exam-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.exam-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: #eaf1fc;
  color: #5983bb;
  display: flex;
  align-items: center;
  justify-content: center;
}
.exam-card h2 {
  font-size: 17px;
  margin-bottom: 15px;
}
.exam-card > .caption {
  font-size: 10px;
  margin: 15px 0;
}
.exam-prerequisites {
  background: #f7f9fd;
  border-radius: 7px;
  padding: 17px;
  margin-top: 18px;
}
.exam-prerequisites h3 {
  font-size: 10px;
  font-weight: 500;
  color: #9aa9bc;
  margin-bottom: 13px;
}
.exam-prerequisites > div {
  display: flex;
  gap: 8px;
  align-items: center;
  color: #8ba2bf;
  font-size: 11px;
  margin-top: 13px;
}
.exam-prerequisites > div > span {
  flex: 1;
  color: #6f86a3;
}
.exam-prerequisites a {
  display: flex;
  align-items: center;
  font-size: 9px;
  gap: 3px;
  color: #3a70b2;
}
.exam-prerequisites small {
  font-size: 9px;
}
.exam-card footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid #e8edf4;
  padding-top: 19px;
  margin-top: 20px;
  font-size: 10px;
  color: #9cabc0;
}
@media (max-width: 950px) {
  .exam-grid {
    grid-template-columns: 1fr;
  }
}
</style>
