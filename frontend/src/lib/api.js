import { reactive } from 'vue'
import { createDemo } from './demo.js'
import sampleVideo from '../assets/demo-training.mp4?url'
const memory = new Map()
export const safeStorage = {
  getItem(k) {
    try {
      return localStorage.getItem(k)
    } catch {
      return memory.get(k) || null
    }
  },
  setItem(k, v) {
    try {
      localStorage.setItem(k, v)
    } catch {
      memory.set(k, v)
    }
  },
}
export const demo = createDemo(safeStorage)
const tokenStorage = {
  getItem(k) {
    try {
      return sessionStorage.getItem(k)
    } catch {
      return memory.get(k) || null
    }
  },
  setItem(k, v) {
    try {
      sessionStorage.setItem(k, v)
    } catch {
      memory.set(k, v)
    }
  },
  removeItem(k) {
    try {
      sessionStorage.removeItem(k)
    } catch {
      memory.delete(k)
    }
  },
}
export const session = reactive({
  mode: location.protocol === 'file:' ? 'demo' : safeStorage.getItem('xuri-mode') || 'demo',
  demoId: Number(safeStorage.getItem('xuri-user')) || 7,
  me: null,
  token: tokenStorage.getItem('xuri-token') || '',
  apiBase: safeStorage.getItem('xuri-api-base') || '/api',
})
export const ui = reactive({ toasts: [], busy: 0 })
export function toast(message, type = 'success') {
  const id = Date.now() + Math.random()
  ui.toasts.push({ id, message, type })
  setTimeout(() => (ui.toasts = ui.toasts.filter((t) => t.id !== id)), 4500)
}
export async function perform(fn, success) {
  ui.busy++
  try {
    const r = await fn()
    if (success) toast(success)
    return r
  } catch (e) {
    toast(e.message || '操作失败，请重试', 'error')
    return null
  } finally {
    ui.busy--
  }
}
export async function api(path, method = 'GET', body) {
  if (session.mode === 'demo')
    return demo.dispatch(path, { method, body: body || {}, userId: session.demoId })
  const headers = {}
  if (session.token) headers.Authorization = 'Bearer ' + session.token
  if (body !== undefined && !(body instanceof FormData))
    headers['Content-Type'] = 'application/json'
  let response
  try {
    response = await fetch(session.apiBase.replace(/\/$/, '') + path, {
      method,
      headers,
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
      signal: AbortSignal.timeout(70000),
    })
  } catch (e) {
    throw new Error(
      e.name === 'TimeoutError'
        ? '请求超时，请检查后端是否启动'
        : '无法连接后端，请检查服务地址和代理配置',
    )
  }
  const text = await response.text()
  let data
  try {
    data = text ? JSON.parse(text) : undefined
  } catch {
    throw new Error('后端返回了非JSON内容，请检查API地址及代理')
  }
  if (!response.ok) {
    if (response.status === 401) {
      session.me = null
      session.token = ''
      tokenStorage.removeItem('xuri-token')
    }
    const e = new Error(data?.message || `请求失败 (${response.status})`)
    e.status = response.status
    throw e
  }
  return data
}
export async function loadMe() {
  if (session.mode === 'api' && !session.token) {
    session.me = null
    return null
  }
  session.me = await api('/me')
  return session.me
}
export async function login(username, password) {
  const r = await api('/auth/login', 'POST', { username, password })
  session.token = r.token
  tokenStorage.setItem('xuri-token', r.token)
  await loadMe()
}
export async function logout() {
  try {
    if (session.mode === 'api' && session.token) await api('/auth/logout', 'POST')
  } catch {
  } finally {
    session.token = ''
    session.me = null
    tokenStorage.removeItem('xuri-token')
  }
}
export async function changeMode(mode) {
  if (location.protocol === 'file:' && mode === 'api')
    throw new Error('离线文件仅支持演示；请使用HTTP服务运行后端模式')
  await logout()
  session.mode = mode
  safeStorage.setItem('xuri-mode', mode)
  await loadMe()
}
export async function switchDemo(id) {
  session.demoId = Number(id)
  safeStorage.setItem('xuri-user', id)
  await loadMe()
}
const temporaryFiles = new Map()
export async function upload(file) {
  if (session.mode === 'demo') {
    const id = 'demo-file-' + Date.now()
    temporaryFiles.set(id, URL.createObjectURL(file))
    return { id, name: file.name, size_bytes: file.size }
  }
  const form = new FormData()
  form.append('file', file)
  return api('/files', 'POST', form)
}
export async function media(asset) {
  if (session.mode === 'demo') return temporaryFiles.get(asset) || sampleVideo
  const ticket = await api('/files/' + asset + '/ticket', 'POST')
  return session.apiBase.replace(/\/$/, '') + ticket.url.replace(/^\/api/, '')
}
export async function download(path, name) {
  if (session.mode === 'demo') throw new Error('该下载需要连接后端；演示数据无需导入。')
  const response = await fetch(session.apiBase + path, {
    headers: { Authorization: 'Bearer ' + session.token },
  })
  if (!response.ok) throw new Error('下载失败，请检查权限和后端连接')
  const blob = await response.blob(),
    url = URL.createObjectURL(blob),
    a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
