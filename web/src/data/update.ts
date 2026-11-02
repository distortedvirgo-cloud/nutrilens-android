// Проверка новой версии: собранный сайт кладёт рядом version.json, открытое
// приложение сравнивает его со своей версией. Выбор пользователя запоминается:
// «позже» — тишина на сутки, «не напоминать» — молчим про эту версию.
export interface UpdateInfo { version: string; title: string; items: string[] }

const SNOOZE_KEY = 'nl-update-snooze'
const SKIP_KEY = 'nl-update-skip'
const DAY = 24 * 60 * 60 * 1000

const read = (k: string) => { try { return localStorage.getItem(k) } catch { return null } }
const write = (k: string, v: string) => { try { localStorage.setItem(k, v) } catch { /* приватный режим */ } }

export async function checkUpdate(): Promise<UpdateInfo | null> {
  if (Date.now() < Number(read(SNOOZE_KEY) ?? 0)) return null
  try {
    const r = await fetch(`./version.json?t=${Date.now()}`, { cache: 'no-store' })
    if (!r.ok) return null
    const info = (await r.json()) as UpdateInfo
    if (!info.version || info.version === __APP_VERSION__ || read(SKIP_KEY) === info.version) return null
    return info
  } catch {
    return null
  }
}

export const remindLater = () => write(SNOOZE_KEY, String(Date.now() + DAY))
export const skipVersion = (v: string) => write(SKIP_KEY, v)

/** Подтянуть новый service worker и перезагрузиться на свежую сборку. */
export async function applyUpdate(): Promise<void> {
  try {
    const reg = await navigator.serviceWorker?.getRegistration()
    await reg?.update()
  } catch { /* без SW — просто перезагрузка */ }
  location.reload()
}
