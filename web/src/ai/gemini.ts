// Gemini из браузера: тот же запрос, схема ответа и пост-обработка, что в
// Android (GeminiApi.kt + MealSanity.kt). Ключ хранится только на устройстве.
import { buildMealAnalysisPrompt } from './prompt'
import type { MealItem } from '../data/db'

const MODEL = 'gemini-3.1-flash-lite'
const BASE = 'https://generativelanguage.googleapis.com/v1beta/models'
import { fetchText } from './net'

export interface AnalysisResult {
  name: string
  calories: number
  protein: number
  fat: number
  carbs: number
  aiThoughts: string
  reasoning: string
  confidenceScore: number
  healthScore: number | null
  healthNote: string | null
  items: MealItem[]
}

export const SAFETY = ['HARM_CATEGORY_HATE_SPEECH', 'HARM_CATEGORY_SEXUALLY_EXPLICIT', 'HARM_CATEGORY_DANGEROUS_CONTENT', 'HARM_CATEGORY_HARASSMENT']
  .map((category) => ({ category, threshold: 'BLOCK_NONE' }))

const S = { type: 'STRING' }, N = { type: 'NUMBER' }
const SCHEMA = {
  type: 'OBJECT',
  properties: {
    name: S, calories: N, protein: N, fat: N, carbs: N, aiThoughts: S, reasoning: S,
    confidence_score: N, health_score: N, health_note: S,
    items: {
      type: 'ARRAY',
      items: {
        type: 'OBJECT',
        properties: {
          name: S, estimated_weight_g: N, portion_basis: S, calorie_density: N,
          calories: N, protein: N, fat: N, carbs: N, breakdown: S
        },
        // Сначала чем оценён вес и расчёт, потом числа — модель считает, а не подгоняет.
        propertyOrdering: ['name', 'portion_basis', 'estimated_weight_g', 'calorie_density', 'breakdown', 'calories', 'protein', 'fat', 'carbs'],
        required: ['name', 'estimated_weight_g', 'portion_basis', 'calorie_density', 'calories', 'protein', 'fat', 'carbs', 'breakdown']
      }
    }
  },
  propertyOrdering: ['reasoning', 'items', 'name', 'calories', 'protein', 'fat', 'carbs', 'aiThoughts', 'confidence_score', 'health_score', 'health_note'],
  required: ['name', 'calories', 'protein', 'fat', 'carbs', 'aiThoughts', 'reasoning', 'confidence_score', 'items']
}

// Бесплатный тир Gemini режет запросы в минуту (RPM) и в сутки (RPD, сброс в
// полночь по Тихоокеанскому времени), квоты — на каждую модель отдельно.
// Минутный лимит — ждём retryDelay и повторяем; суточный — резервная модель.
const RESERVE_MODEL = 'gemini-flash-latest'
export const DAILY_LIMIT = 'Дневной лимит бесплатного Gemini исчерпан'
const EXHAUSTED_KEY = 'nl-gemini-exhausted'

const pacificDate = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Los_Angeles' }).format(new Date())

/** Местное время сброса суточной квоты (полночь по Тихоокеанскому). */
export function dailyResetTime(): string {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'America/Los_Angeles', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
    .formatToParts(new Date())
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value ?? 0)
  const left = 86400 - (get('hour') * 3600 + get('minute') * 60 + get('second'))
  return new Date(Date.now() + left * 1000).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
}

class DailyQuotaError extends Error {}

const USAGE_KEY = 'nl-gemini-usage'

/** Сколько запросов к Gemini ушло за текущие «сутки Google» и упёрлись ли в лимит. */
export function usageToday(): { count: number; exhausted: boolean } {
  try {
    const u = JSON.parse(localStorage.getItem(USAGE_KEY) ?? '{}')
    return { count: u.date === pacificDate() ? u.count ?? 0 : 0, exhausted: localStorage.getItem(EXHAUSTED_KEY) === pacificDate() }
  } catch {
    return { count: 0, exhausted: false }
  }
}

function countRequest() {
  try {
    const today = pacificDate()
    const u = JSON.parse(localStorage.getItem(USAGE_KEY) ?? '{}')
    localStorage.setItem(USAGE_KEY, JSON.stringify({ date: today, count: (u.date === today ? u.count ?? 0 : 0) + 1 }))
  } catch { /* приватный режим */ }
}

const sleep = (ms: number, signal?: AbortSignal) => new Promise<void>((res, rej) => {
  const t = setTimeout(res, ms)
  signal?.addEventListener('abort', () => { clearTimeout(t); rej(new Error('Отменено')) })
})

function friendly(status: number, text: string): string {
  if (/safety|blocked/i.test(text)) return 'Запрос заблокирован фильтрами безопасности Gemini'
  if (status === 400 && /API_KEY_INVALID|API key/i.test(text)) return 'Ключ Gemini не подходит — проверь его в Профиле'
  if (status === 400 && /location/i.test(text)) return 'Gemini недоступен в твоём регионе без VPN'
  if (status === 403) return 'Ключ Gemini не подходит или API не включён'
  if (status === 404) return 'Модель Gemini не найдена'
  if (status === 500 || status === 503) return 'Серверы Gemini перегружены — попробуй чуть позже'
  return `Gemini ${status}: ${text.slice(0, 200)}`
}

async function postModel(model: string, apiKey: string, body: unknown, signal?: AbortSignal): Promise<string> {
  let minuteRetries = 0, serverRetries = 0
  for (;;) {
    const resp = await fetchText(`${BASE}/${model}:generateContent`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey.trim() },
      body: JSON.stringify(body),
      signal
    }, 150_000, 'Gemini')
    const text = resp.text
    if (resp.ok) { countRequest(); return text }
    if (resp.status === 429) {
      const perDay = /PerDay/i.test(text) && !/PerMinute/i.test(text)
      if (perDay) throw new DailyQuotaError(`${DAILY_LIMIT} — обновится в ${dailyResetTime()}`)
      const wait = Number(/"retryDelay"\s*:\s*"(\d+)/.exec(text)?.[1] ?? 20)
      if (minuteRetries < 2 && wait <= 45) { minuteRetries++; await sleep(wait * 1000 + 500, signal); continue }
      throw new Error('Бесплатный Gemini: слишком много запросов подряд — подожди минуту')
    }
    if ((resp.status === 500 || resp.status === 503) && serverRetries < 1) { serverRetries++; await sleep(2500, signal); continue }
    throw new Error(friendly(resp.status, text))
  }
}

/** Общий вызов generateContent с учётом лимитов бесплатного тира (рецепты, анализ, чат). */
export async function post(apiKey: string, body: unknown, signal?: AbortSignal): Promise<string> {
  if (!apiKey.trim()) throw new Error('Укажи ключ Gemini в Профиле → Нейросеть')
  const today = pacificDate()
  let exhausted = false
  try { exhausted = localStorage.getItem(EXHAUSTED_KEY) === today } catch { /* приватный режим */ }
  const models = exhausted ? [RESERVE_MODEL] : [MODEL, RESERVE_MODEL]
  let dailyErr: Error | null = null
  let text = ''
  for (const model of models) {
    try {
      text = await postModel(model, apiKey, body, signal)
      dailyErr = null
      break
    } catch (e) {
      if (e instanceof DailyQuotaError) {
        if (model === MODEL) { try { localStorage.setItem(EXHAUSTED_KEY, today) } catch { /* ignore */ } }
        dailyErr = e
        continue
      }
      throw dailyErr ?? e
    }
  }
  if (dailyErr || !text) throw dailyErr ?? new Error('Пустой ответ модели')
  const json = JSON.parse(text)
  const out = (json.candidates?.[0]?.content?.parts ?? [])
    .map((p: { text?: string }) => p.text ?? '')
    .join('')
    .trim()
  if (!out) throw new Error('Пустой ответ модели')
  return out
}

export const stripFence = (s: string) => s.replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/, '').trim()
const num = (v: unknown) => {
  const n = typeof v === 'number' ? v : parseFloat(String(v ?? ''))
  return Number.isFinite(n) && n > 0 ? n : 0
}
const round1 = (v: number) => Math.round(v * 10) / 10
const atwater = (p: number, f: number, c: number) => p * 4 + f * 9 + c * 4

/** Чистка продукта: отрицательные/пустые → 0, калории из БЖУ или «вес × плотность». */
function sanitizeItem(raw: Record<string, unknown>): MealItem {
  let weight = num(raw.estimated_weight_g)
  const density = num(raw.calorie_density)
  const protein = num(raw.protein), fat = num(raw.fat), carbs = num(raw.carbs)
  let calories = num(raw.calories)
  const fromMacros = atwater(protein, fat, carbs)
  if (calories <= 0 && fromMacros > 0) calories = fromMacros
  if (calories <= 0 && weight > 0 && density > 0) calories = (weight * density) / 100
  if (weight <= 0 && calories > 0 && density > 0) weight = (calories / density) * 100
  return {
    name: String(raw.name ?? '').trim(),
    weightG: Math.round(weight),
    calorieDensity: density,
    calories: Math.round(calories),
    protein: round1(protein), fat: round1(fat), carbs: round1(carbs),
    breakdown: String(raw.breakdown ?? ''),
    portionBasis: String(raw.portion_basis ?? '')
  }
}

/** Итоги сверяются с суммой продуктов (как fixMealDrift + finalizeMeal в Android). */
export function finalize(raw: Record<string, unknown>): AnalysisResult {
  const items = ((raw.items as Record<string, unknown>[]) ?? []).map(sanitizeItem).filter((i) => i.name || i.calories > 0)
  let calories = num(raw.calories), protein = num(raw.protein), fat = num(raw.fat), carbs = num(raw.carbs)
  if (items.length) {
    const sum = items.reduce((a, i) => ({ c: a.c + i.calories, p: a.p + i.protein, f: a.f + i.fat, k: a.k + i.carbs }), { c: 0, p: 0, f: 0, k: 0 })
    if (calories <= 0) calories = sum.c
    if (protein <= 0) protein = sum.p
    if (fat <= 0) fat = sum.f
    if (carbs <= 0) carbs = sum.k
    if (Math.abs(sum.c - calories) > Math.max(30, calories * 0.1)) {
      calories = sum.c; protein = sum.p; fat = sum.f; carbs = sum.k
    }
  }
  if (calories <= 0) calories = atwater(protein, fat, carbs)
  const name = String(raw.name ?? '').trim() || items.map((i) => i.name).filter(Boolean).join(', ') || 'Приём пищи'
  const fromMacros = atwater(protein, fat, carbs)
  const mismatch = calories > 0 && fromMacros > 0 ? Math.abs(calories - fromMacros) / calories : 0
  let confidence = num(raw.confidence_score)
  if (confidence > 0) confidence = mismatch > 0.35 ? Math.min(5, Math.max(1, confidence)) : Math.min(10, Math.max(1, confidence))
  const hs = raw.health_score == null ? null : Math.min(100, Math.max(0, num(raw.health_score)))
  return {
    name,
    calories: Math.round(calories),
    protein: round1(protein), fat: round1(fat), carbs: round1(carbs),
    aiThoughts: String(raw.aiThoughts ?? ''),
    reasoning: String(raw.reasoning ?? ''),
    confidenceScore: confidence,
    healthScore: hs,
    healthNote: raw.health_note ? String(raw.health_note) : null,
    items
  }
}

export async function analyzeMealGemini(opts: {
  apiKey: string
  images: string[] // base64 JPEG без префикса
  userContext: string
  note: string
  recentMealsContext: string
  currentResultContext?: string
  signal?: AbortSignal
}): Promise<AnalysisResult> {
  const prompt = buildMealAnalysisPrompt(opts.userContext, opts.note, opts.recentMealsContext, opts.images.length, opts.currentResultContext ?? '')
  const body = {
    contents: [{ parts: [{ text: prompt }, ...opts.images.map((data) => ({ inline_data: { mime_type: 'image/jpeg', data } }))] }],
    generationConfig: { responseMimeType: 'application/json', responseSchema: SCHEMA },
    safetySettings: SAFETY
  }
  const text = await post(opts.apiKey, body, opts.signal)
  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(stripFence(text))
  } catch {
    throw new Error('ИИ вернул непонятный ответ — попробуй ещё раз')
  }
  return finalize(parsed)
}

export async function chatGemini(opts: {
  apiKey: string
  system: string
  history: { role: 'user' | 'model'; text: string }[]
  signal?: AbortSignal
}): Promise<string> {
  const body = {
    system_instruction: { parts: [{ text: opts.system }] },
    contents: opts.history.map((m) => ({ role: m.role, parts: [{ text: m.text }] })),
    safetySettings: SAFETY
  }
  return post(opts.apiKey, body, opts.signal)
}

/** Быстрая проверка ключа: список моделей. */
export async function checkKey(apiKey: string): Promise<boolean> {
  try {
    const r = await fetchText(`${BASE}?pageSize=1`, { headers: { 'x-goog-api-key': apiKey.trim() } }, 20_000, 'Gemini')
    return r.ok
  } catch {
    return false
  }
}
