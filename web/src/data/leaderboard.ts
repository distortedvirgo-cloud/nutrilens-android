// Общий лидерборд: тот же, что в Android-приложении. Веб ходит не в GitHub
// напрямую (репозиторий приватный, токен нельзя класть в сайт), а через прокси
// leaderboard-worker на Cloudflare. Адрес прокси приходит при сборке
// (VITE_LEADERBOARD_API); не задан — лидерборд показывает «не подключён».
//
// Игрок определяется постоянным id, а не ником: файл data/p_<id>.json, ник —
// просто поле внутри. Поэтому смена ника не создаёт второго участника.
import { getSettings, mealsBetween, saveSettings, type Settings } from './db'
import { addDays, iso, mondayOf } from './insights'
import { effectiveCalories, fatGoalOn } from './body'
import { dayGrades } from './plan'

export const LEADERBOARD_API = (import.meta.env.VITE_LEADERBOARD_API as string | undefined)?.replace(/\/+$/, '') || ''
export const leaderboardConfigured = () => !!LEADERBOARD_API

/** ps — оценка дня программы «Сжигание жира» (0–100), только у её участников. */
export interface LbDay { calories: number; goal: number; ps?: number }
export interface LbEntry { id: string; nickname: string; days: Record<string, LbDay>; updatedAt: number; avatar?: string }
export type LbPeriod = 'day' | 'week' | 'month'
export interface LbRow {
  id: string; nickname: string; score: number; percent: number
  calories: number; goal: number; daysCounted: number; isOver: boolean; isSelf: boolean; avatar: string
  /** В периоде есть дни программы: очки этих дней — оценка дня, а не попадание в калории. */
  program: boolean
}

/** Старые файлы без id (Android до 3.0), не обновлявшиеся столько дней, скрываются. */
const STALE_LEGACY_DAYS = 14
const SYNC_DAYS = 7

const hex = (bytes: Uint8Array) => [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')
const random = (n: number) => hex(crypto.getRandomValues(new Uint8Array(n)))

/** id и секрет создаются один раз и попадают в бэкап — переезд на новый телефон сохраняет место. */
export async function ensurePlayer(): Promise<Settings> {
  const s = await getSettings()
  if (/^[0-9a-f]{16}$/.test(s.playerId) && s.playerSecret.length >= 32) return s
  return saveSettings({ playerId: random(8), playerSecret: random(24) })
}

/** Публичный id — как его отдаёт прокси: первые 16 hex SHA-256("nutrilens:" + id). */
export async function publicIdOf(id: string): Promise<string> {
  if (!id) return ''
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`nutrilens:${id}`))
  return hex(new Uint8Array(buf)).slice(0, 16)
}

export async function fetchEntries(): Promise<LbEntry[]> {
  if (!LEADERBOARD_API) return []
  const r = await fetch(`${LEADERBOARD_API}/entries`, { cache: 'no-store' })
  if (!r.ok) throw new Error(`Лидерборд недоступен (${r.status})`)
  const data = (await r.json()) as { entries?: LbEntry[] }
  return dedupe(data.entries ?? [])
}

let lastPushed = ''

/** Отправляет свои последние дни. Без ника или прокси — ничего не делает. */
export async function pushOwn(): Promise<boolean> {
  if (!LEADERBOARD_API) return false
  const s = await ensurePlayer()
  const nickname = s.nickname.trim()
  if (!nickname) return false
  const today = new Date()
  const start = iso(addDays(today, -(SYNC_DAYS - 1)))
  const meals = await mealsBetween(start, iso(today))
  const days: Record<string, LbDay> = {}
  // Цель — эффективная: в режиме сжигания жира очки считаются от плана, а не от ручной цели.
  const goal = effectiveCalories(s)
  for (const m of meals) {
    const d = (days[m.date] ??= { calories: 0, goal: m.dailyGoalSnapshot || goal })
    d.calories += m.calories
  }
  // Сегодня — всегда, даже без еды, с текущей целью (как в Android).
  days[iso(today)] = { calories: days[iso(today)]?.calories ?? 0, goal }
  for (const d of Object.values(days)) d.calories = Math.round(d.calories * 10) / 10
  // Участник программы соревнуется оценкой дня (калории, белок, движение, сон), а не только
  // калориями. Пауза — обычные очки. Дни без записи активности (до 3.23) — тоже обычные.
  if (fatGoalOn(s) && s.program && !s.program.pausedSince) {
    const grades = await dayGrades(start, iso(today), s).catch(() => null)
    for (const [date, g] of grades ?? []) {
      if (g.score && !g.legacy && days[date]) days[date].ps = g.score.total
    }
  }

  const payload = JSON.stringify({ secret: s.playerSecret, nickname, days, avatar: s.avatar || '' })
  if (payload === lastPushed) return true
  const r = await fetch(`${LEADERBOARD_API}/entries/${s.playerId}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: payload
  })
  if (!r.ok) return false
  lastPushed = payload
  return true
}

let timer: ReturnType<typeof setTimeout> | undefined
/** Отложенный пуш: несколько изменений подряд — один запрос. Ошибки молча. */
export function schedulePush(delay = 4000): void {
  if (!LEADERBOARD_API) return
  clearTimeout(timer)
  timer = setTimeout(() => { pushOwn().catch(() => {}) }, delay)
}

// ---------- Подсчёт (порт LeaderboardMath из Android) ----------

const percentOf = (c: number, g: number) => (g <= 0 ? 0 : Math.round((c / g) * 100))

/** Очки дня: 100 − |процент − 100|, не ниже 0. */
export function dayScore(c: number, g: number): number | null {
  if (g <= 0) return null
  return Math.max(0, 100 - Math.abs(percentOf(c, g) - 100))
}

function periodDates(period: LbPeriod): string[] {
  const today = new Date()
  if (period === 'day') return [iso(today)]
  if (period === 'week') { const m = mondayOf(today); return Array.from({ length: 7 }, (_, i) => iso(addDays(m, i))) }
  const first = new Date(today.getFullYear(), today.getMonth(), 1)
  const len = new Date(today.getFullYear(), today.getMonth() + 1, 0).getDate()
  return Array.from({ length: len }, (_, i) => iso(addDays(first, i)))
}

export function rows(entries: LbEntry[], period: LbPeriod, selfId: string): LbRow[] {
  const dates = periodDates(period)
  return entries.map((e) => {
    const recs = dates.map((d) => e.days[d]).filter(Boolean) as LbDay[]
    const counted = recs.filter((r) => r.goal > 0)
    const calories = counted.reduce((a, r) => a + r.calories, 0)
    const goal = counted.reduce((a, r) => a + r.goal, 0)
    // День программы — её оценка дня; остальные — попадание в калории (обе шкалы 0–100).
    const scores = counted.map((r) => (r.ps != null ? r.ps : dayScore(r.calories, r.goal))).filter((x): x is number => x != null)
    return {
      id: e.id, nickname: e.nickname,
      score: scores.length ? Math.floor(scores.reduce((a, b) => a + b, 0) / scores.length) : 0,
      percent: percentOf(calories, goal), calories, goal,
      daysCounted: counted.length, isOver: calories > goal, isSelf: !!selfId && e.id === selfId, avatar: e.avatar ?? '',
      program: counted.some((r) => r.ps != null)
    }
  }).sort((a, b) => b.score - a.score || b.daysCounted - a.daysCounted || a.nickname.localeCompare(b.nickname))
}

/**
 * Без дублей: по каждому id — самая свежая запись; старый файл без id скрыт,
 * если есть игрок с id и тем же ником или если файл давно не обновлялся
 * (игрок сменил ник до появления id — так было с «Дианой» → «Дишка»).
 */
export function dedupe(entries: LbEntry[]): LbEntry[] {
  const byId = new Map<string, LbEntry>()
  for (const e of entries.filter((x) => x.id)) {
    const cur = byId.get(e.id)
    if (!cur || e.updatedAt > cur.updatedAt) byId.set(e.id, e)
  }
  const withId = [...byId.values()]
  const known = new Set(withId.map((e) => e.nickname.trim().toLowerCase()))
  const cutoff = iso(addDays(new Date(), -STALE_LEGACY_DAYS))
  const legacy = new Map<string, LbEntry>()
  for (const e of entries.filter((x) => !x.id)) {
    const name = e.nickname.trim().toLowerCase()
    if (known.has(name)) continue
    const last = Object.keys(e.days).sort().at(-1) ?? ''
    if (last < cutoff) continue
    const cur = legacy.get(name)
    if (!cur || e.updatedAt > cur.updatedAt) legacy.set(name, e)
  }
  return [...withId, ...legacy.values()]
}
