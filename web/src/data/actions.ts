// Действия приложения: фото, очередь ИИ-анализа, чат, бэкапы.
import {
  type Job, type Meal, addChat, chatHistory, db, deleteJob, getMeal, getPhoto, getSettings,
  jobs, latestWeight, mealsBetween, mealsOn, notifyChanged, putJob, putMeal, putPhoto, recentMeals, uid, type Recipe } from './db'
import { analyzeMeal, chat } from '../ai'
import { addDays, dayTotals, iso, macroGoals, nowTime } from './insights'

// ---------- Фото: сжатие перед ИИ и хранением ----------

async function decode(file: Blob): Promise<ImageBitmap | HTMLImageElement> {
  try {
    return await createImageBitmap(file, { imageOrientation: 'from-image' } as ImageBitmapOptions)
  } catch {
    const url = URL.createObjectURL(file)
    try {
      const img = new Image()
      img.src = url
      await img.decode()
      return img
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
  }
}

async function toJpeg(src: ImageBitmap | HTMLImageElement, maxSide: number, quality: number): Promise<Blob> {
  const w = 'naturalWidth' in src ? src.naturalWidth : src.width
  const h = 'naturalHeight' in src ? src.naturalHeight : src.height
  const k = Math.min(1, maxSide / Math.max(w, h))
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(w * k)
  canvas.height = Math.round(h * k)
  canvas.getContext('2d')!.drawImage(src, 0, 0, canvas.width, canvas.height)
  return new Promise((res, rej) => canvas.toBlob((b) => (b ? res(b) : rej(new Error('Не удалось сжать фото'))), 'image/jpeg', quality))
}

/** Сохраняет фото: 1280 px для ИИ и просмотра, 360 px — миниатюра. */
export async function storePhoto(file: Blob): Promise<string> {
  const img = await decode(file)
  const full = await toJpeg(img, 1280, 0.85)
  const thumb = await toJpeg(img, 360, 0.8)
  const id = uid()
  await putPhoto(id, full, thumb)
  return id
}

export async function blobToBase64(b: Blob): Promise<string> {
  const buf = new Uint8Array(await b.arrayBuffer())
  let s = ''
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000))
  return btoa(s)
}

// ---------- Очередь анализа ----------

function recentContext(meals: Meal[]): string {
  return meals
    .map((m) => `- [${m.date} ${m.time}]: ${m.name} (${Math.round(m.calories)} ккал, Б:${Math.round(m.protein)} Ж:${Math.round(m.fat)} У:${Math.round(m.carbs)})`)
    .join('\n')
}

let running = false

/**
 * Обрабатывает задачи по одной. Задачи лежат в IndexedDB, поэтому если
 * приложение закрыли посреди анализа, при следующем открытии он продолжится.
 */
export async function runQueue(onDone?: (meal: Meal) => void): Promise<void> {
  if (running) return
  running = true
  try {
    for (;;) {
      const next = (await jobs()).find((j) => j.status === 'QUEUED' || j.status === 'RUNNING')
      if (!next) break
      await putJob({ ...next, status: 'RUNNING', error: null })
      try {
        const meal = await processJob(next)
        await deleteJob(next.id)
        onDone?.(meal)
      } catch (e) {
        await putJob({ ...next, status: 'FAILED', error: (e as Error).message })
      }
    }
  } finally {
    running = false
  }
}

async function processJob(job: Job): Promise<Meal> {
  const settings = await getSettings()
  const images: string[] = []
  for (const id of job.photoIds) {
    const p = await getPhoto(id)
    if (p) images.push(await blobToBase64(p.full))
  }
  const recent = recentContext(await recentMeals(10))
  const existing = job.refineMealId ? await getMeal(job.refineMealId) : undefined
  const currentResultContext = existing
    ? `${existing.name}: ${existing.calories} ккал, Б ${existing.protein} Ж ${existing.fat} У ${existing.carbs}. Продукты: ` +
      existing.items.map((i) => `${i.name} ${i.weightG} г ${i.calories} ккал`).join('; ')
    : ''
  const r = await analyzeMeal(settings, {
    images,
    userContext: settings.userContext || 'не указано',
    note: job.note,
    recentMealsContext: recent,
    currentResultContext
  })
  const meal: Meal = {
    id: existing?.id ?? uid(),
    date: existing?.date ?? iso(new Date()),
    time: existing?.time ?? nowTime(),
    name: r.name,
    calories: r.calories,
    protein: r.protein,
    fat: r.fat,
    carbs: r.carbs,
    aiThoughts: r.aiThoughts,
    reasoning: r.reasoning,
    confidenceScore: r.confidenceScore,
    healthScore: r.healthScore,
    healthNote: r.healthNote,
    dailyGoalSnapshot: settings.dailyGoal,
    createdAt: existing?.createdAt ?? Date.now(),
    items: r.items,
    photoIds: existing?.photoIds ?? job.photoIds
  }
  await putMeal(meal)
  return meal
}

export async function enqueueAnalysis(note: string, photoIds: string[], refineMealId?: string): Promise<void> {
  await putJob({ id: uid(), status: 'QUEUED', note, photoIds, createdAt: Date.now(), error: null, refineMealId })
}

export async function retryJob(job: Job, newNote?: string): Promise<void> {
  await putJob({ ...job, status: 'QUEUED', error: null, note: newNote ?? job.note })
}

// ---------- Чат с диетологом ----------

export async function sendChat(text: string): Promise<void> {
  const settings = await getSettings()
  await addChat({ id: uid(), role: 'user', text, createdAt: Date.now() })
  const today = iso(new Date())
  const meals = await mealsOn(today)
  const t = dayTotals(meals)
  const w = (await latestWeight())?.weight ?? null
  const g = macroGoals(settings.proteinGoal, settings.fatGoal, settings.carbsGoal, settings.dailyGoal, w)
  const remaining = Math.round(settings.dailyGoal) - Math.round(t.calories)
  const week = await mealsBetween(iso(addDays(new Date(), -6)), iso(addDays(new Date(), -1)))
  const byDay = new Map<string, number>()
  week.forEach((m) => byDay.set(m.date, (byDay.get(m.date) ?? 0) + m.calories))
  const recentDays = [...byDay.entries()].sort().map(([d, k]) => `${d}: ${Math.round(k)} ккал`).join('\n')
  const system = [
    'Ты ИИ-диетолог в приложении NutriLens: дружелюбный, спокойный, конкретный. Видишь дневник пользователя.',
    '',
    `СЕЙЧАС: ${today} ${nowTime()}.`,
    `ПОЛЬЗОВАТЕЛЬ: ${settings.userContext || 'о себе ничего не написал'}${w != null ? `. Вес ${w.toFixed(1)} кг` : ''}.`,
    `ЦЕЛЬ: ${Math.round(settings.dailyGoal)} ккал/день. Сегодня съедено ${Math.round(t.calories)} ккал, ${remaining >= 0 ? `осталось ${remaining}` : `перебор ${-remaining}`} ккал. ` +
      `БЖУ сегодня: Б ${Math.round(t.protein)}/${g.protein} г, Ж ${Math.round(t.fat)}/${g.fat} г, У ${Math.round(t.carbs)}/${g.carbs} г (съедено/цель).`,
    `СЕГОДНЯ В ДНЕВНИКЕ: ${meals.map((m) => `${m.name} — ${Math.round(m.calories)} ккал`).join('; ') || 'пока ничего'}.`,
    recentDays ? `ПОСЛЕДНИЕ ДНИ:\n${recentDays}` : '',
    '',
    'Как отвечать:',
    '- Опирайся на цифры выше: остаток калорий, недобор или перебор по БЖУ, время дня. Не проси данные, которые уже есть.',
    '- Совет — конкретный: продукт, примерная порция в граммах и её ккал/белок. Без общих слов.',
    '- Коротко: 2–6 предложений или короткий список. Markdown можно, таблицы — нет.',
    '- Не осуждай выбор еды. Не ставь диагнозов и не назначай лечение; при признаках расстройства пищевого поведения мягко посоветуй специалиста.'
  ].join('\n')
  const history = (await chatHistory()).slice(-20).map((m) => ({ role: m.role, text: m.text }))
  const reply = await chat(settings, { system, history })
  await addChat({ id: uid(), role: 'model', text: reply, createdAt: Date.now() })
}

// ---------- Бэкап: формат совместим с Android NutriLens ----------

export async function exportBackup(): Promise<Blob> {
  const d = await db()
  const s = await getSettings()
  const meals = await d.getAll('meals')
  const data = {
    settings: {
      id: 1, apiKey: s.apiKey, nanoApiKey: s.nanoApiKey, nanoApiEndpoint: s.nanoEndpoint,
      apiMode: s.provider === 'gemini' ? 'free' : 'simple', dailyGoal: s.dailyGoal, proteinGoal: s.proteinGoal, fatGoal: s.fatGoal,
      carbsGoal: s.carbsGoal, userContext: s.userContext, leaderboardNickname: s.nickname
    },
    meals: meals.map(({ items: _i, photoIds: _p, ...m }) => m),
    meal_items: meals.flatMap((m) => m.items.map((i) => ({ ...i, mealId: m.id }))),
    favorites: await d.getAll('favorites'),
    weights: await d.getAll('weights'),
    water: await d.getAll('water'),
    workouts: await d.getAll('workouts'),
    habits: (await d.getAll('habits')).map((h) => ({ date: h.date, habitId: h.habitId })),
    chat_history: (await d.getAll('chat')).map((c) => ({ role: c.role, text: c.text })),
    // Рецепты — тем же форматом читает и Android-приложение. Обложки-фото не
    // переносятся (фото живут отдельно), ссылки-превью остаются.
    recipes: (await d.getAll('recipes')).map((r) => ({ ...r, cover: r.cover?.startsWith('photo:') ? null : r.cover })),
    web_player: s.playerId ? { id: s.playerId, secret: s.playerSecret } : null,
    source: 'nutrilens-web'
  }
  return new Blob([JSON.stringify(data)], { type: 'application/json' })
}

/** Импорт бэкапа из Android или веба. Заменяет данные на устройстве. */
export async function importBackup(file: Blob): Promise<number> {
  let root: Record<string, unknown>
  try {
    root = JSON.parse(await file.text())
  } catch {
    throw new Error('Это не файл бэкапа NutriLens')
  }
  if (!Array.isArray(root.meals)) throw new Error('В файле нет дневника NutriLens')
  const d = await db()
  const items = new Map<string, Meal['items']>()
  for (const raw of (root.meal_items as Record<string, unknown>[] | undefined) ?? []) {
    const id = String(raw.mealId ?? '')
    const list = items.get(id) ?? []
    list.push({
      name: String(raw.name ?? ''), weightG: Number(raw.weightG ?? 0), calories: Number(raw.calories ?? 0),
      protein: Number(raw.protein ?? 0), fat: Number(raw.fat ?? 0), carbs: Number(raw.carbs ?? 0),
      breakdown: String(raw.breakdown ?? '')
    })
    items.set(id, list)
  }
  // Рецепты добавляются к книге (а не заменяют её): по id совпадающие обновятся.
  if (Array.isArray(root.recipes)) {
    for (const r of root.recipes as Recipe[]) if (r && r.id && r.title && Array.isArray(r.ingredients)) await d.put('recipes', r)
  }
  const tx = d.transaction(['meals', 'favorites', 'weights', 'water', 'workouts', 'habits', 'settings', 'chat'], 'readwrite')
  await Promise.all(['meals', 'favorites', 'weights', 'water', 'workouts', 'habits', 'chat'].map((s) => tx.objectStore(s as 'meals').clear()))
  const meals = root.meals as Record<string, unknown>[]
  for (const m of meals) {
    const id = String(m.id ?? uid())
    await tx.objectStore('meals').put({
      id,
      date: String(m.date), time: String(m.time ?? '12:00'), name: String(m.name ?? 'Приём пищи'),
      calories: Number(m.calories ?? 0), protein: Number(m.protein ?? 0), fat: Number(m.fat ?? 0), carbs: Number(m.carbs ?? 0),
      aiThoughts: String(m.aiThoughts ?? ''), reasoning: String(m.reasoning ?? ''),
      confidenceScore: Number(m.confidenceScore ?? 0),
      healthScore: m.healthScore == null ? null : Number(m.healthScore),
      healthNote: m.healthNote == null ? null : String(m.healthNote),
      dailyGoalSnapshot: m.dailyGoalSnapshot == null ? null : Number(m.dailyGoalSnapshot),
      createdAt: Number(m.createdAt ?? Date.now()),
      items: items.get(id) ?? [],
      photoIds: []
    })
  }
  for (const f of (root.favorites as Record<string, unknown>[] | undefined) ?? []) {
    await tx.objectStore('favorites').put({
      id: String(f.id ?? uid()), name: String(f.name), calories: Number(f.calories ?? 0),
      protein: Number(f.protein ?? 0), fat: Number(f.fat ?? 0), carbs: Number(f.carbs ?? 0)
    })
  }
  for (const w of (root.weights as { date: string; weight: number }[] | undefined) ?? []) await tx.objectStore('weights').put({ date: w.date, weight: Number(w.weight) })
  for (const w of (root.water as { date: string; ml: number }[] | undefined) ?? []) await tx.objectStore('water').put({ date: w.date, ml: Number(w.ml) })
  for (const w of (root.workouts as { date: string; done: boolean }[] | undefined) ?? []) await tx.objectStore('workouts').put({ date: w.date, done: !!w.done })
  for (const h of (root.habits as { date: string; habitId: string }[] | undefined) ?? []) await tx.objectStore('habits').put({ key: `${h.date}:${h.habitId}`, date: h.date, habitId: h.habitId })
  let t = Date.now() - 100000
  for (const c of (root.chat_history as { role: string; text: string }[] | undefined) ?? []) {
    if (!c?.text) continue
    await tx.objectStore('chat').put({ id: uid(), role: c.role === 'user' ? 'user' : 'model', text: c.text, createdAt: t++ })
  }
  const s = root.settings as Record<string, unknown> | null
  if (s) {
    const current = await tx.objectStore('settings').get(1)
    await tx.objectStore('settings').put({
      ...(current ?? { id: 1, apiKey: '', nanoApiKey: '', nanoEndpoint: '', provider: 'nanogpt' as const, playerId: '', playerSecret: '', avatar: '', userContext: '', nickname: '', onboarded: true, proteinGoal: null, fatGoal: null, carbsGoal: null, dailyGoal: 2000 }),
      id: 1,
      apiKey: String(s.apiKey || current?.apiKey || ''),
      nanoApiKey: String(s.nanoApiKey || current?.nanoApiKey || ''),
      nanoEndpoint: String(s.nanoApiEndpoint || current?.nanoEndpoint || ''),
      provider: ['simple', 'advanced', 'nanogpt'].includes(String(s.apiMode)) ? 'nanogpt' : 'gemini',
      dailyGoal: Number(s.dailyGoal ?? 2000),
      proteinGoal: s.proteinGoal == null ? null : Number(s.proteinGoal),
      fatGoal: s.fatGoal == null ? null : Number(s.fatGoal),
      carbsGoal: s.carbsGoal == null ? null : Number(s.carbsGoal),
      userContext: String(s.userContext ?? ''),
      nickname: String(s.leaderboardNickname ?? current?.nickname ?? ''),
      // Место в лидерборде переезжает вместе с бэкапом веб-версии. Android-id
      // не берём: его файл пишет само Android-приложение, вебу туда нельзя.
      playerId: String((root.web_player as { id?: string } | null)?.id || current?.playerId || ''),
      playerSecret: String((root.web_player as { secret?: string } | null)?.secret || current?.playerSecret || ''),
      onboarded: true
    })
  }
  await tx.done
  notifyChanged()
  return meals.length
}
