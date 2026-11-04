import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'

// Версия веб-версии = versionName Android-приложения (app/build.gradle.kts),
// чтобы обе платформы называли один релиз одним номером. «Что нового» —
// из web/changelog.json: запись этой версии, иначе самая свежая.
// Сборка кладёт рядом version.json: открытое приложение сверяет с ним свою
// версию и предлагает обновиться.
const gradle = readFileSync(new URL('../app/build.gradle.kts', import.meta.url), 'utf-8')
const appVersion = /versionName\s*=\s*"([^"]+)"/.exec(gradle)?.[1] ?? '0'
const changelog = JSON.parse(readFileSync(new URL('./changelog.json', import.meta.url), 'utf-8'))
const latest = { ...(changelog.find((e: { version: string }) => e.version === appVersion) ?? changelog[0]), version: appVersion }

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
  define: { __APP_VERSION__: JSON.stringify(appVersion) },
  build: {
    target: 'es2020',
    assetsInlineLimit: 0
  }
})
