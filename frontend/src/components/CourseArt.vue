<script setup>
defineProps({ tone: { default: 'safety' }, category: String })
</script>
<template>
  <div class="course-art" :class="tone">
    <span class="art-category">{{ category }}</span
    ><svg v-if="tone === 'ai'" viewBox="0 0 300 140" aria-hidden="true">
      <g fill="none" stroke="currentColor">
        <path d="M150 22 205 53v61l-55 31-55-31V53z" />
        <path d="m95 53 55 31 55-31m-55 31v61M70 32l80 46 80-46M70 120l80-45 80 45" opacity=".4" />
        <circle cx="150" cy="78" r="32" />
        <circle cx="150" cy="78" r="10" />
      </g>
      <g fill="currentColor">
        <circle cx="95" cy="53" r="5" />
        <circle cx="205" cy="114" r="5" />
        <circle cx="150" cy="22" r="5" />
      </g></svg
    ><svg v-else-if="tone === 'team'" viewBox="0 0 300 140" aria-hidden="true">
      <g fill="currentColor">
        <rect x="130" y="55" width="48" height="80" rx="5" opacity=".9" />
        <rect x="73" y="81" width="48" height="54" rx="5" opacity=".5" />
        <rect x="187" y="23" width="48" height="112" rx="5" opacity=".7" />
      </g>
      <path
        d="m77 65 76-29 63-24m-19-3 19 3-5 18"
        fill="none"
        stroke="currentColor"
        stroke-width="4"
      /></svg
    ><svg v-else-if="tone === 'craft'" viewBox="0 0 300 140" aria-hidden="true">
      <g stroke="currentColor" fill="none" stroke-width="2">
        <path d="m87 33 70-19 68 36-68 19zm0 25 70 36 68-19m-138 9 70 36 68-19" />
        <path d="M87 33v73l70 35 68-20V50m-68 19v72" />
      </g></svg
    ><svg v-else viewBox="0 0 300 140" aria-hidden="true">
      <path
        d="m155 12 57 24v43c0 29-25 48-57 61-32-13-57-32-57-61V36z"
        fill="currentColor"
        opacity=".25"
      />
      <path
        d="m155 26 43 18v34c0 22-19 38-43 49-24-11-43-27-43-49V44z"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
      />
      <path
        d="m135 75 14 14 27-30"
        fill="none"
        stroke="currentColor"
        stroke-width="5"
        stroke-linecap="round"
      /></svg
    ><span class="art-edition">XURI · LEARNING</span>
  </div>
</template>
