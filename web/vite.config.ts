import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// base './' — приложение живёт в подпапке GitHub Pages (/nutrilens-android/),
// относительные пути работают и там, и локально.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    target: 'es2020',
    assetsInlineLimit: 0
  }
})
