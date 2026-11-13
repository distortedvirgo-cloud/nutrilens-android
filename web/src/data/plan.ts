// Связка плана «Сжигание жира» с хранилищем: собрать вход из IndexedDB,
// пересчитать, если пора, и сохранить в настройки. Сам алгоритм — в fatplan.ts,
// правила чек-ина — в checkin.ts, этапы программы — в program.ts.
import {
  activityBetween, checkins, getSettings, lastCheckin, latestMeasurement, latestWeight, measurements, mealsBetween, putCheckin, saveSettings,
  weightsBetween, workoutsBetween, type Settings
} from './db'
import { activityFactor, bodyProfile, fatGoalOn, livePlan } from './body'
import { type DataStatus, type FatPlan, type PlanInput, PLAN, activityFromSteps, addIsoDays, assessData, computePlan, parsePace, planDue } from './fatplan'
import { type CheckIn, type Ratings, CHECKIN, buildCheckin, checkinDue, checkinPeriod, fatConfirm, nextCheckinDate, trendByCount, trendSeries } from './checkin'
import { iso } from './insights'
import { type DayGrade, SCORE, gradeDay } from './dayscore'
import {
  type PhaseChange, type Program, activePlan, checkinProgram, createProgram, currentPhase, inDeficit, pauseProgram, resumeProgram,
  retargetProgram, tickProgram
} from './program'

const M_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']
const dayMonth = (d: string) => `${Number(d.slice(8, 10))} ${M_GEN[Number(d.slice(5, 7)) - 1]}`

/**
 * Вход для алгоритма; null — режим выключен или веса ещё нет (без веса плана не посчитать).
 * target — цель для предпросмотра программы, когда режим ещё не включён.
 */
export async function loadPlanInput(s?: Settings, target?: number): Promise<PlanInput | null> {
  s ??= await getSettings()
  if (target == null && !fatGoalOn(s)) return null
  const w = await latestWeight()
  if (!w) return null
  const today = iso(new Date())
  const meals = await mealsBetween(addIsoDays(today, -PLAN.windowDays), addIsoDays(today, -1))
  const byDay = new Map<string, number>()
  meals.forEach((m) => byDay.set(m.date, (byDay.get(m.date) ?? 0) + m.calories))
  // Вес и процент жира — по трендам (3.28): одно взвешивание или одна лента не двигают сухую массу.
  const recent = await weightsBetween(addIsoDays(today, -90), today)
  const weightTrend = trendSeries(recent.map((x) => ({ date: x.date, value: x.weight }))).at(-1)?.value ?? w.weight
  const steps = s.stepsEnabled
    ? (await activityBetween(addIsoDays(today, -14), addIsoDays(today, -1))).map((a) => a.steps ?? 0)
    : []
  const phase = s.program?.phases[s.program.currentPhase]
  return {
    female: bodyProfile(s).female ?? false,
    weight: Math.round(weightTrend * 10) / 10,
    weights: await weightsBetween(addIsoDays(today, -(PLAN.windowDays - 1)), today),
    days: [...byDay].map(([date, kcal]) => ({ date, kcal })),
    fatPercent: (await trendFatNow()) ?? null,
    targetFat: target ?? s.targetFatPercent!,
    activity: activityFromSteps(steps) ?? activityFactor(s),
    prev: target != null ? null : s.fatPlan,
    today,
    pace: parsePace(s.fatBurnPace),
    phaseStart: target != null ? null : phase?.startDate ?? null,
    tdeeHint: target != null ? null : s.fatPlan?.tdee ?? null
  }
}

/** Что уже накоплено для расчёта по данным — для карточки плана (живое, не из сохранённого плана). */
export async function planDataStatus(): Promise<DataStatus | null> {
  const input = await loadPlanInput()
  return input ? assessData(input) : null
}

/** Тренд процента жира по замерам — по нему, а не по одному замеру, решаются переходы этапов. */
export async function trendFatNow(): Promise<number | null> {
  const t = trendByCount((await measurements()).map((m) => ({ date: m.date, value: m.fatPercent })))
  return t.at(-1)?.value ?? null
}

// Пересчёт без чек-ина и сохранение чек-ина не должны перетереть друг друга:
// оба пишут fatPlan и program. Идут по очереди — следующий ждёт предыдущий.
let queue: Promise<unknown> = Promise.resolve()
function serial<T>(fn: () => Promise<T>): Promise<T> {
  const run = queue.then(fn, fn)
  queue = run.catch(() => {})
  return run
}

let pending = false

/**
 * Пересчитывает план, если пора: режим только включили, сменили цель или
 * появился первый замер. Недельные изменения — только через чек-ин.
 * Заодно ведёт программу: создаёт её у тех, кто был в режиме до 3.24, и
 * начинает следующий блок, когда 14 дней перерыва прошли.
 * Вызывается при запуске и при изменении данных — дёшево: без пересчёта читает
 * только настройки и последний замер.
 */
export function ensureFatPlan(): Promise<void> {
  // Пока один пересчёт ждёт в очереди, второй не нужен — он прочтёт свежие данные.
  if (pending) return Promise.resolve()
  pending = true
  return serial(async () => {
    pending = false
    let s = await getSettings()
    if (!fatGoalOn(s)) return
    const fatPercent = (await latestMeasurement())?.fatPercent ?? null
    if (planDue(s.fatPlan, { today: iso(new Date()), targetFat: s.targetFatPercent!, fatPercent })) {
      const input = await loadPlanInput(s)
      if (input) {
        const plan = computePlan(input)
        const last = await lastCheckin()
        s = await saveSettings({ fatPlan: { ...plan, nextDate: nextCheckinDate(last?.date ?? plan.date, s.checkinWeekday) } })
      }
    }
    if (!s.program) await migrateToProgram(s)
    else await tickBreak(s)
  })
}

/**
 * Был в режиме до 3.24 — программы нет. Создаём её без мастера запуска: блок 1
 * начался тогда же, когда план (первый чек-ин или дата плана), и один раз
 * показываем карточку, что режим стал программой.
 */
async function migrateToProgram(s: Settings): Promise<void> {
  const today = iso(new Date())
  const ms = await measurements()
  const cis = await checkins()
  const starts = [cis[0]?.periodStart, s.fatPlan?.date].filter((d): d is string => !!d).sort()
  const start = starts[0] ?? ms[0]?.date ?? today
  const ws = (await weightsBetween('1900-01-01', today)).sort((a, b) => a.date.localeCompare(b.date))
  const w = [...ws].reverse().find((x) => x.date <= start) ?? ws[0]
  if (!w) return // без веса нет и плана — создадим, когда вес появится
  const m = [...ms].reverse().find((x) => x.date <= start) ?? ms[0]
  const female = bodyProfile(s).female ?? false
  const fat = m?.fatPercent ?? s.fatPlan?.fatPercent ?? (female ? 32 : 25)
  const p = createProgram({ today, startDate: start, fat, weight: w.weight, female, target: s.targetFatPercent! })
  // Счётчик «вес стоит» переносим: плато могло начаться ещё до перехода.
  p.phases[0] = { ...p.phases[0], checkins: cis.length, flatWeeks: cis.at(-1)?.flatWeeks ?? 0 }
  p.notice = {
    title: 'Режим стал программой: этапы и перерывы',
    text: `Теперь это путь из этапов: блоки дефицита до 8 недель и обязательные 2 недели перерыва на уровне расхода между ними. Твой первый блок идёт с ${dayMonth(start)}. Этапы меняются на чек-ине — весь путь виден в «Прогрессе».`
  }
  await saveSettings({ program: p })
}

/** 14 дней перерыва прошли — следующий блок со свежим дефицитом, не дожидаясь чек-ина. */
async function tickBreak(s: Settings): Promise<void> {
  const today = iso(new Date())
  const { program, change } = tickProgram(s.program!, today, await trendFatNow())
  if (!change) return
  const input = await loadPlanInput(s)
  const fresh = input ? computePlan({ ...input, prev: null }) : null
  await saveSettings({
    program: { ...program, notice: { title: change.title, text: change.text } },
    ...(fresh ? { fatPlan: { ...fresh, note: 'Новый блок — свежий дефицит по твоему текущему расходу', nextDate: s.fatPlan?.nextDate ?? fresh.nextDate } } : {})
  })
}

export interface CheckinStatus {
  due: boolean
  /** Дата ближайшего чек-ина (или с какого дня он ждёт). */
  next: string
  last: CheckIn | null
}

/** Пора ли чек-ин; null — режим выключен или плана ещё нет (нечего подводить). На паузе чек-ин не ждёт. */
export async function checkinStatus(s?: Settings): Promise<CheckinStatus | null> {
  s ??= await getSettings()
  if (!fatGoalOn(s) || !s.fatPlan) return null
  const last = (await lastCheckin()) ?? null
  // Новая программа после старой: отсчёт с её старта, иначе чек-ин «ждал» бы с прошлого раза.
  let anchor = last?.date ?? s.fatPlan.date
  if (s.program && s.program.startDate > anchor) anchor = s.program.startDate
  const today = iso(new Date())
  const paused = !!s.program?.pausedSince
  return { due: !paused && checkinDue(anchor, s.checkinWeekday, today), next: nextCheckinDate(anchor, s.checkinWeekday), last }
}

/** Тексты итога чек-ина, когда этап сменился или идёт перерыв/удержание. */
function phaseTexts(change: PhaseChange | null, before: Program, after: Program, live: FatPlan): { note: string; action: string } | null {
  const kcal = `≈ ${live.calories} ккал`
  if (change) {
    if (change.to === 'block') return { note: `${change.text} План: ${live.calories} ккал, белок ${live.protein} г.`, action: `Новый блок: держи ${live.calories} ккал и белок ${live.protein} г, силовые — как обычно.` }
    if (change.to === 'completed') return { note: change.text, action: 'Выбери на «Сегодня»: остаться на удержании или выключить режим.' }
    return { note: `${change.text} Сейчас это ${kcal}.`, action: `Ешь около ${live.calories} ккал и держи белок ${live.protein} г — это часть плана, не срыв.` }
  }
  if (inDeficit(before)) return null
  const cur = currentPhase(after)
  const where = before.pausedSince ? 'Программа на паузе' : before.completedDate ? 'Удержание' : cur.type === 'break' ? 'Перерыв' : 'Удержание'
  return {
    note: `${where}: калории на уровне расхода — ${kcal}. Расход уточнил по твоим данным.`,
    action: `Ешь около ${live.calories} ккал и держи белок ${live.protein} г.`
  }
}

/**
 * Подводит неделю: собирает дневник, силовые, вес и замеры за период, считает
 * итог и новый план, ведёт этап программы, сохраняет чек-ин, план и программу.
 * prevPlan — план на момент открытия чек-ина (в перерыве — на уровне расхода):
 * замер внутри чек-ина мог уже пересчитать план, а «было» должно показывать то,
 * по чему человек жил неделю.
 */
export function completeCheckin(ratings: Ratings, prevPlan: FatPlan): Promise<CheckIn | null> {
  return serial(async () => {
    const s = await getSettings()
    const input = await loadPlanInput(s)
    if (!input || !s.fatPlan) return null
    const today = input.today
    const prev = (await lastCheckin()) ?? null
    const { start } = checkinPeriod(prev?.date ?? prevPlan.date, today)
    const meals = await mealsBetween(start, addIsoDays(today, -1))
    const byDay = new Map<string, { kcal: number; protein: number }>()
    meals.forEach((m) => {
      const d = byDay.get(m.date) ?? { kcal: 0, protein: 0 }
      byDay.set(m.date, { kcal: d.kcal + m.calories, protein: d.protein + m.protein })
    })
    const allMs = await measurements()
    const { checkin, plan, breakNote } = buildCheckin({
      today,
      plan: { ...input, prev: prevPlan },
      days: [...byDay].map(([date, v]) => ({ date, ...v })),
      workouts: await workoutsBetween(start, addIsoDays(today, -1)),
      activity: await activityBetween(start, addIsoDays(today, -1)),
      stepGoal: s.stepGoal || SCORE.stepGoalDefault,
      // Месяц до начала периода — тренд успевает «разогреться» и не стартует с сырой цифры.
      weights: await weightsBetween(addIsoDays(start, -30), today),
      measurements: allMs.map((m) => ({ date: m.date, fatPercent: m.fatPercent })),
      workoutGoal: s.workoutGoal || CHECKIN.workoutGoalDefault,
      prev,
      ratings,
      phaseStart: s.program?.phases[s.program.currentPhase]?.startDate ?? null,
      inBlock: !!s.program && s.program.phases[s.program.currentPhase]?.type === 'block' && !s.program.pausedSince && !s.program.completedDate
    })
    const nextDate = nextCheckinDate(today, s.checkinWeekday)
    const before = s.program
    if (!before) {
      // Программы ещё нет (создастся при следующем запуске) — как в 3.23.
      await saveSettings({ fatPlan: { ...plan, nextDate } })
      await putCheckin(checkin)
      return checkin
    }

    // Этап программы: переходы — только здесь (и конец перерыва при открытии).
    const fatPoints = allMs.map((m) => ({ date: m.date, value: m.fatPercent }))
    const fatTrend = trendByCount(fatPoints).at(-1)?.value ?? null
    const { program, change } = checkinProgram(before, {
      today,
      trendFat: fatTrend,
      confirmFat: fatConfirm(fatPoints),
      flat: checkin.ratePerWeek != null && checkin.ratePerWeek < CHECKIN.flat,
      diaryComplete: checkin.diaryDays >= Math.ceil(CHECKIN.diaryDays * checkin.periodDays / 7),
      weight: input.weight
    })

    // Хранимый план — всегда дефицитный план блока. В блоке он меняется по
    // правилам чек-ина; в перерыве и удержании калории блока «замораживаются»,
    // а расход уточняется — по нему считаются калории на уровне расхода.
    let stored: FatPlan
    if (change?.to === 'block') stored = { ...computePlan({ ...input, prev: null }), note: 'Новый блок — свежий дефицит по твоему текущему расходу' }
    else if (inDeficit(before)) stored = plan
    else stored = { ...s.fatPlan, tdee: plan.tdee, bmr: plan.bmr, observed: plan.observed, actualRate: plan.actualRate, weight: plan.weight, fatPercent: plan.fatPercent, date: today }
    stored = { ...stored, date: today, nextDate }

    const live = activePlan(stored, program)!
    const texts = phaseTexts(change, before, program, live)
    const out: CheckIn = {
      ...checkin,
      planAfter: { calories: live.calories, protein: live.protein, fat: live.fat, carbs: live.carbs },
      phaseTitle: change?.title ?? null,
      phaseText: change?.text ?? null
    }
    if (texts) { out.note = texts.note; out.action = texts.action }
    else if (breakNote) {
      // Совет 3.22 «поешь 1–2 недели на уровне расхода» теперь — обязательный перерыв
      // по плато внутри блока. Здесь плато блока ещё не набралось — не советуем лишнего.
      out.note = 'Вес стоит не первую неделю. Если так пойдёт и дальше в этом блоке, перерыв начнётся раньше — это часть плана.'
      out.action = 'Продолжай так же: записывай еду и взвешивайся 3–4 раза в неделю.'
    }
    await saveSettings({ fatPlan: stored, program: { ...program, notice: null } })
    await putCheckin(out)
    return out
  })
}

/**
 * Оценки дней за даты (включительно) — для полосы недели и «Прогресса».
 * Прошлые дни считаем от текущего плана (в перерыве — на уровне расхода):
 * исторических планов по дням нет, а шаг плана за неделю — не больше ±150 ккал.
 * null — режим выключен или плана ещё нет.
 */
export async function dayGrades(start: string, end: string, s?: Settings): Promise<Map<string, DayGrade> | null> {
  s ??= await getSettings()
  const plan = livePlan(s)
  if (!plan) return null
  const today = iso(new Date())
  const food = new Map<string, { kcal: number; protein: number }>()
  for (const m of await mealsBetween(start, end)) {
    const d = food.get(m.date) ?? { kcal: 0, protein: 0 }
    food.set(m.date, { kcal: d.kcal + m.calories, protein: d.protein + m.protein })
  }
  const workouts = new Set(await workoutsBetween(start, end))
  const act = new Map((await activityBetween(start, end)).map((a) => [a.date, a]))
  const t = { calories: plan.calories, protein: plan.protein, stepGoal: s.stepGoal || SCORE.stepGoalDefault }
  const out = new Map<string, DayGrade>()
  for (let d = start; d <= end; d = addIsoDays(d, 1)) {
    if (d > today) break
    const f = food.get(d)
    out.set(d, gradeDay({ kcal: f?.kcal ?? 0, protein: f?.protein ?? 0, workout: workouts.has(d) }, act.get(d) ?? null, t, d === today))
  }
  return out
}

// ---------- Запуск, пауза, выключение ----------

/** Предпросмотр для мастера запуска: программа и план на сегодня, режим ещё не включён. */
export async function previewProgram(target: number): Promise<{ program: Program; plan: FatPlan } | null> {
  const s = await getSettings()
  const input = await loadPlanInput(s, target)
  const m = await latestMeasurement()
  if (!input || !m) return null
  const plan = computePlan(input)
  return { program: createProgram({ today: input.today, fat: m.fatPercent, weight: input.weight, female: input.female, target, pace: input.pace }), plan }
}

/** «Начать программу»: включает режим, план и программу одним сохранением. */
export function startProgram(target: number): Promise<boolean> {
  return serial(async () => {
    const pv = await previewProgram(target)
    if (!pv) return false
    const s = await getSettings()
    await saveSettings({
      targetFatPercent: target,
      fatPlan: { ...pv.plan, nextDate: nextCheckinDate(pv.plan.date, s.checkinWeekday) },
      program: pv.program
    })
    return true
  })
}

/**
 * Сменить темп (3.28): в блоке — сразу свежий план под новый темп, в перерыве и
 * удержании — только запоминаем (понадобится в следующем блоке).
 */
export function setPace(key: string): Promise<void> {
  return serial(async () => {
    const pace = parsePace(key)
    if (!pace) return
    let s = await saveSettings({ fatBurnPace: pace })
    if (!fatGoalOn(s) || (s.program && !inDeficit(s.program))) return
    const input = await loadPlanInput(s)
    if (!input) return
    const fresh = computePlan({ ...input, prev: null })
    s = await saveSettings({ fatPlan: { ...fresh, note: `Новый темп — план пересчитан: ${fresh.note.charAt(0).toLowerCase()}${fresh.note.slice(1)}`, nextDate: s.fatPlan?.nextDate ?? fresh.nextDate } })
  })
}

/** Выключить режим: программа заканчивается, ручные цели снова действуют. */
export function endProgram(): Promise<void> {
  return serial(async () => { await saveSettings({ targetFatPercent: null, fatPlan: null, program: null }) })
}

export function setProgramPaused(paused: boolean): Promise<void> {
  return serial(async () => {
    const s = await getSettings()
    if (!s.program) return
    const today = iso(new Date())
    await saveSettings({ program: paused ? pauseProgram(s.program, today) : resumeProgram(s.program, today) })
  })
}

/** Сменили цель посреди программы — блоки до неё пересчитываются от текущего блока. */
export function retarget(target: number): Promise<void> {
  return serial(async () => {
    const s = await getSettings()
    const w = await latestWeight()
    const patch: Partial<Settings> = { targetFatPercent: target }
    if (s.program && w) patch.program = retargetProgram(s.program, target, await trendFatNow(), w.weight)
    await saveSettings(patch)
  })
}

export function updateProgram(patch: Partial<Program>): Promise<void> {
  return serial(async () => {
    const s = await getSettings()
    if (s.program) await saveSettings({ program: { ...s.program, ...patch } })
  })
}
