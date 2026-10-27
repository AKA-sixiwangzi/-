<script setup>
import { ref } from 'vue'
import { api, perform } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import { dateText } from '../lib/rules.js'
import Icon from '../components/Icon.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const rows = ref([])
const { loading, error, reload } = useLoad(async () => (rows.value = await api('/notifications')))
async function read(n) {
  await perform(async () => {
    await api('/notifications/' + n.id + '/read', 'POST')
    await reload()
  })
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">NOTIFICATIONS</p>
      <h1>消息中心</h1>
      <p>任务提醒、课程审核与培训动态，不错过重要进展。</p>
    </div>
    <span class="tag">{{ rows.filter((x) => !x.read_at).length }} 条未读</span>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="panel" v-if="rows.length">
      <article v-for="n in rows" :key="n.id" class="message-row" :class="{ unread: !n.read_at }">
        <span class="avatar"><Icon name="bell" :size="18" /></span>
        <div>
          <h3>{{ n.title }}</h3>
          <p>{{ n.body }}</p>
          <small>{{ dateText(n.created_at) }}</small>
        </div>
        <button v-if="!n.read_at" class="text-link" @click="read(n)">标为已读</button
        ><span v-else class="caption">已读</span>
      </article>
    </div>
    <Empty v-else title="消息已整理好，暂时没有新动态"
  /></LoadState>
</template>
