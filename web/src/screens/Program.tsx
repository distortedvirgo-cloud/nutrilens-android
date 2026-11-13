// Программа режима «Сжигание жира» (3.24): предупреждение и мастер запуска,
// путь из этапов (таймлайн), карточки «Сегодня» — разовое уведомление и итог
// завершённой программы. Логика этапов — в data/program.ts, здесь только экраны.
import { useState } from 'react'
import { type Settings, allMealDates, checkins, getSettings, latestMeasurement, latestWeight, measurementOn, saveSettings, setWeight, weightsBetween } from '../data/db'
import { bodyProfile, defaultFatTarget, fmt1, plural } from '../data/body'
import { type FatPlan, daysBetween } from '../data/fatplan'
import { CHECKIN, WEEKDAYS_SHORT, trendSeries, weekdayAcc } from '../data/checkin'
import { SCORE, clampStepGoal, fmtInt } from '../data/dayscore'
import {
  type Program, PROGRAM, activeDays, blockCount, currentPhase, forecast, phaseName, programSummary, targetFloor
} from '../data/program'
import { endProgram, previewProgram, startProgram, trendFatNow, updateProgram } from '../data/plan'
import { iso } from '../data/insights'
import { MeasureForm, TargetStepper } from './Body'
import { dayMonth } from './Checkin'
import { Segmented, Sheet, useData, useSheetClose, useToast } from '../ui/kit'
import { IcCheck, IcInfo, IcMinus, IcPlus, IcSpark, IcTrophy } from '../ui/icons'

/** Цвета этапов на пути: блоки — оранжевые, перерывы — зелёные, удержание — серое. */
export const PHASE_COLOR = { block: '#F07A2A', break: '#1FA463', maintenance: 'var(--text-3)' } as const

const weeksWord = (n: number) => `${n} ${plural(n, 'неделя', 'недели', 'недель')}`
const weeksShort = (n: number) => `${n} нед.`

// ---------- Ритм: день чек-ина, силовые, шаги ----------

/** День чек-ина и цель по силовым (и шаги — в мастере запуска). Меняется сразу, без «Сохранить». */
export function RhythmSettings({ s, steps }: { s: Settings; steps?: boolean }) {
  const goal = s.workoutGoal || CHECKIN.workoutGoalDefault
  const setGoal = (d: number) => saveSettings({ workoutGoal: Math.min(CHECKIN.workoutGoalMax, Math.max(CHECKIN.workoutGoalMin, goal + d)) })
  const stepGoal = s.stepGoal || SCORE.stepGoalDefault
  return (
    <div className="col gap-12">
      <div className="col gap-8">
        <div className="row between" style={{ padding: '0 4px' }}>
          <span style={{ fontWeight: 700 }}>День чек-ина</span>
          <span className="small">{weekdayAcc(s.checkinWeekday)}</span>
        </div>
        <div role="radiogroup" aria-label="День чек-ина" style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 4 }}>
          {WEEKDAYS_SHORT.map((l, i) => {
            const sel = s.checkinWeekday === i + 1
            return (
              <button key={l} role="radio" aria-checked={sel} onClick={() => saveSettings({ checkinWeekday: i + 1 })}
                style={{
                  height: 40, borderRadius: 12, fontSize: 13, fontWeight: 800, minWidth: 0,
                  background: sel ? 'var(--primary)' : 'var(--surface-high)', color: sel ? 'var(--on-brand)' : 'var(--text)', transition: 'background 200ms'
                }}>{l}</button>
            )
          })}
        </div>
      </div>
      <div className="row gap-12" style={{ padding: '0 4px' }}>
        <span className="col grow">
          <span style={{ fontWeight: 700 }}>Силовых в неделю</span>
          <span className="label">чтобы мышцы было зачем сохранять</span>
        </span>
        <button className="icon-btn" aria-label="Меньше силовых" disabled={goal <= CHECKIN.workoutGoalMin} onClick={() => setGoal(-1)}><IcMinus size={18} /></button>
        <span className="h2 num" aria-live="polite" style={{ minWidth: 18, textAlign: 'center' }}>{goal}</span>
        <button className="icon-btn" aria-label="Больше силовых" disabled={goal >= CHECKIN.workoutGoalMax} onClick={() => setGoal(1)}><IcPlus size={18} /></button>
      </div>
      {steps && (
        <div className="col gap-10">
          <Segmented options={[[0, 'Шаги выкл.'], [1, 'Шаги вкл.']]} value={s.stepsEnabled ? 1 : 0} onChange={(v) => saveSettings({ stepsEnabled: v === 1 })} />
          {s.stepsEnabled && (
            <div className="row gap-12" style={{ padding: '0 4px' }}>
              <span className="col grow">
                <span style={{ fontWeight: 700 }}>Шагов в день</span>
                <span className="label">идут в оценку дня, как силовая</span>
              </span>
              <button className="icon-btn" aria-label="Меньше шагов" disabled={stepGoal <= SCORE.stepGoalMin} onClick={() => saveSettings({ stepGoal: clampStepGoal(stepGoal - SCORE.stepGoalStep) })}><IcMinus size={18} /></button>
              <span className="h2 num" aria-live="polite" style={{ minWidth: 64, textAlign: 'center' }}>{fmtInt(stepGoal)}</span>
              <button className="icon-btn" aria-label="Больше шагов" disabled={stepGoal >= SCORE.stepGoalMax} onClick={() => saveSettings({ stepGoal: clampStepGoal(stepGoal + SCORE.stepGoalStep) })}><IcPlus size={18} /></button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ---------- Путь: таймлайн этапов ----------

/**
 * Горизонтальный путь: ширина этапа — его недели, прошедшие этапы — фактические
 * недели, текущий — с заливкой пройденного, будущие — бледнее. Всё во flex с
 * minWidth 0 — влезает в 375 px без горизонтальной прокрутки при любом числе блоков.
 */
export function ProgramTimeline({ p, today }: { p: Program; today: string }) {
  const segs = p.phases.map((ph, i) => {
    const done = i < p.currentPhase || (i === p.currentPhase && !!p.completedDate)
    const cur = i === p.currentPhase && !p.completedDate
    const elapsed = activeDays(p, i, today) / 7
    const weeks = done ? Math.max(0.5, elapsed) : cur ? Math.max(ph.plannedWeeks, elapsed) : ph.plannedWeeks
    return { ph, i, done, cur, weeks, fill: done ? 1 : cur ? Math.min(1, elapsed / weeks) : 0 }
  })
  const total = segs.reduce((a, s) => a + s.weeks, 0) || 1
  const pos = segs.reduce((a, s) => a + s.weeks * s.fill, 0) / total
  return (
    <div className="col gap-6">
      <div style={{ position: 'relative', paddingTop: 12 }}>
        {!p.completedDate && (
          <span aria-hidden="true" style={{
            position: 'absolute', top: 0, left: `${Math.min(98, Math.max(2, pos * 100))}%`, transform: 'translateX(-50%)',
            width: 0, height: 0, borderLeft: '6px solid transparent', borderRight: '6px solid transparent', borderTop: '8px solid var(--text)'
          }} />
        )}
        <div className="row" role="img" style={{ gap: 3, minWidth: 0 }}
          aria-label={`Путь программы: ${p.phases.map((ph) => `${phaseName(ph)} ${ph.plannedWeeks} нед.`).join(', ')}`}>
          {segs.map(({ ph, i, done, cur, weeks, fill }) => {
            const c = PHASE_COLOR[ph.type]
            return (
              <div key={i} style={{
                flex: `${weeks} 1 0`, minWidth: 4, height: 22, borderRadius: 6, position: 'relative', overflow: 'hidden',
                background: `color-mix(in srgb, ${c} ${done || cur ? 28 : 16}%, transparent)`,
                outline: cur ? `2px solid ${c}` : 'none', outlineOffset: -2
              }}>
                <span style={{ position: 'absolute', inset: 0, width: `${fill * 100}%`, background: c }} />
                {ph.type === 'block' && (
                  <span className="num" style={{
                    position: 'relative', display: 'block', lineHeight: '22px', textAlign: 'center', fontSize: 11, fontWeight: 800,
                    color: fill > 0.5 ? '#fff' : 'var(--text)', overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'clip'
                  }}>{ph.index}</span>
                )}
              </div>
            )
          })}
        </div>
      </div>
      <div className="row gap-12 label" style={{ flexWrap: 'wrap' }}>
        <Legend c={PHASE_COLOR.block} t="блок дефицита" /><Legend c={PHASE_COLOR.break} t="перерыв" /><Legend c={PHASE_COLOR.maintenance} t="удержание" />
      </div>
    </div>
  )
}

const Legend = ({ c, t }: { c: string; t: string }) => (
  <span className="row" style={{ gap: 5 }}><span style={{ width: 8, height: 8, borderRadius: 4, background: c }} />{t}</span>
)

/** Список этапов: «Блок 1 · 8 нед. · до 21%». */
export function PhaseList({ p, today }: { p: Program; today: string }) {
  return (
    <div className="col" style={{ gap: 2 }}>
      {p.phases.map((ph, i) => {
        const cur = i === p.currentPhase && !p.completedDate
        const done = i < p.currentPhase || (i === p.currentPhase && !!p.completedDate)
        const weeks = done ? Math.max(1, Math.round(activeDays(p, i, today) / 7)) : ph.plannedWeeks
        return (
          <div key={i} className="row gap-10" style={{ minHeight: 34, padding: '0 2px' }}>
            <span style={{ width: 10, height: 10, borderRadius: 3, background: PHASE_COLOR[ph.type], flex: 'none', opacity: done || cur ? 1 : 0.55 }} />
            <span className="grow" style={{ fontWeight: cur ? 800 : 600, fontSize: 14.5 }}>
              {phaseName(ph)}{cur ? ' · сейчас' : ''}
            </span>
            <span className="label num" style={{ whiteSpace: 'nowrap' }}>
              {weeksShort(weeks)}{ph.targetFat != null ? ` · до ${fmt1(ph.targetFat)}%` : ph.type === 'break' ? ' · на уровне расхода' : ''}
              {done && <span style={{ color: PHASE_COLOR.break, marginLeft: 4 }}>✓</span>}
            </span>
          </div>
        )
      })}
    </div>
  )
}

// ---------- Предупреждение и мастер запуска ----------

type LaunchStep = 'warn' | 'blocked' | 'measure' | 'goal' | 'rhythm' | 'plan'
const SAFETY: [string, string][] = [
  ['age', 'Тебе меньше 18 лет?'],
  ['preg', 'Ты беременна или кормишь грудью?'],
  ['ed', 'Есть расстройство пищевого поведения — сейчас или в прошлом?']
]

/** Профиль → «Сжигание жира»: сначала предупреждение и вопросы безопасности, потом 4 коротких шага. */
export function LaunchSheet({ onClose }: { onClose: () => void }) {
  return (
    <Sheet label="Сжигание жира — продвинутый режим" onClose={onClose} keyboardAware>
      <LaunchFlow />
    </Sheet>
  )
}

function LaunchFlow() {
  const [step, setStep] = useState<LaunchStep>('warn')
  const [target, setTarget] = useState<number | null>(null)
  const n = step === 'measure' ? 1 : step === 'goal' ? 2 : step === 'rhythm' ? 3 : step === 'plan' ? 4 : 0
  return (
    <div className="col gap-14">
      {n > 0 && <Steps n={n} />}
      {step === 'warn' && <WarnStep onNext={() => setStep('measure')} onBlocked={() => setStep('blocked')} />}
      {step === 'blocked' && <BlockedStep />}
      {step === 'measure' && <MeasureStep onNext={() => setStep('goal')} />}
      {step === 'goal' && <GoalStep initial={target} onNext={(t) => { setTarget(t); setStep('rhythm') }} />}
      {step === 'rhythm' && <RhythmStep onNext={() => setStep('plan')} onBack={() => setStep('goal')} />}
      {step === 'plan' && target != null && <PlanStep target={target} onBack={() => setStep('goal')} />}
    </div>
  )
}

function Steps({ n }: { n: number }) {
  return (
    <div className="row gap-10">
      <span className="overline">Шаг {n} из 4</span>
      <span className="row grow" style={{ gap: 4 }} aria-hidden="true">
        {[1, 2, 3, 4].map((i) => (
          <span key={i} style={{ flex: 1, height: 4, borderRadius: 2, background: i <= n ? 'var(--primary)' : 'var(--surface-high)', transition: 'background 280ms' }} />
        ))}
      </span>
    </div>
  )
}

function WarnStep({ onNext, onBlocked }: { onNext: () => void; onBlocked: () => void }) {
  const close = useSheetClose()
  const [ans, setAns] = useState<Record<string, boolean | null>>({ age: null, preg: null, ed: null })
  // Только для информации: «ты уже сбросил…» по тренду веса или «ведёшь дневник N дней».
  const ctx = useData(async () => {
    const today = iso(new Date())
    const t = trendSeries((await weightsBetween('1900-01-01', today)).map((w) => ({ date: w.date, value: w.weight })))
    const first = t[0], last = t.at(-1)
    if (first && last && first.value - last.value >= 0.5) {
      const kg = first.value - last.value
      return `Ты уже сбросил ${fmt1(kg)} кг (${fmt1((kg / first.value) * 100)}%) с ${dayMonth(first.date)}.`
    }
    const days = (await allMealDates()).size
    return days > 0 ? `Ты ведёшь дневник ${days} ${plural(days, 'день', 'дня', 'дней')}.` : null
  }, [], null)
  const all = SAFETY.every(([k]) => ans[k] != null)
  const any = SAFETY.some(([k]) => ans[k] === true)
  return (
    <div className="col gap-14">
      <div className="h1">Сжигание жира — продвинутый режим</div>
      <div className="small" style={{ fontSize: 14.5, color: 'var(--text)' }}>
        Он для тех, кто уже сбросил 5–10% веса или давно ведёт дневник и видит, что это работает. Если ты только начинаешь —
        начни с обычного похудения: так проще и безопаснее.
      </div>
      <div className="small" style={{ fontSize: 14 }}>
        Это долгая программа на месяцы: блоки дефицита, обязательные перерывы на уровне расхода и недельные чек-ины.
      </div>
      {ctx && (
        <div className="row gap-10 small" style={{ padding: 12, borderRadius: 16, background: 'var(--surface-high)', alignItems: 'flex-start' }}>
          <span style={{ color: 'var(--secondary)', marginTop: 1 }}><IcInfo size={17} /></span>
          <span style={{ color: 'var(--text)' }}>{ctx}</span>
        </div>
      )}
      <div className="col gap-10">
        <div className="overline" style={{ padding: '0 4px' }}>Перед стартом — три вопроса</div>
        {SAFETY.map(([k, q]) => (
          <div key={k} className="row gap-10" role="radiogroup" aria-label={q} style={{ padding: '8px 10px 8px 12px', borderRadius: 16, background: 'var(--surface-low)', border: '1px solid var(--hairline)' }}>
            <span className="grow" style={{ fontWeight: 700, fontSize: 14, minWidth: 0 }}>{q}</span>
            {([[true, 'Да'], [false, 'Нет']] as const).map(([v, l]) => {
              const sel = ans[k] === v
              return (
                <button key={l} role="radio" aria-checked={sel} className="pressable" onClick={() => setAns({ ...ans, [k]: v })}
                  style={{
                    height: 36, minWidth: 52, padding: '0 12px', borderRadius: 999, fontSize: 14, fontWeight: 800, flex: 'none',
                    background: sel ? 'var(--primary)' : 'var(--surface-high)', color: sel ? 'var(--on-brand)' : 'var(--text)', transition: 'background 200ms'
                  }}>{l}</button>
              )
            })}
          </div>
        ))}
      </div>
      <button className="btn btn-brand full" disabled={!all} onClick={() => (any ? onBlocked() : onNext())}>Понятно, включить</button>
      <button className="btn btn-soft full" onClick={close}>Пока не надо</button>
    </div>
  )
}

function BlockedStep() {
  const close = useSheetClose()
  return (
    <div className="col gap-14">
      <div className="h1">Сейчас этот режим не для тебя</div>
      <div className="small" style={{ fontSize: 14.5, color: 'var(--text)' }}>
        В таких случаях дефицит калорий стоит обсудить с врачом — он подберёт безопасный план. Дневник, вода, вес
        и обычные цели в приложении остаются с тобой.
      </div>
      <button className="btn btn-brand full" onClick={close}>Понятно</button>
    </div>
  )
}

/** Шаг 1: свежий замер (если за 7 дней нет) и вес (если за 3 дня нет). */
function MeasureStep({ onNext }: { onNext: () => void }) {
  const today = iso(new Date())
  const data = useData(async () => ({
    s: await getSettings(), w: (await latestWeight()) ?? null, m: (await latestMeasurement()) ?? null,
    mToday: (await measurementOn(today)) ?? null
  }), [], null)
  const [kg, setKg] = useState('')
  if (!data) return <div style={{ height: 300 }} />
  const weightOk = !!data.w && daysBetween(data.w.date, today) <= 3
  const measureOk = !!data.m && daysBetween(data.m.date, today) <= 7
  const v = parseFloat(kg.replace(',', '.'))
  const kgOk = Number.isFinite(v) && v >= 20 && v <= 400
  return (
    <div className="col gap-14">
      <div>
        <div className="h1">Текущий замер</div>
        <div className="small">От него считаются старт программы и промежуточные цели.</div>
      </div>
      {!weightOk ? (
        <form className="col gap-12" onSubmit={async (e) => { e.preventDefault(); if (kgOk) await setWeight(today, Math.round(v * 10) / 10) }}>
          <div>
            <label className="field-label" htmlFor="pl-kg">Вес сегодня, кг</label>
            <input id="pl-kg" className="field num" inputMode="decimal" enterKeyHint="done" value={kg} onChange={(e) => setKg(e.target.value)}
              placeholder="72,4" style={{ fontSize: 22, fontWeight: 800 }} />
          </div>
          <button type="submit" className="btn btn-brand full" disabled={!kgOk}>Записать вес</button>
        </form>
      ) : (
        <Done text={`Вес ${fmt1(data.w!.weight)} кг · ${data.w!.date === today ? 'сегодня' : dayMonth(data.w!.date)}`} />
      )}
      {weightOk && !measureOk && (
        <MeasureForm s={data.s} weight={data.w?.weight ?? null} today={data.mToday} title="Замер лентой" onSaved={() => {}} />
      )}
      {measureOk && <Done text={`Замер ${fmt1(data.m!.fatPercent)}% жира · ${data.m!.date === today ? 'сегодня' : dayMonth(data.m!.date)}`} />}
      {weightOk && measureOk && <button className="btn btn-brand full" onClick={onNext}>Дальше</button>}
    </div>
  )
}

const Done = ({ text }: { text: string }) => (
  <div className="row gap-10" style={{ padding: '12px 14px', borderRadius: 16, background: 'color-mix(in srgb, #1FA463 11%, var(--surface))', border: '1px solid var(--hairline)' }}>
    <span style={{ color: '#1FA463', display: 'flex' }}><IcCheck size={18} /></span>
    <span className="num" style={{ fontWeight: 700, fontSize: 14.5 }}>{text}</span>
  </div>
)

/** Шаг 2: цель. По умолчанию — ориентир по полу, но ниже текущего процента. */
function GoalStep({ initial, onNext }: { initial: number | null; onNext: (t: number) => void }) {
  // Старт программы — свежий замер с шага 1 (тренд нужен для переходов, а не для старта).
  const data = useData(async () => ({ s: await getSettings(), fat: (await latestMeasurement())?.fatPercent ?? null }), [], null)
  const [target, setTarget] = useState<number | null>(initial)
  if (!data) return <div style={{ height: 300 }} />
  const female = bodyProfile(data.s).female ?? false
  const floor = targetFloor(female)
  const fat = data.fat
  const t = target ?? Math.max(floor, Math.min(defaultFatTarget(female), fat != null ? Math.floor(fat) - 2 : 99))
  const ok = fat == null || t < fat - 0.5
  return (
    <div className="col gap-14">
      <div>
        <div className="h1">Цель</div>
        <div className="small num">{fat != null ? `Сейчас ${fmt1(fat)}% жира. ` : ''}Ниже {floor}% цель не ставится — дальше это уже не про здоровье.</div>
      </div>
      <TargetStepper target={t} onChange={setTarget} female={female} currentFat={fat} />
      <button className="btn btn-brand full" disabled={!ok} onClick={() => onNext(t)}>Дальше</button>
    </div>
  )
}

/** Шаг 3: ритм — день чек-ина, силовые, шаги. */
function RhythmStep({ onNext, onBack }: { onNext: () => void; onBack: () => void }) {
  const s = useData(getSettings, [], null)
  if (!s) return <div style={{ height: 300 }} />
  return (
    <div className="col gap-14">
      <div>
        <div className="h1">Ритм</div>
        <div className="small">Раз в неделю — чек-ин на 2 минуты: только на нём меняются план и этапы.</div>
      </div>
      <RhythmSettings s={s} steps />
      <button className="btn btn-brand full" onClick={onNext}>Дальше</button>
      <button className="btn btn-soft full" onClick={onBack}>Назад</button>
    </div>
  )
}

/** Шаг 4: план программы — путь, вилка дат и цифры на сегодня. */
function PlanStep({ target, onBack }: { target: number; onBack: () => void }) {
  const close = useSheetClose()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const today = iso(new Date())
  const pv = useData(() => previewProgram(target), [target], undefined as Awaited<ReturnType<typeof previewProgram>> | undefined)
  if (pv === undefined) return <div style={{ height: 300 }} />
  if (pv === null) return <div className="small">Нужны вес и замер — вернись на первый шаг.</div>
  const { program: p, plan } = pv
  const fc = forecast(p, today)
  const n = blockCount(p)
  return (
    <div className="col gap-14">
      <div>
        <div className="h1">План программы</div>
        <div className="small">{n} {plural(n, 'блок', 'блока', 'блоков')} дефицита, между ними — перерывы по 2 недели, в конце — {weeksWord(PROGRAM.maintenanceWeeks)} удержания.</div>
      </div>
      <div className="row gap-10" style={{ alignItems: 'baseline', flexWrap: 'wrap' }}>
        <span className="display num">{fmt1(p.startFat)}% → {target}%</span>
      </div>
      <ProgramTimeline p={p} today={today} />
      <PhaseList p={p} today={today} />
      {fc && (
        <div className="card pad-14 col gap-4" style={{ background: 'var(--surface-low)' }}>
          <div className="label">Цель — примерно</div>
          <div className="h2">{fc.text}</div>
          <div className="label num">через {fc.loWeeks}–{fc.hiWeeks} {plural(fc.hiWeeks, 'неделю', 'недели', 'недель')}: жир уходит неровно, поэтому вилка, а не дата</div>
        </div>
      )}
      <TodayNumbers plan={plan} />
      <button className="btn btn-brand full" disabled={busy} onClick={async () => {
        setBusy(true)
        try {
          if (await startProgram(target)) { close(); toast('Программа началась — блок 1 🔥') }
          else toast('Нужны вес и замер')
        } finally { setBusy(false) }
      }}>{busy ? <span className="spinner" /> : 'Начать программу'}</button>
      <button className="btn btn-soft full" onClick={onBack}>Изменить цель</button>
    </div>
  )
}

function TodayNumbers({ plan }: { plan: FatPlan }) {
  return (
    <div className="card pad-14 col gap-8">
      <div className="row between"><span className="title">Сегодня</span><span className="num" style={{ fontWeight: 800 }}>{plan.calories} ккал</span></div>
      <div className="row between small num">
        <span style={{ color: 'var(--protein)', fontWeight: 700 }}>Б {plan.protein} г</span>
        <span style={{ color: 'var(--fat)', fontWeight: 700 }}>Ж {plan.fat} г</span>
        <span style={{ color: 'var(--carbs)', fontWeight: 700 }}>У {plan.carbs} г</span>
      </div>
      <div className="label num">Расход ≈ {plan.tdee} ккал ({plan.observed ? 'по твоим данным' : 'по формуле'}) · дефицит {Math.max(0, plan.tdee - plan.calories)} ккал</div>
    </div>
  )
}

// ---------- Выключение ----------

export function EndProgramSheet({ onClose }: { onClose: () => void }) {
  return (
    <Sheet label="Выключить режим" onClose={onClose}>
      <EndProgramForm />
    </Sheet>
  )
}

function EndProgramForm() {
  const close = useSheetClose()
  const toast = useToast()
  return (
    <div className="col gap-14">
      <div className="h1">Выключить режим?</div>
      <div className="small" style={{ fontSize: 14.5, color: 'var(--text)' }}>
        Программа закончится: этапы и путь сбросятся, снова будут действовать твои цели. Дневник, вес, замеры и чек-ины
        останутся. Если нужен отдых — лучше «Приостановить программу».
      </div>
      <button className="btn btn-danger full" onClick={async () => { await endProgram(); close(); toast('Режим «Похудение» — снова твои цели') }}>Выключить и закончить программу</button>
      <button className="btn btn-soft full" onClick={close}>Отмена</button>
    </div>
  )
}

// ---------- «Сегодня»: уведомление и завершение ----------

/** Разовая карточка: «Режим стал программой…» или смена этапа при открытии приложения. */
export function NoticeCard({ title, text }: { title: string; text: string }) {
  return (
    <div className="card pad-16 col gap-10" style={{ border: '1.5px solid color-mix(in srgb, var(--primary) 45%, transparent)', background: 'color-mix(in srgb, var(--primary) 10%, var(--surface))' }}>
      <div className="row gap-10" style={{ alignItems: 'flex-start' }}>
        <span style={{ color: 'var(--primary)', marginTop: 2, display: 'flex' }}><IcSpark size={20} /></span>
        <span className="col gap-4 grow" style={{ minWidth: 0 }}>
          <span className="title">{title}</span>
          <span className="small" style={{ color: 'var(--text)' }}>{text}</span>
        </span>
      </div>
      <button className="btn btn-soft full sm" onClick={() => updateProgram({ notice: null })}>Понятно</button>
    </div>
  )
}

/** Помощник пишет первым (3.25): подсказка по данным программы. «Понятно» — больше не покажется. */
export function NudgeCard({ title, text, onDismiss }: { title: string; text: string; onDismiss: () => void }) {
  return (
    <div className="card pad-16 col gap-10">
      <div className="row gap-10" style={{ alignItems: 'flex-start' }}>
        <span style={{ color: 'var(--primary)', marginTop: 2, display: 'flex' }}><IcSpark size={20} /></span>
        <span className="col gap-4 grow" style={{ minWidth: 0 }}>
          <span className="label">Помощник</span>
          <span className="title">{title}</span>
          <span className="small" style={{ color: 'var(--text)' }}>{text}</span>
        </span>
      </div>
      <button className="btn btn-soft full sm" onClick={onDismiss}>Понятно</button>
    </div>
  )
}

/** Программа завершена: карточка до выбора «остаться на удержании» или «выключить». */
export function CompletedCard({ onOpen }: { onOpen: () => void }) {
  return (
    <button className="card pad-16 pressable row gap-14" onClick={onOpen}
      style={{ textAlign: 'left', border: '1.5px solid color-mix(in srgb, #1FA463 50%, transparent)', background: 'color-mix(in srgb, #1FA463 11%, var(--surface))' }}>
      <span className="badge" style={{ width: 44, height: 44, borderRadius: 14, background: '#1FA463', color: '#fff' }}><IcTrophy size={22} /></span>
      <span className="col grow">
        <span className="title">Программа завершена</span>
        <span className="small">Посмотри итог и выбери, что дальше</span>
      </span>
    </button>
  )
}

export function CompletedSheet({ p, onClose }: { p: Program; onClose: () => void }) {
  return <Sheet label="Программа завершена" onClose={onClose}><CompletedSummary p={p} /></Sheet>
}

function CompletedSummary({ p }: { p: Program }) {
  const close = useSheetClose()
  const toast = useToast()
  const today = iso(new Date())
  const sum = useData(async () => {
    const list = (await checkins()).filter((c) => c.date >= p.startDate && c.avgScore != null)
    const avg = list.length ? Math.round(list.reduce((a, c) => a + c.avgScore!, 0) / list.length) : null
    return programSummary(p, today, await trendFatNow(), (await latestWeight())?.weight ?? null, avg)
  }, [], null)
  if (!sum) return <div style={{ height: 300 }} />
  return (
    <div className="col gap-14">
      <div>
        <div className="h1">Программа завершена 🎉</div>
        <div className="small">{dayMonth(p.startDate)} — {dayMonth(p.completedDate ?? today)} · {weeksWord(sum.weeks)}</div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
        <Mini v={`${fmt1(sum.startFat)}% → ${sum.fatNow != null ? `${fmt1(sum.fatNow)}%` : '—'}`} l="процент жира" />
        <Mini v={weeksShort(sum.weeks)} l="в программе" />
        <Mini v={sum.fatKgLost != null ? `${fmt1(Math.max(0, sum.fatKgLost))} кг` : '—'} l="жира ушло" />
        <Mini v={sum.avgScore != null ? `${sum.avgScore}` : '—'} l="средняя оценка дня" />
      </div>
      <div className="small" style={{ fontSize: 14 }}>
        Удержание — на уровне расхода, с тем же белком и силовыми. Так результат остаётся надолго.
      </div>
      <button className="btn btn-brand full" onClick={async () => { await updateProgram({ stayMaintenance: true }); close(); toast('Остаёмся на удержании') }}>Остаться на удержании</button>
      <button className="btn btn-soft full" onClick={async () => { await endProgram(); close(); toast('Режим выключен — снова твои цели') }}>Выключить режим</button>
    </div>
  )
}

const Mini = ({ v, l }: { v: string; l: string }) => (
  <div className="col" style={{ padding: '10px 12px', borderRadius: 16, background: 'var(--surface-low)', border: '1px solid var(--hairline)', minWidth: 0 }}>
    <span className="title num" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{v}</span>
    <span className="label">{l}</span>
  </div>
)

// ---------- Чек-ин: смена этапа ----------

/** Заметный блок в итоге чек-ина, если этап сменился: «Блок 1 завершён → 2 недели перерыва». */
export function PhaseChangeBanner({ title, text }: { title: string; text: string | null | undefined }) {
  const toBreak = /перерыв/i.test(title) && !/окончен/i.test(title)
  const c = toBreak || /удержани|заверш/i.test(title) ? PHASE_COLOR.break : PHASE_COLOR.block
  return (
    <div className="col gap-6" style={{ padding: 16, borderRadius: 20, border: `2px solid ${c}`, background: `color-mix(in srgb, ${c} 12%, var(--surface))` }}>
      <div className="row gap-8">
        <span style={{ width: 10, height: 10, borderRadius: 5, background: c, flex: 'none' }} />
        <span className="overline" style={{ color: c }}>Новый этап</span>
      </div>
      <div className="h2">{title}</div>
      {text && <div className="small" style={{ color: 'var(--text)' }}>{text}</div>}
    </div>
  )
}

/** Подпись текущего этапа для карточки плана на «Сегодня». */
export function phaseTitle(p: Program, today: string): string {
  if (p.pausedSince) return 'Программа на паузе'
  if (p.completedDate) return 'Удержание'
  const cur = currentPhase(p)
  const days = activeDays(p, p.currentPhase, today)
  if (cur.type === 'break') return `Перерыв · день ${Math.min(PROGRAM.breakDays, days + 1)} из ${PROGRAM.breakDays}`
  const wk = Math.min(cur.plannedWeeks, Math.floor(days / 7) + 1)
  if (cur.type === 'maintenance') return `Удержание · неделя ${wk} из ${cur.plannedWeeks}`
  return `Блок ${cur.index} из ${blockCount(p)} · неделя ${wk} из ${cur.plannedWeeks}`
}

