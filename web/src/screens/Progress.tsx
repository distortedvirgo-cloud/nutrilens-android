// «Прогресс»: неделя/месяц/год — сводка, калории по дням, вес, вода.
import { useState } from 'react'
import { getSettings, mealsBetween, waterBetween, weightsBetween } from '../data/db'
import { addDays, calcStreak, iso, parseIso, waterNorm } from '../data/insights'
import { type BodyMeasurement, allMealDates, latestWeight, measurements } from '../data/db'
import { composition, effectiveCalories, etaText, fatGoalOn, fmt1, plural } from '../data/body'
import { type DataStatus, type FatPlan, daysBetween } from '../data/fatplan'
import { planDataStatus } from '../data/plan'
import { Badge, Bar, Segmented, useData } from '../ui/kit'
import { IcAlert, IcChart, IcDrop, IcFire, IcFood, IcInfo, IcPlus, IcRuler, IcScale, IcSpark, IcTarget } from '../ui/icons'
import { MeasureSheet } from './Body'

const SHORT_WD = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб']
const SHORT_M = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек']

export function ProgressScreen() {
  const [period, setPeriod] = useState<7 | 30 | 365>(7)
  const [measuring, setMeasuring] = useState(false)
  const data = useData(async () => {
    const today = new Date()
    const start = iso(addDays(today, -(period - 1)))
    const end = iso(today)
    const meals = await mealsBetween(start, end)
    const settings = await getSettings()
    const byDay = new Map<string, number>()
    let protein = 0
    meals.forEach((m) => { byDay.set(m.date, (byDay.get(m.date) ?? 0) + m.calories); protein += m.protein })
    const days = Array.from({ length: period }, (_, i) => {
      const d = addDays(today, -(period - 1 - i))
      return { date: iso(d), kcal: byDay.get(iso(d)) ?? 0 }
    })
    const active = days.filter((d) => d.kcal > 0)
    const goal = effectiveCalories(settings)
    const water = await waterBetween(start, end)
    const w = (await latestWeight())?.weight ?? null
    return {
      days, goal,
      avg: active.length ? active.reduce((a, d) => a + d.kcal, 0) / active.length : 0,
      avgProtein: active.length ? protein / active.length : 0,
      inGoal: active.length ? Math.round((active.filter((d) => d.kcal <= goal * 1.05 && d.kcal >= goal * 0.8).length / active.length) * 100) : 0,
      over: active.filter((d) => d.kcal > goal).length,
      activeCount: active.length,
      streak: calcStreak(await allMealDates(), today),
      weights: (await weightsBetween(start, end)).sort((a, b) => a.date.localeCompare(b.date)),
      waterAvg: water.length ? Math.round(water.reduce((a, x) => a + x.ml, 0) / water.length) : 0,
      waterNorm: waterNorm(w, false),
      weight: w,
      targetFat: fatGoalOn(settings) ? settings.targetFatPercent : null,
      body: await measurements(),
      fatMode: fatGoalOn(settings),
      plan: settings.fatPlan,
      status: fatGoalOn(settings) ? await planDataStatus() : null
    }
  }, [period], null)

  return (
    <div className="screen screen-enter">
      <div className="col gap-16 stagger">
        <div>
          <div className="display">Прогресс</div>
          <div className="small">Как проходит твой период</div>
        </div>
        <Segmented options={[[7, 'Неделя'], [30, 'Месяц'], [365, 'Год']]} value={period} onChange={setPeriod} />
        {data?.fatMode && <PlanCard plan={data.plan} status={data.status} />}
        {data && (data.targetFat != null || data.body.length > 0) && (
          <BodyCard list={data.body} weight={data.weight} target={data.targetFat} onMeasure={() => setMeasuring(true)} />
        )}
        {data && data.activeCount === 0 && (
          <div className="card col" style={{ alignItems: 'center', padding: '36px 24px', textAlign: 'center', gap: 8 }}>
            <Badge tint="var(--primary)" size={60}><IcChart size={30} /></Badge>
            <div className="h2" style={{ marginTop: 6 }}>Пока нет данных за период</div>
            <div className="small">Запиши пару приёмов пищи — и здесь появится статистика</div>
          </div>
        )}
        {data && data.activeCount > 0 && (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <Stat icon={<IcFood size={19} />} tint="var(--primary)" v={`${Math.round(data.avg)}`} l="ккал в среднем" />
              <Stat icon={<IcTarget size={19} />} tint="var(--secondary)" v={`${data.inGoal}%`} l="дней в цели" />
              <Stat icon={<IcFire size={19} />} tint="var(--warn-to)" v={`${data.streak}`} l="дней подряд" />
              <Stat icon={<IcFood size={19} />} tint="var(--protein)" v={`${Math.round(data.avgProtein)} г`} l="белка в день" />
            </div>
            <Calories days={period === 365 ? monthly(data.days) : data.days} goal={data.goal} mode={period === 7 ? 'wd' : period === 30 ? 'date' : 'month'} key={period} />
            <Weight weights={data.weights} key={`w${period}`} />
            <div className="card pad-18 row gap-14">
              <Badge tint="var(--water-deep)" size={44}><IcDrop size={22} /></Badge>
              <div className="grow col gap-8">
                <div className="title">Вода</div>
                <Bar fraction={data.waterNorm ? data.waterAvg / data.waterNorm : 0} color="var(--water-deep)" height={10} />
                <div className="small num">В среднем {data.waterAvg} мл из ~{data.waterNorm} мл в день</div>
              </div>
            </div>
          </>
        )}
      </div>
      {measuring && <MeasureSheet onClose={() => setMeasuring(false)} />}
    </div>
  )
}

function monthly(days: { date: string; kcal: number }[]) {
  const map = new Map<string, number[]>()
  days.forEach((d) => {
    if (!d.kcal) return
    const k = d.date.slice(0, 7)
    map.set(k, [...(map.get(k) ?? []), d.kcal])
  })
  const now = new Date()
  return Array.from({ length: 12 }, (_, i) => {
    const d = new Date(now.getFullYear(), now.getMonth() - 11 + i, 1)
    const k = iso(d).slice(0, 7)
    const v = map.get(k) ?? []
    return { date: `${k}-01`, kcal: v.length ? v.reduce((a, b) => a + b, 0) / v.length : 0 }
  })
}

const Stat = ({ icon, tint, v, l }: { icon: React.ReactNode; tint: string; v: string; l: string }) => (
  <div className="card r24 pad-14 col gap-10">
    <Badge tint={tint} size={36}>{icon}</Badge>
    <div>
      <div className="h1 num">{v}</div>
      <div className="small">{l}</div>
    </div>
  </div>
)

function Calories({ days, goal, mode }: { days: { date: string; kcal: number }[]; goal: number; mode: 'wd' | 'date' | 'month' }) {
  const max = Math.max(goal, ...days.map((d) => d.kcal)) * 1.15 || 1
  const H = 170
  return (
    <div className="card pad-18 col gap-14">
      <div>
        <div className="title">{mode === 'month' ? 'Средние калории по месяцам' : 'Калории по дням'}</div>
        <div className="row gap-12 label" style={{ marginTop: 4 }}>
          <Legend c="var(--brand-from)" t="в цели" /><Legend c="var(--warn-from)" t="до +200" /><Legend c="var(--tertiary)" t="выше" />
        </div>
      </div>
      <div style={{ position: 'relative', height: H, display: 'grid', gridTemplateColumns: `repeat(${days.length}, 1fr)`, alignItems: 'end', gap: days.length > 14 ? 2 : 8 }}
        role="img" aria-label={`График калорий, цель ${Math.round(goal)} ккал`}>
        <div style={{ position: 'absolute', left: 0, right: 0, bottom: (goal / max) * H, borderTop: '1.5px dashed color-mix(in srgb, var(--text-2) 60%, transparent)', zIndex: 1 }} />
        {days.map((d, i) => {
          const h = d.kcal > 0 ? Math.max(6, (d.kcal / max) * H) : 0
          const bg = d.kcal > goal + 200 ? 'var(--tertiary)' : d.kcal > goal ? 'var(--warn-from)' : 'linear-gradient(to top, var(--brand-to), var(--brand-from))'
          return (
            <div key={d.date} style={{ height: H, display: 'flex', alignItems: 'flex-end', justifyContent: 'center', borderRadius: 8, background: 'var(--surface-low)' }}>
              <div style={{
                width: '100%', maxWidth: 28, height: h, borderRadius: 8, background: bg,
                transformOrigin: 'bottom', animation: `grow 900ms var(--ease-emph) ${i * (days.length > 14 ? 12 : 40)}ms both`
              }} />
            </div>
          )
        })}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: `repeat(${days.length}, 1fr)`, gap: days.length > 14 ? 2 : 8 }}>
        {days.map((d, i) => {
          const dt = parseIso(d.date)
          const label = mode === 'wd' ? SHORT_WD[dt.getDay()] : mode === 'month' ? SHORT_M[dt.getMonth()] : i % 5 === 0 || i === days.length - 1 ? `${dt.getDate()}` : ''
          return <span key={d.date} className="label" style={{ textAlign: 'center', fontSize: 10.5 }}>{label}</span>
        })}
      </div>
      <div className="small">Пунктир — цель {Math.round(goal)} ккал</div>
      <style>{'@keyframes grow { from { transform: scaleY(0); } to { transform: scaleY(1); } }'}</style>
    </div>
  )
}

const Legend = ({ c, t }: { c: string; t: string }) => (
  <span className="row" style={{ gap: 5 }}><span style={{ width: 8, height: 8, borderRadius: 4, background: c }} />{t}</span>
)

function Weight({ weights }: { weights: { date: string; weight: number }[] }) {
  if (weights.length < 2) {
    return (
      <div className="card pad-18 row gap-14">
        <Badge tint="var(--secondary)" size={40}><IcScale size={20} /></Badge>
        <div className="grow">
          <div className="title">Вес</div>
          <div className="small">Запиши вес хотя бы дважды, чтобы увидеть динамику</div>
        </div>
      </div>
    )
  }
  const min = Math.min(...weights.map((w) => w.weight)), max = Math.max(...weights.map((w) => w.weight))
  const delta = weights.at(-1)!.weight - weights[0].weight
  const dc = delta > 0 ? 'var(--tertiary)' : 'var(--primary)'
  return (
    <div className="card pad-18 col gap-12">
      <div className="row between">
        <div className="title">Вес</div>
        <span className="chip num" style={{ height: 30, fontSize: 13, color: dc, background: `color-mix(in srgb, ${dc} 13%, transparent)` }}>
          {delta > 0 ? '+' : ''}{delta.toFixed(1).replace('.', ',')} кг
        </span>
      </div>
      <LineChart values={weights.map((w) => w.weight)} color="var(--secondary)" id="wfill" label={`Вес от ${min} до ${max} кг`} />
      <div className="row between label num"><span>мин {min.toFixed(1).replace(".", ",")}</span><span>макс {max.toFixed(1).replace(".", ",")}</span></div>
    </div>
  )
}

/** Линия с заливкой — для веса и процента жира. */
function LineChart({ values, color, id, label, minSpan = 1 }: { values: number[]; color: string; id: string; label: string; minSpan?: number }) {
  const W = 320, H = 120, pad = 10
  const min = Math.min(...values), max = Math.max(...values)
  const span = Math.max(minSpan, max - min)
  const pts = values.map((v, i) => [pad + (i * (W - pad * 2)) / (values.length - 1), H - pad - ((v - min) / span) * (H - pad * 2)])
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ')
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={H} role="img" aria-label={label}>
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={color} stopOpacity="0.25" />
          <stop offset="1" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${line} L${pts.at(-1)![0]} ${H} L${pts[0][0]} ${H} Z`} fill={`url(#${id})`} />
      <path d={line} fill="none" stroke={color} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"
        pathLength={1} strokeDasharray={1} style={{ animation: 'draw 1200ms var(--ease-emph) both' }} />
      {pts.map((p, i) => <circle key={i} cx={p[0]} cy={p[1]} r="4.5" fill="var(--surface)" stroke={color} strokeWidth="2.5" />)}
      <style>{'@keyframes draw { from { stroke-dashoffset: 1; } to { stroke-dashoffset: 0; } }'}</style>
    </svg>
  )
}

/** «Состав тела»: процент жира, жир и сухая масса, путь к цели, тренд замеров. */
function BodyCard({ list, weight, target, onMeasure }: {
  list: BodyMeasurement[]; weight: number | null; target: number | null; onMeasure: () => void
}) {
  const last = list.at(-1)
  const head = (
    <div className="row gap-12">
      <Badge tint="var(--secondary)" size={40}><IcRuler size={20} /></Badge>
      <div className="grow title">Состав тела</div>
      {target != null && <span className="chip primary num" style={{ height: 30, fontSize: 13 }}>цель {target}%</span>}
    </div>
  )
  const addBtn = <button className="btn btn-soft full sm" onClick={onMeasure}><IcPlus size={18} />Добавить замер</button>
  if (!last) {
    return (
      <div className="card pad-18 col gap-12">
        {head}
        <div className="small">Измерь талию и шею — посчитаю процент жира, сухую массу и сколько осталось до цели.</div>
        {addBtn}
      </div>
    )
  }
  const days = Math.round((parseIso(iso(new Date())).getTime() - parseIso(last.date).getTime()) / 86400000)
  const c = weight != null ? composition(weight, last.fatPercent, target) : null
  const start = list[0].fatPercent
  const progress = target == null ? 0 : start <= target ? 1 : (start - last.fatPercent) / (start - target)
  const delta = list.length > 1 ? last.fatPercent - start : 0
  return (
    <div className="card pad-18 col gap-14">
      {head}
      <div className="row gap-12" style={{ alignItems: 'flex-end' }}>
        <div className="grow">
          <div className="display num">{fmt1(last.fatPercent)}%</div>
          <div className="small">жира · {days === 0 ? 'замер сегодня' : `замер ${days} ${plural(days, 'день', 'дня', 'дней')} назад`}</div>
        </div>
        {list.length > 1 && Math.abs(delta) >= 0.1 && (
          <span className="chip num" style={{ height: 30, fontSize: 13, color: delta > 0 ? 'var(--tertiary)' : 'var(--primary)', background: `color-mix(in srgb, ${delta > 0 ? 'var(--tertiary)' : 'var(--primary)'} 13%, transparent)` }}>
            {delta > 0 ? '+' : ''}{fmt1(delta)}% со старта
          </span>
        )}
      </div>
      {c ? (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <MiniStat v={`${fmt1(c.fatMass)} кг`} l="жировая масса" color="var(--fat)" />
          <MiniStat v={`${fmt1(c.leanMass)} кг`} l="сухая масса" color="var(--protein)" />
        </div>
      ) : (
        <div className="small">Запиши вес — посчитаю массу жира и мышц.</div>
      )}
      {target != null && (
        <div className="col gap-8">
          <Bar fraction={progress} color="var(--secondary)" height={10} />
          <div className="row between label num"><span>старт {fmt1(start)}%</span><span>цель {target}%</span></div>
          {c?.targetWeight != null && (
            <div className="small">
              Вес при цели — при сохранении мышц ≈ <b className="num" style={{ color: 'var(--text)' }}>{Math.round(c.targetWeight)} кг</b>
              {c.fatToLose! > 0.2 ? <> · сбросить ≈ {fmt1(c.fatToLose!)} кг жира, {etaText(c.etaWeeks!)}</> : <> · цель достигнута</>}
            </div>
          )}
        </div>
      )}
      {list.length > 1 && (
        <LineChart values={list.slice(-30).map((m) => m.fatPercent)} color="var(--secondary)" id="ffill" minSpan={2}
          label={`Процент жира: от ${fmt1(start)}% до ${fmt1(last.fatPercent)}%`} />
      )}
      {days > 7 && (
        <div className="row gap-10 small" style={{ padding: 12, borderRadius: 16, background: 'color-mix(in srgb, var(--warn-to) 10%, transparent)', alignItems: 'flex-start' }}>
          <span style={{ color: 'var(--warn-to)', marginTop: 1 }}><IcAlert size={17} /></span>
          <span>Последний замер {days} {plural(days, 'день', 'дня', 'дней')} назад — пора обновить, лучше утром натощак.</span>
        </div>
      )}
      <div className="label">Домашние методы ошибаются на ±3–4% — смотри на тренд, а не на одну цифру.</div>
      {addBtn}
    </div>
  )
}

const MiniStat = ({ v, l, color, small }: { v: string; l: string; color: string; small?: boolean }) => (
  <div className="col" style={{ padding: '10px 12px', borderRadius: 16, background: 'var(--surface-low)', border: '1px solid var(--hairline)', minWidth: 0 }}>
    <span className={small ? 'title num' : 'h2 num'} style={{ color, whiteSpace: small ? undefined : 'nowrap' }}>{v}</span>
    <span className="label">{l}</span>
  </div>
)

const rateText = (r: number) => `${r > 0 ? '−' : r < 0 ? '+' : ''}${fmt1(Math.abs(r))}% в неделю`

/**
 * «План на неделю» режима сжигания жира: калории и БЖУ, откуда расход, темп
 * цель/факт, почему план такой и когда пересчёт. Нехватку данных показываем
 * по живым цифрам, а не по сохранённому плану — иначе «нужно ещё 3 взвешивания»
 * висело бы до пересчёта.
 */
function PlanCard({ plan, status }: { plan: FatPlan | null; status: DataStatus | null }) {
  const head = (
    <div className="row gap-12">
      <Badge tint="var(--primary)" size={40}><IcFire size={20} /></Badge>
      <div className="grow title">План на неделю</div>
      <span className="chip primary" style={{ height: 30, fontSize: 13 }}>авто</span>
    </div>
  )
  const explain = <div className="label">План сам подстраивается по весу и дневнику еды. Точнее он станет примерно через 2 недели записей и 4+ взвешиваний.</div>
  if (!plan) {
    return (
      <div className="card pad-18 col gap-12">
        {head}
        <div className="small">Запиши вес — посчитаю калории и БЖУ для сжигания жира.</div>
        {explain}
      </div>
    )
  }
  const left = Math.max(0, daysBetween(iso(new Date()), plan.nextDate))
  return (
    <div className="card pad-18 col gap-14">
      {head}
      <div className="row gap-8" style={{ alignItems: 'baseline' }}>
        <span className="display num">{plan.calories}</span>
        <span className="small">ккал в день</span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8 }}>
        <MiniStat v={`${plan.protein} г`} l="белок" color="var(--protein)" />
        <MiniStat v={`${plan.fat} г`} l="жиры" color="var(--fat)" />
        <MiniStat v={`${plan.carbs} г`} l="углеводы" color="var(--carbs)" />
      </div>
      <div className="small num">
        Твой расход ≈ <b style={{ color: 'var(--text)' }}>{plan.tdee} ккал</b> ({plan.observed ? 'по твоим данным' : 'по формуле'})
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
        <MiniStat v={rateText(plan.targetRate)} l="цель темпа" color="var(--primary)" small />
        <MiniStat v={plan.actualRate != null ? rateText(plan.actualRate) : '—'} l={plan.actualRate != null ? 'по факту, 3 недели' : 'факт — пока мало данных'} color="var(--text)" small />
      </div>
      {plan.note && (
        <div className="row gap-10 small" style={{ padding: 12, borderRadius: 16, background: 'color-mix(in srgb, var(--primary) 10%, transparent)', alignItems: 'flex-start' }}>
          <span style={{ color: 'var(--primary)', marginTop: 1 }}><IcSpark size={17} /></span>
          <span style={{ color: 'var(--text)' }}>{plan.note}</span>
        </div>
      )}
      {status && !status.enough && status.missing && (
        <div className="row gap-10 small" style={{ alignItems: 'flex-start' }}>
          <span style={{ color: 'var(--secondary)', marginTop: 1 }}><IcInfo size={17} /></span>
          <span>Чтобы считать расход по твоим данным, {status.missing}.</span>
        </div>
      )}
      <div className="small" style={{ fontWeight: 700 }}>
        Следующий пересчёт — {left === 0 ? 'сегодня' : `через ${left} ${plural(left, 'день', 'дня', 'дней')}`}
      </div>
      {explain}
    </div>
  )
}
