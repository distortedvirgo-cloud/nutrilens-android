// Оценка процента жира по фото (3.25). Грубый ориентир: независимых проверок
// точности таких оценок нет, поэтому просим диапазон, а в план он не идёт —
// план считается только по замерам лентой. Та же логика — BodyFatAi.kt.
import type { Settings } from '../data/db'
import { post, SAFETY, stripFence } from './gemini'
import { NANO_SENIOR, complete } from './nano'

export interface FatEstimate { low: number; high: number; comment: string }

export function bodyFatPrompt(c: { female: boolean | null; heightCm: number | null; weight: number | null }): string {
  const who = [
    c.female == null ? null : c.female ? 'женщина' : 'мужчина',
    c.heightCm ? `рост ${Math.round(c.heightCm)} см` : null,
    c.weight ? `вес ${Math.round(c.weight * 10) / 10} кг` : null
  ].filter(Boolean).join(', ')
  return `На фото — человек, который следит за составом тела${who ? ` (${who})` : ''}.
Оцени процент жира в теле по внешнему виду: рельеф, распределение жира, талия.
Это грубая визуальная оценка, поэтому дай диапазон шириной 3–6 процентных пунктов.
Если на фото не видно тела (лицо, одежда скрывает фигуру, нет человека) — верни low и high равными 0 и объясни в comment, какое фото нужно.
comment — 1–2 коротких предложения по-русски на «ты», без оценок внешности: на что ты смотрел и как сделать следующее фото сравнимым (тот же свет, поза, расстояние).
Ответ — только JSON: {"low": число, "high": число, "comment": "строка"}`
}

export function parseFatEstimate(text: string): FatEstimate {
  let j: Record<string, unknown>
  try { j = JSON.parse(stripFence(text)) as Record<string, unknown> } catch { throw new Error('ИИ вернул непонятный ответ — попробуй ещё раз') }
  const low = Number(j.low), high = Number(j.high)
  const comment = typeof j.comment === 'string' ? j.comment.trim().slice(0, 400) : ''
  if (!Number.isFinite(low) || !Number.isFinite(high)) throw new Error('ИИ не смог оценить фото — попробуй другое')
  if (low <= 0 || high <= 0) throw new Error(comment || 'На фото не видно фигуры — нужно фото в полный рост или по пояс')
  const lo = Math.max(3, Math.min(low, high)), hi = Math.min(60, Math.max(low, high))
  return { low: Math.round(lo * 10) / 10, high: Math.round(hi * 10) / 10, comment }
}

/** imageB64 — JPEG в base64. Ошибки — понятным текстом (нет ключа, лимит, сеть). */
export async function estimateBodyFat(s: Settings, imageB64: string, c: { female: boolean | null; heightCm: number | null; weight: number | null }): Promise<FatEstimate> {
  const prompt = bodyFatPrompt(c)
  if (s.provider === 'gemini') {
    const text = await post(s.apiKey, {
      contents: [{ parts: [{ text: prompt }, { inline_data: { mime_type: 'image/jpeg', data: imageB64 } }] }],
      generationConfig: { responseMimeType: 'application/json' },
      safetySettings: SAFETY
    })
    return parseFatEstimate(text)
  }
  if (!s.nanoApiKey.trim()) throw new Error('Нет ключа NanoGPT — добавь его в Профиле → Нейросеть')
  const text = await complete({
    apiKey: s.nanoApiKey, endpoint: s.nanoEndpoint, model: NANO_SENIOR, jsonMode: true, timeoutMs: 90_000,
    messages: [{ role: 'user', text: prompt, images: [imageB64] }]
  })
  return parseFatEstimate(text)
}
