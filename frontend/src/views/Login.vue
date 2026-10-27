<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { session, login, changeMode, perform, ui } from '../lib/api.js'
import Icon from '../components/Icon.vue'
const username = ref(''),
  password = ref(''),
  router = useRouter()
async function submit() {
  await perform(async () => {
    await login(username.value, password.value)
    password.value = ''
    await router.push('/')
  })
}
</script>
<template>
  <div class="login-layout">
    <section class="login-brand">
      <div>
        <span class="hero-tag">XURI ACADEMY</span>
        <h1>让每一份努力，<br />都有成长的回响。</h1>
        <p>围绕岗位，系统学习。<br />从知识到行动，让个人与企业共同向前。</p>
        <div class="btn-row section-spacer">
          <span class="hero-tag">岗位成长路线</span><span class="hero-tag">学习 · 考核 · 成果</span>
        </div>
      </div>
    </section>
    <section class="login-form">
      <div>
        <p class="eyebrow">WELCOME BACK</p>
        <h2>欢迎回到旭日学堂</h2>
        <p>使用公司培训账号登录，开启今天的成长。</p>
        <form @submit.prevent="submit">
          <label class="field"
            ><span>账号</span
            ><input
              v-model="username"
              required
              autocomplete="username"
              placeholder="请输入员工账号"
              maxlength="80" /></label
          ><label class="field"
            ><span>密码</span
            ><input
              v-model="password"
              type="password"
              required
              autocomplete="current-password"
              placeholder="请输入密码"
              maxlength="72" /></label
          ><button class="btn full" :disabled="ui.busy > 0">
            登录学堂<Icon name="arrow" :size="17" />
          </button>
        </form>
        <div class="notice section-spacer">
          当前连接：{{ session.apiBase }}<br />本版不内置正式账号密码，请使用后端已创建的员工账号。
        </div>
        <button
          class="btn white full"
          :disabled="ui.busy > 0"
          @click="
            perform(async () => {
              await changeMode('demo')
              router.push('/')
            })
          "
        >
          无需数据库，进入演示模式
        </button>
      </div>
    </section>
  </div>
</template>
