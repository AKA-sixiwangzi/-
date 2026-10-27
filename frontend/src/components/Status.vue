<script setup>
import { statusText } from '../lib/rules.js'
defineProps({ value: [String, Boolean, Number] })
</script>
<template>
  <span class="status" :class="String(value).toLowerCase()">{{ statusText[value] || value }}</span>
</template>
