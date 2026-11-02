// «Прогресс»: неделя/месяц/год — сводка, калории по дням, вес, вода.
import { useState } from 'react'
import { getSettings, mealsBetween, waterBetween, weightsBetween } from '../data/db'
import { addDays, calcStreak, iso, parseIso, waterNorm } from '../data/insights'
import { allMealDates, latestWeight } from '../data/db'
import { Badge, Bar, Segmented, useData } from '../ui/kit'
import { IcChart, IcDrop, IcFire, IcFood, IcScale, IcTarget } from '../ui/icons'

const SHORT_WD = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб']
const SHORT_M = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек']

export function ProgressScreen() {
  const [period, setPeriod] = useState<7 | 30 | 365>(7)
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
    const goal = settings.dailyGoal
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
      waterNorm: waterNorm(w, false)
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
  const W = 320, H = 120, pad = 10
  const min = Math.min(...weights.map((w) => w.weight)), max = Math.max(...weights.map((w) => w.weight))
  const span = Math.max(1, max - min)
  const pts = weights.map((w, i) => [pad + (i * (W - pad * 2)) / (weights.length - 1), H - pad - ((w.weight - min) / span) * (H - pad * 2)])
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ')
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
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={H} role="img" aria-label={`Вес от ${min} до ${max} кг`}>
        <defs>
          <linearGradient id="wfill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="var(--secondary)" stopOpacity="0.25" />
            <stop offset="1" stopColor="var(--secondary)" stopOpacity="0" />
          </linearGradient>
        </defs>
        <path d={`${line} L${pts.at(-1)![0]} ${H} L${pts[0][0]} ${H} Z`} fill="url(#wfill)" />
        <path d={line} fill="none" stroke="var(--secondary)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"
          pathLength={1} strokeDasharray={1} style={{ animation: 'draw 1200ms var(--ease-emph) both' }} />
        {pts.map((p, i) => <circle key={i} cx={p[0]} cy={p[1]} r="4.5" fill="var(--surface)" stroke="var(--secondary)" strokeWidth="2.5" />)}
      </svg>
      <div className="row between label num"><span>мин {min.toFixed(1).replace(".", ",")}</span><span>макс {max.toFixed(1).replace(".", ",")}</span></div>
      <style>{'@keyframes draw { from { stroke-dashoffset: 1; } to { stroke-dashoffset: 0; } }'}</style>
    </div>
  )
}
