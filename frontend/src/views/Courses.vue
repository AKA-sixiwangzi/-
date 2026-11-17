<script setup>
import { ref, computed } from 'vue'
import { api } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import Icon from '../components/Icon.vue'
import CourseCard from '../components/CourseCard.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const rows = ref([]),
  progress = ref({}),
  learning = ref({}),
  search = ref(''),
  category = ref('全部课程'),
  status = ref('all')
function courseFormat(state) {
  if (state?.videoTotal === undefined) return '培训课程'
  return state.videoTotal ? (state.materials?.length ? '视频 + 资料' : '视频课程') : '资料课程'
}
const { loading, error, reload } = useLoad(async () => {
  rows.value = await api('/courses')
  await Promise.all(
    rows.value.map(async (c) => {
      try {
        const state = await api('/courses/' + c.id + '/learning-status')
        learning.value[c.id] = state
        progress.value[c.id] = state.percent
      } catch {
        learning.value[c.id] = { completed: false }
        progress.value[c.id] = 0
      }
    }),
  )
})
const categories = computed(() => ['全部课程', ...new Set(rows.value.map((c) => c.category))]),
  filtered = computed(() =>
    rows.value.filter(
      (c) =>
        c.title.includes(search.value) &&
        (category.value === '全部课程' || c.category === category.value) &&
        (status.value === 'all' ||
          (status.value === 'done'
            ? learning.value[c.id]?.completed
            : !learning.value[c.id]?.completed)),
    ),
  )
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">COURSE LIBRARY</p>
      <h1>我的课程</h1>
      <p>围绕岗位所需，发现值得投入的每一课。</p>
    </div>
    <RouterLink to="/creation" class="btn white"
      ><Icon name="plus" :size="17" />我也来分享课程</RouterLink
    >
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="panel filter-bar">
      <div class="tabs">
        <button
          v-for="c in categories"
          :key="c"
          :class="{ active: category === c }"
          @click="category = c"
        >
          {{ c }}
        </button>
      </div>
      <div class="filter-controls">
        <div class="search-input">
          <Icon name="search" :size="17" /><input
            v-model="search"
            placeholder="搜索课程名称"
            aria-label="搜索课程"
          />
        </div>
        <select v-model="status" aria-label="学习状态">
          <option value="all">全部学习状态</option>
          <option value="pending">待完成</option>
          <option value="done">已完成</option>
        </select>
      </div>
    </div>
    <div class="list-summary">
      共 <b>{{ filtered.length }}</b> 门课程<span>学习进度综合视频有效完成与必读资料确认</span>
    </div>
    <div class="course-grid" v-if="filtered.length">
      <CourseCard
        v-for="c in filtered"
        :key="c.id"
        :course="c"
        :progress="progress[c.id] || 0"
        :completed="!!learning[c.id]?.completed"
        :format="courseFormat(learning[c.id])"
      />
    </div>
    <Empty v-else title="没有符合条件的课程" text="试试其他关键词，或等待管理员分配新的课程。"
  /></LoadState>
</template>
