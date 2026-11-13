// «Сегодня»: неделя с кольцами, сводка калорий и БЖУ, совет коуча,
// вода и вес, приёмы пищи по слотам и состояние ИИ-анализа.
import { useRef, useState } from 'react'
import { Avatar } from '../ui/Avatar'
import {
  type Meal, addWater, allMealDates, getSettings, jobs, latestMeasurement, latestWeight, mealsBetween, mealsOn,
  waterOn, workoutOn, workoutsBetween, toggleWorkout, deleteJob, favorites
} from '../data/db'
import {
  SLOTS, type Slot, addDays, calcStreak, dayOverline, dayTitle, dayTotals, daysWord, healthScoreOf, iso,
  mondayOf, nextMealFocus, parseIso, slotOf, waterNorm
} from '../data/insights'
import { retryJob, runQueue } from '../data/actions'
import { effectiveGoals, fatGoalOn } from '../data/body'
import { type FatPlan, daysBetween } from '../data/fatplan'
import { checkinWhen } from '../data/checkin'
import { checkinStatus, type CheckinStatus } from '../data/plan'
import { MeasureSheet } from './Body'
import { CheckinCard, CheckinSheet } from './Checkin'
import { Bar, Badge, Photo, Ring, useCountUp, useData, useToast } from '../ui/kit'
import {
  IcCamera, IcCheck, IcChevronR, IcDumbbell, IcKey, IcDrop, IcFire, IcPlus, IcRefresh, IcScale, IcSpark, IcStar, IcTrophy, IcAlert, IcClose
} from '../ui/icons'

const WEEK = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс']

export function TodayScreen({ onOpenMeal, onAddPhoto, onOpenCoach, onWeigh, onOpenProgress, onOpenLeaderboard, onOpenProfile, needsKey, onSetupAi }: {
  onOpenMeal: (m: Meal) => void
  onAddPhoto: () => void
  onOpenCoach: () => void
  onWeigh: () => void
  onOpenProgress: () => void
  onOpenLeaderboard: () => void
  onOpenProfile: () => void
  needsKey: boolean
  onSetupAi: () => void
}) {
  const [date, setDate] = useState(() => iso(new Date()))
  const [measuring, setMeasuring] = useState(false)
  // План «до» берём в момент открытия — чек-ин хранит его снимок.
  const [checkin, setCheckin] = useState<FatPlan | null>(null)
  const toast = useToast()
  const d = parseIso(date)
  const today = iso(new Date())
  const isToday = date === today

  const data = useData(async () => {
    const monday = mondayOf(d)
    const week = await mealsBetween(iso(monday), iso(addDays(monday, 6)))
    const weekKcal = new Map<string, number>()
    week.forEach((m) => weekKcal.set(m.date, (weekKcal.get(m.date) ?? 0) + m.calories))
    const w = await latestWeight()
    const settings = await getSettings()
    return {
      meals: await mealsOn(date),
      weekKcal,
      settings,
      checkin: await checkinStatus(settings),
      weekWorkouts: (await workoutsBetween(iso(monday), iso(addDays(monday, 6)))).length,
      water: await waterOn(date),
      workout: await workoutOn(date),
      weight: w?.weight ?? null,
      weightDays: w ? Math.round((parseIso(today).getTime() - parseIso(w.date).getTime()) / 86400000) : null,
      streak: calcStreak(await allMealDates(), new Date()),
      body: (await latestMeasurement()) ?? null,
      jobs: await jobs(),
      favNames: new Set((await favorites()).map((f) => f.name))
    }
  }, [date], null)

  if (!data) return <div className="screen" />
  const { meals, settings, water, workout, weight, weightDays, streak } = data
  const totals = dayTotals(meals)
  const goals = effectiveGoals(settings, weight, data.body)
  const fatMode = fatGoalOn(settings)
  const norm = waterNorm(weight, workout)
  const focus = isToday ? nextMealFocus(totals, goals) : null
  const active = data.jobs.filter((j) => j.status !== 'FAILED')
  const failed = data.jobs.filter((j) => j.status === 'FAILED')

  const grouped = new Map<Slot, Meal[]>()
  meals.forEach((m) => {
    const s = slotOf(m.time)
    grouped.set(s, [...(grouped.get(s) ?? []), m])
  })
  const hour = new Date().getHours()
  const currentSlot = slotOf(`${String(hour).padStart(2, '0')}:00`)
  const suggest = isToday && meals.length > 0 && !grouped.has(currentSlot) ? SLOTS.find((s) => s.key === currentSlot) : undefined

  return (
    <div className="screen screen-enter">
      <div className="col gap-16 stagger">
        <Header date={d} streak={streak} isToday={isToday} onToday={() => setDate(today)} nickname={settings.nickname} avatar={settings.avatar} onProfile={onOpenProfile} />
        {fatMode && (
          <button className="mode-pill pressable num" onClick={data.body ? onOpenProgress : () => setMeasuring(true)}
            aria-label={data.body ? 'Режим сжигания жира — открыть прогресс' : 'Режим сжигания жира — сделать первый замер'}>
            <span aria-hidden="true">🔥</span>
            Сжигание жира · {data.body ? `${Math.round(data.body.fatPercent)}% → ${settings.targetFatPercent}%` : 'сделай первый замер'}
          </button>
        )}
        {needsKey && (
          <button className="card pad-16 pressable row gap-14" onClick={onSetupAi}
            style={{ textAlign: 'left', border: '1.5px solid color-mix(in srgb, var(--primary) 50%, transparent)', background: 'color-mix(in srgb, var(--primary) 12%, var(--surface))' }}>
            <span className="badge" style={{ width: 44, height: 44, borderRadius: 14, background: 'var(--primary)', color: 'var(--on-brand)' }}><IcKey size={22} /></span>
            <span className="col grow">
              <span className="title">Подключи нейросеть</span>
              <span className="small">Без ключа NanoGPT или Gemini ИИ не посчитает калории. Нажми — займёт минуту</span>
            </span>
            <span className="muted"><IcChevronR size={18} /></span>
          </button>
        )}
        <WeekStrip date={d} weekKcal={data.weekKcal} goal={goals.calories} onSelect={(x) => setDate(iso(x))} />
        <Hero meals={meals} goal={goals.calories} goals={goals} proteinFirst={fatMode} onLeaderboard={onOpenLeaderboard} />
        {fatMode && isToday && goals.plan && data.checkin?.due && (
          <CheckinCard overdue={Math.max(0, daysBetween(data.checkin.next, today))} onOpen={() => setCheckin(goals.plan)} />
        )}
        {fatMode && isToday && (goals.plan
          ? <PlanMini plan={goals.plan} today={today} status={data.checkin} onOpen={onOpenProgress} />
          : weight == null && (
            <button className="card r22 pressable row gap-12" onClick={onWeigh} style={{ padding: '14px 16px', textAlign: 'left' }}>
              <Badge tint="var(--primary)" size={38}><IcScale size={20} /></Badge>
              <span className="col grow">
                <span className="title">План на неделю</span>
                <span className="small">Запиши вес — посчитаю калории и БЖУ для сжигания жира</span>
              </span>
              <span className="muted"><IcChevronR size={18} /></span>
            </button>
          ))}

        {fatMode && (
          <WorkoutToggle done={workout} week={data.weekWorkouts} goal={settings.workoutGoal}
            onToggle={async () => { const on = await toggleWorkout(date); if (on) toast('Силовая отмечена 💪') }} />
        )}

        {active.length > 0 && <Processing count={active.length} note={active[0].note} />}
        {failed.map((j) => (
          <div key={j.id} className="card r22 pad-14 row gap-12" style={{ background: 'color-mix(in srgb, var(--error-cont) 60%, var(--surface))' }}>
            <Badge tint="var(--error)" size={38}><IcAlert size={20} /></Badge>
            <div className="grow">
              <div className="title ellipsis">{j.note || 'Анализ фото'}</div>
              <div className="small" style={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{j.error}</div>
            </div>
            <button className="icon-btn" aria-label="Повторить" onClick={() => retryJob(j).then(() => runQueue())}><IcRefresh size={18} /></button>
            <button className="icon-btn" aria-label="Скрыть" onClick={() => deleteJob(j.id)}><IcClose size={18} /></button>
          </div>
        ))}

        {focus && (
          <button className="card r22 pressable row gap-12" onClick={onOpenCoach}
            style={{ padding: '14px 16px', textAlign: 'left', background: 'color-mix(in srgb, var(--primary-cont) 75%, var(--surface))', borderColor: 'color-mix(in srgb, var(--primary) 25%, transparent)' }}>
            <span style={{ color: 'var(--primary)' }}><IcSpark size={22} /></span>
            <span className="grow" style={{ fontWeight: 700, fontSize: 14.5 }}>{focus[0]}</span>
            <span className="muted"><IcChevronR size={20} /></span>
          </button>
        )}

        <div className="row gap-12">
          <button className="card r24 pressable grow pad-14 col" style={{ textAlign: 'left', gap: 10 }}
            onClick={async () => { const ml = await addWater(date, 250); toast(`Вода: ${ml} мл`) }}>
            <div className="row between">
              <Badge tint="var(--water-deep)" size={34}><IcDrop size={18} /></Badge>
              <span className="badge" style={{ width: 34, height: 34, borderRadius: 999, background: 'linear-gradient(90deg, var(--water), var(--water-deep))', color: '#fff' }}><IcPlus size={18} /></span>
            </div>
            <div>
              <div className="h2 num">{water} мл</div>
              <div className="label">из {norm} мл</div>
            </div>
            <Bar fraction={water / norm} color="var(--water-deep)" />
          </button>
          <button className="card r24 pressable grow pad-14 col" style={{ textAlign: 'left', gap: 10 }} onClick={onWeigh}>
            <div className="row between">
              <Badge tint={weightDays == null || weightDays >= 7 ? 'var(--warn-to)' : 'var(--secondary)'} size={34}><IcScale size={18} /></Badge>
              {(weightDays == null || weightDays >= 7) && <span style={{ width: 10, height: 10, borderRadius: 5, background: 'var(--warn-to)' }} />}
            </div>
            <div>
              <div className="h2 num">{weight != null ? `${weight.toFixed(1).replace(".", ",")} кг` : '— кг'}</div>
              <div className="label" style={{ color: weightDays == null || weightDays >= 7 ? 'var(--warn-to)' : undefined }}>
                {weightDays == null ? 'Записать вес' : weightDays === 0 ? 'сегодня' : weightDays >= 7 ? 'пора взвеситься' : `${weightDays} ${daysWord(weightDays)} назад`}
              </div>
            </div>
            <div style={{ height: 6 }} />
          </button>
        </div>

        <div className="row between" style={{ marginTop: 6 }}>
          <div className="h2">Приёмы пищи</div>
          {meals.length > 0 && <div className="label num">{meals.length} · {Math.round(totals.calories)} ккал</div>}
        </div>

        {meals.length === 0 && (
          <button className="card pressable col" onClick={isToday ? onAddPhoto : undefined}
            style={{ alignItems: 'center', padding: '28px 24px', textAlign: 'center', gap: 6 }}>
            <span className="badge" style={{
              width: 64, height: 64, borderRadius: 22, marginBottom: 8,
              background: isToday ? 'linear-gradient(135deg, var(--brand-from), var(--brand-to))' : 'var(--surface-high)',
              color: isToday ? 'var(--on-brand)' : 'var(--text-2)'
            }}><IcCamera size={30} /></span>
            <div className="title">{isToday ? 'Сфотографируй первый приём пищи' : 'В этот день записей нет'}</div>
            {isToday && <div className="small">ИИ посчитает калории и БЖУ</div>}
          </button>
        )}
      </div>

      <div className="col gap-10" style={{ marginTop: 10 }}>
        {SLOTS.filter((s) => grouped.has(s.key)).map((s) => (
          <div key={s.key} className="col gap-10">
            <div className="row between" style={{ padding: '6px 4px 0' }}>
              <span className="overline">{s.title}</span>
              <span className="overline num">{Math.round(grouped.get(s.key)!.reduce((a, m) => a + m.calories, 0))} ккал</span>
            </div>
            {grouped.get(s.key)!.map((m) => <MealRow key={m.id} meal={m} fav={data.favNames.has(m.name)} onClick={() => onOpenMeal(m)} />)}
          </div>
        ))}
        {suggest && (
          <button className="pressable row gap-14" onClick={onAddPhoto}
            style={{ padding: 10, borderRadius: 22, border: '1.5px dashed color-mix(in srgb, var(--text-3) 45%, transparent)', textAlign: 'left' }}>
            <span className="badge" style={{ width: 56, height: 56, borderRadius: 16, background: 'var(--primary-cont)', color: 'var(--on-primary-cont)' }}><IcCamera size={24} /></span>
            <span className="col">
              <span className="overline">{suggest.title}</span>
              <span className="title muted">{suggest.cta}</span>
            </span>
          </button>
        )}
      </div>
      {measuring && <MeasureSheet onClose={() => setMeasuring(false)} />}
      {checkin && <CheckinSheet plan={checkin} onClose={() => setCheckin(null)} />}
    </div>
  )
}

/**
 * Отметка «Силовая сделана» за выбранный день — в режиме прямо на «Сегодня»:
 * чек-ин сравнивает силовые недели с целью, и без отметок сигнал мышц не зелёный.
 */
function WorkoutToggle({ done, week, goal, onToggle }: { done: boolean; week: number; goal: number; onToggle: () => void }) {
  return (
    <button className="card r22 pressable row gap-12" role="switch" aria-checked={done} onClick={onToggle}
      style={{ padding: '12px 16px', textAlign: 'left' }}>
      <Badge tint="var(--secondary)" size={38}><IcDumbbell size={20} /></Badge>
      <span className="col grow">
        <span className="title">Силовая сделана</span>
        <span className="label num">на этой неделе {week} из {goal}</span>
      </span>
      <span className="badge" style={{
        width: 30, height: 30, borderRadius: 10, transition: 'background 220ms',
        background: done ? 'var(--primary)' : 'var(--surface-high)', color: done ? 'var(--on-brand)' : 'transparent'
      }}><IcCheck size={18} /></span>
    </button>
  )
}

/** «Сегодня» → компактный план режима: цифры дня и когда чек-ин. Подробно — в «Прогрессе». */
function PlanMini({ plan, today, status, onOpen }: { plan: FatPlan; today: string; status: CheckinStatus | null; onOpen: () => void }) {
  return (
    <button className="card r22 pressable col gap-8" onClick={onOpen} style={{ padding: '14px 16px', textAlign: 'left' }}>
      <div className="row gap-12">
        <Badge tint="var(--primary)" size={38}><IcFire size={20} /></Badge>
        <div className="grow">
          <div className="row gap-6"><span className="title">План на неделю</span><span className="label">· авто</span></div>
          <div className="small num">
            <b style={{ color: 'var(--text)' }}>{plan.calories} ккал</b> · Б {plan.protein} · Ж {plan.fat} · У {plan.carbs} г
          </div>
        </div>
        <span className="muted"><IcChevronR size={18} /></span>
      </div>
      <div className="label num" style={{ paddingLeft: 50 }}>
        Расход ≈ {plan.tdee} ккал ({plan.observed ? 'по твоим данным' : 'по формуле'}) · чек-ин {status?.due ? 'ждёт' : checkinWhen(status?.next ?? plan.nextDate, today)}
      </div>
    </button>
  )
}

function Header({ date, streak, isToday, onToday, nickname, avatar, onProfile }: {
  date: Date; streak: number; isToday: boolean; onToday: () => void; nickname: string; avatar: string; onProfile: () => void
}) {
  return (
    <div className="row gap-8">
      <div className="grow">
        <div className="overline">{dayOverline(date)}</div>
        <div className="display" key={iso(date)} style={{ animation: 'rise 360ms var(--ease-emph) both' }}>{dayTitle(date)}</div>
      </div>
      {streak > 0 && (
        <span className="chip warm num" aria-label={`Серия ${streak} ${daysWord(streak)} подряд`}><IcFire size={17} />{streak}</span>
      )}
      {!isToday && <button className="chip primary pressable" onClick={onToday}>Сегодня</button>}
      <button className="pressable" aria-label="Профиль и настройки" onClick={onProfile}
        style={{ borderRadius: 22, flex: 'none', boxShadow: 'var(--shadow-1)' }}>
        <Avatar src={avatar} nickname={nickname} size={44} />
      </button>
    </div>
  )
}

/** Неделя: кольцо на каждый день, свайп листает недели. */
function WeekStrip({ date, weekKcal, goal, onSelect }: {
  date: Date; weekKcal: Map<string, number>; goal: number; onSelect: (d: Date) => void
}) {
  const monday = mondayOf(date)
  const today = iso(new Date())
  const touch = useRef<{ x: number; y: number } | null>(null)
  return (
    <div
      style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 6, touchAction: 'pan-y' }}
      onTouchStart={(e) => { touch.current = { x: e.touches[0].clientX, y: e.touches[0].clientY } }}
      onTouchEnd={(e) => {
        const t = touch.current
        touch.current = null
        if (!t) return
        const dx = e.changedTouches[0].clientX - t.x, dy = e.changedTouches[0].clientY - t.y
        if (Math.abs(dx) < 60 || Math.abs(dy) > Math.abs(dx)) return
        const next = addDays(date, dx > 0 ? -7 : 7)
        if (iso(next) <= today) onSelect(next)
        else onSelect(new Date())
      }}
    >
      {WEEK.map((label, i) => {
        const day = addDays(monday, i)
        const key = iso(day)
        const kcal = weekKcal.get(key) ?? 0
        const sel = key === iso(date)
        const future = key > today
        const over = goal > 0 && kcal > goal
        return (
          <button key={key} disabled={future} onClick={() => onSelect(day)} className="pressable"
            aria-label={`${label} ${day.getDate()}: ${Math.round(kcal)} ккал`}
            style={{
              display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6, padding: '8px 0', borderRadius: 18,
              background: sel ? 'var(--primary-cont)' : 'transparent', transition: 'background 280ms'
            }}>
            <span className="label" style={{ color: sel ? 'var(--on-primary-cont)' : undefined, fontWeight: 700 }}>{label}</span>
            <Ring size={36} stroke={3.5} fraction={goal > 0 ? kcal / goal : 0}
              from={over ? 'var(--warn-from)' : 'var(--brand-from)'} to={over ? 'var(--warn-to)' : 'var(--brand-to)'}>
              <span className="num" style={{
                fontSize: 13.5, fontWeight: 800,
                color: future ? 'var(--text-3)' : key === today ? 'var(--primary)' : 'var(--text)'
              }}>{day.getDate()}</span>
            </Ring>
          </button>
        )
      })}
    </div>
  )
}

function Hero({ meals, goal, goals, proteinFirst, onLeaderboard }: {
  meals: Meal[]; goal: number; goals: { protein: number; fat: number; carbs: number }; proteinFirst: boolean; onLeaderboard: () => void
}) {
  const t = dayTotals(meals)
  const over = goal > 0 && t.calories > goal
  const shown = useCountUp(Math.round(t.calories))
  const left = Math.round(Math.abs(goal - t.calories))
  return (
    <div className="card pad-18 row gap-16" style={{ position: 'relative' }}>
      <Ring size={132} stroke={12} fraction={goal > 0 ? t.calories / goal : 0} glow
        from={over ? 'var(--warn-from)' : 'var(--brand-from)'} to={over ? 'var(--warn-to)' : 'var(--brand-to)'}>
        <span className="num" style={{ fontSize: 30, fontWeight: 800, letterSpacing: -1 }}>{Math.round(shown)}</span>
        <span className="label num">из {Math.round(goal)} ккал</span>
      </Ring>
      <div className="grow col gap-10">
        <div>
          <div className="overline">{over ? 'Перебор' : 'Осталось'}</div>
          <div className="num" style={{ fontSize: 20, fontWeight: 800, color: over ? 'var(--tertiary)' : 'var(--primary)' }}>{left} ккал</div>
        </div>
        {proteinFirst
          ? <ProteinFocus v={t.protein} g={goals.protein} />
          : <MacroBar label="Белки" v={t.protein} g={goals.protein} color="var(--protein)" />}
        <MacroBar label="Жиры" v={t.fat} g={goals.fat} color="var(--fat)" />
        <MacroBar label="Углеводы" v={t.carbs} g={goals.carbs} color="var(--carbs)" />
      </div>
      <button aria-label="Лидерборд" onClick={onLeaderboard}
        style={{ position: 'absolute', top: 6, right: 6, width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--warn-to)' }}>
        <IcTrophy size={20} />
      </button>
    </div>
  )
}

function MacroBar({ label, v, g, color }: { label: string; v: number; g: number; color: string }) {
  return (
    <div aria-label={`${label}: ${Math.round(v)} из ${Math.round(g)} грамм`}>
      <div className="row between" style={{ fontSize: 12, fontWeight: 700, marginBottom: 4 }}>
        <span>{label}</span>
        <span className="muted num">{Math.round(v)} / {Math.round(g)} г</span>
      </div>
      <Bar fraction={g > 0 ? v / g : 0} color={color} />
    </div>
  )
}

/** Цель по жиру: белок — вторая главная цифра дня, крупнее остальных макросов. */
function ProteinFocus({ v, g }: { v: number; g: number }) {
  const done = g > 0 && v >= g
  return (
    <div aria-label={`Белок: ${Math.round(v)} из ${Math.round(g)} грамм`}
      style={{ padding: '8px 10px 10px', margin: '0 -2px', borderRadius: 14, background: 'color-mix(in srgb, var(--protein) 9%, transparent)' }}>
      <div className="row between" style={{ marginBottom: 6, gap: 6 }}>
        <span style={{ fontSize: 13, fontWeight: 800, color: 'var(--protein)' }}>Белок</span>
        <span className="num" style={{ fontSize: 15, fontWeight: 800 }}>
          {Math.round(v)}<span className="muted" style={{ fontWeight: 700 }}> / {Math.round(g)} г</span>
        </span>
      </div>
      <Bar fraction={g > 0 ? v / g : 0} color="var(--protein)" height={9} />
      <div className="label" style={{ marginTop: 5, fontSize: 11 }}>{done ? 'Норма есть, мышцы целы' : `ещё ${Math.round(g - v)} г до нормы`}</div>
    </div>
  )
}

function Processing({ count, note }: { count: number; note: string }) {
  return (
    <div className="card r22 pad-14 row gap-12">
      <span className="badge scan" style={{ width: 52, height: 52, borderRadius: 16, background: 'var(--primary-cont)', color: 'var(--on-primary-cont)' }}>
        <IcSpark size={22} />
      </span>
      <div className="grow">
        <div className="title ellipsis">{note || 'Анализ фото'}</div>
        <div className="small">ИИ считает калории и БЖУ{count > 1 ? ` · ещё ${count - 1}` : ''}</div>
      </div>
      <span className="spinner" style={{ color: 'var(--primary)' }} />
    </div>
  )
}

export function MealRow({ meal, fav, onClick }: { meal: Meal; fav: boolean; onClick: () => void }) {
  const hs = healthScoreOf(meal)
  const hc = hs >= 75 ? 'var(--primary)' : hs >= 50 ? 'var(--water-deep)' : hs >= 25 ? 'var(--tertiary)' : 'var(--error)'
  return (
    <button className="card r24 pressable row gap-14" onClick={onClick} style={{ padding: 10, textAlign: 'left' }}>
      {meal.photoIds[0]
        ? <Photo id={meal.photoIds[0]} style={{ width: 60, height: 60, borderRadius: 18, flexShrink: 0 }} />
        : <span className="badge" style={{ width: 60, height: 60, borderRadius: 18, background: 'linear-gradient(135deg, var(--primary-cont), color-mix(in srgb, var(--secondary) 18%, var(--surface)))', color: 'var(--on-primary-cont)' }}><IcCamera size={24} /></span>}
      <div className="grow">
        <div className="title ellipsis" style={{ fontSize: 15 }}>{meal.name}</div>
        <div className="row gap-8" style={{ marginTop: 2 }}>
          <span className="label num">{meal.time}</span>
          <span className="row num" style={{ gap: 4, padding: '1px 7px', borderRadius: 999, fontSize: 12, fontWeight: 800, color: hc, background: `color-mix(in srgb, ${hc} 13%, transparent)` }}>
            <span style={{ width: 6, height: 6, borderRadius: 3, background: hc }} />{hs}
          </span>
          {fav && <span style={{ color: 'var(--warn-to)' }}><IcStar size={14} /></span>}
        </div>
        <div className="row gap-10 label num" style={{ marginTop: 5 }}>
          <Dot c="var(--protein)" t={`Б ${Math.round(meal.protein)}`} />
          <Dot c="var(--fat)" t={`Ж ${Math.round(meal.fat)}`} />
          <Dot c="var(--carbs)" t={`У ${Math.round(meal.carbs)}`} />
        </div>
      </div>
      <div style={{ textAlign: 'right', paddingRight: 6 }}>
        <div className="num" style={{ fontSize: 18, fontWeight: 800 }}>{Math.round(meal.calories)}</div>
        <div className="label">ккал</div>
      </div>
    </button>
  )
}

const Dot = ({ c, t }: { c: string; t: string }) => (
  <span className="row" style={{ gap: 4 }}><span style={{ width: 7, height: 7, borderRadius: 4, background: c }} />{t}</span>
)
