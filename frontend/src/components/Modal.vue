<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import Icon from './Icon.vue'
defineProps({ title: String, wide: Boolean })
const emit = defineEmits(['close'])
const root = ref()
let previous
function key(e) {
  if (e.key === 'Escape') emit('close')
  if (e.key === 'Tab') {
    const all = [...root.value.querySelectorAll('button,input,select,textarea,a[href]')].filter(
      (x) => !x.disabled,
    )
    if (!all.length) return
    const first = all[0],
      last = all.at(-1)
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault()
      first.focus()
    }
  }
}
onMounted(() => {
  previous = document.activeElement
  document.addEventListener('keydown', key)
  root.value?.querySelector('button')?.focus()
  document.body.style.overflow = 'hidden'
})
onUnmounted(() => {
  document.removeEventListener('keydown', key)
  document.body.style.overflow = ''
  previous?.focus()
})
</script>
<template>
  <Teleport to="body"
    ><div class="modal-shade" @click.self="emit('close')">
      <section
        ref="root"
        class="modal"
        :class="{ wide }"
        role="dialog"
        aria-modal="true"
        :aria-label="title"
      >
        <header>
          <div>
            <small>旭日学堂</small>
            <h2>{{ title }}</h2>
          </div>
          <button class="icon-btn" aria-label="关闭" @click="emit('close')">
            <Icon name="x" />
          </button>
        </header>
        <div class="modal-body"><slot /></div>
      </section></div
  ></Teleport>
</template>
