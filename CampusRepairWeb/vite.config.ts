import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    // Windows 下编辑器/工具写入临时文件时，Vite 的文件监听会抛 EBUSY 直接把进程打挂
    // （曾导致 dev server 在演示期间崩溃），这里忽略临时目录与常见交换文件。
    watch: {
      ignored: ['**/*.tmp', '**/*.tmpdir/**', '**/.*.tmpdir/**', '**/*.swp', '**/*~'],
    },
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8999',
        changeOrigin: true,
      },
    },
  },
})
