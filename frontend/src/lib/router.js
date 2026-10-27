import { createRouter, createWebHashHistory } from 'vue-router'
import { session, loadMe, toast } from './api.js'
import Home from '../views/Home.vue'
import Courses from '../views/Courses.vue'
import Player from '../views/Player.vue'
import Paths from '../views/Paths.vue'
import Plans from '../views/Plans.vue'
import Exams from '../views/Exams.vue'
import Attempt from '../views/Attempt.vue'
import Results from '../views/Results.vue'
import Creator from '../views/Creator.vue'
import Teaching from '../views/Teaching.vue'
import Assessments from '../views/Assessments.vue'
import Reviews from '../views/Reviews.vue'
import Warnings from '../views/Warnings.vue'
import Company from '../views/Company.vue'
import Users from '../views/Users.vue'
import Messages from '../views/Messages.vue'
import Settings from '../views/Settings.vue'
import Login from '../views/Login.vue'
const routes = [
  { path: '/', component: Home, meta: { title: '学习工作台' } },
  { path: '/courses', component: Courses, meta: { title: '课程中心' } },
  { path: '/courses/:id', component: Player, meta: { title: '课程学习' } },
  { path: '/paths', component: Paths, meta: { title: '我的成长路线' } },
  { path: '/plans', component: Plans, meta: { title: '培训计划' } },
  { path: '/exams', component: Exams, meta: { title: '考试中心' } },
  { path: '/attempt/:id', component: Attempt, meta: { title: '在线考试' } },
  { path: '/results', component: Results, meta: { title: '我的培训成果' } },
  { path: '/creation', component: Creator, meta: { title: '我的课程创作' } },
  {
    path: '/teaching',
    component: Teaching,
    meta: { title: '授课工作台', roles: ['INSTRUCTOR', 'ADMIN'] },
  },
  {
    path: '/assessment',
    component: Assessments,
    meta: { title: '题库与考试管理', roles: ['INSTRUCTOR', 'ADMIN'] },
  },
  { path: '/manage/plans', component: Plans, meta: { title: '培训计划管理', roles: ['ADMIN'] } },
  { path: '/manage/paths', component: Paths, meta: { title: '成长路线管理', roles: ['ADMIN'] } },
  { path: '/reviews', component: Reviews, meta: { title: '课程审核', roles: ['ADMIN'] } },
  { path: '/warnings', component: Warnings, meta: { title: '预警中心', roles: ['ADMIN'] } },
  {
    path: '/company',
    component: Company,
    meta: { title: '公司培训看板', roles: ['ADMIN', 'MANAGER'] },
  },
  { path: '/users', component: Users, meta: { title: '组织与人员', roles: ['ADMIN'] } },
  { path: '/messages', component: Messages, meta: { title: '消息中心' } },
  { path: '/settings', component: Settings, meta: { title: '运行与账户设置' } },
  { path: '/login', component: Login, meta: { title: '登录' } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]
export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})
router.beforeEach(async (to) => {
  if (!session.me)
    try {
      await loadMe()
    } catch (e) {
      toast(e.message, 'error')
    }
  if (to.path === '/login') return session.me ? '/' : true
  if (!session.me) return '/login'
  if (to.meta.roles && !to.meta.roles.some((r) => session.me.roles.includes(r))) {
    toast('当前身份无权访问该工作区', 'error')
    return '/'
  }
  return true
})
