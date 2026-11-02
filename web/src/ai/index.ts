// Выбор провайдера ИИ: NanoGPT (по умолчанию) или Gemini (бесплатный ключ).
// Нет ключа основного сервиса — берём второй, если он задан.
import type { Settings } from '../data/db'
import { analyzeMealGemini, chatGemini, type AnalysisResult } from './gemini'
import { analyzeMealNano, chatNano } from './nano'

export const NO_KEY = 'Нет ключа ИИ — добавь ключ NanoGPT или Gemini в Профиле → Нейросеть'

export const hasAnyKey = (s: Settings) => !!(s.apiKey.trim() || s.nanoApiKey.trim())

/** Какой сервис реально сработает для этих настроек. */
export function activeProvider(s: Settings): 'nanogpt' | 'gemini' | null {
  const nano = !!s.nanoApiKey.trim(), gem = !!s.apiKey.trim()
  if (s.provider === 'gemini') return gem ? 'gemini' : nano ? 'nanogpt' : null
  return nano ? 'nanogpt' : gem ? 'gemini' : null
}

export async function analyzeMeal(s: Settings, o: {
  images: string[]; userContext: string; note: string; recentMealsContext: string
  currentResultContext?: string; signal?: AbortSignal
}): Promise<AnalysisResult> {
  const p = activeProvider(s)
  if (!p) throw new Error(NO_KEY)
  return p === 'nanogpt'
    ? analyzeMealNano({ ...o, apiKey: s.nanoApiKey, endpoint: s.nanoEndpoint })
    : analyzeMealGemini({ ...o, apiKey: s.apiKey })
}

export async function chat(s: Settings, o: {
  system: string; history: { role: 'user' | 'model'; text: string }[]; signal?: AbortSignal
}): Promise<string> {
  const p = activeProvider(s)
  if (!p) throw new Error(NO_KEY)
  return p === 'nanogpt'
    ? chatNano({ ...o, apiKey: s.nanoApiKey, endpoint: s.nanoEndpoint })
    : chatGemini({ ...o, apiKey: s.apiKey })
}
