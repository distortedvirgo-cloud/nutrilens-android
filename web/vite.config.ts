import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'

// Версия и «что нового» — из changelog.json (новые записи сверху). Сборка кладёт
// рядом version.json: открытое приложение сверяет с ним свою версию и предлагает обновиться.
const changelog = JSON.parse(readFileSync(new URL('./changelog.json', import.meta.url), 'utf-8'))
const latest = changelog[0]

const versionFile = (): Plugin => ({
  name: 'nutrilens-version',
  generateBundle() {
    this.emitFile({ type: 'asset', fileName: 'version.json', source: JSON.stringify(latest) })
  }
})

// base './' — приложение живёт в подпапке GitHub Pages (/nutrilens-android/),
// относительные пути работают и там, и локально.
export default defineConfig({
  base: './',
  plugins: [react(), versionFile()],
  define: { __APP_VERSION__: JSON.stringify(latest.version) },
  build: {
    target: 'es2020',
    assetsInlineLimit: 0
  }
})
