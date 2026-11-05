// NanoGPT из браузера: OpenAI-совместимый chat/completions, те же модели,
// промпт и маршрутизатор сложности, что в Android (NanoGptApi.kt + AnalysisCascade.kt).
import { buildMealAnalysisPrompt } from './prompt'
import { fetchText } from './net'
import { finalize, stripFence, type AnalysisResult } from './gemini'

export const NANO_SENIOR = 'google/gemini-3.8-flash'
export const NANO_JUNIOR = 'openai/gpt-6-luna'
const SENIOR_THRESHOLD = 7

/** images — base64 JPEG; videos — https-URL (YouTube через прямой Gemini-маршрут) или data:video/*;base64. */
export interface Msg { role: 'system' | 'user' | 'assistant'; text: string; images?: string[]; videos?: string[] }

const base = (endpoint: string) => (endpoint.trim() || 'https://nano-gpt.com').replace(/\/+$/, '')

export async function complete(opts: {
  apiKey: string; endpoint: string; model: string; messages: Msg[]; jsonMode: boolean
  reasoningEffort?: string; signal?: AbortSignal; timeoutMs?: number
  /** Очередь NanoGPT; null — обычная. У flex нет маршрутов для видео (503). */
  serviceTier?: string | null
}): Promise<string> {
  const msgs = opts.messages.map((m, i) => {
    const text = opts.jsonMode && i === opts.messages.length - 1
      ? `${m.text}\n\nIMPORTANT: Return ONLY valid JSON without any markdown formatting wrappers like \`\`\`json.`
      : m.text
    if (!m.images?.length && !m.videos?.length) return { role: m.role, content: text }
    return {
      role: m.role,
      content: [
        { type: 'text', text },
        ...(m.images ?? []).map((b64) => ({ type: 'image_url', image_url: { url: `data:image/jpeg;base64,${b64}` } })),
        ...(m.videos ?? []).map((url) => ({ type: 'video_url', video_url: { url, detail: 'low' } }))
      ]
    }
  })
  // Видео и flex-очередь NanoGPT могут думать долго — 3 минуты, затем понятная ошибка.
  const resp = await fetchText(`${base(opts.endpoint)}/api/v1/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${opts.apiKey.trim()}` },
    body: JSON.stringify({
      model: opts.model,
      ...(opts.serviceTier === null ? {} : { service_tier: opts.serviceTier ?? 'flex' }),
      ...(opts.reasoningEffort ? { reasoning_effort: opts.reasoningEffort } : {}),
      messages: msgs
    }),
    signal: opts.signal
  }, opts.timeoutMs ?? 180_000, 'NanoGPT')
  const raw = resp.text
  if (!resp.ok) {
    if (resp.status === 401 || resp.status === 403) throw new Error('Ключ NanoGPT не подходит — проверь его в Профиле → Нейросеть')
    if (resp.status === 402 || /balance|insufficient|credit/i.test(raw)) throw new Error('На балансе NanoGPT закончились средства — пополни на nano-gpt.com')
    if (resp.status === 429) throw new Error('NanoGPT: слишком много запросов — подожди минуту')
    const code = /"code"\s*:\s*"([a-z_]+)"/.exec(raw)?.[1]
    if (code && /video|youtube/.test(code)) throw new Error(`video:${code}`)
    throw new Error(`NanoGPT ${resp.status}: ${raw.slice(0, 200)}`)
  }
  const text = (JSON.parse(raw).choices?.[0]?.message?.content ?? '').toString()
  if (!text.trim()) throw new Error('Пустой ответ NanoGPT')
  return opts.jsonMode ? stripFence(text) : text
}

/** Младшая модель одним дешёвым вызовом оценивает сложность блюда 1–10. */
async function complexity(o: { apiKey: string; endpoint: string; images: string[]; note: string }): Promise<number | null> {
  try {
    const prompt = 'Ты маршрутизатор, а не калькулятор. Посмотри на фото приёма пищи и оцени одно: насколько сложно точно рассчитать его калории и БЖУ. ' +
      '1 — просто (одно-два очевидных продукта), 10 — очень сложно (много компонентов, составные блюда, соусы, неизвестные рецепты, упаковки без состава). ' +
      'Калории и БЖУ НЕ считай, продуктов не перечисляй. Верни ровно такой JSON: {"complexity": <целое 1-10>}' +
      (o.note ? `\n\nЗаметка пользователя: ${o.note}` : '')
    const t = await complete({ apiKey: o.apiKey, endpoint: o.endpoint, model: NANO_JUNIOR, jsonMode: true, timeoutMs: 40_000, messages: [{ role: 'user', text: prompt, images: o.images }] })
    const n = Number(JSON.parse(t).complexity)
    return Number.isFinite(n) ? Math.min(10, Math.max(1, Math.round(n))) : null
  } catch {
    return null
  }
}

async function analyzeWith(model: string, o: {
  apiKey: string; endpoint: string; images: string[]; prompt: string; signal?: AbortSignal
}): Promise<AnalysisResult> {
  const text = await complete({
    apiKey: o.apiKey, endpoint: o.endpoint, model, jsonMode: true, reasoningEffort: 'low', signal: o.signal,
    messages: [{ role: 'user', text: o.prompt, images: o.images }]
  })
  let parsed: Record<string, unknown>
  try { parsed = JSON.parse(text) } catch { throw new Error('NanoGPT вернул непонятный ответ — попробуй ещё раз') }
  return finalize(parsed)
}

const usable = (r: AnalysisResult) => r.items.length > 0 || r.calories > 0

export async function analyzeMealNano(opts: {
  apiKey: string; endpoint: string; images: string[]; userContext: string; note: string
  recentMealsContext: string; currentResultContext?: string; signal?: AbortSignal
}): Promise<AnalysisResult> {
  const prompt = buildMealAnalysisPrompt(opts.userContext, opts.note, opts.recentMealsContext, opts.images.length, opts.currentResultContext ?? '')
  const o = { apiKey: opts.apiKey, endpoint: opts.endpoint, images: opts.images, prompt, signal: opts.signal }
  // Без фото оценивать сложность «на глаз» нечего — сразу младшая модель, без лишнего запроса.
  const score = opts.images.length ? await complexity({ apiKey: opts.apiKey, endpoint: opts.endpoint, images: opts.images, note: opts.note }) : 1
  if (score != null && score >= SENIOR_THRESHOLD) return analyzeWith(NANO_SENIOR, o)
  try {
    const junior = await analyzeWith(NANO_JUNIOR, o)
    return usable(junior) || junior.aiThoughts ? junior : analyzeWith(NANO_SENIOR, o)
  } catch (e) {
    // Ключ/баланс не починить сменой модели — показываем причину сразу.
    if (/Ключ|баланс|интернет/.test((e as Error).message)) throw e
    return analyzeWith(NANO_SENIOR, o)
  }
}

export async function chatNano(opts: {
  apiKey: string; endpoint: string; system: string
  history: { role: 'user' | 'model'; text: string }[]; signal?: AbortSignal
}): Promise<string> {
  const messages: Msg[] = [
    { role: 'system', text: opts.system },
    ...opts.history.map((m) => ({ role: (m.role === 'model' ? 'assistant' : 'user') as Msg['role'], text: m.text }))
  ]
  const run = (model: string) => complete({ apiKey: opts.apiKey, endpoint: opts.endpoint, model, messages, jsonMode: false, signal: opts.signal })
  try { return await run(NANO_JUNIOR) } catch (e) {
    if (/Ключ|баланс|интернет/.test((e as Error).message)) throw e
    return run(NANO_SENIOR)
  }
}

/** Проверка ключа: самый дешёвый запрос — одно слово. */
export async function checkNanoKey(apiKey: string, endpoint: string): Promise<true | string> {
  try {
    await complete({ apiKey, endpoint, model: NANO_JUNIOR, jsonMode: false, reasoningEffort: 'low', timeoutMs: 45_000, messages: [{ role: 'user', text: 'Ответь одним словом: ок' }] })
    return true
  } catch (e) {
    return (e as Error).message
  }
}
