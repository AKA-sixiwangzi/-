<script setup>
import { ref } from 'vue'
import {
  session,
  safeStorage,
  changeMode,
  demo,
  perform,
  api,
  logout,
  loadMe,
  toast,
  ui,
} from '../lib/api.js'
import { useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
const router = useRouter(),
  mode = ref(session.mode),
  base = ref(session.apiBase),
  reset = ref(false),
  oldPassword = ref(''),
  newPassword = ref(''),
  health = ref(''),
  file = location.protocol === 'file:'
async function apply() {
  await perform(async () => {
    if (!/^\/(?!\/)[\w/-]*$/.test(base.value))
      throw new Error('请输入同域API路径，例如 /api 或 /training/api。')
    session.apiBase = base.value.replace(/\/$/, '')
    safeStorage.setItem('xuri-api-base', session.apiBase)
    if (mode.value !== session.mode) await changeMode(mode.value)
    await router.push(session.me ? '/' : '/login')
  }, '运行模式已更新')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">WORKSPACE SETTINGS</p>
      <h1>运行与账户设置</h1>
      <p>清楚区分本地体验与正式服务，按你的环境开始。</p>
    </div>
  </div>
  <div class="cards-2">
    <section class="panel">
      <header class="panel-head">
        <h2>运行模式</h2>
        <Icon name="settings" :size="20" />
      </header>
      <form @submit.prevent="apply">
        <label class="mode-option"
          ><input type="radio" v-model="mode" value="demo" />
          <div>
            <h3>演示模式</h3>
            <p>无需数据库，体验不同身份和完整操作流程。示例数据保存在本浏览器，不上传到公司。</p>
          </div>
          <span class="tag">离线可用</span></label
        ><label class="mode-option"
          ><input type="radio" v-model="mode" value="api" :disabled="file" />
          <div>
            <h3>后端模式</h3>
            <p>连接SSM服务，使用真实员工、学习记录和服务端权限。请求失败不会自动切换成模拟数据。</p>
          </div></label
        >
        <div v-if="file" class="notice gold">
          双击离线文件仅支持演示模式；接入后端请通过HTTP服务运行前端。
        </div>
        <label class="field section-spacer"
          ><span>同域 API 路径</span><input v-model="base" placeholder="/api" /><small
            >Vite开发代理使用 /api；同Tomcat部署可使用
            /training/api。前端不保存数据库密码和模型密钥。</small
          ></label
        ><button class="btn section-spacer" :disabled="ui.busy > 0">应用设置</button>
      </form>
    </section>
    <section class="panel">
      <header class="panel-head">
        <h2>{{ session.mode === 'demo' ? '演示数据管理' : '账户安全' }}</h2>
        <Icon :name="session.mode === 'demo' ? 'refresh' : 'shield'" />
      </header>
      <template v-if="session.mode === 'demo'"
        ><p class="prose">
          演示中可以观看短视频、参加考试、创作课程、切换管理员审核，以及处理预警。操作会保留在当前浏览器。<br /><br />演示账号与权限切换只用于体验，正式模式以服务端返回的角色为准。
        </p>
        <button class="btn white section-spacer" @click="reset = true">
          <Icon name="refresh" :size="16" />重置本地演示数据
        </button></template
      >
      <form
        v-else
        @submit.prevent="
          perform(async () => {
            await api('/auth/password', 'PUT', { oldPassword: oldPassword, newPassword })
            await logout()
            router.push('/login')
          }, '密码已修改，请重新登录')
        "
      >
        <label class="field"
          ><span>原密码</span
          ><input
            v-model="oldPassword"
            type="password"
            required
            autocomplete="current-password" /></label
        ><label class="field section-spacer"
          ><span>新密码</span
          ><input
            v-model="newPassword"
            type="password"
            required
            minlength="12"
            maxlength="72"
            autocomplete="new-password"
          /><small>至少12字符，UTF-8编码不超过72字节。</small></label
        ><button class="btn section-spacer">修改密码并重新登录</button>
      </form>
    </section>
  </div>
  <section class="panel section-spacer">
    <h2>在你的电脑上运行</h2>
    <p class="prose section-spacer">
      离线体验：双击“旭日学堂-离线演示.html”，无需Node.js、Maven或数据库。<br />源码开发：在
      frontend 目录运行 npm ci，再运行 npm run dev，打开终端中的本地地址。<br />正式联调：启动Tomcat和SQL
      Server后，在本页切换后端模式；开发代理默认连接 localhost:8080/training/api。
    </p>
    <p class="caption section-spacer">
      版本 1.0 · Vue 3 / SSM / SQL Server · 主题 #003f98 + #ffcb0b
    </p>
  </section>
  <Modal v-if="reset" title="重置演示数据" @close="reset = false"
    ><p class="prose">
      此操作将清除本浏览器中的演示课程、考试、进度和处理记录，恢复初始示例。不会影响正式数据库。
    </p>
    <footer class="form-actions">
      <button class="btn white" @click="reset = false">取消</button
      ><button
        class="btn danger"
        @click="
          perform(async () => {
            demo.reset()
            reset = false
            await loadMe()
            router.push('/')
          }, '演示数据已重置')
        "
      >
        确认重置
      </button>
    </footer></Modal
  >
</template>
<style scoped>
.mode-option {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  border: 1px solid #e1e9f4;
  padding: 18px 15px;
  border-radius: 8px;
  margin-bottom: 15px;
  cursor: pointer;
}
.mode-option input {
  margin-top: 4px;
}
.mode-option > div {
  flex: 1;
}
.mode-option h3 {
  font-size: 13px;
  font-weight: 500;
}
.mode-option p {
  font-size: 11px;
  line-height: 1.9;
  color: #97a6ba;
  margin-top: 7px;
}
.mode-option:has(input:checked) {
  border-color: #93b2da;
  background: #f7faff;
}
.mode-option .tag {
  font-size: 8px;
}
</style>
