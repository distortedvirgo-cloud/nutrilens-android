// Книга рецептов: импорт (ссылка, видео, фото, текст), масштабирование порций,
// запись порции в дневник, список покупок для «Поделиться».
import { useEffect, useState } from 'react'
import { extractRecipe, MAX_VIDEO_BYTES, sourceKindOf, type RecipeInput } from '../ai/recipe'
import { blobToBase64, storePhoto } from './actions'
import { fetchText } from '../ai/net'
import { getPhoto, getSettings, putMeal, putRecipe, uid, type Meal, type Recipe, type RecipeIngredient, type RecipeSourceKind } from './db'
import { LEADERBOARD_API } from './leaderboard'
import { iso, nowTime } from './insights'
import { effectiveCalories } from './body'

// ---------- Очередь импорта (в памяти: разбор занимает 10–60 с) ----------

export interface ImportJob { id: string; label: string; kind: RecipeSourceKind; error: string | null; retry: () => void }

let importJobs: ImportJob[] = []
const jobListeners = new Set<() => void>()
const emit = () => jobListeners.forEach((f) => f())

export function useImportJobs(): ImportJob[] {
  const [list, setList] = useState(importJobs)
  useEffect(() => {
    const f = () => setList([...importJobs])
    jobListeners.add(f)
    return () => { jobListeners.delete(f) }
  }, [])
  return list
}

export function dismissImport(id: string) {
  importJobs = importJobs.filter((j) => j.id !== id)
  emit()
}

/** Запускает разбор в фоне; onDone — id готового рецепта. */
type Prepared = { input: RecipeInput; cover: string | null; videoFile?: File }

function runImport(label: string, kind: RecipeSourceKind, prepare: () => Promise<Prepared>, onDone?: (id: string) => void) {
  const id = uid()
  const job: ImportJob = { id, label, kind, error: null, retry: () => { dismissImport(id); runImport(label, kind, prepare, onDone) } }
  importJobs = [job, ...importJobs]
  emit()
  ;(async () => {
    try {
      const settings = await getSettings()
      const { input, cover, videoFile } = await prepare()
      const { coverTimeSec, ...draft } = await extractRecipe(settings, input)
      // Обложка — кадр с готовым блюдом, который назвал ИИ; не вышло — первый кадр.
      const best = videoFile && coverTimeSec != null
        ? await videoFrame(videoFile, coverTimeSec).then(storePhoto).then((pid) => `photo:${pid}`).catch(() => null)
        : null
      const recipe: Recipe = {
        ...draft, id: uid(), cover: best || cover || input.thumbnail || null, favorite: false, createdAt: Date.now(), cookedCount: 0,
        source: { kind, url: input.url, author: input.author ?? '' }
      }
      await putRecipe(recipe)
      dismissImport(id)
      onDone?.(recipe.id)
    } catch (e) {
      importJobs = importJobs.map((j) => (j.id === id ? { ...j, error: (e as Error).message } : j))
      emit()
    }
  })()
}

/** Подпись, описание, превью и текст страницы — через прокси (сайты закрыты для браузера по CORS). */
async function resolveLink(url: string): Promise<RecipeInput> {
  const kind = sourceKindOf(url)
  const base: RecipeInput = { kind, url }
  if (!LEADERBOARD_API) return base
  try {
    const r = await fetchText(`${LEADERBOARD_API}/resolve?url=${encodeURIComponent(url)}`, { cache: 'no-store' }, 25_000, 'Сервер рецептов')
    if (!r.ok) return base
    const j = JSON.parse(r.text)
    return { ...base, title: j.title, description: j.description, author: j.author, thumbnail: j.thumbnail, jsonLd: j.jsonLd, pageText: j.pageText, videoUrl: j.videoUrl || undefined }
  } catch {
    return base
  }
}

export function normalizeUrl(text: string): string | null {
  const m = text.match(/https?:\/\/[^\s<>"']+/i)
  if (!m) return null
  try { return new URL(m[0].replace(/[).,!?»]+$/, '')).toString() } catch { return null }
}

const LABEL: Record<RecipeSourceKind, string> = {
  youtube: 'Видео YouTube', tiktok: 'Видео TikTok', instagram: 'Пост Instagram', web: 'Страница с рецептом',
  video: 'Видео из галереи', photo: 'Фото рецепта', text: 'Текст рецепта'
}

export function importFromLink(url: string, note: string, onDone?: (id: string) => void) {
  const kind = sourceKindOf(url)
  runImport(LABEL[kind], kind, async () => ({ input: { ...(await resolveLink(url)), note }, cover: null }), onDone)
}

export function importFromText(text: string, onDone?: (id: string) => void) {
  runImport(LABEL.text, 'text', async () => ({ input: { kind: 'text', url: '', note: text }, cover: null }), onDone)
}

export function importFromPhotos(files: File[], note: string, onDone?: (id: string) => void) {
  runImport(LABEL.photo, 'photo', async () => {
    const ids: string[] = []
    for (const f of files.slice(0, 4)) ids.push(await storePhoto(f))
    const images: string[] = []
    for (const pid of ids) { const p = await getPhoto(pid); if (p) images.push(await blobToBase64(p.full)) }
    return { input: { kind: 'photo', url: '', images, note }, cover: ids[0] ? `photo:${ids[0]}` : null }
  }, onDone)
}

export function videoTooLarge(file: File) { return file.size > MAX_VIDEO_BYTES }

export function importFromVideo(file: File, note: string, onDone?: (id: string) => void) {
  runImport(LABEL.video, 'video', async () => {
    const cover = await videoFrame(file).then(storePhoto).then((pid) => `photo:${pid}`).catch(() => null)
    // iPhone пишет .mov (video/quicktime); Gemini знает этот формат как video/mov.
    const mime = !file.type || file.type === 'video/quicktime' ? 'video/mov' : file.type
    return { input: { kind: 'video', url: '', note, video: { base64: await blobToBase64(file), mime } }, cover, videoFile: file }
  }, onDone)
}

/** Кадр ролика на секунде at (по умолчанию ~1,2 с) — обложка рецепта. */
function videoFrame(file: File, at?: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const v = document.createElement('video')
    const url = URL.createObjectURL(file)
    const done = (f: () => void) => { URL.revokeObjectURL(url); f() }
    v.muted = true; v.playsInline = true; v.preload = 'auto'; v.src = url
    v.onloadeddata = () => {
      const d = v.duration || 2
      v.currentTime = at != null ? Math.min(Math.max(0, at), Math.max(0, d - 0.1)) : Math.min(1.2, d / 2)
    }
    v.onseeked = () => {
      const c = document.createElement('canvas')
      c.width = v.videoWidth; c.height = v.videoHeight
      c.getContext('2d')!.drawImage(v, 0, 0)
      c.toBlob((b) => done(() => (b ? resolve(b) : reject(new Error('no frame')))), 'image/jpeg', 0.85)
    }
    v.onerror = () => done(() => reject(new Error('video')))
    setTimeout(() => done(() => reject(new Error('timeout'))), 8000)
  })
}

// ---------- Порции и количества ----------

const FRACTIONS: [number, string][] = [[0.25, '¼'], [0.33, '⅓'], [0.5, '½'], [0.67, '⅔'], [0.75, '¾']]

/** Красивое число: 1½, 0,3, 250 — без лишних знаков. */
export function formatAmount(v: number, unit: string): string {
  const countable = /^(шт|зубч|ст\. ?л|ч\. ?л|стак|пуч|щеп|лист|ломт|банк|упак)/i.test(unit)
  if (countable && v < 20) {
    const whole = Math.floor(v), frac = v - whole
    const f = FRACTIONS.find(([x]) => Math.abs(frac - x) < 0.06)
    if (frac < 0.06) return String(whole || 1)
    if (frac > 0.94) return String(whole + 1)
    if (f) return `${whole || ''}${f[1]}`
  }
  if (v >= 100) return String(Math.round(v / 5) * 5)
  if (v >= 10) return String(Math.round(v))
  return String(Math.round(v * 10) / 10).replace('.', ',')
}

export function ingredientAmount(i: RecipeIngredient, factor: number): string {
  if (i.amount == null || i.amount <= 0) return i.unit || 'по вкусу'
  return `${formatAmount(i.amount * factor, i.unit)} ${i.unit}`.trim()
}

/** Текст списка покупок — для «Поделиться» и буфера обмена. */
export function shoppingText(r: Recipe, servings: number): string {
  const f = servings / r.servings
  return [`${r.emoji} ${r.title} — ${servings} порц.`, ...r.ingredients.map((i) => `• ${i.name} — ${ingredientAmount(i, f)}`)].join('\n')
}

/** Записывает съеденные порции в дневник: КБЖУ на порцию × порции, состав — ингредиенты. */
export async function logRecipe(r: Recipe, portions: number): Promise<Meal> {
  const s = await getSettings()
  const f = portions / r.servings
  const n = r.nutrition
  const meal: Meal = {
    id: uid(), date: iso(new Date()), time: nowTime(),
    name: portions === 1 ? r.title : `${r.title} ×${formatAmount(portions, 'шт')}`,
    calories: Math.round(n.calories * portions), protein: Math.round(n.protein * portions * 10) / 10,
    fat: Math.round(n.fat * portions * 10) / 10, carbs: Math.round(n.carbs * portions * 10) / 10,
    aiThoughts: `Из книги рецептов: ${portions} порц. из ${r.servings}.`, reasoning: '', confidenceScore: 8,
    healthScore: null, healthNote: null, dailyGoalSnapshot: effectiveCalories(s), createdAt: Date.now(),
    items: r.ingredients.filter((i) => (i.calories ?? 0) > 0 || (i.grams ?? 0) > 0).map((i) => ({
      name: i.name, weightG: Math.round((i.grams ?? 0) * f), calories: Math.round((i.calories ?? 0) * f),
      protein: 0, fat: 0, carbs: 0, breakdown: ingredientAmount(i, f)
    })),
    photoIds: r.cover?.startsWith('photo:') ? [r.cover.slice(6)] : []
  }
  await putMeal(meal)
  await putRecipe({ ...r, cookedCount: r.cookedCount + 1 })
  return meal
}

export const CATEGORY_LABEL: Record<Recipe['category'], string> = {
  breakfast: 'Завтраки', lunch: 'Обеды', dinner: 'Ужины', snack: 'Перекусы', dessert: 'Десерты', drink: 'Напитки'
}
export const DIFFICULTY_LABEL: Record<Recipe['difficulty'], string> = { easy: 'Легко', medium: 'Средне', hard: 'Сложно' }
