// Недельный чек-ин режима «Сжигание жира»: 3 коротких шага (вес, замер,
// самочувствие) → итог недели и новый план. Единственное место, где план
// меняется по неделе, поэтому итог объясняет каждое изменение.
import { useEffect, useRef, useState } from 'react'
import { type BodyMeasurement, getSettings, measurementOn, setWeight, weightsBetween } from '../data/db'
import { type CheckIn, type PlanMacros, type Ratings, type Signal } from '../data/checkin'
import { completeCheckin } from '../data/plan'
import { type ScoreLevel, SCORE } from '../data/dayscore'
import { type FatPlan, addIsoDays, plural } from '../data/fatplan'
import { fmt1 } from '../data/body'
import { iso, parseIso } from '../data/insights'
import { MeasureForm } from './Body'
import { Badge, Sheet, useData, useSheetClose, useToast } from '../ui/kit'
import { IcCalendar, IcChevronR, IcSpark } from '../ui/icons'

export const SIGNAL_COLOR: Record<Signal, string> = { green: '#1FA463', yellow: 'var(--gold)', red: 'var(--error)' }
/** Цвета оценки дня: хорошо — зелёный, средне — золотой, слабо — мягкий оранжевый, сегодня — цвет режима. */
export const LEVEL_COLOR: Record<ScoreLevel, string> = {
  good: '#1FA463', medium: 'var(--gold)', low: 'var(--warn-to)', pending: 'var(--primary)', none: 'var(--surface-high)'
}
const SIGNAL_TITLE: Record<Signal, string> = { green: 'Мышцы под защитой', yellow: 'Мышцам — внимание', red: 'Мышцы под угрозой' }

const M_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']
export const dayMonth = (s: string) => { const d = parseIso(s); return `${d.getDate()} ${M_GEN[d.getMonth()]}` }

/** Карточка на «Сегодня», когда чек-ин пора сделать. */
export function CheckinCard({ onOpen, overdue }: { onOpen: () => void; overdue: number }) {
  return (
    <button className="card pad-16 pressable row gap-14" onClick={onOpen}
      style={{ textAlign: 'left', border: '1.5px solid color-mix(in srgb, var(--primary) 50%, transparent)', background: 'color-mix(in srgb, var(--primary) 12%, var(--surface))' }}>
      <span className="badge" style={{ width: 44, height: 44, borderRadius: 14, background: 'var(--primary)', color: 'var(--on-brand)' }}><IcCalendar size={22} /></span>
      <span className="col grow">
        <span className="title">Пора подвести неделю · 2 минуты</span>
        <span className="small">
          {overdue > 0 ? 'Сделай сейчас — посчитаю за всё время с прошлого раза' : 'Вес, замер, самочувствие — и план на следующую неделю'}
        </span>
      </span>
      <span className="muted"><IcChevronR size={18} /></span>
    </button>
  )
}

type Step = 'weight' | 'measure' | 'feel' | 'result'

export function CheckinSheet({ plan, onClose }: { plan: FatPlan; onClose: () => void }) {
  // План «до» — снимок на момент открытия: замер внутри чек-ина может его пересчитать.
  const before = useRef(plan).current
  return (
    <Sheet label="Недельный чек-ин" onClose={onClose} keyboardAware>
      <CheckinFlow before={before} />
    </Sheet>
  )
}

function CheckinFlow({ before }: { before: FatPlan }) {
  const today = iso(new Date())
  const data = useData(async () => ({
    s: await getSettings(),
    w: (await weightsBetween(today, today))[0]?.weight ?? null,
    m: (await measurementOn(today)) ?? null
  }), [], null)
  const [step, setStep] = useState<Step>('weight')
  const [weight, setW] = useState<number | null>(null)
  const [result, setResult] = useState<CheckIn | null>(null)
  const top = useRef<HTMLDivElement>(null)
  // Новый шаг — с начала листа, а не с середины прошлого.
  useEffect(() => { top.current?.scrollIntoView({ block: 'start' }) }, [step])
  if (!data) return <div style={{ height: 300 }} />
  const n = step === 'weight' ? 1 : step === 'measure' ? 2 : 3
  return (
    <div className="col gap-14" ref={top} style={{ scrollMarginTop: 40 }}>
      {step !== 'result' && <Steps n={n} />}
      {step === 'weight' && <WeightStep initial={data.w} onNext={(kg) => { setW(kg); setStep('measure') }} />}
      {step === 'measure' && (
        <MeasureStep s={data.s} weight={weight} today={data.m} onNext={() => setStep('feel')} />
      )}
      {step === 'feel' && <FeelStep before={before} onDone={(c) => { setResult(c); setStep('result') }} />}
      {step === 'result' && result && <CheckinResult c={result} />}
    </div>
  )
}

function Steps({ n }: { n: number }) {
  return (
    <div className="row gap-10">
      <span className="overline">Шаг {n} из 3</span>
      <span className="row grow" style={{ gap: 4 }} aria-hidden="true">
        {[1, 2, 3].map((i) => (
          <span key={i} style={{ flex: 1, height: 4, borderRadius: 2, background: i <= n ? 'var(--primary)' : 'var(--surface-high)', transition: 'background 280ms' }} />
        ))}
      </span>
    </div>
  )
}

function WeightStep({ initial, onNext }: { initial: number | null; onNext: (kg: number) => void }) {
  const [v, setV] = useState(initial != null ? String(initial).replace('.', ',') : '')
  const kg = parseFloat(v.replace(',', '.'))
  const ok = Number.isFinite(kg) && kg >= 20 && kg <= 400
  const next = async () => {
    if (!ok) return
    const r = Math.round(kg * 10) / 10
    if (r !== initial) await setWeight(iso(new Date()), r)
    onNext(r)
  }
  return (
    // form — чтобы «Далее» на клавиатуре iPhone сразу переходил к следующему шагу.
    <form className="col gap-14" onSubmit={(e) => { e.preventDefault(); void next() }}>
      <div>
        <div className="h1">Вес сегодня</div>
        <div className="small">{initial != null ? 'Уже записан сегодня — поправь, если нужно' : 'Лучше утром, после туалета и до еды'}</div>
      </div>
      <div>
        <label className="field-label" htmlFor="ci-kg">Вес, кг</label>
        <input id="ci-kg" className="field num" inputMode="decimal" enterKeyHint="next" autoFocus={initial == null}
          value={v} onChange={(e) => setV(e.target.value)} placeholder="72,4" style={{ fontSize: 22, fontWeight: 800 }} />
      </div>
      <button type="submit" className="btn btn-brand full" disabled={!ok}>Дальше</button>
    </form>
  )
}

function MeasureStep({ s, weight, today, onNext }: {
  s: Awaited<ReturnType<typeof getSettings>>; weight: number | null; today: BodyMeasurement | null; onNext: () => void
}) {
  return (
    <MeasureForm s={s} weight={weight} today={today} onSaved={onNext} title="Замер лентой"
      extra={<button className="btn btn-soft full" onClick={onNext}>{today ? 'Оставить сегодняшний замер' : 'Замерю позже'}</button>} />
  )
}

const RATINGS: [keyof Ratings, string, string, string][] = [
  ['hunger', 'Голод', 'почти не голоден', 'голоден постоянно'],
  ['energy', 'Энергия', 'без сил', 'полно сил'],
  ['sleep', 'Сон', 'плохо', 'отлично']
]

function FeelStep({ before, onDone }: { before: FatPlan; onDone: (c: CheckIn) => void }) {
  const toast = useToast()
  const [r, setR] = useState<Ratings>({ hunger: null, energy: null, sleep: null })
  const [busy, setBusy] = useState(false)
  const done = async () => {
    setBusy(true)
    try {
      const c = await completeCheckin(r, before)
      if (c) onDone(c)
      else toast('Не получилось: нужен вес и включённый режим')
    } catch (e) {
      toast((e as Error).message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="col gap-16">
      <div>
        <div className="h1">Как прошла неделя</div>
        <div className="small">Оцени от 1 до 5 — можно пропустить</div>
      </div>
      {RATINGS.map(([key, title, lo, hi]) => (
        <div key={key} className="col gap-8" role="radiogroup" aria-label={title}>
          <div className="row between">
            <span className="title">{title}</span>
            {r[key] != null && <button className="label" style={{ padding: '4px 0' }} onClick={() => setR({ ...r, [key]: null })}>сбросить</button>}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 8 }}>
            {[1, 2, 3, 4, 5].map((v) => {
              const sel = r[key] === v
              return (
                <button key={v} role="radio" aria-checked={sel} className="pressable num" onClick={() => setR({ ...r, [key]: v })}
                  style={{
                    height: 48, borderRadius: 14, fontSize: 17, fontWeight: 800,
                    background: sel ? 'var(--primary)' : 'var(--surface-high)', color: sel ? 'var(--on-brand)' : 'var(--text)',
                    transition: 'background 200ms'
                  }}>{v}</button>
              )
            })}
          </div>
          <div className="row between label"><span>1 — {lo}</span><span>5 — {hi}</span></div>
        </div>
      ))}
      <button className="btn btn-brand full" disabled={busy} onClick={done}>
        {busy ? <span className="spinner" /> : <><IcSpark size={19} />Подвести итог</>}
      </button>
    </div>
  )
}

const signed = (v: number, unit: string, digits = 1) =>
  `${v > 0 ? '+' : v < 0 ? '−' : ''}${Math.abs(v).toFixed(digits).replace('.', ',')}${unit}`

/** Итог недели: изменения, дневник, сигнал мышц, новый план и одно действие. */
export function CheckinResult({ c, onDone }: { c: CheckIn; onDone?: () => void }) {
  const close = useSheetClose()
  const end = addIsoDays(c.date, -1)
  const n = c.periodDays
  return (
    <div className="col gap-14">
      <div>
        <div className="h1">Итог недели</div>
        <div className="small">{dayMonth(c.periodStart)} — {dayMonth(end)}{n !== 7 ? ` · ${n} ${plural(n, 'день', 'дня', 'дней')}` : ''}</div>
      </div>

      <div className="card pad-16 col gap-6" style={{ background: 'var(--surface-low)' }}>
        <div className="label">Тренд веса</div>
        <div className="row gap-10" style={{ alignItems: 'baseline', flexWrap: 'wrap' }}>
          <span className="display num">{fmt1(c.trendWeight)} кг</span>
          {c.trendChangeKg != null && (
            <span className="chip num" style={{ height: 30, fontSize: 13, color: c.trendChangeKg > 0 ? 'var(--tertiary)' : 'var(--primary)', background: `color-mix(in srgb, ${c.trendChangeKg > 0 ? 'var(--tertiary)' : 'var(--primary)'} 13%, transparent)` }}>
              {signed(c.trendChangeKg, ' кг')}
            </span>
          )}
        </div>
        <div className="small num">
          {c.ratePerWeek != null ? `${signed(-c.ratePerWeek, '%')} веса в неделю` : 'мало взвешиваний для темпа'} · на весах {fmt1(c.weight)} кг
        </div>
        {c.fatPercent != null && (
          <div className="small num">
            Жир {fmt1(c.fatPercent)}%{c.fatChange != null ? ` · ${signed(c.fatChange, '%')} с прошлого замера` : ''}
          </div>
        )}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
        {c.avgScore != null && (
          <div className="row gap-12" style={{ gridColumn: '1 / -1', padding: '10px 12px', borderRadius: 16, background: 'var(--surface-low)', border: '1px solid var(--hairline)' }}>
            <span className="h2 num" style={{ color: LEVEL_COLOR[c.avgScore >= SCORE.good ? 'good' : c.avgScore >= SCORE.medium ? 'medium' : 'low'] }}>{c.avgScore}</span>
            <span className="label grow">средняя оценка дня из 100</span>
          </div>
        )}
        <Mini v={`${c.diaryDays} из ${n}`} l="дней в дневнике" ok={c.diaryDays >= Math.ceil(5 * n / 7)} />
        <Mini v={`${c.goodDays} из ${n}`} l="хороших дней" ok={c.goodDays >= Math.ceil(4 * n / 7)} />
        <Mini v={`${c.workouts} из ${c.workoutGoal}`} l="силовых" ok={c.workouts >= c.workoutGoal} />
        <Mini v={`${c.proteinDays} из ${n}`} l="дней с белком" ok={c.proteinDays >= Math.ceil(5 * n / 7)} />
      </div>
      <div className="label">
        {c.avgScore != null
          ? `Хороший день — оценка от ${SCORE.good}: калории, белок, движение и сон. Дни без отметок шагов и сна — по калориям и белку.`
          : 'Хороший день — калории от −10% до +5% плана и белок от 90% нормы.'}
      </div>

      <div className="row gap-12" style={{ padding: 14, borderRadius: 18, alignItems: 'flex-start', background: `color-mix(in srgb, ${SIGNAL_COLOR[c.signal]} 12%, transparent)` }}>
        <span style={{ width: 14, height: 14, borderRadius: 7, background: SIGNAL_COLOR[c.signal], flex: 'none', marginTop: 3 }} aria-hidden="true" />
        <span className="col gap-4">
          <span className="title">{SIGNAL_TITLE[c.signal]}</span>
          <span className="small" style={{ color: 'var(--text)' }}>{c.signalText}</span>
        </span>
      </div>

      <div className="card pad-16 col gap-10">
        <div className="title">План на неделю</div>
        <PlanDiff before={c.planBefore} after={c.planAfter} />
        <div className="row gap-10 small" style={{ padding: 12, borderRadius: 16, background: 'color-mix(in srgb, var(--primary) 10%, transparent)', alignItems: 'flex-start' }}>
          <span style={{ color: 'var(--primary)', marginTop: 1 }}><IcSpark size={17} /></span>
          <span style={{ color: 'var(--text)' }}>{c.note}</span>
        </div>
      </div>

      <div className="col gap-4" style={{ padding: '4px 4px 0' }}>
        <div className="overline">Задача на неделю</div>
        <div style={{ fontWeight: 700, fontSize: 15.5 }}>{c.action}</div>
      </div>

      <button className="btn btn-brand full" onClick={onDone ?? close}>Готово</button>
    </div>
  )
}

const Mini = ({ v, l, ok }: { v: string; l: string; ok: boolean }) => (
  <div className="col" style={{ padding: '10px 12px', borderRadius: 16, background: 'var(--surface-low)', border: '1px solid var(--hairline)', minWidth: 0 }}>
    <span className="h2 num" style={{ color: ok ? 'var(--text)' : 'var(--warn-to)', whiteSpace: 'nowrap' }}>{v}</span>
    <span className="label">{l}</span>
  </div>
)

function PlanDiff({ before, after }: { before: PlanMacros | null; after: PlanMacros }) {
  const rows: [string, keyof PlanMacros, string, string][] = [
    ['Калории', 'calories', '', 'var(--text)'],
    ['Белок', 'protein', ' г', 'var(--protein)'],
    ['Жиры', 'fat', ' г', 'var(--fat)'],
    ['Углеводы', 'carbs', ' г', 'var(--carbs)']
  ]
  return (
    <div className="col" style={{ gap: 6 }}>
      {rows.map(([l, k, u, color]) => {
        const b = before?.[k], a = after[k]
        const changed = b != null && b !== a
        return (
          <div key={k} className="row between num" style={{ fontSize: k === 'calories' ? 16 : 14, fontWeight: 700 }}>
            <span style={{ color }}>{l}</span>
            <span>
              {changed && <span className="muted" style={{ fontWeight: 600 }}>{b}{u} → </span>}
              <span>{a}{u}{k === 'calories' ? ' ккал' : ''}</span>
              {changed && <span style={{ color: a > b! ? 'var(--primary)' : 'var(--tertiary)', marginLeft: 6, fontSize: 12.5 }}>{signed(a - b!, '', 0)}</span>}
            </span>
          </div>
        )
      })}
    </div>
  )
}

/** «Прогресс» → история чек-инов. Нажатие — полный итог того чек-ина. */
export function CheckinHistory({ list, onOpen }: { list: CheckIn[]; onOpen: (c: CheckIn) => void }) {
  if (!list.length) return null
  const rows = [...list].reverse().slice(0, 12)
  return (
    <div className="card pad-18 col gap-10">
      <div className="row gap-12">
        <Badge tint="var(--primary)" size={40}><IcCalendar size={20} /></Badge>
        <div className="grow title">Чек-ины</div>
        <span className="label num">{list.length}</span>
      </div>
      <div className="col">
        {rows.map((c, i) => (
          <button key={c.date} className="row gap-10 pressable" onClick={() => onOpen(c)}
            style={{ minHeight: 52, padding: '6px 0', textAlign: 'left', borderTop: i ? '1px solid var(--hairline)' : 'none' }}
            aria-label={`Чек-ин ${dayMonth(c.date)}: тренд ${fmt1(c.trendWeight)} кг, ${c.planAfter.calories} ккал`}>
            <span style={{ width: 10, height: 10, borderRadius: 5, background: SIGNAL_COLOR[c.signal], flex: 'none' }} />
            <span className="col grow" style={{ minWidth: 0 }}>
              <span style={{ fontWeight: 700, fontSize: 14.5 }}>{dayMonth(c.date)}</span>
              <span className="label num">{fmt1(c.trendWeight)} кг{c.fatPercent != null ? ` · ${fmt1(c.fatPercent)}%` : ''}</span>
            </span>
            <span className="num" style={{ fontWeight: 800, fontSize: 14.5, whiteSpace: 'nowrap' }}>
              {c.planAfter.calories}<span className="label"> ккал</span>
            </span>
            <span className="muted"><IcChevronR size={16} /></span>
          </button>
        ))}
      </div>
    </div>
  )
}

export function CheckinResultSheet({ c, onClose }: { c: CheckIn; onClose: () => void }) {
  return <Sheet label="Итог чек-ина" onClose={onClose}><CheckinResult c={c} /></Sheet>
}
