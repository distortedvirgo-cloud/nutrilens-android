// NutriLens Leaderboard — прокси между веб-версией и приватным репозиторием
// лидерборда. Токен GitHub живёт только здесь (секрет GITHUB_TOKEN), сайт
// получает узкое API:
//
//   GET  /entries      — все участники (без служебных полей), кеш 30 с
//   PUT  /entries/:id  — записать свой файл data/p_<id>.json
//
// Защита записи: при первом PUT файл получает keyHash = SHA-256(secret)
// устройства, дальше писать в него может только тот же secret. Файлы без
// keyHash принадлежат Android-приложению (оно пишет в репозиторий напрямую) —
// через прокси их не перезаписать.
//
// Переменные окружения (wrangler.toml / настройки Worker):
//   GITHUB_TOKEN    — секрет: fine-grained токен, Contents: Read and write
//                     только на репозиторий лидерборда
//   REPO            — "owner/repo" репозитория лидерборда
//   ALLOWED_ORIGINS — через запятую: откуда разрешены запросы (CORS)

const DATA_DIR = 'data'
const MAX_DAYS = 35
const CACHE_SECONDS = 30
const MAX_BODY = 16 * 1024
const ID_RE = /^[0-9a-f]{16}$/
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/

export default {
  async fetch(request, env, ctx) {
    const origin = request.headers.get('Origin') || ''
    const allowed = (env.ALLOWED_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean)
    const cors = {
      'Access-Control-Allow-Origin': allowed.includes(origin) ? origin : allowed[0] || '*',
      'Access-Control-Allow-Methods': 'GET, PUT, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Max-Age': '86400',
      Vary: 'Origin'
    }
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors })
    if (origin && allowed.length && !allowed.includes(origin)) return json({ error: 'origin_not_allowed' }, 403, cors)
    if (!env.GITHUB_TOKEN || !env.REPO) return json({ error: 'not_configured' }, 500, cors)

    const url = new URL(request.url)
    try {
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

  const list = await gh(env, `contents/${DATA_DIR}`)
  let files = []
  if (list.status === 200) files = (await list.json()).filter((f) => f.type === 'file' && f.name.endsWith('.json'))
  else if (list.status !== 404) throw new Error(`GitHub list ${list.status}`)

  const entries = (await Promise.all(files.map(async (f) => {
    const r = await gh(env, `contents/${DATA_DIR}/${encodeURIComponent(f.name)}`, { raw: true })
    if (r.status !== 200) return null
    try {
      const e = JSON.parse(await r.text())
      return {
        id: String(e.id || ''),
        nickname: String(e.nickname || ''),
        days: e.days && typeof e.days === 'object' ? e.days : {},
        updatedAt: Number(e.updatedAt || 0)
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
      if (!prev.keyHash) return json({ error: 'owned_by_app' }, 403, cors)
      if (prev.keyHash !== keyHash) return json({ error: 'forbidden' }, 403, cors)
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
      keyHash,
      source: 'web'
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
