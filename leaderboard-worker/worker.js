// NutriLens Leaderboard — прокси между веб-версией и приватным репозиторием
// лидерборда. Токен GitHub живёт только здесь (секрет GITHUB_TOKEN), сайт
// получает узкое API:
//
//   GET  /entries      — все участники (без служебных полей), кеш 30 с
//   PUT  /entries/:id  — записать свой файл data/p_<id>.json
//   POST /video        — временно (15 мин) сохранить ролик рецепта в KV и вернуть
//                        https-ссылку: NanoGPT берёт видео внутри запроса только до
//                        ~4,5 МБ, а по ссылке — до 20 МБ. Нужен KV-биндинг VIDEOS.
//   GET  /video/:id    — отдать сохранённый ролик
//   GET  /resolve?url= — данные для книги рецептов: подпись/описание ролика,
//                        превью, разметка Recipe и текст страницы (сайты не отдают
//                        это браузеру напрямую из-за CORS). Наружу — только эти поля.
//
// Защита записи: при первом PUT файл получает keyHash = SHA-256(secret)
// устройства, дальше писать в него может только тот же secret. Наружу GET
// отдаёт не настоящий id, а его хеш (publicId), поэтому старый файл без keyHash
// может «привязать» только владелец — лишь он знает свой настоящий id.
//
// Переменные окружения (wrangler.toml / настройки Worker):
//   GITHUB_TOKEN    — секрет: fine-grained токен, Contents: Read and write
//                     только на репозиторий лидерборда
//   REPO            — "owner/repo" репозитория лидерборда
//   ALLOWED_ORIGINS — через запятую: откуда разрешены запросы (CORS)

const DATA_DIR = 'data'
const MAX_DAYS = 35
const CACHE_SECONDS = 30
const MAX_BODY = 48 * 1024
/** Аватарка участника: маленький JPEG/PNG/WebP data-URL. */
const AVATAR_RE = /^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/
const MAX_AVATAR = 24000
const ID_RE = /^[0-9a-f]{16}$/
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/

export default {
  async fetch(request, env, ctx) {
    const origin = request.headers.get('Origin') || ''
    const allowed = (env.ALLOWED_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean)
    const cors = {
      'Access-Control-Allow-Origin': allowed.includes(origin) ? origin : allowed[0] || '*',
      'Access-Control-Allow-Methods': 'GET, PUT, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Max-Age': '86400',
      Vary: 'Origin'
    }
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors })
    if (origin && allowed.length && !allowed.includes(origin)) return json({ error: 'origin_not_allowed' }, 403, cors)
    const url = new URL(request.url)
    // Ролик для NanoGPT забирает их сервер — без Origin, поэтому до проверки источника.
    const vm = url.pathname.match(/^\/video\/([a-f0-9]{32})$/)
    if (request.method === 'GET' && vm) return await getVideo(env, vm[1])
    if (request.method === 'POST' && url.pathname === '/video') return await putVideo(request, env, url, cors)
    if (url.pathname !== '/resolve' && (!env.GITHUB_TOKEN || !env.REPO)) return json({ error: 'not_configured' }, 500, cors)

    try {
      if (request.method === 'GET' && url.pathname === '/resolve') return await resolveLink(url, ctx, cors)
      if (request.method === 'GET' && url.pathname === '/entries') return await listEntries(request, env, ctx, cors)
      const m = url.pathname.match(/^\/entries\/([^/]+)$/)
      if (request.method === 'PUT' && m) return await putEntry(request, env, ctx, cors, m[1], url)
      return json({ error: 'not_found' }, 404, cors)
    } catch (e) {
      return json({ error: 'upstream', message: String(e && e.message || e).slice(0, 200) }, 502, cors)
    }
  }
}

// ---------- GET /entries ----------

async function listEntries(request, env, ctx, cors) {
  const cacheKey = new Request(new URL('/entries', request.url).toString(), { method: 'GET' })
  const cache = caches.default
  const hit = await cache.match(cacheKey)
  if (hit) return withHeaders(hit, { ...cors, 'Cache-Control': 'no-store' })

  const raw = await readAllFiles(env)
  const entries = (await Promise.all(raw.map(async (text) => {
    try {
      const e = JSON.parse(text)
      return {
        id: e.id ? await publicId(String(e.id)) : '',
        nickname: String(e.nickname || ''),
        days: e.days && typeof e.days === 'object' ? e.days : {},
        updatedAt: Number(e.updatedAt || 0),
        avatar: typeof e.avatar === 'string' && AVATAR_RE.test(e.avatar) && e.avatar.length <= MAX_AVATAR ? e.avatar : ''
      }
    } catch {
      return null
    }
  }))).filter(Boolean)

  // Кеш на стороне Cloudflare — 30 с (бережёт лимит GitHub API), а браузеру
  // no-store: иначе он держит старую таблицу и не видит только что записанное.
  const body = JSON.stringify({ entries })
  const type = { 'Content-Type': 'application/json; charset=utf-8' }
  ctx.waitUntil(cache.put(cacheKey, new Response(body, { headers: { ...type, 'Cache-Control': `public, max-age=${CACHE_SECONDS}` } })))
  return new Response(body, { status: 200, headers: { ...type, ...cors, 'Cache-Control': 'no-store' } })
}

/** Все файлы data/*.json одним GraphQL-запросом (вместо запроса на каждого игрока). */
async function readAllFiles(env) {
  const [owner, name] = env.REPO.split('/')
  const r = await fetch('https://api.github.com/graphql', {
    method: 'POST',
    headers: { Authorization: `Bearer ${env.GITHUB_TOKEN}`, 'User-Agent': 'nutrilens-leaderboard-worker', 'Content-Type': 'application/json' },
    body: JSON.stringify({
      query: 'query($o:String!,$n:String!,$e:String!){repository(owner:$o,name:$n){object(expression:$e){... on Tree{entries{name type object{... on Blob{text}}}}}}}',
      variables: { o: owner, n: name, e: `HEAD:${DATA_DIR}` }
    })
  })
  if (r.ok) {
    const j = await r.json()
    const list = j?.data?.repository?.object?.entries
    if (Array.isArray(list)) return list.filter((f) => f.type === 'blob' && f.name.endsWith('.json') && f.object?.text).map((f) => f.object.text)
    if (j?.data?.repository && j.data.repository.object === null) return [] // каталога data/ ещё нет
  }
  // Запасной путь: REST, по файлу на запрос.
  const list = await gh(env, `contents/${DATA_DIR}`)
  if (list.status === 404) return []
  if (list.status !== 200) throw new Error(`GitHub list ${list.status}`)
  const files = (await list.json()).filter((f) => f.type === 'file' && f.name.endsWith('.json'))
  return (await Promise.all(files.map(async (f) => {
    const x = await gh(env, `contents/${DATA_DIR}/${encodeURIComponent(f.name)}`, { raw: true })
    return x.status === 200 ? x.text() : null
  }))).filter(Boolean)
}

/** Публичный id игрока: первые 16 hex от SHA-256 настоящего id. */
async function publicId(id) {
  return (await sha256(`nutrilens:${id}`)).slice(0, 16)
}

// ---------- PUT /entries/:id ----------

async function putEntry(request, env, ctx, cors, id, url) {
  if (!ID_RE.test(id)) return json({ error: 'bad_id' }, 400, cors)
  const raw = await request.text()
  if (raw.length > MAX_BODY) return json({ error: 'too_large' }, 413, cors)
  let body
  try { body = JSON.parse(raw) } catch { return json({ error: 'bad_json' }, 400, cors) }

  const secret = typeof body.secret === 'string' ? body.secret : ''
  if (secret.length < 32 || secret.length > 128) return json({ error: 'bad_secret' }, 400, cors)
  const nickname = typeof body.nickname === 'string' ? body.nickname.trim().slice(0, 32) : ''
  if (!nickname) return json({ error: 'bad_nickname' }, 400, cors)
  const days = cleanDays(body.days)
  const avatar = typeof body.avatar === 'string' && AVATAR_RE.test(body.avatar) && body.avatar.length <= MAX_AVATAR ? body.avatar : ''
  if (!days) return json({ error: 'bad_days' }, 400, cors)
  const keyHash = await sha256(secret)
  const path = `contents/${DATA_DIR}/p_${id}.json`

  for (let attempt = 0; attempt < 2; attempt++) {
    const cur = await gh(env, path)
    let sha = null
    let prev = null
    if (cur.status === 200) {
      const meta = await cur.json()
      sha = meta.sha
      prev = JSON.parse(fromBase64(meta.content || ''))
      // Файл без keyHash (создан старой версией приложения) привязывает первый,
      // кто знает настоящий id — это сам владелец: наружу id не отдаётся.
      if (prev.keyHash && prev.keyHash !== keyHash) return json({ error: 'forbidden' }, 403, cors)
    } else if (cur.status !== 404) {
      throw new Error(`GitHub get ${cur.status}`)
    }

    const merged = { ...(prev ? prev.days : {}), ...days }
    const kept = Object.keys(merged).sort().slice(-MAX_DAYS)
    const entry = {
      id,
      nickname,
      days: Object.fromEntries(kept.map((k) => [k, merged[k]])),
      updatedAt: Date.now(),
      avatar,
      keyHash,
      source: typeof body.source === 'string' ? body.source.slice(0, 16) : 'web'
    }
    const put = await gh(env, path, {
      method: 'PUT',
      body: JSON.stringify({ message: 'leaderboard update (web)', content: toBase64(JSON.stringify(entry)), ...(sha ? { sha } : {}) })
    })
    if (put.status === 200 || put.status === 201) {
      ctx.waitUntil(caches.default.delete(new Request(new URL('/entries', url).toString(), { method: 'GET' })))
      return json({ ok: true }, 200, cors)
    }
    if (put.status !== 409 && put.status !== 422) throw new Error(`GitHub put ${put.status}`)
  }
  return json({ error: 'conflict' }, 409, cors)
}

function cleanDays(days) {
  if (!days || typeof days !== 'object') return null
  const keys = Object.keys(days)
  if (keys.length === 0 || keys.length > MAX_DAYS + 5) return null
  const out = {}
  for (const k of keys) {
    if (!DATE_RE.test(k)) return null
    const d = days[k] || {}
    const calories = Number(d.calories), goal = Number(d.goal)
    if (!Number.isFinite(calories) || calories < 0 || calories > 20000) return null
    if (!Number.isFinite(goal) || goal < 0 || goal > 10000) return null
    out[k] = { calories: Math.round(calories * 10) / 10, goal: Math.round(goal) }
  }
  return out
}

// ---------- Видео для NanoGPT ----------

const MAX_VIDEO = 20 * 1024 * 1024
const VIDEO_TTL = 15 * 60

async function putVideo(request, env, url, cors) {
  if (!env.VIDEOS) return json({ error: 'video_store_not_configured' }, 501, cors)
  const type = (request.headers.get('Content-Type') || '').split(';')[0].trim()
  if (!/^video\/[\w.+-]+$/.test(type)) return json({ error: 'not_video' }, 400, cors)
  const len = Number(request.headers.get('Content-Length') || 0)
  if (len > MAX_VIDEO) return json({ error: 'too_large' }, 413, cors)
  const body = await request.arrayBuffer()
  if (!body.byteLength || body.byteLength > MAX_VIDEO) return json({ error: 'too_large' }, 413, cors)
  const id = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, '0')).join('')
  await env.VIDEOS.put(id, body, { expirationTtl: VIDEO_TTL, metadata: { type } })
  return json({ url: `${url.origin}/video/${id}` }, 200, cors)
}

async function getVideo(env, id) {
  if (!env.VIDEOS) return new Response('not configured', { status: 501 })
  const { value, metadata } = await env.VIDEOS.getWithMetadata(id, { type: 'arrayBuffer' })
  if (!value) return new Response('not found', { status: 404 })
  return new Response(value, { headers: { 'Content-Type': (metadata && metadata.type) || 'video/mp4', 'Cache-Control': 'no-store' } })
}

// ---------- GET /resolve ----------

const RESOLVE_CACHE_SECONDS = 24 * 60 * 60
const MAX_PAGE = 1_500_000
const BROWSER_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1'

async function resolveLink(url, ctx, cors) {
  const target = url.searchParams.get('url') || ''
  let u
  try { u = new URL(target) } catch { return json({ error: 'bad_url' }, 400, cors) }
  if (!/^https?:$/.test(u.protocol) || target.length > 2048) return json({ error: 'bad_url' }, 400, cors)

  const cacheKey = new Request(`https://resolve.cache/${encodeURIComponent(target)}`)
  const hit = await caches.default.match(cacheKey)
  if (hit) return withHeaders(hit, { ...cors, 'Cache-Control': 'no-store' })

  const out = { url: target, kind: 'web', title: '', description: '', author: '', thumbnail: '', jsonLd: '', pageText: '', videoUrl: '' }
  const host = u.hostname.replace(/^(www|m)\./, '')
  try {
    const yt = youtubeId(u, host)
    if (yt) {
      out.kind = 'youtube'
      const o = await fetchJson(`https://www.youtube.com/oembed?format=json&url=${encodeURIComponent('https://www.youtube.com/watch?v=' + yt)}`)
      if (o) { out.title = o.title || ''; out.author = o.author_name || ''; out.thumbnail = o.thumbnail_url || '' }
      const html = await fetchText(`https://www.youtube.com/watch?v=${yt}&hl=ru`)
      const m = html && /"shortDescription":"((?:[^"\\]|\\.)*)"/.exec(html)
      if (m) { try { out.description = JSON.parse(`"${m[1]}"`) } catch { /* битая строка */ } }
    } else if (/(^|\.)tiktok\.com$/.test(host)) {
      out.kind = 'tiktok'
      const o = await fetchJson(`https://www.tiktok.com/oembed?url=${encodeURIComponent(target)}`)
      if (o) { out.description = o.title || ''; out.author = o.author_name || ''; out.thumbnail = o.thumbnail_url || '' }
      // Прямая ссылка на ролик — из данных страницы (подписанный адрес CDN; может не открыться).
      const html = await fetchText(target)
      const m = html && /"playAddr":"(https:[^"]+)"/.exec(html)
      if (m) { try { out.videoUrl = JSON.parse(`"${m[1]}"`) } catch { /* битая строка */ } }
    } else {
      if (/(^|\.)instagram\.com$/.test(host)) out.kind = 'instagram'
      const html = await fetchText(target)
      if (html) Object.assign(out, parsePage(html, out))
    }
  } catch { /* отдаём что успели собрать */ }

  const body = JSON.stringify(out)
  const type = { 'Content-Type': 'application/json; charset=utf-8' }
  ctx.waitUntil(caches.default.put(cacheKey, new Response(body, { headers: { ...type, 'Cache-Control': `public, max-age=${RESOLVE_CACHE_SECONDS}` } })))
  return new Response(body, { status: 200, headers: { ...type, ...cors, 'Cache-Control': 'no-store' } })
}

function youtubeId(u, host) {
  let id = null
  if (host === 'youtu.be') id = u.pathname.slice(1).split('/')[0]
  else if (host === 'youtube.com' || host === 'music.youtube.com') {
    id = u.searchParams.get('v') || ((/^\/(shorts|embed|live|v)\/([^/?#]+)/.exec(u.pathname) || [])[2] || null)
  }
  return id && /^[\w-]{6,20}$/.test(id) ? id : null
}

async function fetchJson(url) {
  const r = await fetch(url, { headers: { 'User-Agent': BROWSER_UA }, cf: { cacheTtl: 3600 } })
  if (!r.ok) return null
  try { return await r.json() } catch { return null }
}

async function fetchText(url) {
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), 8000)
  try {
    const r = await fetch(url, { signal: ctrl.signal, redirect: 'follow', headers: { 'User-Agent': BROWSER_UA, 'Accept-Language': 'ru,en;q=0.8', Accept: 'text/html,application/xhtml+xml' } })
    const type = r.headers.get('content-type') || ''
    if (!r.ok || !/html|xml|text/.test(type)) return ''
    const reader = r.body.getReader()
    const chunks = []
    let size = 0
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.length
      chunks.push(value)
      if (size > MAX_PAGE) { reader.cancel(); break }
    }
    const all = new Uint8Array(size)
    let off = 0
    for (const c of chunks) { all.set(c.subarray(0, Math.min(c.length, size - off)), off); off += c.length }
    return new TextDecoder().decode(all)
  } catch {
    return ''
  } finally {
    clearTimeout(t)
  }
}

function meta(html, prop) {
  const re = new RegExp(`<meta[^>]+(?:property|name)=["']${prop}["'][^>]*>`, 'i')
  const tag = re.exec(html)
  const c = tag && /content=["']([^"']*)["']/i.exec(tag[0])
  return c ? decodeEntities(c[1]) : ''
}

function parsePage(html, out) {
  const res = {
    title: meta(html, 'og:title') || decodeEntities((/<title[^>]*>([^<]*)<\/title>/i.exec(html) || [])[1] || ''),
    description: meta(html, 'og:description') || meta(html, 'description'),
    thumbnail: meta(html, 'og:image'),
    videoUrl: meta(html, 'og:video:secure_url') || meta(html, 'og:video:url') || meta(html, 'og:video'),
    author: out.author,
    jsonLd: '',
    pageText: ''
  }
  // schema.org Recipe — на сайтах рецептов это самый точный источник.
  for (const m of html.matchAll(/<script[^>]+application\/ld\+json[^>]*>([\s\S]*?)<\/script>/gi)) {
    try {
      const found = findRecipe(JSON.parse(m[1].trim()))
      if (found) { res.jsonLd = JSON.stringify(found).slice(0, 8000); break }
    } catch { /* битый JSON-LD */ }
  }
  if (!res.jsonLd) {
    res.pageText = decodeEntities(html
      .replace(/<(script|style|noscript|svg|nav|footer|header)[\s\S]*?<\/\1>/gi, ' ')
      .replace(/<[^>]+>/g, ' '))
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 9000)
  }
  return res
}

function findRecipe(node) {
  if (!node || typeof node !== 'object') return null
  if (Array.isArray(node)) { for (const n of node) { const f = findRecipe(n); if (f) return f } return null }
  const t = node['@type']
  if (t === 'Recipe' || (Array.isArray(t) && t.includes('Recipe'))) {
    const { name, description, recipeYield, totalTime, recipeIngredient, recipeInstructions, nutrition, image } = node
    return { name, description, recipeYield, totalTime, recipeIngredient, recipeInstructions, nutrition, image }
  }
  return findRecipe(node['@graph'])
}

function decodeEntities(s) {
  return s.replace(/&(#x?[0-9a-f]+|amp|lt|gt|quot|apos|nbsp|#39);/gi, (_, e) => {
    const map = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', '#39': "'" }
    if (map[e.toLowerCase()]) return map[e.toLowerCase()]
    const code = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10)
    return Number.isFinite(code) ? String.fromCodePoint(code) : ''
  })
}

// ---------- helpers ----------

function gh(env, path, { method = 'GET', body, raw = false } = {}) {
  return fetch(`https://api.github.com/repos/${env.REPO}/${path}`, {
    method,
    body,
    headers: {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: raw ? 'application/vnd.github.raw+json' : 'application/vnd.github+json',
      'User-Agent': 'nutrilens-leaderboard-worker',
      'X-GitHub-Api-Version': '2022-11-28',
      ...(body ? { 'Content-Type': 'application/json' } : {})
    }
  })
}

function json(data, status, headers = {}) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers } })
}

function withHeaders(resp, headers) {
  const r = new Response(resp.body, resp)
  for (const [k, v] of Object.entries(headers)) r.headers.set(k, v)
  return r
}

async function sha256(s) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s))
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

function toBase64(s) {
  const bytes = new TextEncoder().encode(s)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin)
}

function fromBase64(b64) {
  const bin = atob(b64.replace(/\s/g, ''))
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}
