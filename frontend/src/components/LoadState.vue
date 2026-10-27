<script setup>
import Empty from './Empty.vue'
defineProps({ loading: Boolean, error: String })
defineEmits(['retry'])
</script>
<template>
  <div v-if="loading" class="loading-state"><span class="spinner" />正在加载…</div>
  <Empty v-else-if="error" icon="warning" title="暂时无法加载" :text="error"
    ><button class="btn" @click="$emit('retry')">重新加载</button></Empty
  ><slot v-else />
</template>
