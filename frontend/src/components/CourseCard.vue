<script setup>
import { computed } from 'vue'
import Icon from './Icon.vue'
import CourseArt from './CourseArt.vue'
const p = defineProps({ course: Object, progress: { type: Number, default: 0 } })
const tone = computed(
  () =>
    p.course.tone ||
    { 安全生产: 'safety', 数字化技能: 'ai', 通用能力: 'team' }[p.course.category] ||
    'craft',
)
</script>
<template>
  <article class="course-card">
    <RouterLink :to="'/courses/' + course.id" :aria-label="'学习课程：' + course.title"
      ><CourseArt :tone="tone" :category="course.category"
    /></RouterLink>
    <div class="course-info">
      <div class="course-tags">
        <span>{{ course.badge || '岗位学习' }}</span
        ><span class="muted">{{
          progress >= 95 ? '已完成' : progress > 0 ? '学习中' : '待学习'
        }}</span>
      </div>
      <h3>
        <RouterLink :to="'/courses/' + course.id">{{ course.title }}</RouterLink>
      </h3>
      <p class="course-desc">{{ course.description }}</p>
      <div class="progress-label">
        <span>学习进度</span><b>{{ Math.min(100, Math.round(progress)) }}%</b>
      </div>
      <div class="progress"><i :style="{ width: Math.min(100, progress) + '%' }" /></div>
      <footer>
        <span><Icon name="video" :size="14" />视频课程</span
        ><RouterLink :to="'/courses/' + course.id"
          >{{ progress ? '继续学习' : '开始学习' }}<Icon name="arrow" :size="15"
        /></RouterLink>
      </footer>
    </div>
  </article>
</template>
