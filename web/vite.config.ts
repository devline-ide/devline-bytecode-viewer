import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)), plugins: [vue()],
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  resolve: { dedupe: ['vue'] },
  build: { target: 'es2022', outDir: '../build/panel', emptyOutDir: true, cssCodeSplit: false,
    lib: { entry: fileURLToPath(new URL('./main.ts', import.meta.url)), name: 'DevLineBytecode', formats: ['iife'], fileName: () => 'bytecode.js', cssFileName: 'bytecode' } },
})
