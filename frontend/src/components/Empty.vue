<script setup>
import Icon from './Icon.vue'
defineProps({
  title: { default: '暂时没有记录' },
  text: { default: '有新的任务或记录时，会显示在这里。' },
  icon: { default: 'folder' },
})
</script>
<template>
  <div class="empty">
    <span><Icon :name="icon" :size="30" /></span>
    <h3>{{ title }}</h3>
    <p>{{ text }}</p>
    <slot />
  </div>
</template>
