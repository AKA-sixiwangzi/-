<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, isNavigationFailure, NavigationFailureType } from 'vue-router'
import { session, ui, perform, switchDemo, logout, api } from './lib/api.js'
import Icon from './components/Icon.vue'
const route = useRoute(),
  router = useRouter(),
  mobile = ref(false),
  unread = ref(0)
const roles = computed(() => session.me?.roles || []),
  admin = computed(() => roles.value.includes('ADMIN')),
  teacher = computed(() => roles.value.includes('INSTRUCTOR'))
const user = computed(() => session.me?.user || {}),
  name = computed(() => user.value.display_name || '学习者')
const groups = computed(() => [
  {
    label: '学习与成长',
    items: [
      ['/', 'home', '学习工作台'],
      ['/courses', 'book', '我的课程'],
      ['/paths', 'route', '成长路线'],
      ['/plans', 'calendar', '培训计划'],
      ['/exams', 'exam', '考试中心'],
      ['/results', 'award', '培训成果'],
    ],
  },
  {
    label: '知识共创',
    items: [
      ['/creation', 'plus', '我的课程创作'],
      ...(teacher.value || admin.value
        ? [
            ['/teaching', 'teach', '授课工作台'],
            ['/assessment', 'list', '题库与考试'],
          ]
        : []),
    ],
  },
  ...(admin.value
    ? [
        {
          label: '培训管理',
          items: [
            ['/company', 'chart', '公司培训看板'],
            ['/manage/plans', 'calendar', '计划管理'],
            ['/manage/paths', 'layers', '路线管理'],
            ['/reviews', 'shield', '课程审核'],
            ['/warnings', 'warning', '预警中心'],
            ['/users', 'users', '组织与人员'],
          ],
        },
      ]
    : roles.value.includes('MANAGER')
      ? [{ label: '团队管理', items: [['/company', 'chart', '部门培训看板']] }]
      : []),
])
watch(
  () => [route.path, session.demoId, session.mode],
  async () => {
    mobile.value = false
    if (session.me)
      try {
        unread.value = (await api('/notifications')).filter((n) => !n.read_at).length
      } catch {}
  },
  { immediate: true },
)
async function persona(e) {
  const id = e.target.value
  await perform(async () => {
    const result = await router.push('/')
    if (
      isNavigationFailure(result, NavigationFailureType.aborted) ||
      isNavigationFailure(result, NavigationFailureType.cancelled)
    )
      return
    await switchDemo(id)
  })
}
async function leave() {
  await perform(logout)
  router.push('/login')
}
</script>
<template>
  <RouterView v-if="route.path === '/login'" />
  <div v-else class="app-shell">
    <div v-if="mobile" class="nav-shade" @click="mobile = false" />
    <aside class="sidebar" :class="{ open: mobile }">
      <RouterLink to="/" class="brand"
        ><span class="brand-mark"><i /><i /><i /></span
        ><span><strong>旭日学堂</strong><small>XURI ACADEMY</small></span></RouterLink
      >
      <div class="company-label">
        <Icon name="building" :size="15" /><span>企业学习与成长平台</span>
      </div>
      <nav aria-label="主导航">
        <section v-for="g in groups" :key="g.label" class="nav-group">
          <p>{{ g.label }}</p>
          <RouterLink
            v-for="[to, icon, label] in g.items"
            :key="to"
            :to="to"
            :class="{
              active:
                route.path === to || (to === '/courses' && route.path.startsWith('/courses/')),
            }"
            ><Icon :name="icon" :size="19" /><span>{{ label }}</span
            ><i v-if="to === '/creation'" class="new-dot"
          /></RouterLink>
        </section>
      </nav>
      <div class="sidebar-bottom">
        <div class="share-note">
          <Icon name="ai" :size="20" /><strong>每个人，都可以成为讲师</strong>
          <p>让你的好经验，帮助更多同事。</p>
          <RouterLink to="/creation">分享我的经验 <Icon name="arrow" :size="15" /></RouterLink>
        </div>
        <RouterLink class="settings-link" to="/settings"
          ><Icon name="settings" :size="18" />运行与账户设置</RouterLink
        >
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <div class="topbar-left">
          <button class="icon-btn mobile-only" aria-label="展开菜单" @click="mobile = true">
            <Icon name="menu" /></button
          ><span class="top-breadcrumb"
            >{{ admin ? '企业培训平台' : '学习中心' }}<Icon name="right" :size="13" /><b>{{
              route.meta.title
            }}</b></span
          >
        </div>
        <div class="topbar-right">
          <span class="mode-chip" :class="session.mode"
            ><i />{{ session.mode === 'demo' ? '演示模式' : '后端模式' }}</span
          ><label v-if="session.mode === 'demo'" class="persona-select"
            ><select
              aria-label="演示身份"
              :disabled="ui.busy > 0"
              :value="session.demoId"
              @change="persona"
            >
              <option :value="7">员工 · 张楠</option>
              <option :value="8">讲师 · 陈知行</option>
              <option :value="1">管理员 · 林</option>
              <option :value="2">复核管理员 · 周</option></select
            ><Icon name="down" :size="13" /></label
          ><RouterLink to="/messages" class="icon-btn notification-btn" aria-label="消息中心"
            ><Icon name="bell" /><i v-if="unread"
          /></RouterLink>
          <div class="profile">
            <span class="avatar">{{ name[0] }}</span>
            <div>
              <b>{{ name }}</b
              ><small>{{ admin ? '培训管理员' : teacher ? '员工 / 讲师' : '企业员工' }}</small>
            </div>
          </div>
          <button
            v-if="session.mode === 'api'"
            class="icon-btn"
            aria-label="退出登录"
            @click="leave"
          >
            <Icon name="logout" :size="18" />
          </button>
        </div>
      </header>
      <main class="page-main">
        <RouterView :key="route.fullPath + '-' + session.demoId + '-' + session.mode" />
      </main>
      <footer class="page-footer">
        <span>旭日学堂 · 让每一份努力，都被看见</span
        ><span>{{
          session.mode === 'demo' ? '本地演示数据 · 不作为正式培训记录' : '学习结果以服务端校验为准'
        }}</span>
      </footer>
    </div>
  </div>
  <div class="toast-stack" aria-live="polite">
    <div v-for="t in ui.toasts" :key="t.id" class="toast" :class="t.type">
      <Icon :name="t.type === 'error' ? 'warning' : 'success'" :size="20" /><span>{{
        t.message
      }}</span>
    </div>
  </div>
</template>
