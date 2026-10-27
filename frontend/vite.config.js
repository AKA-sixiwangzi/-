import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()],
  base: './',
  build: { assetsInlineLimit: 1000000 },
  server: {
    proxy: {
      '/api': {
        target: process.env.TRAINING_API_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true,
        rewrite: (path) => '/training' + path,
      },
    },
  },
})
