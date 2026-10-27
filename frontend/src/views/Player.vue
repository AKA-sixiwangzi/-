<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import { api, media, session, perform, toast } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import Icon from '../components/Icon.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const route = useRoute(),
  id = Number(route.params.id),
  detail = ref(null),
  progress = ref([]),
  selected = ref(null),
  video = ref(),
  src = ref(''),
  playing = ref(false),
  buffering = ref(false),
  networkError = ref(''),
  preview = ref(false),
  tab = ref('materials'),
  question = ref(''),
  answer = ref(null),
  asking = ref(false),
  work = ref({ assignments: [], submissions: [] }),
  submissions = ref({}),
  rating = ref(5),
  comment = ref('')
let sid = '',
  seq = 1,
  running = false,
  outbox = [],
  heartbeat,
  refreshTicket,
  internalSeek = false,
  started = false,
  leaving = false
const total = computed(
    () => detail.value?.lessons.reduce((a, l) => a + l.duration_seconds, 0) || 1,
  ),
  covered = computed(() => progress.value.reduce((a, l) => a + l.covered_seconds, 0)),
  effective = computed(() => progress.value.reduce((a, l) => a + l.effective_seconds, 0)),
  percent = computed(() => Math.min(100, Math.round((covered.value / total.value) * 100)))
const { loading, error, reload } = useLoad(async () => {
  detail.value = await api('/courses/' + id)
  try {
    progress.value = await api('/courses/' + id + '/progress')
  } catch (e) {
    if (e.status === 403) preview.value = true
    else throw e
  }
  work.value = await api('/courses/' + id + '/coursework')
  selected.value =
    detail.value.lessons.find((l) => !progress.value.find((p) => p.id === l.id)?.completed) ||
    detail.value.lessons[0]
  if (selected.value) src.value = await media(selected.value.video_asset_id)
})
async function flush() {
  if (running || !outbox.length || !sid) return
  running = true
  while (outbox.length) {
    const entry = outbox[0]
    try {
      const r = await api('/learning/sessions/' + sid + '/events', 'POST', { ...entry, seq })
      seq++
      outbox.shift()
      networkError.value = ''
      if (r?.lessons) progress.value = r.lessons
    } catch (e) {
      networkError.value = e.message
      video.value?.pause()
      playing.value = false
      break
    }
  }
  running = false
}
function report(event) {
  if (preview.value || !sid) return
  outbox.push({
    position: Math.max(0, video.value?.currentTime || 0),
    event,
    visible: document.visibilityState === 'visible',
    rate: video.value?.playbackRate || 1,
  })
  void flush()
}
async function onPlaying() {
  if (internalSeek || leaving) return
  playing.value = true
  buffering.value = false
  if (preview.value) return
  try {
    if (!sid) {
      if (started) return
      started = true
      const r = await api('/lessons/' + selected.value.id + '/sessions', 'POST')
      if (r.conflict) throw new Error(r.message)
      sid = r.sessionId
      seq = r.nextSeq || 1
    }
    report('PLAY')
  } catch (e) {
    networkError.value = e.message
    video.value?.pause()
  } finally {
    started = false
  }
}
function pause() {
  playing.value = false
  if (!internalSeek) report('PAUSE')
}
function wait() {
  buffering.value = true
  report('PAUSE')
}
function seek() {
  if (internalSeek) return
  report('SEEK')
  toast('拖动位置已记录，跳过的片段不计入有效进度。', 'info')
}
function seeked() {
  if (internalSeek) {
    internalSeek = false
    return
  }
  if (!video.value?.paused) report('PLAY')
}
function ended() {
  playing.value = false
  report('END')
}
async function closeSession() {
  video.value?.pause()
  if (sid) {
    report('END')
    await flush()
    if (outbox.length) throw new Error('学习记录尚未同步，请先重试同步后再离开。')
    await api('/learning/stop', 'POST')
    sid = ''
    seq = 1
    outbox = []
  }
}
async function choose(l) {
  if (l.id === selected.value?.id) return
  await perform(async () => {
    await closeSession()
    selected.value = l
    src.value = await media(l.video_asset_id)
    networkError.value = ''
    playing.value = false
  })
}
async function retry() {
  if (!sid) {
    networkError.value = ''
    return
  }
  await flush()
  if (!outbox.length) toast('学习记录已同步，可继续播放')
}
function visibility() {
  if (document.visibilityState === 'hidden') {
    video.value?.pause()
    playing.value = false
  }
}
async function openAsset(asset) {
  const popup = window.open('about:blank', '_blank')
  try {
    const url = await media(asset)
    if (popup) {
      popup.opener = null
      popup.location.href = url
    }
  } catch (e) {
    popup?.close()
    toast(e.message, 'error')
  }
}
async function ask() {
  if (!question.value.trim()) return
  asking.value = true
  try {
    answer.value = await api('/courses/' + id + '/ai/ask', 'POST', { question: question.value })
    question.value = ''
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    asking.value = false
  }
}
async function renew() {
  if (session.mode === 'demo' || !selected.value) return
  const v = video.value
  if (!v) return
  const pos = v.currentTime,
    active = !v.paused
  v.pause()
  try {
    src.value = await media(selected.value.video_asset_id)
    v.addEventListener(
      'loadedmetadata',
      () => {
        internalSeek = true
        v.currentTime = pos
        if (active) void v.play().catch(() => {})
      },
      { once: true },
    )
  } catch (e) {
    networkError.value = e.message
  }
}
onMounted(() => {
  heartbeat = setInterval(() => {
    if (playing.value && !buffering.value && !video.value?.seeking) report('HEARTBEAT')
  }, 10000)
  refreshTicket = setInterval(renew, 840000)
  document.addEventListener('visibilitychange', visibility)
})
onBeforeRouteLeave(async () => {
  leaving = true
  try {
    await closeSession()
    return true
  } catch (e) {
    toast(e.message, 'error')
    leaving = false
    return window.confirm('还有学习记录未同步。确认离开吗？未同步部分可能不计入进度。')
  }
})
onUnmounted(() => {
  clearInterval(heartbeat)
  clearInterval(refreshTicket)
  document.removeEventListener('visibilitychange', visibility)
})
</script>
<template>
  <RouterLink to="/courses" class="back-link"
    ><Icon name="arrow" :size="15" />返回我的课程</RouterLink
  ><LoadState :loading="loading" :error="error" @retry="reload"
    ><template v-if="detail"
      ><div class="page-heading">
        <div>
          <p class="eyebrow">LEARN WITH PURPOSE</p>
          <h1>{{ detail.course.title }}</h1>
          <p>{{ detail.course.category }} · {{ detail.lessons.length }} 个章节</p>
        </div>
        <span class="tag">{{ preview ? '讲师内容预览' : '岗位学习' }}</span>
      </div>
      <div v-if="session.mode === 'demo'" class="notice gold">
        演示视频每章30秒，使用浏览器本地记录演示有效播放；正式模式由后端校验，演示结果不计入正式培训。
      </div>
      <div v-if="networkError" class="notice error">
        {{ networkError }} <button class="text-link" @click="retry">重试同步</button>
      </div>
      <div class="player-layout">
        <section>
          <div class="video-frame">
            <video
              v-if="src"
              ref="video"
              :src="src"
              controls
              playsinline
              preload="metadata"
              controlslist="nodownload noplaybackrate"
              @playing="onPlaying"
              @pause="pause"
              @waiting="wait"
              @seeking="seek"
              @seeked="seeked"
              @ended="ended"
              @ratechange="
                () => {
                  if (video.playbackRate !== 1) {
                    video.playbackRate = 1
                    toast('首次学习仅支持1倍速', 'info')
                  }
                }
              "
              @error="networkError = '视频加载失败，请检查资源或重新获取播放凭证。'"
            /><Empty v-else title="暂无视频资源" />
          </div>
          <div class="player-status">
            <span
              ><i :class="{ playing }" />{{
                preview
                  ? '预览不记录个人进度'
                  : networkError
                    ? '记录同步异常'
                    : playing
                      ? '正在记录有效播放'
                      : '播放视频以记录有效学习'
              }}</span
            ><span>已覆盖 {{ percent }}% · 有效 {{ Math.floor(effective) }} 秒</span>
          </div>
          <div class="panel section-spacer">
            <div class="tabs page-tabs">
              <button :class="{ active: tab === 'materials' }" @click="tab = 'materials'">
                课程讲义</button
              ><button :class="{ active: tab === 'work' }" @click="tab = 'work'">课后作业</button
              ><button :class="{ active: tab === 'feedback' }" @click="tab = 'feedback'">
                学习评价
              </button>
            </div>
            <template v-if="tab === 'materials'"
              ><article v-for="m in detail.materials" :key="m.id" class="material-block">
                <h3><Icon name="file" :size="17" />{{ m.title }}</h3>
                <p class="prose">{{ m.content }}</p>
                <button v-if="m.asset_id" class="text-link" @click="openAsset(m.asset_id)">
                  查看附件<Icon name="external" :size="14" />
                </button>
              </article>
              <Empty v-if="!detail.materials.length" title="讲师尚未上传文字资料" /></template
            ><template v-if="tab === 'work'"
              ><article class="material-block" v-for="a in work.assignments" :key="a.id">
                <h3>{{ a.title }}</h3>
                <p class="prose">{{ a.instructions }}</p>
                <textarea
                  v-model="submissions[a.id]"
                  class="full-input"
                  placeholder="写下你的实践过程或作业内容"
                /><button
                  class="btn section-spacer"
                  @click="
                    perform(async () => {
                      await api('/assignments/' + a.id + '/submissions', 'POST', {
                        content: submissions[a.id] || '',
                        assetId: null,
                      })
                      work = await api('/courses/' + id + '/coursework')
                    }, '作业已提交')
                  "
                >
                  提交作业
                </button>
                <p
                  class="caption section-spacer"
                  v-for="s in work.submissions.filter((s) => s.assignment_id === a.id)"
                  :key="s.id"
                >
                  已提交：{{ s.content }} ·
                  {{ s.score == null ? '待讲师评价' : s.score + '分 · ' + s.feedback }}
                </p>
              </article>
              <Empty
                v-if="!work.assignments.length"
                title="本课程暂无作业"
                text="你可以先复习讲义，再参加关联考试。"
            /></template>
            <form
              v-if="tab === 'feedback'"
              @submit.prevent="
                perform(
                  () =>
                    api('/courses/' + id + '/feedback', 'POST', {
                      rating: Number(rating),
                      comment,
                    }),
                  '评价已提交',
                )
              "
            >
              <div class="field">
                <span>课程满意度</span
                ><select v-model="rating">
                  <option v-for="n in 5" :key="n" :value="n">{{ n }} 星</option></select
                ><textarea
                  v-model="comment"
                  placeholder="你的建议，将帮助讲师改进课程。"
                  maxlength="2000"
                />
              </div>
              <button class="btn section-spacer" :disabled="percent < 95 || preview">
                提交评价
              </button>
              <p class="caption section-spacer">完成课程后可提交评价，最终以服务端完成状态为准。</p>
            </form>
          </div>
        </section>
        <aside class="stack">
          <section class="panel chapter-panel">
            <header class="panel-head">
              <h2>课程目录</h2>
              <span class="tag">{{ percent }}%</span>
            </header>
            <div class="progress"><i :style="{ width: percent + '%' }" /></div>
            <button
              v-for="(l, i) in detail.lessons"
              :key="l.id"
              class="chapter"
              :class="{ active: l.id === selected?.id }"
              @click="choose(l)"
            >
              <span class="chapter-index"
                ><Icon
                  v-if="progress.find((p) => p.id === l.id)?.completed"
                  name="check"
                  :size="15"
                /><b v-else>{{ String(i + 1).padStart(2, '0') }}</b></span
              ><span
                ><b>{{ l.title }}</b
                ><small
                  >{{ Math.ceil(l.duration_seconds / 60) }} 分钟 ·
                  {{ progress.find((p) => p.id === l.id)?.completed ? '已完成' : '待完成' }}</small
                ></span
              ><Icon :name="l.id === selected?.id ? 'play' : 'right'" :size="14" />
            </button>
          </section>
          <section class="panel ai-panel">
            <header class="panel-head">
              <h2><Icon name="ai" :size="18" />课程学习助手</h2>
              <span class="tag">AI</span>
            </header>
            <p class="caption">围绕本课程资料提问，答案附来源。正式考试期间暂停答疑。</p>
            <div v-if="answer" class="ai-answer">
              <p>{{ answer.answer }}</p>
              <small v-for="r in answer.citations" :key="r.id"
                ><Icon name="file" :size="12" />资料 #{{ r.id }} · {{ r.title }}</small
              >
            </div>
            <form @submit.prevent="ask">
              <textarea
                v-model="question"
                required
                maxlength="1000"
                placeholder="例如：本课程有哪些操作要点？"
              /><button class="btn full" :disabled="asking || preview">
                <Icon :name="asking ? 'clock' : 'send'" :size="15" />{{
                  asking ? '正在查阅资料…' : '向课程助手提问'
                }}
              </button>
            </form>
          </section>
        </aside>
      </div></template
    ></LoadState
  >
</template>
<style scoped>
.player-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 22px;
}
.video-frame {
  background: #001c3c;
  border-radius: 9px;
  overflow: hidden;
  aspect-ratio: 16/9;
  display: flex;
  align-items: center;
  justify-content: center;
}
.video-frame video {
  width: 100%;
  height: 100%;
  display: block;
}
.player-status {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 10px;
  color: #91a1b6;
  margin-top: 13px;
}
.player-status > span {
  display: flex;
  gap: 7px;
  align-items: center;
}
.player-status i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #aab6c5;
}
.player-status i.playing {
  background: #4b9e7c;
}
.chapter {
  width: 100%;
  background: transparent;
  padding: 15px 0;
  display: flex;
  align-items: center;
  gap: 10px;
  text-align: left;
  border-bottom: 1px solid #eef2f8;
  color: #9aa9bc;
}
.chapter.active {
  color: #003f98;
}
.chapter > span:nth-child(2) {
  flex: 1;
}
.chapter b {
  font-size: 11px;
  font-weight: 500;
  line-height: 1.8;
}
.chapter small {
  display: block;
  color: #a3afbf;
  font-size: 9px;
  margin-top: 3px;
}
.chapter-index {
  background: #edf3fc;
  border-radius: 5px;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.chapter.active .chapter-index {
  background: #003f98;
  color: #fff;
}
.ai-panel h2 {
  display: flex;
  gap: 8px;
  align-items: center;
}
.ai-panel textarea {
  margin: 17px 0 10px;
  width: 100%;
  font-size: 11px;
}
.ai-answer {
  background: #f5f8fd;
  border-radius: 7px;
  padding: 13px;
  margin-top: 15px;
}
.ai-answer p {
  font-size: 11px;
  line-height: 1.9;
  white-space: pre-wrap;
  color: #6983a4;
}
.ai-answer small {
  display: flex;
  gap: 4px;
  font-size: 9px;
  color: #9aabc1;
  margin-top: 13px;
  line-height: 1.6;
}
.material-block + .material-block {
  border-top: 1px solid #e8edf4;
  margin-top: 20px;
  padding-top: 20px;
}
.material-block h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 15px;
}
.full-input {
  width: 100%;
}
.ai-panel .btn {
  font-size: 10px;
}
@media (max-width: 1050px) {
  .player-layout {
    grid-template-columns: 1fr;
  }
  .player-layout > aside {
    display: grid;
    grid-template-columns: 1fr 1fr;
    align-items: start;
  }
}
@media (max-width: 620px) {
  .player-layout > aside {
    display: flex;
  }
  .player-status {
    font-size: 8px;
  }
}
</style>
