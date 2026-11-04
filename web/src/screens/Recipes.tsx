// «Рецепты»: книга рецептов. Сверху — импорт (ссылка на видео/сайт, видео,
// фото, текст), под ним — идущие разборы, поиск, категории и сетка карточек.
import { useRef, useState } from 'react'
import { allRecipes, putRecipe, type Recipe } from '../data/db'
import {
  CATEGORY_LABEL, dismissImport, importFromLink, importFromPhotos, importFromText, importFromVideo,
  normalizeUrl, useImportJobs, videoTooLarge, type ImportJob
} from '../data/recipes'
import { Photo, Sheet, useData, useSheetClose, useToast } from '../ui/kit'
import { IcBook, IcCamera, IcClipboard, IcClock, IcFlame, IcHeart, IcRefresh, IcSearch, IcSend, IcText, IcVideo } from '../ui/icons'

/** Цвет обложки без картинки — по категории блюда. */
export const CATEGORY_TINT: Record<Recipe['category'], [string, string]> = {
  breakfast: ['#FFB868', '#F2766B'], lunch: ['#5FD49B', '#16936A'], dinner: ['#8C9BFF', '#5A4FCF'],
  snack: ['#5CD5D0', '#1E8FA3'], dessert: ['#FF9CC2', '#D9508A'], drink: ['#7FC8FF', '#3A7BE0']
}

export function Cover({ r, height, radius = 0, emojiSize = 64 }: { r: Recipe; height: number | string; radius?: number; emojiSize?: number }) {
  const [a, b] = CATEGORY_TINT[r.category]
  const box: React.CSSProperties = { height, borderRadius: radius, overflow: 'hidden', position: 'relative', flex: 'none' }
  if (r.cover?.startsWith('photo:')) return <div style={box}><Photo id={r.cover.slice(6)} kind="full" style={{ width: '100%', height: '100%', objectFit: 'cover' }} /></div>
  if (r.cover) {
    return (
      <div style={{ ...box, background: `linear-gradient(135deg, ${a}, ${b})` }}>
        <img src={r.cover} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }}
          onError={(e) => { (e.target as HTMLImageElement).style.display = 'none' }} />
      </div>
    )
  }
  return (
    <div style={{ ...box, background: `linear-gradient(135deg, ${a}, ${b})`, display: 'grid', placeItems: 'center' }}>
      <span style={{ fontSize: emojiSize, filter: 'drop-shadow(0 6px 14px rgba(0,0,0,.18))' }}>{r.emoji}</span>
    </div>
  )
}

type Filter = 'all' | 'fav' | Recipe['category']

export function RecipesScreen({ onOpen }: { onOpen: (id: string) => void }) {
  const recipes = useData(allRecipes, [], null as Recipe[] | null)
  const jobs = useImportJobs()
  const [filter, setFilter] = useState<Filter>('all')
  const [q, setQ] = useState('')
  const [sheet, setSheet] = useState<'text' | null>(null)
  const list = (recipes ?? []).filter((r) =>
    (filter === 'all' || (filter === 'fav' ? r.favorite : r.category === filter)) &&
    (!q.trim() || `${r.title} ${r.tags.join(' ')} ${r.ingredients.map((i) => i.name).join(' ')}`.toLowerCase().includes(q.trim().toLowerCase())))
  const categories = [...new Set((recipes ?? []).map((r) => r.category))]
  const count = recipes?.length ?? 0

  return (
    <div className="screen screen-enter">
      <div className="col gap-16 stagger">
        <div>
          <div className="display">Рецепты</div>
          <div className="small">{count ? `${count} ${plural(count, 'рецепт', 'рецепта', 'рецептов')} в книге` : 'Твоя книга рецептов'}</div>
        </div>

        <ImportCard onText={() => setSheet('text')} onOpen={onOpen} />

        {jobs.map((j) => <JobCard key={j.id} job={j} />)}

        {recipes && count === 0 && jobs.length === 0 && <EmptyBook />}

        {count > 0 && (
          <>
            {count >= 4 && (
              <div className="row gap-10 card r22" style={{ padding: '0 14px', height: 50 }}>
                <span className="muted"><IcSearch size={19} /></span>
                <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Блюдо или продукт"
                  style={{ flex: 1, border: 'none', background: 'transparent', font: 'inherit', fontSize: 16, color: 'var(--text)', outline: 'none' }} />
              </div>
            )}
            <div className="row gap-8" style={{ overflowX: 'auto', margin: '0 -20px', padding: '0 20px', scrollbarWidth: 'none' }}>
              <FilterChip on={filter === 'all'} onClick={() => setFilter('all')}>Все</FilterChip>
              {(recipes ?? []).some((r) => r.favorite) && (
                <FilterChip on={filter === 'fav'} onClick={() => setFilter('fav')}><IcHeart size={15} />Избранное</FilterChip>
              )}
              {categories.map((c) => <FilterChip key={c} on={filter === c} onClick={() => setFilter(c)}>{CATEGORY_LABEL[c]}</FilterChip>)}
            </div>
            {list.length === 0 ? (
              <div className="small" style={{ textAlign: 'center', padding: 24 }}>Ничего не нашлось</div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                {list.map((r) => <RecipeCard key={r.id} r={r} onOpen={() => onOpen(r.id)} />)}
              </div>
            )}
          </>
        )}
      </div>
      {sheet === 'text' && (
        <Sheet label="Текст рецепта" onClose={() => setSheet(null)}><TextImport onOpen={onOpen} /></Sheet>
      )}
    </div>
  )
}

function ImportCard({ onText, onOpen }: { onText: () => void; onOpen: (id: string) => void }) {
  const toast = useToast()
  const [link, setLink] = useState('')
  const videoRef = useRef<HTMLInputElement>(null)
  const photoRef = useRef<HTMLInputElement>(null)
  const done = (id: string) => toast('Рецепт готов', { label: 'Открыть', run: () => onOpen(id) })
  const submit = (text = link) => {
    const url = normalizeUrl(text)
    if (!url) { toast('Вставь ссылку на видео или страницу с рецептом'); return }
    importFromLink(url, '', done)
    setLink('')
    toast('Разбираю рецепт — это займёт до минуты')
  }
  const paste = async () => {
    try {
      const t = await navigator.clipboard.readText()
      const url = normalizeUrl(t)
      if (url) submit(url)
      else { setLink(t); toast('В буфере нет ссылки') }
    } catch {
      toast('Нет доступа к буферу — вставь ссылку в поле')
    }
  }
  return (
    <div className="card pad-18 col gap-14" style={{ background: 'linear-gradient(145deg, color-mix(in srgb, var(--primary) 16%, var(--surface)), var(--surface))' }}>
      <div className="row gap-12">
        <span className="badge" style={{ width: 46, height: 46, borderRadius: 15, background: 'linear-gradient(135deg, var(--brand-from), var(--brand-to))', color: 'var(--on-brand)' }}><IcBook size={23} /></span>
        <div className="grow">
          <div className="title">Добавить рецепт</div>
          <div className="small">Ссылка на YouTube, TikTok, Instagram или сайт</div>
        </div>
      </div>
      <div className="row gap-8">
        <input className="field" value={link} onChange={(e) => setLink(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && submit()}
          placeholder="https://…" inputMode="url" autoCapitalize="off" autoCorrect="off" style={{ flex: 1 }} />
        {link.trim()
          ? <button className="icon-btn" aria-label="Разобрать" onClick={() => submit()} style={{ width: 52, height: 52, background: 'var(--primary)', color: 'var(--on-brand)' }}><IcSend size={20} /></button>
          : <button className="icon-btn" aria-label="Вставить ссылку" onClick={paste} style={{ width: 52, height: 52, background: 'var(--surface-high)' }}><IcClipboard size={20} /></button>}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8 }}>
        <SourceButton icon={<IcVideo size={19} />} label="Видео" onClick={() => videoRef.current?.click()} />
        <SourceButton icon={<IcCamera size={19} />} label="Фото" onClick={() => photoRef.current?.click()} />
        <SourceButton icon={<IcText size={19} />} label="Текст" onClick={onText} />
      </div>
      <input ref={videoRef} type="file" accept="video/*" hidden onChange={(e) => {
        const f = e.target.files?.[0]
        e.target.value = ''
        if (!f) return
        if (videoTooLarge(f)) { toast('Видео больше 14 МБ — обрежь ролик или пришли ссылку'); return }
        importFromVideo(f, '', done)
        toast('Смотрю видео — это займёт до минуты')
      }} />
      <input ref={photoRef} type="file" accept="image/*" multiple hidden onChange={(e) => {
        const files = Array.from(e.target.files ?? [])
        e.target.value = ''
        if (!files.length) return
        importFromPhotos(files, '', done)
        toast('Читаю рецепт с фото')
      }} />
    </div>
  )
}

const SourceButton = ({ icon, label, onClick }: { icon: React.ReactNode; label: string; onClick: () => void }) => (
  <button className="pressable col" onClick={onClick}
    style={{ alignItems: 'center', gap: 6, padding: '12px 0', borderRadius: 16, background: 'var(--surface-high)', fontWeight: 700, fontSize: 13 }}>
    <span style={{ color: 'var(--primary)' }}>{icon}</span>{label}
  </button>
)

function JobCard({ job }: { job: ImportJob }) {
  if (job.error) {
    return (
      <div className="card pad-16 col gap-10" style={{ border: '1.5px solid color-mix(in srgb, var(--tertiary) 45%, transparent)' }}>
        <div className="title">{job.label}: не получилось</div>
        <div className="small">{job.error}</div>
        <div className="row gap-8">
          <button className="btn btn-soft" style={{ height: 42 }} onClick={job.retry}><IcRefresh size={17} />Повторить</button>
          <button className="btn" style={{ height: 42, color: 'var(--text-2)' }} onClick={() => dismissImport(job.id)}>Скрыть</button>
        </div>
      </div>
    )
  }
  return (
    <div className="card pad-16 row gap-14 scan" style={{ overflow: 'hidden' }}>
      <span className="spinner" />
      <div className="grow">
        <div className="title">Разбираю: {job.label.toLowerCase()}</div>
        <div className="small">Ингредиенты, шаги и КБЖУ на порцию. Можно уйти с экрана.</div>
      </div>
    </div>
  )
}

function RecipeCard({ r, onOpen }: { r: Recipe; onOpen: () => void }) {
  return (
    <button className="card r24 pressable col" onClick={onOpen} style={{ textAlign: 'left', overflow: 'hidden', padding: 0 }}>
      <div style={{ position: 'relative' }}>
        <Cover r={r} height={128} emojiSize={52} />
        <span role="button" aria-label={r.favorite ? 'Убрать из избранного' : 'В избранное'}
          onClick={(e) => { e.stopPropagation(); putRecipe({ ...r, favorite: !r.favorite }) }}
          style={{ position: 'absolute', top: 8, right: 8, width: 34, height: 34, borderRadius: 17, display: 'grid', placeItems: 'center',
            background: 'rgba(0,0,0,.32)', color: '#fff', backdropFilter: 'blur(8px)', WebkitBackdropFilter: 'blur(8px)' }}>
          <IcHeart size={17} fill={r.favorite ? '#FF5E7E' : 'none'} stroke={r.favorite ? '#FF5E7E' : '#fff'} />
        </span>
      </div>
      <div className="col gap-6" style={{ padding: '10px 12px 12px' }}>
        <div style={{ fontWeight: 800, fontSize: 15, lineHeight: 1.25, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden', minHeight: 37 }}>{r.title}</div>
        <div className="row gap-8 label num" style={{ color: 'var(--text-2)' }}>
          {r.totalMinutes ? <span className="row" style={{ gap: 3 }}><IcClock size={13} />{r.totalMinutes} мин</span> : null}
          <span className="row" style={{ gap: 3 }}><IcFlame size={13} />{r.nutrition.calories}</span>
        </div>
      </div>
    </button>
  )
}

const FilterChip = ({ on, onClick, children }: { on: boolean; onClick: () => void; children: React.ReactNode }) => (
  <button className="chip pressable" onClick={onClick} style={{ flex: 'none', height: 36, ...(on ? { background: 'var(--text)', color: 'var(--bg)' } : {}) }}>{children}</button>
)

function EmptyBook() {
  return (
    <div className="card col" style={{ alignItems: 'center', textAlign: 'center', padding: '30px 22px', gap: 10 }}>
      <div style={{ fontSize: 46, letterSpacing: 4 }}>🍝🥗🍰</div>
      <div className="h2">Собери свою книгу рецептов</div>
      <div className="small" style={{ maxWidth: 300 }}>
        Увидел рецепт в Reels, Shorts или TikTok — скопируй ссылку и вставь выше. ИИ разберёт ингредиенты, шаги и посчитает КБЖУ на порцию.
      </div>
    </div>
  )
}

function TextImport({ onOpen }: { onOpen: (id: string) => void }) {
  const close = useSheetClose()
  const toast = useToast()
  const [text, setText] = useState('')
  return (
    <div className="col gap-14">
      <div className="h1">Текст рецепта</div>
      <div className="small" style={{ fontSize: 14 }}>Вставь рецепт из заметок, чата или описания под видео — ИИ приведёт его в порядок и посчитает КБЖУ.</div>
      <textarea className="field" value={text} onChange={(e) => setText(e.target.value)} style={{ minHeight: 180 }}
        placeholder={'Например:\nСырники: творог 400 г, яйцо 1, мука 3 ст. л…'} />
      <button className="btn btn-brand full" disabled={text.trim().length < 20} onClick={() => {
        importFromText(text.trim(), (id) => toast('Рецепт готов', { label: 'Открыть', run: () => onOpen(id) }))
        close()
        toast('Разбираю рецепт')
      }}>Разобрать</button>
    </div>
  )
}

export function plural(n: number, one: string, few: string, many: string) {
  const m10 = n % 10, m100 = n % 100
  if (m10 === 1 && m100 !== 11) return one
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few
  return many
}
