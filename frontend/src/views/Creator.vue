<script setup>
import { ref, computed } from 'vue'
import { api, upload, perform, ui, loadMe, session } from '../lib/api.js'
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
  material = ref(newMaterial()),
  materialUploadName = ref(''),
  uploadName = ref(''),
  step = ref('info')
const canSubmit = computed(
  () =>
    !!detail.value?.lessons.length ||
    detail.value?.materials.some((m) => m.required === true || m.required === 1),
)
const materialAccept = computed(
  () =>
    ({
      TEXT: '.txt,text/plain',
      PDF: '.pdf,application/pdf',
      PPT: '.ppt,.pptx,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation',
    })[material.value.materialType],
)
function newMaterial() {
  return {
    title: '',
    content: '',
    assetId: null,
    materialType: 'TEXT',
    externalUrl: '',
    required: true,
  }
}
function materialLabel(type) {
  return (
    { TEXT: '文字 / TXT', PDF: 'PDF', PPT: 'PPT / PPTX', LINK: '网页链接' }[type || 'TEXT'] || type
  )
}
function changeMaterialType() {
  material.value.assetId = null
  material.value.externalUrl = ''
  materialUploadName.value = ''
}
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
  material.value = newMaterial()
  materialUploadName.value = ''
  uploadName.value = ''
  lesson.value.videoAssetId = ''
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
    if (!/\.(mp4|webm)$/i.test(f.name)) throw new Error('请选择 MP4 或 WebM 视频文件')
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
async function materialFileChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  await perform(async () => {
    const allowed = { TEXT: /\.txt$/i, PDF: /\.pdf$/i, PPT: /\.(ppt|pptx)$/i }
    if (!allowed[material.value.materialType]?.test(file.name))
      throw new Error('文件格式与所选资料类型不符，请重新选择')
    let text
    if (material.value.materialType === 'TEXT') {
      text = await file.text()
      if (text.length > 200000) throw new Error('TXT 正文超过20万字，请精简后上传')
    }
    const result = await upload(file)
    material.value.assetId = result.id
    materialUploadName.value = file.name
    if (text !== undefined) material.value.content = text
    if (!material.value.title)
      material.value.title = file.name.replace(/\.[^.]+$/, '').slice(0, 200)
  }, '资料已上传，请保存资料')
  e.target.value = ''
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
    const value = { ...material.value, title: material.value.title.trim() }
    if (!value.title) throw new Error('请填写资料名称')
    if (value.materialType === 'LINK') {
      let url
      try {
        url = new URL(value.externalUrl.trim())
      } catch {
        throw new Error('请输入完整的 HTTP 或 HTTPS 网页地址')
      }
      if (
        !['http:', 'https:'].includes(url.protocol) ||
        !url.hostname ||
        url.username ||
        url.password
      )
        throw new Error('网页链接仅支持不含账号密码的 HTTP 或 HTTPS 地址')
      value.externalUrl = url.href
      value.assetId = null
    } else {
      delete value.externalUrl
      if (value.materialType !== 'TEXT' && !value.assetId) throw new Error('请先上传对应资料文件')
      if (value.materialType === 'TEXT' && !value.content.trim() && !value.assetId)
        throw new Error('请填写文字正文或上传 TXT 附件')
    }
    await api('/courses/' + selected.value.id + '/materials', 'POST', value)
    detail.value = await api('/courses/' + selected.value.id)
    material.value = newMaterial()
    materialUploadName.value = ''
  }, '课程资料已保存')
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
  }, '课程已提交审核，发布后原账号将获得内训师身份')
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
      <h3>每一位员工，都可以成为内训师</h3>
      <p>
        创建课程 → 添加视频、文档或网页资料 → 提交审核 →
        发布后兼任内训师，在同一账号查看课程学习情况。
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
                ><RouterLink v-else :to="'/courses/' + c.id + '?preview=1'" class="text-link"
                  >预览课程</RouterLink
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
        02 视频章节（可选）</button
      ><button
        :disabled="!selected"
        :class="{ active: step === 'materials' }"
        @click="step = 'materials'"
      >
        03 课程资料
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
          保存并添加学习内容<Icon name="arrow" :size="16" />
        </button>
      </footer>
    </form>
    <template v-if="step === 'content'"
      ><div class="notice">
        视频课程需核对标准时长与有效播放要求。也可跳过视频，直接添加必读文档、文字或网页资料，创建纯资料课程。
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
          ><button type="button" class="btn" @click="step = 'materials'">下一步：课程资料</button>
        </footer>
      </form></template
    ><template v-if="step === 'materials'"
      ><p class="notice">
        支持文字、PDF、PPT/PPTX
        和网页链接。无需视频也能开课，至少添加一份必读资料即可提交。阅读完成需员工主动确认，不统计为视频播放时长。
      </p>
      <p v-if="session.mode === 'demo'" class="notice gold">
        演示上传文件仅在当前页面有效，刷新后需重新上传；资料名称和学习记录会保留。正式模式由服务器保存文件。
      </p>
      <div v-for="m in detail?.materials" :key="m.id" class="saved-material">
        <Icon :name="m.material_type === 'LINK' ? 'external' : 'file'" :size="16" />
        <div>
          <strong>{{ m.title }}</strong
          ><small
            >{{ materialLabel(m.material_type) }}{{ m.file_name ? ' · ' + m.file_name : '' }}</small
          >
        </div>
        <span class="tag">{{ m.required ? '必读' : '选读' }}</span>
      </div>
      <form class="section-spacer" @submit.prevent="addMaterial">
        <div class="form-grid">
          <label class="field"
            ><span>资料类型</span
            ><select
              v-model="material.materialType"
              :disabled="ui.busy > 0"
              @change="changeMaterialType"
            >
              <option value="TEXT">文字 / TXT</option>
              <option value="PDF">PDF 文档</option>
              <option value="PPT">PPT / PPTX 课件</option>
              <option value="LINK">网页链接</option>
            </select></label
          >
          <label class="material-required"
            ><input type="checkbox" v-model="material.required" />必读资料（计入课程完成）</label
          >
          <label class="field span-2"
            ><span>资料名称</span><input v-model="material.title" required maxlength="200"
          /></label>
          <label v-if="material.materialType === 'LINK'" class="field span-2">
            <span>网页地址</span
            ><input
              v-model="material.externalUrl"
              type="url"
              required
              maxlength="2048"
              placeholder="https://example.com/training"
            />
            <small>仅支持 HTTP / HTTPS；请使用员工有权限访问的学习页面。</small>
          </label>
          <label v-else class="upload-zone span-2" :key="material.materialType">
            <Icon name="upload" :size="24" />
            <p>
              {{
                materialUploadName ||
                (material.materialType === 'TEXT'
                  ? '选择 TXT 文件（可选，UTF-8 编码）'
                  : '选择 ' + materialLabel(material.materialType) + ' 文件')
              }}
            </p>
            <input
              type="file"
              :accept="materialAccept"
              :disabled="ui.busy > 0"
              @change="materialFileChange"
            />
            <small v-if="materialUploadName"
              >已上传：{{ materialUploadName }}，保存资料后才会加入课程。</small
            >
          </label>
          <label class="field span-2"
            ><span>{{
              material.materialType === 'TEXT' ? '课程文字正文' : '补充讲稿 / 内容摘要（可选）'
            }}</span
            ><textarea
              v-model="material.content"
              :required="material.materialType === 'TEXT' && !material.assetId"
              maxlength="200000"
              rows="6"
              placeholder="课程助手使用这里的文字内容答疑；PDF、PPT、网页和视频不会自动提取全文。"
            />
          </label>
        </div>
        <p v-if="material.materialType === 'PPT'" class="caption section-spacer">
          PPT / PPTX 提供下载后用 Office 或兼容软件打开，不提供浏览器原生翻页预览。
        </p>
        <footer class="form-actions">
          <button class="btn outline" :disabled="ui.busy > 0">保存课程资料</button
          ><button
            type="button"
            class="btn"
            :disabled="ui.busy > 0 || !canSubmit"
            @click="submit(selected)"
          >
            提交课程审核
          </button>
        </footer>
        <p v-if="!canSubmit" class="caption">
          请先保存至少一个视频章节或一份必读资料，再提交审核。
        </p>
      </form></template
    ></Modal
  >
</template>
<style scoped>
.saved-material {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
  border-bottom: 1px solid #edf1f7;
  color: #003f98;
}
.saved-material > div {
  flex: 1;
  min-width: 0;
}
.saved-material strong {
  display: block;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.saved-material small {
  display: block;
  color: #8294ad;
  font-size: 10px;
  margin-top: 5px;
  overflow-wrap: anywhere;
}
.material-required {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: #61748e;
  padding-top: 23px;
}
.upload-zone small,
.field small {
  color: #8093ad;
  font-size: 10px;
  line-height: 1.8;
}
@media (max-width: 600px) {
  .material-required {
    padding-top: 0;
  }
}
</style>
