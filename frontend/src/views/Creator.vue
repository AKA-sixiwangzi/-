<script setup>
import { ref } from 'vue'
import { api, upload, perform, ui, loadMe, toast } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import Modal from '../components/Modal.vue'
import Icon from '../components/Icon.vue'
import Status from '../components/Status.vue'
import Empty from '../components/Empty.vue'
import LoadState from '../components/LoadState.vue'
const rows = ref([]),
  dialog = ref(false),
  selected = ref(null),
  detail = ref(null),
  form = ref({ title: '', category: '经验分享', description: '' }),
  lesson = ref({
    title: '',
    sortOrder: 1,
    durationSeconds: 30,
    videoAssetId: '',
    coverageThreshold: 0.95,
    timeThreshold: 0.95,
  }),
  material = ref({ title: '', content: '', assetId: null }),
  uploadName = ref(''),
  step = ref('info')
const { loading, error, reload } = useLoad(async () => {
  rows.value = await api('/teaching/courses')
  await loadMe()
})
async function open(c) {
  selected.value = c || null
  step.value = 'info'
  form.value = c
    ? { title: c.title, category: c.category, description: c.description }
    : { title: '', category: '经验分享', description: '' }
  detail.value = c ? await api('/courses/' + c.id) : null
  dialog.value = true
}
async function saveInfo() {
  await perform(async () => {
    if (selected.value) await api('/courses/' + selected.value.id, 'PUT', form.value)
    else {
      selected.value = await api('/courses', 'POST', form.value)
    }
    detail.value = await api('/courses/' + selected.value.id)
    selected.value = detail.value.course
    step.value = 'content'
    await reload()
  }, '课程基本信息已保存')
}
async function fileChange(e) {
  const f = e.target.files?.[0]
  if (!f) return
  await perform(async () => {
    const r = await upload(f)
    lesson.value.videoAssetId = r.id
    uploadName.value = f.name
    const url = URL.createObjectURL(f),
      v = document.createElement('video')
    v.preload = 'metadata'
    v.src = url
    v.onloadedmetadata = () => {
      if (Number.isFinite(v.duration)) lesson.value.durationSeconds = Math.ceil(v.duration)
      URL.revokeObjectURL(url)
    }
    v.onerror = () => URL.revokeObjectURL(url)
  }, '视频已上传，请核对标准时长')
}
async function addLesson() {
  await perform(async () => {
    await api('/courses/' + selected.value.id + '/lessons', 'POST', lesson.value)
    detail.value = await api('/courses/' + selected.value.id)
    lesson.value = {
      title: '',
      sortOrder: detail.value.lessons.length + 1,
      durationSeconds: 30,
      videoAssetId: '',
      coverageThreshold: 0.95,
      timeThreshold: 0.95,
    }
    uploadName.value = ''
  }, '章节已添加')
}
async function addMaterial() {
  await perform(async () => {
    await api('/courses/' + selected.value.id + '/materials', 'POST', material.value)
    detail.value = await api('/courses/' + selected.value.id)
    material.value = { title: '', content: '', assetId: null }
  }, '文字资料已保存')
}
async function archive(c) {
  if (window.confirm('下架后员工将无法继续学习，确认下架？'))
    await perform(async () => {
      await api('/courses/' + c.id + '/archive', 'POST')
      await reload()
    }, '课程已下架')
}
async function submit(c) {
  await perform(async () => {
    await api('/courses/' + c.id + '/submit-review', 'POST')
    dialog.value = false
    await reload()
  }, '课程已提交审核，发布后原账号将获得讲师身份')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">EVERYONE CAN TEACH</p>
      <h1>我的课程创作</h1>
      <p>把工作中的好经验，变成团队都能学习的好方法。</p>
    </div>
    <button class="btn" @click="open()"><Icon name="plus" :size="17" />创建分享课程</button>
  </div>
  <div class="workspace-banner">
    <Icon name="ai" :size="28" />
    <div>
      <h3>每一位员工，都可以成为培训讲师</h3>
      <p>
        创建课程 → 完善视频与资料 → 提交管理员审核 → 发布后兼任讲师，在同一账号查看课程学习情况。
      </p>
    </div>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="table-wrap" v-if="rows.length">
      <table>
        <thead>
          <tr>
            <th>课程名称</th>
            <th>分类</th>
            <th>审核状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in rows" :key="c.id">
            <td>
              <strong>{{ c.title }}</strong>
              <p v-if="c.review_note" class="caption">审核说明：{{ c.review_note }}</p>
            </td>
            <td>{{ c.category }}</td>
            <td><Status :value="c.status" /></td>
            <td>
              <div class="actions">
                <template v-if="['DRAFT', 'REJECTED'].includes(c.status)"
                  ><button class="text-link" @click="perform(() => open(c))">编辑内容</button
                  ><button class="text-link" :disabled="ui.busy > 0" @click="submit(c)">
                    提交审核
                  </button></template
                ><RouterLink v-else :to="'/courses/' + c.id" class="text-link">预览课程</RouterLink
                ><RouterLink v-if="c.status === 'PUBLISHED'" to="/teaching" class="text-link"
                  >学习统计</RouterLink
                ><button
                  v-if="c.status === 'PUBLISHED'"
                  class="text-link danger-text"
                  @click="archive(c)"
                >
                  下架
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Empty
      v-else
      title="你的经验，值得被更多人看见"
      text="从一段视频、一份讲义开始，创建你的第一门分享课程。"
      ><button class="btn" @click="open()">创建第一门课程</button></Empty
    ></LoadState
  ><Modal
    v-if="dialog"
    :title="selected ? '编辑课程 · ' + selected.title : '创建分享课程'"
    wide
    @close="dialog = false"
    ><div class="tabs page-tabs">
      <button :class="{ active: step === 'info' }" @click="step = 'info'">01 基本信息</button
      ><button
        :disabled="!selected"
        :class="{ active: step === 'content' }"
        @click="step = 'content'"
      >
        02 视频与章节</button
      ><button
        :disabled="!selected"
        :class="{ active: step === 'materials' }"
        @click="step = 'materials'"
      >
        03 文字资料
      </button>
    </div>
    <form v-if="step === 'info'" @submit.prevent="saveInfo">
      <div class="form-grid">
        <label class="field span-2"
          ><span>课程名称</span
          ><input
            v-model="form.title"
            required
            maxlength="200"
            placeholder="清楚说明你要分享的内容" /></label
        ><label class="field span-2"
          ><span>课程分类</span
          ><select v-model="form.category">
            <option>经验分享</option>
            <option>安全生产</option>
            <option>数字化技能</option>
            <option>通用能力</option>
          </select></label
        ><label class="field span-2"
          ><span>课程介绍</span
          ><textarea
            v-model="form.description"
            maxlength="3000"
            placeholder="适合谁学习？学完能解决什么问题？"
          />
        </label>
      </div>
      <footer class="form-actions">
        <button class="btn" :disabled="ui.busy > 0">
          保存并完善章节<Icon name="arrow" :size="16" />
        </button>
      </footer>
    </form>
    <template v-if="step === 'content'"
      ><div class="notice">
        标准时长自动尝试读取视频元数据，请核对。课程审核时还需核验视频内容与时长。
      </div>
      <div class="caption" v-for="l in detail?.lessons" :key="l.id">
        {{ l.sort_order }}. {{ l.title }} · {{ l.duration_seconds }} 秒
      </div>
      <form class="section-spacer" @submit.prevent="addLesson">
        <div class="form-grid">
          <label class="field span-2"
            ><span>章节标题</span><input v-model="lesson.title" required maxlength="200" /></label
          ><label class="field"
            ><span>顺序</span
            ><input v-model.number="lesson.sortOrder" type="number" min="1" required /></label
          ><label class="field"
            ><span>标准时长（秒）</span
            ><input
              v-model.number="lesson.durationSeconds"
              type="number"
              min="1"
              max="86400"
              required /></label
          ><label class="upload-zone span-2"
            ><Icon name="upload" :size="26" />
            <p>{{ uploadName || '选择 MP4 / WebM 视频' }}</p>
            <input type="file" accept="video/mp4,video/webm" @change="fileChange" /></label
          ><label class="field"
            ><span>有效覆盖要求</span
            ><input
              v-model.number="lesson.coverageThreshold"
              type="number"
              min="0.5"
              max="1"
              step="0.01"
              required /></label
          ><label class="field"
            ><span>最低有效时长比例</span
            ><input
              v-model.number="lesson.timeThreshold"
              type="number"
              min="0.5"
              max="1"
              step="0.01"
              required
          /></label>
        </div>
        <footer class="form-actions">
          <button class="btn outline" :disabled="ui.busy > 0 || !lesson.videoAssetId">
            添加此章节</button
          ><button type="button" class="btn" @click="step = 'materials'">下一步：文字资料</button>
        </footer>
      </form></template
    ><template v-if="step === 'materials'"
      ><p class="notice">
        课程助手只使用已录入的文字资料。请提供准确讲稿或要点；本版不会自动转写视频。
      </p>
      <div v-for="m in detail?.materials" :key="m.id" class="caption">已添加：{{ m.title }}</div>
      <form class="section-spacer" @submit.prevent="addMaterial">
        <label class="field"
          ><span>资料名称</span><input v-model="material.title" required maxlength="200" /></label
        ><label class="field section-spacer"
          ><span>课程讲稿 / 文字要点</span
          ><textarea v-model="material.content" required maxlength="200000" rows="7" />
        </label>
        <footer class="form-actions">
          <button class="btn outline" :disabled="ui.busy > 0">保存文字资料</button
          ><button
            type="button"
            class="btn"
            :disabled="ui.busy > 0 || !detail?.lessons.length"
            @click="submit(selected)"
          >
            提交课程审核
          </button>
        </footer>
      </form></template
    ></Modal
  >
</template>
