<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { api, perform, toast, ui } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import LoadState from '../components/LoadState.vue'
import Status from '../components/Status.vue'
const id = useRoute().params.id,
  router = useRouter(),
  data = ref(null),
  answers = ref({}),
  saveStatus = ref('答案修改后自动保存'),
  now = ref(Date.now()),
  confirm = ref(false),
  leaving = false
let timer,
  saving = Promise.resolve(),
  pendingError = ''
const { loading, error, reload } = useLoad(async () => {
  data.value = await api('/attempts/' + id)
  data.value.answers.forEach((a) => {
    const q = data.value.questions.find((q) => q.id === a.paper_question_id)
    answers.value[a.paper_question_id] =
      q?.type === 'MULTIPLE' ? a.answer.split(',').filter(Boolean) : a.answer
  })
})
const active = computed(() => data.value?.attempt.status === 'IN_PROGRESS'),
  left = computed(() =>
    Math.max(0, Math.ceil(((data.value?.attempt.deadline_ms || 0) - now.value) / 1000)),
  ),
  clock = computed(
    () =>
      `${String(Math.floor(left.value / 60)).padStart(2, '0')}:${String(left.value % 60).padStart(2, '0')}`,
  )
function save(q) {
  const value = Array.isArray(answers.value[q.id])
    ? answers.value[q.id].join(',')
    : answers.value[q.id] || ''
  saveStatus.value = '正在保存…'
  saving = saving.then(async () => {
    try {
      await api('/attempts/' + id + '/answers', 'PUT', { questionId: q.id, answer: value })
      saveStatus.value = '答案已保存'
      pendingError = ''
    } catch (e) {
      pendingError = e.message
      saveStatus.value = '保存失败：' + e.message
      toast('答案未保存，请修改后重试或检查网络。', 'error')
    }
  })
}
async function submit() {
  await perform(async () => {
    await saving
    if (pendingError) throw new Error('还有未保存的答案，请先重试保存。')
    data.value = await api('/attempts/' + id + '/submit', 'POST')
    confirm.value = false
  }, '已交卷，成绩以最终阅卷结果为准')
}
async function tick() {
  now.value = Date.now()
  if (data.value && active.value && left.value === 0) {
    try {
      data.value = await api('/attempts/' + id + '/submit', 'POST')
    } catch (e) {
      saveStatus.value = '到时交卷同步中：' + e.message
    }
  }
}
function blur() {
  if (active.value)
    void api('/attempts/' + id + '/events', 'POST', {
      event: document.hidden ? 'BLUR' : 'FOCUS',
    }).catch(() => {})
}
onMounted(() => {
  timer = setInterval(tick, 1000)
  document.addEventListener('visibilitychange', blur)
})
onUnmounted(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', blur)
})
onBeforeRouteLeave(
  () => !active.value || window.confirm('考试计时不会停止。确定离开吗？未成功保存的答案可能丢失。'),
)
</script>
<template>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><template v-if="data"
      ><div class="page-heading">
        <div>
          <p class="eyebrow">FOCUS & DO YOUR BEST</p>
          <h1>{{ data.exam.title }}</h1>
          <p>请独立作答。切换页面会记录风险事件，课程助手暂时停用。</p>
        </div>
        <Status :value="data.attempt.status" />
      </div>
      <div class="exam-toolbar" v-if="active">
        <div>
          <p class="caption">剩余时间</p>
          <b class="exam-timer">{{ clock }}</b>
        </div>
        <span class="caption">{{ saveStatus }}</span
        ><button class="btn" :disabled="ui.busy > 0" @click="confirm = true">
          交卷<Icon name="check" :size="16" />
        </button>
      </div>
      <div class="panel section-spacer" v-else>
        <div class="result-hero">
          <span><Icon name="award" :size="36" /></span>
          <div>
            <h2>
              {{
                data.attempt.status === 'PENDING_GRADE'
                  ? '已交卷，等待人工阅卷'
                  : data.attempt.passed
                    ? '恭喜，考核已通过'
                    : '本次考核尚未通过'
              }}
            </h2>
            <p class="caption">
              {{
                data.attempt.score == null
                  ? '主观题阅卷完成后发布最终成绩。'
                  : '本次成绩：' +
                    data.attempt.score +
                    ' 分 · 及格线 ' +
                    data.exam.pass_score +
                    ' 分'
              }}
            </p>
          </div>
          <RouterLink to="/exams" class="btn outline">返回考试中心</RouterLink>
        </div>
      </div>
      <div v-for="(q, i) in data.questions" :key="q.id" class="question-block section-spacer">
        <div class="inline-meta">
          <span>第 {{ i + 1 }} 题</span
          ><span>{{
            q.type === 'MULTIPLE'
              ? '多选题'
              : q.type === 'TEXT'
                ? '简答题'
                : q.type === 'TRUE_FALSE'
                  ? '判断题'
                  : '单选题'
          }}</span
          ><span>{{ q.score }} 分</span>
        </div>
        <h3 class="section-spacer">{{ q.stem }}</h3>
        <textarea
          v-if="q.type === 'TEXT'"
          v-model="answers[q.id]"
          :disabled="!active || left === 0"
          maxlength="4000"
          class="full-input"
          placeholder="请填写你的答案"
          @change="save(q)"
        /><label v-else v-for="(text, key) in q.options" :key="key" class="answer-option"
          ><input
            v-if="q.type === 'MULTIPLE'"
            type="checkbox"
            :disabled="!active || left === 0"
            :value="key"
            :checked="(answers[q.id] || []).includes(key)"
            @change="
              (e) => {
                let a = answers[q.id] || []
                answers[q.id] = e.target.checked ? [...a, key] : a.filter((x) => x !== key)
                save(q)
              }
            "
          /><input
            v-else
            type="radio"
            :disabled="!active || left === 0"
            :name="'q' + q.id"
            :value="key"
            v-model="answers[q.id]"
            @change="save(q)"
          /><b>{{ key }}</b
          ><span>{{ text }}</span></label
        >
      </div></template
    ></LoadState
  ><Modal v-if="confirm" title="确认提交试卷" @close="confirm = false"
    ><p class="prose">交卷后将不能继续修改本次答案，是否确认提交？</p>
    <footer class="form-actions">
      <button class="btn white" @click="confirm = false">继续答题</button
      ><button class="btn" :disabled="ui.busy > 0" @click="submit">确认交卷</button>
    </footer></Modal
  >
</template>
<style scoped>
.full-input {
  width: 100%;
}
.result-hero {
  display: flex;
  align-items: center;
  gap: 20px;
}
.result-hero > span {
  color: #b89a43;
  background: #fff6db;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 65px;
  height: 65px;
  border-radius: 18px;
}
.result-hero > div {
  flex: 1;
}
.result-hero .caption {
  margin-top: 9px;
}
@media (max-width: 650px) {
  .result-hero {
    align-items: flex-start;
    flex-direction: column;
  }
  .question-block {
    padding: 17px;
  }
}
</style>
