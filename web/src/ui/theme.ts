// Тема режима: «Сжигание жира» перекрашивает акцент в оранжевый. Палитра —
// в styles.css под :root[data-mode="fat"]; здесь только переключатель атрибута
// и цвета строки состояния (theme-color), которые CSS-переменные не достают.

const KEY = 'nl-mode'
/** Фон приложения по режиму и схеме — совпадает с --bg в styles.css. */
const BAR: Record<'normal' | 'fat', { light: string; dark: string }> = {
  normal: { light: '#F5F6F2', dark: '#090D0B' },
  fat: { light: '#F7F4F0', dark: '#0E0B09' }
}

export function applyMode(fat: boolean): void {
  const root = document.documentElement
  if (fat) root.dataset.mode = 'fat'
  else delete root.dataset.mode
  const c = BAR[fat ? 'fat' : 'normal']
  document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]').forEach((m) => {
    m.content = (m.media ?? '').includes('dark') ? c.dark : c.light
  })
  // Запоминаем, чтобы следующий запуск сразу открылся в нужном цвете, без
  // зелёной вспышки до чтения IndexedDB. Хранилище может быть недоступно — не страшно.
  try { localStorage.setItem(KEY, fat ? 'fat' : 'normal') } catch { /* приватный режим */ }
}

/** Режим из прошлого запуска — до загрузки настроек. */
export function applyCachedMode(): void {
  let fat = false
  try { fat = localStorage.getItem(KEY) === 'fat' } catch { /* нет доступа */ }
  applyMode(fat)
}
