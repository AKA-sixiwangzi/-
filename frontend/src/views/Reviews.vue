<script setup>
import { ref } from 'vue'
import { api, perform, ui } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import Modal from '../components/Modal.vue'
import Status from '../components/Status.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
import Icon from '../components/Icon.vue'
const rows = ref([]),
  selected = ref(null),
  detail = ref(null),
  approve = ref(true),
  note = ref('')
const { loading, error, reload } = useLoad(
  async () => (rows.value = await api('/admin/courses/pending')),
)
async function open(c) {
  await perform(async () => {
    detail.value = await api('/courses/' + c.id)
    selected.value = c
    note.value = ''
    approve.value = true
  })
}
async function submit() {
  await perform(async () => {
    await api('/admin/courses/' + selected.value.id + '/review', 'POST', {
      approve: approve.value,
      note: note.value,
    })
    selected.value = null
    await reload()
  }, '审核结果已保存并通知课程作者')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">CONTENT QUALITY</p>
      <h1>课程审核</h1>
      <p>让来自每位员工的经验，以可靠的课程形式被传递。</p>
    </div>
    <span class="tag">{{ rows.length }} 门待审核</span>
  </div>
  <div class="notice">
    审核内容准确性、视频可用性、标准时长与文字资料。审核发布后，课程作者原账号将增加讲师身份。不能审核本人课程。
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="table-wrap" v-if="rows.length">
      <table>
        <thead>
          <tr>
            <th>待审核课程</th>
            <th>分类</th>
            <th>作者 ID</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in rows" :key="c.id">
            <td>
              <strong>{{ c.title }}</strong>
            </td>
            <td>{{ c.category }}</td>
            <td>{{ c.owner_id }}</td>
            <td><Status :value="c.status" /></td>
            <td><button class="text-link" @click="open(c)">查看并审核</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <Empty
      v-else
      title="待审核课程已处理完毕"
      text="员工提交新课程后，会进入这个审核队列。" /></LoadState
  ><Modal v-if="selected" :title="selected.title" wide @close="selected = null"
    ><p class="prose">{{ selected.description }}</p>
    <div class="inline-meta section-spacer">
      <span>课程作者 ID {{ selected.owner_id }}</span
      ><span>{{ detail.lessons.length }} 个章节</span
      ><span>{{ detail.materials.length }} 份文字资料</span>
    </div>
    <div class="table-wrap section-spacer">
      <table>
        <thead>
          <tr>
            <th>章节</th>
            <th>标准时长</th>
            <th>覆盖 / 时长要求</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in detail.lessons" :key="l.id">
            <td>{{ l.title }}</td>
            <td>{{ l.duration_seconds }} 秒</td>
            <td>{{ l.coverage_threshold * 100 }}% / {{ l.time_threshold * 100 }}%</td>
          </tr>
        </tbody>
      </table>
    </div>
    <RouterLink
      class="btn outline small section-spacer"
      :to="'/courses/' + selected.id"
      @click="selected = null"
      ><Icon name="play" :size="14" />预览视频与讲义</RouterLink
    >
    <form @submit.prevent="submit" class="section-spacer">
      <div class="field">
        <span>审核决定</span
        ><select v-model="approve">
          <option :value="true">审核通过并发布</option>
          <option :value="false">驳回修改</option>
        </select>
      </div>
      <label class="field section-spacer"
        ><span>审核说明（必填）</span
        ><textarea
          v-model="note"
          required
          maxlength="1000"
          placeholder="写明核验结果或需要修改的内容"
        />
      </label>
      <footer class="form-actions">
        <button class="btn white" type="button" @click="selected = null">取消</button
        ><button class="btn" :disabled="ui.busy > 0">保存审核结果</button>
      </footer>
    </form></Modal
  >
</template>
