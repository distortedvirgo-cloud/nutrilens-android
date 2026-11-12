// Хранилище NutriLens Web: IndexedDB на устройстве (данные никуда не уходят).
// Схема повторяет Android-версию, чтобы бэкапы переносились в обе стороны.
import { openDB, type DBSchema, type IDBPDatabase } from 'idb'

export interface MealItem {
  name: string
  weightG: number
  calories: number
  protein: number
  fat: number
  carbs: number
  breakdown: string
  portionBasis?: string
  calorieDensity?: number
}

export interface Meal {
  id: string
  date: string // YYYY-MM-DD
  time: string // HH:mm
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
  dailyGoalSnapshot: number | null
  createdAt: number
  items: MealItem[]
  photoIds: string[]
}

export interface Settings {
  id: 1
  /** Ключ Gemini (бесплатный, Google AI Studio). */
  apiKey: string
  /** Ключ NanoGPT (nano-gpt.com), основной провайдер по умолчанию. */
  nanoApiKey: string
  nanoEndpoint: string
  /** Какой сервис ИИ использовать в первую очередь. */
  provider: 'nanogpt' | 'gemini'
  dailyGoal: number
  proteinGoal: number | null
  fatGoal: number | null
  carbsGoal: number | null
  userContext: string
  nickname: string
  onboarded: boolean
  /** Постоянный id в лидерборде (не зависит от ника) и секрет устройства для записи. */
  playerId: string
  playerSecret: string
  /** Аватарка 128 px, data-URL JPEG; пусто — буква ника. Видна и в лидерборде. */
  avatar: string
  /** Рост, см, и пол — для формулы процента жира. null — не указано (берём из «О себе»). */
  heightCm: number | null
  sex: 'male' | 'female' | null
  /** Цель по проценту жира; null — функция выключена. */
  targetFatPercent: number | null
}

/** Замер тела: один на дату. manualFatPercent (весы, калипер, DEXA) важнее формулы. */
export interface BodyMeasurement {
  date: string // YYYY-MM-DD
  waistCm: number
  neckCm: number
  hipsCm?: number
  manualFatPercent?: number
  fatPercent: number
}

export interface Favorite {
  id: string
  name: string
  calories: number
  protein: number
  fat: number
  carbs: number
}

export interface Job {
  id: string
  status: 'QUEUED' | 'RUNNING' | 'FAILED'
  note: string
  photoIds: string[]
  createdAt: number
  error: string | null
  /** Уточнение уже записанного блюда: id блюда и текст правки. */
  refineMealId?: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'model'
  text: string
  createdAt: number
}

export const DEFAULT_SETTINGS: Settings = {
  id: 1,
  apiKey: '',
  nanoApiKey: '',
  nanoEndpoint: '',
  provider: 'nanogpt',
  dailyGoal: 2000,
  proteinGoal: null,
  fatGoal: null,
  carbsGoal: null,
  userContext: '',
  nickname: '',
  onboarded: false,
  playerId: '',
  playerSecret: '',
  avatar: '',
  heightCm: null,
  sex: null,
  targetFatPercent: null
}

// ---------- Книга рецептов ----------

export type RecipeCategory = 'breakfast' | 'lunch' | 'dinner' | 'snack' | 'dessert' | 'drink'
export type RecipeSourceKind = 'youtube' | 'tiktok' | 'instagram' | 'web' | 'video' | 'photo' | 'text'

export interface RecipeIngredient {
  name: string
  /** Количество на исходное число порций; null — «по вкусу». */
  amount: number | null
  unit: string
  /** Примерный вес в граммах на исходное число порций. */
  grams: number | null
  /** Калории этого ингредиента на весь рецепт. */
  calories: number | null
  emoji: string
  group: string
  note: string
}

export interface RecipeStep { text: string; minutes: number | null }

export interface Recipe {
  id: string
  title: string
  description: string
  emoji: string
  /** Превью из видео или страницы (URL) либо id фото в хранилище photos (photo:<id>). */
  cover: string | null
  servings: number
  totalMinutes: number | null
  difficulty: 'easy' | 'medium' | 'hard'
  category: RecipeCategory
  tags: string[]
  ingredients: RecipeIngredient[]
  steps: RecipeStep[]
  /** КБЖУ на одну порцию. */
  nutrition: { calories: number; protein: number; fat: number; carbs: number }
  tips: string[]
  source: { kind: RecipeSourceKind; url: string; author: string }
  favorite: boolean
  createdAt: number
  cookedCount: number
}

interface NutriSchema extends DBSchema {
  recipes: { key: string; value: Recipe; indexes: { byTime: number } }
  meals: { key: string; value: Meal; indexes: { byDate: string } }
  photos: { key: string; value: { id: string; full: Blob; thumb: Blob } }
  water: { key: string; value: { date: string; ml: number } }
  weights: { key: string; value: { date: string; weight: number } }
  workouts: { key: string; value: { date: string; done: boolean } }
  habits: { key: string; value: { key: string; date: string; habitId: string } }
  favorites: { key: string; value: Favorite }
  settings: { key: number; value: Settings }
  jobs: { key: string; value: Job }
  chat: { key: string; value: ChatMessage; indexes: { byTime: number } }
  measurements: { key: string; value: BodyMeasurement }
}

let dbPromise: Promise<IDBPDatabase<NutriSchema>> | null = null

export function db(): Promise<IDBPDatabase<NutriSchema>> {
  if (!dbPromise) {
    dbPromise = openDB<NutriSchema>('nutrilens', 3, {
      upgrade(d, oldVersion) {
        // v3: замеры тела (процент жира). Остальные хранилища не трогаем.
        if (oldVersion < 3) d.createObjectStore('measurements', { keyPath: 'date' })
        // v2: книга рецептов. Хранилища v1 создаются только на свежей базе.
        if (oldVersion < 2) d.createObjectStore('recipes', { keyPath: 'id' }).createIndex('byTime', 'createdAt')
        if (oldVersion >= 1) return
        const meals = d.createObjectStore('meals', { keyPath: 'id' })
        meals.createIndex('byDate', 'date')
        d.createObjectStore('photos', { keyPath: 'id' })
        d.createObjectStore('water', { keyPath: 'date' })
        d.createObjectStore('weights', { keyPath: 'date' })
        d.createObjectStore('workouts', { keyPath: 'date' })
        d.createObjectStore('habits', { keyPath: 'key' })
        d.createObjectStore('favorites', { keyPath: 'id' })
        d.createObjectStore('settings', { keyPath: 'id' })
        d.createObjectStore('jobs', { keyPath: 'id' })
        const chat = d.createObjectStore('chat', { keyPath: 'id' })
        chat.createIndex('byTime', 'createdAt')
      }
    })
    // Просим браузер не вытеснять данные (iOS чистит хранилище сайтов без этого).
    navigator.storage?.persist?.().catch(() => {})
  }
  return dbPromise
}

export const uid = (): string =>
  (crypto.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`)

// ---------- Подписка на изменения: экраны перечитывают данные ----------

type Listener = () => void
const listeners = new Set<Listener>()
export function onDataChanged(fn: Listener): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}
export function notifyChanged(): void {
  listeners.forEach((fn) => fn())
}

// ---------- Настройки ----------

export async function getSettings(): Promise<Settings> {
  return { ...DEFAULT_SETTINGS, ...((await (await db()).get('settings', 1)) ?? {}) }
}

export async function saveSettings(patch: Partial<Settings>): Promise<Settings> {
  const next = { ...(await getSettings()), ...patch, id: 1 as const }
  await (await db()).put('settings', next)
  notifyChanged()
  return next
}

// ---------- Еда ----------

export async function mealsOn(date: string): Promise<Meal[]> {
  const list = await (await db()).getAllFromIndex('meals', 'byDate', date)
  return list.sort((a, b) => a.time.localeCompare(b.time) || a.createdAt - b.createdAt)
}

export async function mealsBetween(start: string, end: string): Promise<Meal[]> {
  return (await db()).getAllFromIndex('meals', 'byDate', IDBKeyRange.bound(start, end))
}

export async function allMealDates(): Promise<Set<string>> {
  const dates = new Set<string>()
  let cursor = await (await db()).transaction('meals').store.index('byDate').openKeyCursor(null, 'nextunique')
  while (cursor) {
    dates.add(cursor.key)
    cursor = await cursor.continue()
  }
  return dates
}

export async function getMeal(id: string): Promise<Meal | undefined> {
  return (await db()).get('meals', id)
}

export async function putMeal(meal: Meal): Promise<void> {
  await (await db()).put('meals', meal)
  notifyChanged()
}

export async function deleteMeal(id: string): Promise<Meal | undefined> {
  const d = await db()
  const meal = await d.get('meals', id)
  await d.delete('meals', id)
  notifyChanged()
  return meal
}

export async function recentMeals(limit: number): Promise<Meal[]> {
  const all = await (await db()).getAll('meals')
  return all.sort((a, b) => b.createdAt - a.createdAt).slice(0, limit)
}

// ---------- Фото ----------

export async function putPhoto(id: string, full: Blob, thumb: Blob): Promise<void> {
  await (await db()).put('photos', { id, full, thumb })
}

export async function getPhoto(id: string): Promise<{ full: Blob; thumb: Blob } | undefined> {
  return (await db()).get('photos', id)
}

// ---------- Вода, вес, тренировка, привычки ----------

export async function waterOn(date: string): Promise<number> {
  return (await (await db()).get('water', date))?.ml ?? 0
}

export async function addWater(date: string, delta: number): Promise<number> {
  const ml = Math.max(0, (await waterOn(date)) + delta)
  await (await db()).put('water', { date, ml })
  notifyChanged()
  return ml
}

export async function waterBetween(start: string, end: string): Promise<{ date: string; ml: number }[]> {
  return (await db()).getAll('water', IDBKeyRange.bound(start, end))
}

export async function latestWeight(): Promise<{ date: string; weight: number } | undefined> {
  const all = await (await db()).getAll('weights')
  return all.sort((a, b) => a.date.localeCompare(b.date)).at(-1)
}

export async function weightsBetween(start: string, end: string): Promise<{ date: string; weight: number }[]> {
  return (await db()).getAll('weights', IDBKeyRange.bound(start, end))
}

export async function setWeight(date: string, weight: number): Promise<void> {
  await (await db()).put('weights', { date, weight })
  notifyChanged()
}

export async function workoutOn(date: string): Promise<boolean> {
  return (await (await db()).get('workouts', date))?.done ?? false
}

export async function toggleWorkout(date: string): Promise<boolean> {
  const next = !(await workoutOn(date))
  await (await db()).put('workouts', { date, done: next })
  notifyChanged()
  return next
}

export async function habitsOn(date: string): Promise<Set<string>> {
  const all = await (await db()).getAll('habits')
  return new Set(all.filter((h) => h.date === date).map((h) => h.habitId))
}

export async function toggleHabit(date: string, habitId: string): Promise<void> {
  const d = await db()
  const key = `${date}:${habitId}`
  if (await d.get('habits', key)) await d.delete('habits', key)
  else await d.put('habits', { key, date, habitId })
  notifyChanged()
}

// ---------- Замеры тела ----------

export async function measurements(): Promise<BodyMeasurement[]> {
  return (await (await db()).getAll('measurements')).sort((a, b) => a.date.localeCompare(b.date))
}

export async function latestMeasurement(): Promise<BodyMeasurement | undefined> {
  return (await measurements()).at(-1)
}

export async function measurementOn(date: string): Promise<BodyMeasurement | undefined> {
  return (await db()).get('measurements', date)
}

/** Запись на ту же дату перезаписывается. */
export async function putMeasurement(m: BodyMeasurement): Promise<void> {
  await (await db()).put('measurements', m)
  notifyChanged()
}

// ---------- Избранное ----------

export async function favorites(): Promise<Favorite[]> {
  return (await db()).getAll('favorites')
}

export async function addFavorite(m: Meal): Promise<void> {
  await (await db()).put('favorites', {
    id: uid(), name: m.name, calories: m.calories, protein: m.protein, fat: m.fat, carbs: m.carbs
  })
  notifyChanged()
}

export async function removeFavorite(id: string): Promise<void> {
  await (await db()).delete('favorites', id)
  notifyChanged()
}

// ---------- Задачи анализа ----------

export async function jobs(): Promise<Job[]> {
  return ((await (await db()).getAll('jobs')) as Job[]).sort((a, b) => a.createdAt - b.createdAt)
}

export async function putJob(job: Job): Promise<void> {
  await (await db()).put('jobs', job)
  notifyChanged()
}

export async function deleteJob(id: string): Promise<void> {
  await (await db()).delete('jobs', id)
  notifyChanged()
}

// ---------- Чат ----------

export async function chatHistory(): Promise<ChatMessage[]> {
  return (await db()).getAllFromIndex('chat', 'byTime')
}

export async function addChat(msg: ChatMessage): Promise<void> {
  await (await db()).put('chat', msg)
  notifyChanged()
}

export async function clearChat(): Promise<void> {
  await (await db()).clear('chat')
  notifyChanged()
}

// ---------- Рецепты ----------

export async function allRecipes(): Promise<Recipe[]> {
  return (await (await db()).getAll('recipes')).sort((a, b) => b.createdAt - a.createdAt)
}
export async function getRecipe(id: string): Promise<Recipe | undefined> {
  return (await db()).get('recipes', id)
}
export async function putRecipe(r: Recipe): Promise<void> {
  await (await db()).put('recipes', r)
  notifyChanged()
}
export async function deleteRecipe(id: string): Promise<Recipe | undefined> {
  const d = await db()
  const r = await d.get('recipes', id)
  await d.delete('recipes', id)
  notifyChanged()
  return r
}
