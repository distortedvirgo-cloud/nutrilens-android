// Выбор провайдера ИИ: NanoGPT (по умолчанию) или Gemini (бесплатный ключ).
// Провайдер строгий, как в Android 3.4: нет ключа выбранного сервиса — ошибка
// с подсказкой, а не тихий запрос в чужой API.
import type { Settings } from '../data/db'
import { analyzeMealGemini, chatGemini, type AnalysisResult } from './gemini'
import { analyzeMealNano, chatNano } from './nano'

const NO_KEY: Record<Settings['provider'], string> = {
  nanogpt: 'Нет ключа NanoGPT — добавь его в Профиле → Нейросеть',
  gemini: 'Нет ключа Gemini — добавь его в Профиле → Нейросеть'
}

/** Есть ли ключ выбранного сервиса — без него ИИ не работает. */
export const hasSelectedKey = (s: Settings) => !!(s.provider === 'gemini' ? s.apiKey : s.nanoApiKey).trim()

/** Выбранный сервис, если для него задан ключ. */
export function activeProvider(s: Settings): 'nanogpt' | 'gemini' | null {
  return hasSelectedKey(s) ? s.provider : null
}

export async function analyzeMeal(s: Settings, o: {
  images: string[]; userContext: string; note: string; recentMealsContext: string
  currentResultContext?: string; signal?: AbortSignal
}): Promise<AnalysisResult> {
  const p = activeProvider(s)
  if (!p) throw new Error(NO_KEY[s.provider])
  return p === 'nanogpt'
    ? analyzeMealNano({ ...o, apiKey: s.nanoApiKey, endpoint: s.nanoEndpoint })
    : analyzeMealGemini({ ...o, apiKey: s.apiKey })
}

export async function chat(s: Settings, o: {
  system: string; history: { role: 'user' | 'model'; text: string }[]; signal?: AbortSignal
}): Promise<string> {
  const p = activeProvider(s)
  if (!p) throw new Error(NO_KEY[s.provider])
  return p === 'nanogpt'
    ? chatNano({ ...o, apiKey: s.nanoApiKey, endpoint: s.nanoEndpoint })
    : chatGemini({ ...o, apiKey: s.apiKey })
}
