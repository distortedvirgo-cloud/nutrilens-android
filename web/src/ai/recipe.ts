// Рецепт из ссылки, видео, фото или текста — через выбранный сервис ИИ.
//
// Что видит модель:
//  - YouTube (в т.ч. Shorts): само видео по ссылке — Gemini напрямую, NanoGPT через
//    прямой Gemini-маршрут; если маршрут видео не принял — описание и название ролика;
//  - TikTok / Instagram / сайты: подпись, описание, разметка Recipe и текст страницы
//    (их собирает прокси /resolve — сайты не отдают это браузеру напрямую);
//  - видео из галереи: файл целиком (до ~14 МБ), фото — как изображения.
import type { Recipe, RecipeCategory, RecipeIngredient, RecipeSourceKind, Settings } from '../data/db'
import { post, SAFETY, stripFence } from './gemini'
import { complete } from './nano'
import { LEADERBOARD_API } from '../data/leaderboard'

/** У NanoGPT для рецептов — Gemini Flash Lite: документированно принимает видео. */
const NANO_RECIPE_MODEL = 'google/gemini-3.1-flash-lite'
/** Видео уходит в base64 внутри запроса: у Gemini предел запроса 20 МБ, у NanoGPT — 20 МБ на файл. */
export const MAX_VIDEO_BYTES = 14 * 1024 * 1024

export interface RecipeInput {
  kind: RecipeSourceKind
  url: string
  title?: string
  description?: string
  author?: string
  thumbnail?: string
  jsonLd?: string
  pageText?: string
  /** Прямая ссылка на ролик (TikTok/Instagram/og:video), если страница её отдала. */
  videoUrl?: string
  note?: string
  images?: string[]
  video?: { base64: string; mime: string }
}

export type RecipeDraft = Omit<Recipe, 'id' | 'cover' | 'favorite' | 'createdAt' | 'cookedCount' | 'source'> & {
  /** Секунда видео с лучшим кадром готового блюда — для обложки. */
  coverTimeSec: number | null
}

const CATEGORIES: RecipeCategory[] = ['breakfast', 'lunch', 'dinner', 'snack', 'dessert', 'drink']

const S = { type: 'STRING' }, N = { type: 'NUMBER' }, NN = { type: 'NUMBER', nullable: true }
const SCHEMA = {
  type: 'OBJECT',
  properties: {
    is_recipe: { type: 'BOOLEAN' }, reason: S,
    title: S, description: S, emoji: S,
    servings: N, total_minutes: NN,
    difficulty: { type: 'STRING', enum: ['easy', 'medium', 'hard'] },
    category: { type: 'STRING', enum: CATEGORIES },
    tags: { type: 'ARRAY', items: S },
    ingredients: {
      type: 'ARRAY',
      items: {
        type: 'OBJECT',
        properties: { name: S, amount: NN, unit: S, grams: NN, calories: NN, emoji: S, group: S, note: S },
        propertyOrdering: ['name', 'emoji', 'amount', 'unit', 'grams', 'calories', 'group', 'note'],
        required: ['name', 'emoji', 'unit']
      }
    },
    steps: { type: 'ARRAY', items: { type: 'OBJECT', properties: { text: S, minutes: NN }, required: ['text'] } },
    nutrition_per_serving: {
      type: 'OBJECT', properties: { calories: N, protein: N, fat: N, carbs: N },
      required: ['calories', 'protein', 'fat', 'carbs']
    },
    tips: { type: 'ARRAY', items: S },
    cover_time_sec: NN
  },
  propertyOrdering: ['is_recipe', 'reason', 'title', 'description', 'emoji', 'servings', 'total_minutes', 'difficulty', 'category', 'tags', 'ingredients', 'steps', 'nutrition_per_serving', 'tips', 'cover_time_sec'],
  required: ['is_recipe', 'title', 'servings', 'ingredients', 'steps', 'nutrition_per_serving']
}

const JSON_SHAPE = `{"is_recipe": true, "reason": "", "title": "", "description": "", "emoji": "🍝", "servings": 2, "total_minutes": 30,
"difficulty": "easy|medium|hard", "category": "breakfast|lunch|dinner|snack|dessert|drink", "tags": [""],
"ingredients": [{"name": "", "emoji": "🍅", "amount": 2, "unit": "шт", "grams": 240, "calories": 43, "group": "", "note": ""}],
"steps": [{"text": "", "minutes": 5}], "nutrition_per_serving": {"calories": 0, "protein": 0, "fat": 0, "carbs": 0}, "tips": [""], "cover_time_sec": 42}`

const KIND_LABEL: Record<RecipeSourceKind, string> = {
  youtube: 'видео YouTube', tiktok: 'видео TikTok', instagram: 'пост Instagram', web: 'страница сайта',
  video: 'видео из галереи', photo: 'фото рецепта', text: 'текст рецепта'
}

export function buildRecipePrompt(src: RecipeInput, hasVideo: boolean): string {
  const lines = [
    'Ты — кулинарный редактор. Составь из материала ниже аккуратный рецепт для книги рецептов.',
    '',
    'ПРАВИЛА:',
    '- Пиши по-русски, даже если источник на другом языке. Название — короткое и аппетитное, description — 1–2 предложения о блюде.',
    '- Ингредиенты: метрические единицы (г, мл, шт, ст. л., ч. л., щепотка). amount — число на ВСЕ порции рецепта; «по вкусу» — amount null, unit «по вкусу».',
    '- grams — примерный вес ингредиента на весь рецепт; calories — его ккал на весь рецепт по справочным значениям.',
    '- nutrition_per_serving — КБЖУ одной порции: сумма calories ингредиентов / servings, и 4×белки + 9×жиры + 4×углеводы ≈ калории.',
    '- emoji ингредиента — ОДИН эмодзи, максимально похожий на продукт: 🍅 помидор, 🧅 лук, 🧄 чеснок, 🥕 морковь, 🥔 картофель, 🫑 перец, 🌶️ острый перец, 🥒 огурец, 🥬 салат/капуста, 🌿 зелень, 🍄 грибы, 🥑 авокадо, 🍋 лимон, 🍎 яблоко, 🍌 банан, 🫐 ягоды, 🥚 яйцо, 🥛 молоко/сливки, 🧀 сыр/творог, 🧈 сливочное масло, 🫒 растительное масло, 🍗 курица, 🥩 мясо, 🥓 бекон, 🐟 рыба, 🦐 морепродукты, 🍚 рис/крупы, 🍝 паста, 🍞 хлеб, 🌾 мука, 🧂 соль/специи, 🍯 мёд, 🍫 шоколад, 🥜 орехи, 💧 вода. Нет точного — ближайший по смыслу.',
    '- emoji рецепта — одно эмодзи готового блюда.',
    '- group — подраздел («Тесто», «Соус», «Для подачи»), если в рецепте есть части; иначе пусто. note — уточнение («комнатной температуры»), можно пусто.',
    '- steps — по одному действию на шаг, коротко и понятно; minutes — время шага, если оно есть или очевидно, иначе null.',
    '- total_minutes — общее время; category — к какому приёму пищи блюдо подходит больше всего; tags — 2–4 коротких метки («быстро», «без глютена», «на ужин»).',
    '- tips — 0–3 полезных совета из источника, без воды.',
    '- Нет граммовок в источнике — оцени реалистично по видео или описанию. Ничего не выдумывай сверх нужного: если продукт не упомянут, не добавляй его.',
    hasVideo ? '- Главный и единственный источник — ВИДЕО: смотри, что и в каком порядке делают, читай надписи на экране и слушай, что говорят.' : '',
    hasVideo ? '- Перечисли ВСЕ ингредиенты, которые появляются в кадре или называются вслух/текстом, включая специи, подсластители, жидкости и посыпки. Не пропускай ничего, что кладут в блюдо.' : '',
    hasVideo ? '- НИКОГДА не добавляй продукты, которых нет в видео, даже если они типичны для такого блюда. Название и описание ролика — только чтобы уточнить граммовки уже увиденных продуктов.' : '',
    hasVideo
      ? '- cover_time_sec — секунда видео, где готовое блюдо видно лучше всего: крупно, аппетитно, без рук и текста поверх (обычно ближе к концу).'
      : '- cover_time_sec — null (видео нет).',
    '- Если в материале нет рецепта еды — is_recipe false, reason — коротко почему; остальные поля можно заполнить пустыми значениями.',
    '',
    `ИСТОЧНИК: ${KIND_LABEL[src.kind]}${src.url ? ` — ${src.url}` : ''}`
  ]
  if (src.title) lines.push(`НАЗВАНИЕ: ${src.title}`)
  if (src.author) lines.push(`АВТОР: ${src.author}`)
  if (src.description) lines.push(`ОПИСАНИЕ / ПОДПИСЬ:\n${src.description.slice(0, 5000)}`)
  if (src.jsonLd) lines.push(`РАЗМЕТКА РЕЦЕПТА СО СТРАНИЦЫ (schema.org):\n${src.jsonLd.slice(0, 6000)}`)
  if (src.pageText) lines.push(`ТЕКСТ СТРАНИЦЫ:\n${src.pageText.slice(0, 8000)}`)
  if (src.note) lines.push(`ОТ ПОЛЬЗОВАТЕЛЯ:\n${src.note.slice(0, 4000)}`)
  return lines.filter((l) => l !== '').join('\n')
}

/** Ссылка на YouTube в каноническом виде (Shorts, youtu.be, m.youtube) или null. */
export function youtubeWatchUrl(url: string): string | null {
  try {
    const u = new URL(url)
    const host = u.hostname.replace(/^(www|m|music)\./, '')
    let id: string | null = null
    if (host === 'youtu.be') id = u.pathname.slice(1).split('/')[0]
    else if (host === 'youtube.com' || host === 'youtube-nocookie.com') {
      id = u.searchParams.get('v') ?? (/^\/(shorts|embed|live|v)\/([^/?#]+)/.exec(u.pathname)?.[2] ?? null)
    }
    return id && /^[\w-]{6,20}$/.test(id) ? `https://www.youtube.com/watch?v=${id}` : null
  } catch {
    return null
  }
}

export function sourceKindOf(url: string): RecipeSourceKind {
  if (youtubeWatchUrl(url)) return 'youtube'
  const host = (() => { try { return new URL(url).hostname } catch { return '' } })()
  if (/(^|\.)tiktok\.com$/.test(host)) return 'tiktok'
  if (/(^|\.)instagram\.com$/.test(host)) return 'instagram'
  return 'web'
}

/** base64 ~4 МБ ≈ файл 3 МБ: больше NanoGPT внутри запроса не принимает. */
const NANO_INLINE_MAX = 4_000_000

async function uploadVideo(file: { base64: string; mime: string }): Promise<string> {
  if (!LEADERBOARD_API) throw new Error('Ролик больше 3 МБ: для NanoGPT нужен сервер приложения — переключись на Gemini или загрузи ролик короче')
  const bin = atob(file.base64)
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0))
  const type = file.mime === 'video/mov' ? 'video/quicktime' : file.mime
  const r = await fetch(`${LEADERBOARD_API}/video`, { method: 'POST', headers: { 'Content-Type': type }, body: bytes })
  if (r.status === 501) throw new Error('Ролик больше 3 МБ: для NanoGPT нужно хранилище роликов в Cloudflare (KV «VIDEOS») — или переключись на Gemini')
  if (!r.ok) throw new Error(`Не удалось передать ролик (HTTP ${r.status})`)
  return (await r.json()).url
}

const isHardError = (e: unknown) => /хранилище роликов|сервер приложения|Ключ|ключ|баланс|интернет|Дневной лимит|слишком много/.test((e as Error).message)

/** Один вызов выбранного сервиса; withVideo=false — без видео (запасной путь). */
async function callModel(settings: Settings, src: RecipeInput, withVideo: boolean): Promise<string> {
  const yt = withVideo && src.kind === 'youtube' ? youtubeWatchUrl(src.url) : null
  const file = withVideo ? src.video : undefined
  // Прямую ссылку на ролик TikTok/Instagram NanoGPT скачает сам; Gemini по URL видео не берёт.
  const direct = withVideo && !yt && !file && settings.provider !== 'gemini' ? src.videoUrl : undefined
  const prompt = buildRecipePrompt(src, !!(yt || file || direct))
  if (settings.provider === 'gemini') {
    const parts: unknown[] = [{ text: prompt }]
    if (yt) parts.push({ file_data: { file_uri: yt } })
    if (file) parts.push({ inline_data: { mime_type: file.mime, data: file.base64 } })
    for (const img of src.images ?? []) parts.push({ inline_data: { mime_type: 'image/jpeg', data: img } })
    return post(settings.apiKey, {
      contents: [{ parts }],
      // Видео — в низком разрешении кадров: для рецепта хватает, а токенов ~в 3 раза меньше.
      generationConfig: { responseMimeType: 'application/json', responseSchema: SCHEMA, ...(yt || file ? { mediaResolution: 'MEDIA_RESOLUTION_LOW' } : {}) },
      safetySettings: SAFETY
    })
  }
  // NanoGPT принимает видео внутри запроса только до ~4,5 МБ (лимит их хостинга):
  // большие ролики кладём во временное хранилище Worker и отдаём ссылкой (до 20 МБ).
  const fileUrl = file ? (file.base64.length <= NANO_INLINE_MAX ? `data:${file.mime};base64,${file.base64}` : await uploadVideo(file)) : null
  const videos = [...(yt ? [yt] : []), ...(direct ? [direct] : []), ...(fileUrl ? [fileUrl] : [])]
  // Ссылку YouTube на NanoGPT принимают только эти маршруты (проверено тестом).
  return complete({
    apiKey: settings.nanoApiKey, endpoint: settings.nanoEndpoint, model: yt ? 'google/gemini-flash-latest' : NANO_RECIPE_MODEL, jsonMode: true, serviceTier: null,
    messages: [{ role: 'user', text: `${prompt}\n\nФормат ответа — JSON строго такой формы:\n${JSON_SHAPE}`, images: src.images, videos }]
  })
}

export async function extractRecipe(settings: Settings, src: RecipeInput): Promise<RecipeDraft> {
  const key = settings.provider === 'gemini' ? settings.apiKey : settings.nanoApiKey
  if (!key.trim()) throw new Error(`Нет ключа ${settings.provider === 'gemini' ? 'Gemini' : 'NanoGPT'} — добавь его в Профиле → Нейросеть`)
  // Ролики разбираются только по самому видео — никаких догадок по названию и описанию.
  const isVideoSource = ['youtube', 'tiktok', 'instagram', 'video'].includes(src.kind)
  if ((src.kind === 'tiktok' || src.kind === 'instagram') && !src.video) {
    throw new Error('В веб-версии ролики TikTok и Instagram по ссылке не скачать. Сохрани видео и загрузи его кнопкой «Видео»')
  }
  let raw: string
  try {
    raw = await callModel(settings, { ...src, videoUrl: undefined }, isVideoSource)
  } catch (e) {
    const m = (e as Error).message
    if (src.kind === 'youtube' && settings.provider !== 'gemini' && (m.startsWith('video:') || /youtube/i.test(m))) {
      throw new Error('NanoGPT не открыл ролик YouTube. Переключись на Gemini в Профиле → Нейросеть или загрузи видео файлом')
    }
    if (isVideoSource && !isHardError(e)) throw new Error(`ИИ не смог посмотреть видео: ${m.slice(0, 120)}`)
    throw e
  }
  return parseRecipe(raw)
}

// ---------- Разбор ответа ----------

const num = (v: unknown): number | null => {
  const n = typeof v === 'number' ? v : parseFloat(String(v ?? '').replace(',', '.'))
  return Number.isFinite(n) ? n : null
}
const str = (v: unknown, max = 400) => String(v ?? '').trim().slice(0, max)
const firstEmoji = (v: unknown, fallback: string) => {
  const s = str(v, 16)
  const m = s.match(/\p{Extended_Pictographic}(️|‍\p{Extended_Pictographic})*/u)
  return m ? m[0] : fallback
}

export function parseRecipe(text: string): RecipeDraft {
  let j: Record<string, unknown>
  try { j = JSON.parse(stripFence(text)) } catch { throw new Error('ИИ вернул непонятный ответ — попробуй ещё раз') }
  if (j.is_recipe === false) throw new Error(`Не нашёл здесь рецепта${j.reason ? `: ${str(j.reason, 160)}` : ''}`)
  const ingredients: RecipeIngredient[] = (Array.isArray(j.ingredients) ? j.ingredients : [])
    .map((x: Record<string, unknown>) => ({
      name: str(x.name, 80),
      amount: num(x.amount),
      unit: str(x.unit, 20),
      grams: num(x.grams),
      calories: num(x.calories),
      emoji: firstEmoji(x.emoji, '🥄'),
      group: str(x.group, 40),
      note: str(x.note, 80)
    }))
    .filter((i) => i.name)
  const steps = (Array.isArray(j.steps) ? j.steps : [])
    .map((x: Record<string, unknown> | string) => typeof x === 'string'
      ? { text: str(x, 600), minutes: null }
      : { text: str(x.text, 600), minutes: num(x.minutes) })
    .filter((s) => s.text)
  if (!ingredients.length || !steps.length) throw new Error('Не получилось собрать рецепт: нет ингредиентов или шагов')
  const servings = Math.min(20, Math.max(1, Math.round(num(j.servings) ?? 2)))
  const n = (j.nutrition_per_serving ?? {}) as Record<string, unknown>
  let calories = num(n.calories) ?? 0
  const protein = num(n.protein) ?? 0, fat = num(n.fat) ?? 0, carbs = num(n.carbs) ?? 0
  // Калорийность сверяем с ингредиентами: сумма ингредиентов надёжнее итоговой цифры.
  const fromItems = ingredients.reduce((a, i) => a + (i.calories ?? 0), 0) / servings
  if (fromItems > 0 && (calories <= 0 || Math.abs(fromItems - calories) > Math.max(60, calories * 0.25))) calories = fromItems
  if (calories <= 0) calories = protein * 4 + fat * 9 + carbs * 4
  const category = CATEGORIES.includes(j.category as RecipeCategory) ? (j.category as RecipeCategory) : 'lunch'
  const difficulty = (['easy', 'medium', 'hard'] as const).find((d) => d === j.difficulty) ?? 'medium'
  return {
    title: str(j.title, 90) || 'Рецепт',
    description: str(j.description, 300),
    emoji: firstEmoji(j.emoji, '🍽️'),
    servings,
    totalMinutes: (() => { const t = num(j.total_minutes); return t && t > 0 ? Math.round(t) : steps.reduce((a, s) => a + (s.minutes ?? 0), 0) || null })(),
    difficulty,
    category,
    tags: (Array.isArray(j.tags) ? j.tags : []).map((t) => str(t, 24)).filter(Boolean).slice(0, 4),
    ingredients,
    steps,
    nutrition: { calories: Math.round(calories), protein: Math.round(protein), fat: Math.round(fat), carbs: Math.round(carbs) },
    tips: (Array.isArray(j.tips) ? j.tips : []).map((t) => str(t, 300)).filter(Boolean).slice(0, 3),
    coverTimeSec: (() => { const t = num(j.cover_time_sec); return t != null && t >= 0 ? t : null })()
  }
}
