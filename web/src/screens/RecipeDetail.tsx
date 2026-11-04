// Карточка рецепта: обложка, главное в плашках, порции с пересчётом, ингредиенты
// с эмодзи (отмечаются тапом), шаги, советы. Снизу — «Готовить» (пошаговый
// режим с таймером, экран не гаснет) и «В дневник».
import { useEffect, useRef, useState } from 'react'
import { deleteRecipe, getRecipe, putRecipe, type Recipe, type RecipeIngredient } from '../data/db'
import { CATEGORY_LABEL, DIFFICULTY_LABEL, formatAmount, ingredientAmount, logRecipe, shoppingText } from '../data/recipes'
import { Sheet, useData, useSheetClose, useToast } from '../ui/kit'
import { IcCheck, IcChef, IcChevronL, IcClock, IcClose, IcFlame, IcHeart, IcLink, IcMinus, IcPause, IcPlay, IcPlus, IcShare, IcTrash, IcUsers } from '../ui/icons'
import { Cover } from './Recipes'

const SOURCE_LABEL: Record<Recipe['source']['kind'], string> = {
  youtube: 'Открыть на YouTube', tiktok: 'Открыть в TikTok', instagram: 'Открыть в Instagram', web: 'Открыть источник',
  video: '', photo: '', text: ''
}
/** Мягкий фон под эмодзи ингредиента — стабильный цвет по названию. */
const TINTS = ['var(--tertiary)', 'var(--primary)', 'var(--warn-from)', 'var(--secondary)', 'var(--protein)', 'var(--carbs)', 'var(--fat)', 'var(--water)']
const tintOf = (s: string) => TINTS[[...s].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7) % TINTS.length]

export function RecipeDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const toast = useToast()
  const r = useData(() => getRecipe(id), [id], null as Recipe | null | undefined)
  const [servings, setServings] = useState<number | null>(null)
  const [have, setHave] = useState<Set<number>>(new Set())
  const [mode, setMode] = useState<'cook' | 'log' | 'delete' | null>(null)
  if (r === undefined) return <div className="screen detail"><button className="icon-btn" onClick={onBack}><IcChevronL size={20} /></button><div className="small">Рецепт не найден</div></div>
  if (!r) return <div className="screen detail" />
  const n = servings ?? r.servings
  const factor = n / r.servings
  const groups = [...new Set(r.ingredients.map((i) => i.group))]

  const share = async () => {
    const text = shoppingText(r, n)
    try {
      if (navigator.share) await navigator.share({ title: r.title, text })
      else { await navigator.clipboard.writeText(text); toast('Список скопирован') }
    } catch { /* отменили */ }
  }

  // Нижняя панель и оверлеи — вне прокручиваемого экрана: у него анимация
  // transform, и position: fixed внутри привязался бы к нему, а не к окну.
  return (
    <>
    <div className="screen detail screen-push" style={{ padding: 0 }}>
      <div style={{ position: 'relative' }}>
        <Cover r={r} height="min(46vh, 340px)" emojiSize={110} />
        <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, rgba(0,0,0,.35), transparent 30%, transparent 62%, var(--bg))', pointerEvents: 'none' }} />
        <div className="row" style={{ position: 'absolute', top: 'calc(var(--safe-top) + 10px)', left: 16, right: 16, gap: 10 }}>
          <GlassBtn label="Назад" onClick={onBack}><IcChevronL size={21} /></GlassBtn>
          <span className="grow" />
          <GlassBtn label="Список покупок" onClick={share}><IcShare size={19} /></GlassBtn>
          <GlassBtn label={r.favorite ? 'Убрать из избранного' : 'В избранное'} onClick={() => putRecipe({ ...r, favorite: !r.favorite })}>
            <IcHeart size={20} fill={r.favorite ? '#FF5E7E' : 'none'} stroke={r.favorite ? '#FF5E7E' : 'currentColor'} />
          </GlassBtn>
        </div>
      </div>

      <div className="col gap-18 stagger" style={{ padding: '0 20px calc(var(--safe-bottom) + 110px)', marginTop: -28, position: 'relative' }}>
        <div className="col gap-8">
          <div className="row gap-6" style={{ flexWrap: 'wrap' }}>
            <span className="chip primary" style={{ height: 28, fontSize: 12 }}>{CATEGORY_LABEL[r.category]}</span>
            {r.tags.map((t) => <span key={t} className="chip" style={{ height: 28, fontSize: 12 }}>{t}</span>)}
          </div>
          <div className="display" style={{ fontSize: 30, lineHeight: 1.12 }}>{r.title}</div>
          {r.description && <div className="small" style={{ fontSize: 15, lineHeight: 1.45 }}>{r.description}</div>}
          {r.source.url && SOURCE_LABEL[r.source.kind] && (
            <a className="chip pressable" href={r.source.url} target="_blank" rel="noreferrer" style={{ alignSelf: 'flex-start', textDecoration: 'none', height: 34, fontSize: 13 }}>
              <IcLink size={15} />{SOURCE_LABEL[r.source.kind]}{r.source.author ? ` · ${r.source.author}` : ''}
            </a>
          )}
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
          <Stat icon={<IcClock size={18} />} v={r.totalMinutes ? `${r.totalMinutes} мин` : '—'} l="время" />
          <Stat icon={<IcFlame size={18} />} v={`${r.nutrition.calories}`} l="ккал / порц." />
          <Stat icon={<IcChef size={18} />} v={DIFFICULTY_LABEL[r.difficulty]} l="сложность" />
        </div>

        <div className="card pad-16 col gap-12">
          <div className="row between">
            <div className="title">На одну порцию</div>
            <span className="label num">{r.nutrition.calories} ккал</span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8 }}>
            <Macro label="Белки" v={r.nutrition.protein} color="var(--protein)" />
            <Macro label="Жиры" v={r.nutrition.fat} color="var(--fat)" />
            <Macro label="Углеводы" v={r.nutrition.carbs} color="var(--carbs)" />
          </div>
        </div>

        <div className="col gap-12">
          <div className="row between">
            <div className="h2">Ингредиенты</div>
            <Stepper value={n} onChange={(v) => setServings(v)} icon={<IcUsers size={16} />} />
          </div>
          {have.size > 0 && <div className="small">Отмечено {have.size} из {r.ingredients.length} — нажми ещё раз, чтобы снять</div>}
          {groups.map((g) => (
            <div key={g || '_'} className="col gap-8">
              {g && <div className="overline" style={{ marginTop: 4 }}>{g}</div>}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                {r.ingredients.map((ing, idx) => ing.group !== g ? null : (
                  <IngredientTile key={idx} ing={ing} amount={ingredientAmount(ing, factor)} checked={have.has(idx)}
                    onToggle={() => setHave((s) => { const x = new Set(s); if (x.has(idx)) x.delete(idx); else x.add(idx); return x })} />
                ))}
              </div>
            </div>
          ))}
        </div>

        <div className="col gap-12">
          <div className="h2">Приготовление</div>
          {r.steps.map((s, i) => (
            <div key={i} className="row gap-14" style={{ alignItems: 'flex-start' }}>
              <span className="num" style={{ width: 32, height: 32, borderRadius: 16, flex: 'none', display: 'grid', placeItems: 'center', fontWeight: 800, fontSize: 14,
                background: 'linear-gradient(135deg, var(--brand-from), var(--brand-to))', color: 'var(--on-brand)' }}>{i + 1}</span>
              <div className="col gap-6 grow" style={{ paddingTop: 4 }}>
                <div style={{ fontSize: 16, lineHeight: 1.5 }}>{s.text}</div>
                {s.minutes ? <span className="label row" style={{ gap: 4, color: 'var(--primary)' }}><IcClock size={13} />{s.minutes} мин</span> : null}
              </div>
            </div>
          ))}
        </div>

        {r.tips.length > 0 && (
          <div className="card pad-16 col gap-8" style={{ background: 'color-mix(in srgb, var(--warn-from) 12%, var(--surface))' }}>
            <div className="title">💡 Советы</div>
            {r.tips.map((t, i) => <div key={i} className="small" style={{ fontSize: 14, lineHeight: 1.45 }}>{t}</div>)}
          </div>
        )}

        <div className="row gap-8" style={{ justifyContent: 'center' }}>
          {r.cookedCount > 0 && <span className="small">Готовил {r.cookedCount} {r.cookedCount === 1 ? 'раз' : r.cookedCount < 5 ? 'раза' : 'раз'}</span>}
          <button className="btn" style={{ height: 40, color: 'var(--text-3)', fontSize: 14 }} onClick={() => setMode('delete')}><IcTrash size={16} />Удалить рецепт</button>
        </div>
      </div>
    </div>

      <div className="row gap-10" style={{ position: 'fixed', left: 0, right: 0, bottom: 0, zIndex: 20, padding: '12px 20px calc(var(--safe-bottom) + 12px)',
        background: 'var(--tab-bg)', backdropFilter: 'saturate(160%) blur(18px)', WebkitBackdropFilter: 'saturate(160%) blur(18px)', borderTop: '1px solid var(--hairline)' }}>
        <button className="btn btn-soft grow" onClick={() => setMode('log')}><IcPlus size={18} />В дневник</button>
        <button className="btn btn-brand grow" onClick={() => setMode('cook')}><IcPlay size={17} />Готовить</button>
      </div>

      {mode === 'cook' && <CookMode r={r} factor={factor} onClose={() => setMode(null)} onFinish={() => setMode('log')} />}
      {mode === 'log' && <Sheet label="Записать в дневник" onClose={() => setMode(null)}><LogForm r={r} /></Sheet>}
      {mode === 'delete' && (
        <Sheet label="Удалить рецепт" onClose={() => setMode(null)}>
          <DeleteForm r={r} onDeleted={() => { onBack(); toast('Рецепт удалён', { label: 'Вернуть', run: () => putRecipe(r) }) }} />
        </Sheet>
      )}
    </>
  )
}

const GlassBtn = ({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) => (
  <button aria-label={label} onClick={onClick} className="pressable"
    style={{ width: 44, height: 44, borderRadius: 22, display: 'grid', placeItems: 'center', color: '#fff',
      background: 'rgba(0,0,0,.32)', backdropFilter: 'blur(12px)', WebkitBackdropFilter: 'blur(12px)' }}>{children}</button>
)

const Stat = ({ icon, v, l }: { icon: React.ReactNode; v: string; l: string }) => (
  <div className="card r22 col" style={{ padding: '12px 10px', gap: 4, alignItems: 'center', textAlign: 'center' }}>
    <span style={{ color: 'var(--primary)' }}>{icon}</span>
    <span className="title num" style={{ fontSize: 16 }}>{v}</span>
    <span className="label">{l}</span>
  </div>
)

const Macro = ({ label, v, color }: { label: string; v: number; color: string }) => (
  <div className="col" style={{ padding: '10px 0', borderRadius: 16, alignItems: 'center', background: `color-mix(in srgb, ${color} 14%, transparent)` }}>
    <span className="h2 num" style={{ color }}>{v} г</span>
    <span className="label">{label}</span>
  </div>
)

function Stepper({ value, onChange, icon, step = 1, min = 1 }: { value: number; onChange: (v: number) => void; icon?: React.ReactNode; step?: number; min?: number }) {
  return (
    <div className="row" style={{ background: 'var(--surface-high)', borderRadius: 999, padding: 3, gap: 2 }}>
      <button className="icon-btn" aria-label="Меньше" disabled={value <= min} onClick={() => onChange(Math.max(min, value - step))} style={{ width: 34, height: 34 }}><IcMinus size={16} /></button>
      <span className="row num" style={{ gap: 5, minWidth: 46, justifyContent: 'center', fontWeight: 800 }}>{icon}{formatAmount(value, 'шт')}</span>
      <button className="icon-btn" aria-label="Больше" disabled={value >= 30} onClick={() => onChange(Math.min(30, value + step))} style={{ width: 34, height: 34 }}><IcPlus size={16} /></button>
    </div>
  )
}

function IngredientTile({ ing, amount, checked, onToggle }: { ing: RecipeIngredient; amount: string; checked: boolean; onToggle: () => void }) {
  const tint = tintOf(ing.name)
  return (
    <button className="card r22 row gap-10 pressable" onClick={onToggle} aria-pressed={checked}
      style={{ padding: 10, textAlign: 'left', opacity: checked ? 0.55 : 1, transition: 'opacity var(--t-med)' }}>
      <span style={{ width: 44, height: 44, borderRadius: 14, flex: 'none', display: 'grid', placeItems: 'center', fontSize: 25, position: 'relative',
        background: `color-mix(in srgb, ${tint} 18%, var(--surface))` }}>
        {ing.emoji}
        {checked && <span style={{ position: 'absolute', right: -4, bottom: -4, width: 20, height: 20, borderRadius: 10, display: 'grid', placeItems: 'center', background: 'var(--primary)', color: 'var(--on-brand)' }}><IcCheck size={12} strokeWidth={3} /></span>}
      </span>
      <span className="col grow" style={{ minWidth: 0 }}>
        <span style={{ fontWeight: 700, fontSize: 14, lineHeight: 1.25, textDecoration: checked ? 'line-through' : 'none' }}>{ing.name}</span>
        <span className="label num" style={{ color: 'var(--text-2)' }}>{amount}{ing.note ? ` · ${ing.note}` : ''}</span>
      </span>
    </button>
  )
}

/** Пошаговый режим: крупный шрифт, таймер шага, экран не гаснет. */
function CookMode({ r, factor, onClose, onFinish }: { r: Recipe; factor: number; onClose: () => void; onFinish: () => void }) {
  const [i, setI] = useState(0)
  const [left, setLeft] = useState<number | null>(null)
  const [running, setRunning] = useState(false)
  const toast = useToast()
  const step = r.steps[i]
  const last = i === r.steps.length - 1

  useEffect(() => {
    let lock: { release: () => Promise<void> } | null = null
    const nav = navigator as Navigator & { wakeLock?: { request: (t: 'screen') => Promise<{ release: () => Promise<void> }> } }
    nav.wakeLock?.request('screen').then((l) => { lock = l }).catch(() => {})
    return () => { lock?.release().catch(() => {}) }
  }, [])
  useEffect(() => { setLeft(step.minutes ? step.minutes * 60 : null); setRunning(false) }, [i, step.minutes])
  const tick = useRef<number>()
  useEffect(() => {
    if (!running) return
    tick.current = window.setInterval(() => setLeft((s) => {
      if (s == null) return s
      if (s <= 1) { setRunning(false); navigator.vibrate?.([300, 120, 300]); toast(`Шаг ${i + 1}: время вышло`); return 0 }
      return s - 1
    }), 1000)
    return () => clearInterval(tick.current)
  }, [running, i, toast])

  // Ингредиенты, упомянутые в шаге, — подсказка «что взять».
  const used = r.ingredients.filter((g) => step.text.toLowerCase().includes(g.name.toLowerCase().split(' ')[0].slice(0, 5)))
  const mm = left != null ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}` : ''

  return (
    <div className="col" style={{ position: 'fixed', inset: 0, zIndex: 40, background: 'var(--bg)', padding: 'calc(var(--safe-top) + 12px) 20px calc(var(--safe-bottom) + 16px)', animation: 'rise 300ms var(--ease-emph) both' }}>
      <div className="row gap-12">
        <button className="icon-btn" aria-label="Закрыть" onClick={onClose}><IcClose size={20} /></button>
        <div className="grow">
          <div className="label">Шаг {i + 1} из {r.steps.length}</div>
          <div className="title ellipsis">{r.title}</div>
        </div>
      </div>
      <div className="row gap-4" style={{ marginTop: 14 }}>
        {r.steps.map((_, k) => <span key={k} className="grow" style={{ height: 4, borderRadius: 2, background: k <= i ? 'var(--primary)' : 'var(--hairline)', transition: 'background 300ms' }} />)}
      </div>
      <div className="col grow" style={{ justifyContent: 'center', gap: 22, overflowY: 'auto' }} key={i}>
        <div style={{ fontSize: 26, lineHeight: 1.4, fontWeight: 700, animation: 'rise 320ms var(--ease-emph) both' }}>{step.text}</div>
        {used.length > 0 && (
          <div className="row gap-8" style={{ flexWrap: 'wrap' }}>
            {used.map((g, k) => <span key={k} className="chip" style={{ height: 36 }}>{g.emoji} {g.name} · {ingredientAmount(g, factor)}</span>)}
          </div>
        )}
        {left != null && (
          <button className="card pad-16 row gap-14 pressable" onClick={() => setRunning((x) => !x)} style={{ alignSelf: 'flex-start' }}>
            <span className="badge" style={{ width: 46, height: 46, borderRadius: 23, background: running ? 'var(--tertiary)' : 'var(--primary)', color: 'var(--on-brand)' }}>
              {running ? <IcPause size={20} /> : <IcPlay size={20} />}
            </span>
            <span className="col">
              <span className="h1 num">{mm}</span>
              <span className="label">{running ? 'идёт таймер' : left === 0 ? 'готово' : 'запустить таймер'}</span>
            </span>
          </button>
        )}
      </div>
      <div className="row gap-10">
        <button className="btn btn-soft" style={{ width: 110 }} disabled={i === 0} onClick={() => setI(i - 1)}>Назад</button>
        {last
          ? <button className="btn btn-brand grow" onClick={() => { onClose(); onFinish() }}><IcCheck size={18} />Готово — в дневник</button>
          : <button className="btn btn-brand grow" onClick={() => setI(i + 1)}>Дальше</button>}
      </div>
    </div>
  )
}

function LogForm({ r }: { r: Recipe }) {
  const close = useSheetClose()
  const toast = useToast()
  const [portions, setPortions] = useState(1)
  return (
    <div className="col gap-16">
      <div className="h1">Сколько съел?</div>
      <div className="row between card pad-16">
        <div className="col">
          <span className="title">{r.emoji} Порции</span>
          <span className="small num">{Math.round(r.nutrition.calories * portions)} ккал · Б {Math.round(r.nutrition.protein * portions)} Ж {Math.round(r.nutrition.fat * portions)} У {Math.round(r.nutrition.carbs * portions)}</span>
        </div>
        <Stepper value={portions} onChange={setPortions} step={0.5} min={0.5} />
      </div>
      <button className="btn btn-brand full" onClick={async () => {
        await logRecipe(r, portions)
        close()
        toast(`Записано: ${Math.round(r.nutrition.calories * portions)} ккал`)
      }}>Записать в «Сегодня»</button>
    </div>
  )
}

function DeleteForm({ r, onDeleted }: { r: Recipe; onDeleted: () => void }) {
  const close = useSheetClose()
  return (
    <div className="col gap-14">
      <div className="h1">Удалить «{r.title}»?</div>
      <div className="small" style={{ fontSize: 14 }}>Записи в дневнике останутся.</div>
      <button className="btn btn-danger full" onClick={async () => { await deleteRecipe(r.id); close(); setTimeout(onDeleted, 200) }}>Удалить</button>
      <button className="btn btn-soft full" onClick={close}>Отмена</button>
    </div>
  )
}
