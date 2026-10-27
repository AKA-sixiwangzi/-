<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { api, perform, ui } from '../lib/api.js'
import { useLoad } from '../lib/load.js'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import LoadState from '../components/LoadState.vue'
import Empty from '../components/Empty.vue'
const route = useRoute(),
  manage = computed(() => route.path.startsWith('/manage')),
  rows = ref([]),
  detail = ref(null),
  courses = ref([]),
  users = ref([]),
  org = ref({ positions: [], grades: [] }),
  dialog = ref(''),
  form = ref({}),
  selected = ref(null)
const { loading, error, reload } = useLoad(async () => {
  rows.value = await api('/paths')
  if (rows.value.length) detail.value = await api('/paths/' + rows.value[0].id)
  if (manage.value)
    [courses.value, users.value, org.value] = await Promise.all([
      api('/courses'),
      api('/admin/users'),
      api('/organization'),
    ])
})
async function choose(id) {
  await perform(async () => (detail.value = await api('/paths/' + id)))
}
function open(type, p) {
  selected.value = p
  form.value =
    type === 'path'
      ? { title: '', positionId: null, gradeId: null }
      : type === 'stage'
        ? { title: '', sortOrder: (detail.value?.stages.length || 0) + 1, courseIds: [] }
        : { userIds: [] }
  dialog.value = type
}
async function submit() {
  await perform(async () => {
    if (dialog.value === 'path') await api('/paths', 'POST', form.value)
    if (dialog.value === 'stage')
      await api('/paths/' + selected.value.id + '/stages', 'POST', form.value)
    if (dialog.value === 'assign')
      await api('/paths/' + selected.value.id + '/assign', 'POST', form.value)
    dialog.value = ''
    await reload()
  }, '操作已保存')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">CAREER DEVELOPMENT</p>
      <h1>{{ manage ? '成长路线管理' : '我的成长路线' }}</h1>
      <p>从岗位基础到专业进阶，清楚知道下一步往哪里走。</p>
    </div>
    <button v-if="manage" class="btn" @click="open('path')">
      <Icon name="plus" :size="16" />新建路线
    </button>
  </div>
  <LoadState :loading="loading" :error="error" @retry="reload"
    ><div class="tabs page-tabs">
      <button
        v-for="p in rows"
        :key="p.id"
        :class="{ active: detail?.path.id === p.id }"
        @click="choose(p.id)"
      >
        {{ p.title }} {{ manage && !p.published ? '· 草稿' : '' }}
      </button>
    </div>
    <template v-if="detail"
      ><section class="panel path-banner">
        <div>
          <span class="tag">岗位成长计划</span>
          <h2>{{ detail.path.title }}</h2>
          <p class="caption">
            完成阶段课程并通过关联考试，即可解锁下一阶段。已有有效学习结果可复用。
          </p>
        </div>
        <div class="btn-row" v-if="manage">
          <button
            v-if="!detail.path.published"
            class="btn outline"
            @click="open('stage', detail.path)"
          >
            增加阶段</button
          ><button
            v-if="!detail.path.published"
            class="btn"
            @click="
              perform(async () => {
                await api('/paths/' + detail.path.id + '/publish', 'POST')
                await choose(detail.path.id)
              }, '路线已发布')
            "
          >
            发布路线</button
          ><button v-else class="btn" @click="open('assign', detail.path)">分配员工</button>
        </div>
        <button
          v-else
          class="btn yellow"
          @click="
            perform(
              () => api('/paths/' + detail.path.id + '/certificate', 'POST'),
              '已领取路线证书',
            )
          "
        >
          <Icon name="award" :size="17" />领取通关证书
        </button>
      </section>
      <div class="path-stages">
        <article
          v-for="(item, i) in detail.stages"
          :key="item.stage.id"
          class="path-stage"
          :class="{ locked: !item.unlocked && !manage }"
        >
          <div class="stage-number">
            <Icon v-if="item.passed" name="check" :size="23" /><Icon
              v-else-if="!item.unlocked && !manage"
              name="lock"
              :size="20"
            /><span v-else>{{ String(i + 1).padStart(2, '0') }}</span>
          </div>
          <section class="panel">
            <header class="panel-head">
              <div>
                <small class="eyebrow">STAGE {{ String(i + 1).padStart(2, '0') }}</small>
                <h2>{{ item.stage.title }}</h2>
              </div>
              <span class="tag">{{
                manage
                  ? '阶段 ID ' + item.stage.id
                  : item.passed
                    ? '已通关'
                    : item.unlocked
                      ? '当前阶段'
                      : '完成前序阶段后解锁'
              }}</span>
            </header>
            <div class="stage-courses">
              <RouterLink v-for="c in item.courses" :key="c.id" :to="'/courses/' + c.id"
                ><span class="stage-course-icon"><Icon name="book" :size="20" /></span>
                <div>
                  <h3>{{ c.title }}</h3>
                  <small>{{ c.completed ? '课程已完成' : '完成必修课程' }}</small>
                </div>
                <Icon :name="c.completed ? 'success' : 'right'" :size="17"
              /></RouterLink>
            </div>
            <div class="stage-exams" v-for="e in item.exams" :key="e.id">
              <Icon name="exam" :size="17" /><span>{{ e.title }}</span
              ><RouterLink to="/exams" class="text-link"
                >{{ e.passed ? '已通过' : '前往考试中心' }}<Icon name="right" :size="13"
              /></RouterLink>
            </div>
          </section>
        </article></div></template
    ><Empty
      v-else
      title="成长路线尚未开启"
      text="管理员会根据岗位配置成长阶段，发布后分配给你。" /></LoadState
  ><Modal
    v-if="dialog"
    :title="
      dialog === 'path' ? '新建岗位成长路线' : dialog === 'stage' ? '配置学习阶段' : '分配成长路线'
    "
    @close="dialog = ''"
    ><form @submit.prevent="submit">
      <div class="form-grid">
        <template v-if="dialog === 'path'"
          ><label class="field span-2"
            ><span>路线名称</span><input v-model="form.title" required maxlength="200" /></label
          ><label class="field"
            ><span>适用岗位</span
            ><select v-model="form.positionId">
              <option :value="null">不限定</option>
              <option v-for="p in org.positions" :value="p.id" :key="p.id">{{ p.name }}</option>
            </select></label
          ><label class="field"
            ><span>适用职级</span
            ><select v-model="form.gradeId">
              <option :value="null">不限定</option>
              <option v-for="p in org.grades" :value="p.id" :key="p.id">{{ p.name }}</option>
            </select></label
          ></template
        ><template v-if="dialog === 'stage'"
          ><label class="field"><span>阶段名称</span><input v-model="form.title" required /></label
          ><label class="field"
            ><span>阶段顺序</span
            ><input v-model.number="form.sortOrder" type="number" min="1" required
          /></label>
          <div class="field span-2">
            <span>阶段课程</span>
            <div class="check-list">
              <label v-for="c in courses" :key="c.id"
                ><input type="checkbox" v-model="form.courseIds" :value="c.id" />{{
                  c.title
                }}</label
              >
            </div>
          </div>
          <p class="notice span-2">
            如需关联阶段考试，请在“题库与考试”中使用阶段 ID 配置，再发布路线。
          </p></template
        >
        <div v-if="dialog === 'assign'" class="field span-2">
          <span>选择员工</span>
          <div class="check-list">
            <label v-for="u in users.filter((x) => x.active)" :key="u.id"
              ><input type="checkbox" v-model="form.userIds" :value="u.id" />{{
                u.display_name
              }}</label
            >
          </div>
        </div>
      </div>
      <footer class="form-actions">
        <button type="button" class="btn white" @click="dialog = ''">取消</button
        ><button class="btn" :disabled="ui.busy > 0">保存</button>
      </footer>
    </form></Modal
  >
</template>
<style scoped>
.path-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  padding: 26px;
  margin-bottom: 28px;
  background: linear-gradient(110deg, #fff, #f0f5ff);
}
.path-banner h2 {
  font-size: 21px;
  margin: 12px 0 9px;
}
.path-stages {
  margin-left: 20px;
}
.path-stage {
  position: relative;
  display: grid;
  grid-template-columns: 60px 1fr;
  gap: 20px;
  padding-bottom: 28px;
}
.path-stage:before {
  content: '';
  position: absolute;
  left: 21px;
  top: 45px;
  bottom: 0;
  border-left: 2px dashed #dae3ef;
}
.path-stage:last-child:before {
  display: none;
}
.stage-number {
  width: 44px;
  height: 44px;
  border: 5px solid #e4edf9;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #003f98;
  color: #fff;
  font-size: 14px;
  margin-top: 10px;
  z-index: 1;
}
.locked .stage-number {
  background: #eef2f7;
  color: #a8b5c7;
  border-color: #e9eef5;
}
.stage-courses {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.stage-courses > a {
  border: 1px solid #e8edf5;
  border-radius: 7px;
  padding: 15px;
  display: flex;
  gap: 12px;
  align-items: center;
  background: #fbfcff;
}
.stage-course-icon {
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #eaf1fb;
  color: #7595bd;
  border-radius: 8px;
}
.stage-courses > a > div {
  flex: 1;
}
.stage-courses h3 {
  font-size: 12px;
  font-weight: 500;
}
.stage-courses small {
  font-size: 10px;
  color: #9dabc0;
  display: block;
  margin-top: 5px;
}
.stage-courses > a > svg {
  color: #a3b5cc;
}
.stage-exams {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: #8399b5;
  margin-top: 17px;
}
.stage-exams .text-link {
  margin-left: auto;
}
@media (max-width: 1000px) {
  .stage-courses {
    grid-template-columns: 1fr;
  }
  .path-banner {
    align-items: flex-start;
    flex-direction: column;
  }
}
@media (max-width: 600px) {
  .path-stages {
    margin-left: 0;
  }
  .path-stage {
    grid-template-columns: 35px 1fr;
    gap: 10px;
  }
  .stage-number {
    width: 34px;
    height: 34px;
    font-size: 11px;
  }
  .path-stage:before {
    left: 16px;
  }
  .path-stage .panel {
    padding: 14px;
  }
  .stage-courses > a {
    padding: 12px;
    gap: 9px;
  }
  .stage-course-icon {
    display: none;
  }
  .stage-courses h3 {
    font-size: 11px;
  }
  .path-stage .panel-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
