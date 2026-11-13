// Программа режима «Сжигание жира» (3.24): блоки дефицита по 8 недель,
// обязательные перерывы по 2 недели на уровне расхода между ними, удержание
// 4 недели после цели. Чистый модуль, как fatplan.ts и checkin.ts: без IndexedDB,
// React и «сейчас» — всё приходит во входе, поэтому правила легко проверить
// скриптом. Логика одинакова с Android; пороги — в PROGRAM.
import { type FatPlan, PLAN, addIsoDays, daysBetween, macrosFor, planFloor, plural } from './fatplan'

export const PROGRAM = {
  /** Блок дефицита: 8 недель; последний может быть короче, но не меньше 3. */
  blockWeeks: 8,
  minBlockWeeks: 3,
  /** Перерыв на уровне расхода между блоками — 14 дней, обязательный. */
  breakDays: 14,
  /** Удержание после цели — 4 недели. */
  maintenanceWeeks: 4,
  /** Цель считается достигнутой с запасом 0,5%: домашние замеры шумят. */
  reachedMargin: 0.5,
  /** Вес стоит 3 недели внутри блока — перерыв начинается раньше. */
  plateauWeeks: 3,
  /**
   * Чек-ины идут раз в неделю в выбранный день, а этап мог начаться в другой.
   * Поэтому «8 недель прошло» засчитываем на ближайшем чек-ине: за 3 дня до срока.
   */
  dueSlackDays: 3,
  /** Вилка прогноза: оценка × 0,9 … × 1,3 — жир уходит неровно, честнее диапазон. */
  rangeLo: 0.9,
  rangeHi: 1.3,
  /** Нижняя граница цели и порог предупреждения, % жира. */
  floorMale: 10,
  floorFemale: 18,
  warnMale: 12,
  warnFemale: 20
} as const

export type PhaseType = 'block' | 'break' | 'maintenance'
/** time — вышло время; target — промежуточная цель раньше срока; plateau — вес стоял 3 недели; final — цель всей программы; done — удержание отработано. */
export type EndReason = 'time' | 'target' | 'plateau' | 'final' | 'done'

export interface Phase {
  type: PhaseType
  /** Блок — номер 1..N; перерыв — номер блока перед ним; удержание — 1. */
  index: number
  /** Дата начала; null — этап ещё впереди. */
  startDate: string | null
  plannedWeeks: number
  /** Промежуточная цель блока, % жира; у перерыва и удержания — null. */
  targetFat: number | null
  /** % жира на старте этапа — для бейджа «21% → 17%». */
  startFat: number | null
  endedDate: string | null
  endReason: EndReason | null
  /** Дни паузы внутри этапа: в 8 недель блока не считаются. */
  pausedDays: number
  /** Чек-ины внутри этапа и сколько из них подряд «вес стоит» — плато считаем только внутри блока. */
  checkins: number
  flatWeeks: number
}

export interface ProgramNotice { title: string; text: string }

export interface Program {
  startDate: string
  startFat: number
  startWeight: number
  target: number
  female: boolean
  phases: Phase[]
  /** Индекс текущего этапа в phases. */
  currentPhase: number
  /** Пауза: с какой даты; null — программа идёт. */
  pausedSince: string | null
  /** Удержание отработано — программа завершена (дата). */
  completedDate: string | null
  /** После завершения выбрал «Остаться на удержании». */
  stayMaintenance: boolean
  /** Разовая карточка на «Сегодня»: смена этапа при открытии, переход со старого режима. */
  notice: ProgramNotice | null
}

export interface PhaseChange {
  /** Во что перешли. */
  to: 'break' | 'block' | 'maintenance' | 'completed'
  reason: EndReason
  /** «Блок 1 завершён → 2 недели перерыва». */
  title: string
  text: string
}

// ---------- Мелочи ----------

const round05 = (v: number) => Math.round(v * 2) / 2
const round10 = (v: number) => Math.round(v / 10) * 10
const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))
const f1 = (v: number) => (Math.round(v * 10) / 10).toString().replace('.', ',')
export const fmtPct = (v: number) => `${f1(v)}%`
const weeksWord = (n: number) => `${n} ${plural(n, 'неделя', 'недели', 'недель')}`
const daysWord = (n: number) => `${n} ${plural(n, 'день', 'дня', 'дней')}`

export const targetFloor = (female: boolean) => (female ? PROGRAM.floorFemale : PROGRAM.floorMale)
export const targetWarn = (female: boolean) => (female ? PROGRAM.warnFemale : PROGRAM.warnMale)

const emptyPhase = (type: PhaseType, index: number, plannedWeeks: number, targetFat: number | null): Phase => ({
  type, index, startDate: null, plannedWeeks, targetFat, startFat: null,
  endedDate: null, endReason: null, pausedDays: 0, checkins: 0, flatWeeks: 0
})

// ---------- Генерация ----------

/**
 * Сколько недель дефицита до цели по темпу планировщика: 0,6% веса в неделю,
 * 0,4% — когда до цели ≤ 3% или уже сухо (то же правило, что в computePlan).
 * Сухая масса считается неизменной — ради этого и белок, и силовые.
 */
export function estimateWeeks(fat: number, target: number, weight: number, female: boolean): number {
  if (!(weight > 0) || fat <= target) return 0
  const lean = weight * (1 - fat / 100)
  let w = weight, f = fat, weeks = 0
  while (f > target && weeks < 520) {
    const slow = f - target <= 3 || f < (female ? 23 : 15)
    w -= w * (slow ? PLAN.slowRate : PLAN.rate) / 100
    f = ((w - lean) / w) * 100
    weeks++
  }
  return weeks
}

/** Длины блоков: по 8 недель, последний — остаток, но не меньше 3. Всегда хотя бы один блок. */
export function blockLengths(totalWeeks: number): number[] {
  const n = Math.max(1, Math.ceil(totalWeeks / PROGRAM.blockWeeks))
  return Array.from({ length: n }, (_, i) =>
    i < n - 1 ? PROGRAM.blockWeeks : Math.max(PROGRAM.minBlockWeeks, totalWeeks - PROGRAM.blockWeeks * (n - 1)))
}

/** Промежуточные цели: поровну от старта до цели, с шагом 0,5%; последняя — ровно цель. */
export function blockTargets(startFat: number, target: number, n: number): number[] {
  return Array.from({ length: n }, (_, i) => (i === n - 1 ? target : round05(startFat - ((startFat - target) * (i + 1)) / n)))
}

/** Этапы от текущего процента: блок, перерыв, блок, …, удержание. Номера блоков — с firstIndex. */
export function buildPhases(fromFat: number, target: number, weight: number, female: boolean, firstIndex = 1): Phase[] {
  const lengths = blockLengths(estimateWeeks(fromFat, target, weight, female))
  const targets = blockTargets(fromFat, target, lengths.length)
  const out: Phase[] = []
  lengths.forEach((w, i) => {
    if (i > 0) out.push(emptyPhase('break', firstIndex + i - 1, PROGRAM.breakDays / 7, null))
    out.push(emptyPhase('block', firstIndex + i, w, targets[i]))
  })
  out.push(emptyPhase('maintenance', 1, PROGRAM.maintenanceWeeks, null))
  return out
}

export interface CreateInput {
  today: string
  /** С какой даты считать первый блок (переход со старого режима); по умолчанию — сегодня. */
  startDate?: string
  fat: number
  weight: number
  female: boolean
  target: number
}

export function createProgram(i: CreateInput): Program {
  const start = i.startDate ?? i.today
  const phases = buildPhases(i.fat, i.target, i.weight, i.female)
  phases[0] = { ...phases[0], startDate: start, startFat: i.fat }
  return {
    startDate: start, startFat: i.fat, startWeight: i.weight, target: i.target, female: i.female,
    phases, currentPhase: 0, pausedSince: null, completedDate: null, stayMaintenance: false, notice: null
  }
}

/** Номера блоков по порядку, перерыв — номер блока перед ним. */
function renumber(phases: Phase[]): Phase[] {
  let b = 0
  return phases.map((p) => {
    if (p.type === 'block') return { ...p, index: ++b }
    if (p.type === 'break') return { ...p, index: b }
    return p
  })
}

/**
 * Сменили цель посреди программы: пройденные этапы остаются, текущий блок
 * и всё после — заново от процента на старте блока (перерыв — от текущего).
 */
export function retargetProgram(p: Program, target: number, fatNow: number | null, weight: number): Program {
  const cur = p.phases[p.currentPhase]
  if (p.completedDate || cur.type === 'maintenance') return { ...p, target }
  const from = cur.type === 'block' ? (cur.startFat ?? fatNow ?? p.startFat) : (fatNow ?? p.startFat)
  const fresh = buildPhases(from, target, weight, p.female)
  const done = p.phases.slice(0, p.currentPhase)
  const phases = cur.type === 'block'
    ? [...done, { ...fresh[0], startDate: cur.startDate, startFat: cur.startFat, pausedDays: cur.pausedDays, checkins: cur.checkins, flatWeeks: cur.flatWeeks }, ...fresh.slice(1)]
    : [...done, cur, ...fresh]
  return { ...p, target, phases: renumber(phases) }
}

// ---------- Где мы ----------

export const currentPhase = (p: Program): Phase => p.phases[Math.min(p.currentPhase, p.phases.length - 1)]
export const blockCount = (p: Program): number => p.phases.filter((x) => x.type === 'block').length

/** Активные дни этапа: с начала до конца (или сегодня) минус паузы. */
export function activeDays(p: Program, i: number, today: string): number {
  const ph = p.phases[i]
  if (!ph?.startDate) return 0
  let d = daysBetween(ph.startDate, ph.endedDate ?? today) - ph.pausedDays
  if (i === p.currentPhase && p.pausedSince && !ph.endedDate) {
    d -= daysBetween(p.pausedSince > ph.startDate ? p.pausedSince : ph.startDate, today)
  }
  return Math.max(0, d)
}

/**
 * Дефицит или уровень расхода. Оранжевый и дефицит — только в блоке без паузы;
 * перерыв, удержание, пауза и завершённая программа — расход и спокойный зелёный.
 * Программы нет (старые данные до перехода) — как раньше, дефицит.
 */
export function inDeficit(p: Program | null | undefined): boolean {
  if (!p) return true
  return !p.pausedSince && !p.completedDate && currentPhase(p).type === 'block'
}

/**
 * План на уровне расхода для перерыва, удержания и паузы: калории = расход
 * (по данным, если хватает, иначе по формуле — его уже посчитал планировщик и
 * уточняет на чек-ине), белок по тому же правилу, жир 0,8 г/кг, остальное — углеводы.
 */
export function maintenancePlan(plan: FatPlan, female: boolean): FatPlan {
  const calories = Math.max(round10(plan.tdee), planFloor(female, plan.bmr))
  const m = macrosFor(calories, plan.weight, plan.fatPercent ?? (female ? 32 : 25))
  return { ...plan, calories, ...m }
}

/** План, по которому живём сегодня: дефицит блока или расход — по этапу. */
export function activePlan(plan: FatPlan | null, p: Program | null | undefined): FatPlan | null {
  if (!plan) return null
  return inDeficit(p) ? plan : maintenancePlan(plan, p!.female)
}

// ---------- Переходы ----------

export interface CheckinFacts {
  today: string
  /** Тренд процента жира по замерам; null — замеров нет. */
  trendFat: number | null
  /** Неделя «вес стоит» по тренду и дневник полный (правило чек-ина). */
  flat: boolean
  diaryComplete: boolean
  weight: number
}

function startPhase(p: Program, i: number, today: string, fat: number | null): Program {
  const phases = p.phases.map((ph, k) => (k === i ? { ...ph, startDate: today, startFat: fat, pausedDays: 0, checkins: 0, flatWeeks: 0 } : ph))
  return { ...p, phases, currentPhase: i }
}

function endCurrent(p: Program, today: string, reason: EndReason): Program {
  const phases = p.phases.map((ph, k) => (k === p.currentPhase ? { ...ph, endedDate: today, endReason: reason } : ph))
  return { ...p, phases }
}

const dueDays = (weeks: number) => weeks * 7 - PROGRAM.dueSlackDays

/** Перерыв после текущего блока; был последний — добавляем перерыв и ещё блок до цели. */
function goToBreak(p: Program, f: CheckinFacts, reason: EndReason): { program: Program; change: PhaseChange } {
  const cur = currentPhase(p)
  let q = endCurrent(p, f.today, reason)
  let extra = false
  if (q.phases[q.currentPhase + 1]?.type !== 'break') {
    // Последний блок закончился, а до цели не дошли: ещё один блок — после перерыва.
    const from = f.trendFat ?? cur.targetFat ?? q.target
    const weeks = clamp(estimateWeeks(from, q.target, f.weight, q.female), PROGRAM.minBlockWeeks, PROGRAM.blockWeeks)
    const phases = [...q.phases]
    phases.splice(q.currentPhase + 1, 0, emptyPhase('break', 0, PROGRAM.breakDays / 7, null), emptyPhase('block', 0, weeks, q.target))
    q = { ...q, phases: renumber(phases) }
    extra = true
  }
  q = startPhase(q, q.currentPhase + 1, f.today, f.trendFat)
  const t = cur.targetFat
  const why = reason === 'target'
    ? `Промежуточная цель ${t}% достигнута раньше срока — блок засчитан.`
    : reason === 'plateau'
      ? `Вес стоял ${PROGRAM.plateauWeeks} недели подряд — перерыв начинается раньше. Две недели на уровне расхода часто сдвигают плато.`
      : f.trendFat != null && t != null
        ? `${weeksWord(cur.plannedWeeks)} позади. До промежуточной цели ${t}% пока не дошли (сейчас ${fmtPct(f.trendFat)}) — это нормально, продолжим после перерыва.`
        : `${weeksWord(cur.plannedWeeks)} позади — время отдохнуть.`
  return {
    program: q,
    change: {
      to: 'break', reason,
      title: `Блок ${cur.index} завершён → 2 недели перерыва`,
      text: `${why}${extra ? ` Добавил ещё один блок до цели ${q.target}%.` : ''} Перерыв — обязательная часть плана: ешь на уровне расхода.`
    }
  }
}

function goToMaintenance(p: Program, f: CheckinFacts): { program: Program; change: PhaseChange } {
  let q = endCurrent(p, f.today, 'final')
  // Будущие блоки и перерывы больше не нужны — цель уже здесь.
  const keep = q.phases.slice(0, q.currentPhase + 1)
  const m = q.phases.find((x) => x.type === 'maintenance') ?? emptyPhase('maintenance', 1, PROGRAM.maintenanceWeeks, null)
  q = { ...q, phases: [...keep, { ...m, startDate: null }] }
  q = startPhase(q, keep.length, f.today, f.trendFat)
  return {
    program: q,
    change: {
      to: 'maintenance', reason: 'final',
      title: `Цель ${q.target}% достигнута → ${PROGRAM.maintenanceWeeks} недели удержания`,
      text: `Следующие ${PROGRAM.maintenanceWeeks} недели — на уровне расхода: закрепляем результат, белок прежний. Расход уточняю на каждом чек-ине.`
    }
  }
}

function goToNextBlock(p: Program, today: string, fat: number | null): { program: Program; change: PhaseChange } {
  let q = endCurrent(p, today, 'time')
  q = startPhase(q, q.currentPhase + 1, today, fat ?? p.phases[p.currentPhase - 1]?.targetFat ?? null)
  const b = currentPhase(q)
  return {
    program: q,
    change: {
      to: 'block', reason: 'time',
      title: `Перерыв окончен → блок ${b.index} из ${blockCount(q)}`,
      text: `Две недели на уровне расхода позади — теперь свежий дефицит${b.targetFat != null ? `, цель блока ${b.targetFat}%` : ''}.`
    }
  }
}

/**
 * Этапы меняются только на чек-ине. Порядок проверок важен: сначала цель всей
 * программы, потом промежуточная, потом плато, потом время.
 */
export function checkinProgram(p: Program, f: CheckinFacts): { program: Program; change: PhaseChange | null } {
  if (p.pausedSince || p.completedDate) return { program: p, change: null }
  const i = p.currentPhase
  const cur0 = currentPhase(p)
  // Плато считаем только внутри блока: в перерыве вес и должен стоять.
  const flatWeeks = cur0.type !== 'block' ? 0 : !f.flat ? 0 : cur0.flatWeeks + (f.diaryComplete ? 1 : 0)
  const q: Program = { ...p, phases: p.phases.map((ph, k) => (k === i ? { ...ph, checkins: ph.checkins + 1, flatWeeks } : ph)) }
  const cur = currentPhase(q)
  const days = activeDays(q, i, f.today)
  const final = f.trendFat != null && f.trendFat <= q.target + PROGRAM.reachedMargin

  if (cur.type === 'maintenance') {
    if (days < dueDays(cur.plannedWeeks)) return { program: q, change: null }
    const done = { ...endCurrent(q, f.today, 'done'), completedDate: f.today }
    return {
      program: done,
      change: { to: 'completed', reason: 'done', title: 'Программа завершена', text: `${weeksWord(cur.plannedWeeks)} удержания позади — результат закреплён. Итог и что дальше — на «Сегодня».` }
    }
  }
  if (final) return goToMaintenance(q, f)
  if (cur.type === 'break') {
    return days >= PROGRAM.breakDays ? goToNextBlock(q, f.today, f.trendFat) : { program: q, change: null }
  }
  if (f.trendFat != null && cur.targetFat != null && f.trendFat <= cur.targetFat) return goToBreak(q, f, 'target')
  if (cur.flatWeeks >= PROGRAM.plateauWeeks) return goToBreak(q, f, 'plateau')
  if (days >= dueDays(cur.plannedWeeks)) return goToBreak(q, f, 'time')
  return { program: q, change: null }
}

/**
 * При открытии приложения: перерыв закончился ровно через 14 дней — следующий
 * блок начинается сразу, не дожидаясь чек-ина. Остальные переходы — только на чек-ине.
 */
export function tickProgram(p: Program, today: string, fat: number | null): { program: Program; change: PhaseChange | null } {
  if (p.pausedSince || p.completedDate) return { program: p, change: null }
  const cur = currentPhase(p)
  if (cur.type !== 'break' || activeDays(p, p.currentPhase, today) < PROGRAM.breakDays) return { program: p, change: null }
  return goToNextBlock(p, today, fat)
}

export function pauseProgram(p: Program, today: string): Program {
  return p.pausedSince ? p : { ...p, pausedSince: today }
}

/** Продолжить: дни паузы уходят в pausedDays этапа и в его недели не считаются. */
export function resumeProgram(p: Program, today: string): Program {
  if (!p.pausedSince) return p
  const i = p.currentPhase
  const ph = p.phases[i]
  const from = ph.startDate && p.pausedSince < ph.startDate ? ph.startDate : p.pausedSince
  const add = Math.max(0, daysBetween(from, today))
  return { ...p, pausedSince: null, phases: p.phases.map((x, k) => (k === i ? { ...x, pausedDays: x.pausedDays + add } : x)) }
}

// ---------- Тексты ----------

/** Неделя этапа, 1..plannedWeeks. */
export function phaseWeek(p: Program, today: string): number {
  const cur = currentPhase(p)
  return clamp(Math.floor(activeDays(p, p.currentPhase, today) / 7) + 1, 1, Math.max(1, cur.plannedWeeks))
}

/** Бейдж на «Сегодня»: «🔥 Блок 2 из 3 · неделя 5 · 21% → 17%», «Перерыв · ещё 9 дней», «Удержание · неделя 2», «Пауза». */
export function phaseBadge(p: Program, today: string): string {
  if (p.pausedSince) return 'Пауза'
  if (p.completedDate) return p.stayMaintenance ? 'Удержание' : 'Программа завершена'
  const cur = currentPhase(p)
  if (cur.type === 'break') {
    const left = Math.max(1, PROGRAM.breakDays - activeDays(p, p.currentPhase, today))
    return `Перерыв · ещё ${daysWord(left)}`
  }
  if (cur.type === 'maintenance') return `Удержание · неделя ${phaseWeek(p, today)}`
  const from = cur.startFat != null ? `${Math.round(cur.startFat)}%` : null
  return `🔥 Блок ${cur.index} из ${blockCount(p)} · неделя ${phaseWeek(p, today)}${cur.targetFat != null ? ` · ${from ? `${from} → ` : 'цель '}${f1(cur.targetFat)}%` : ''}`
}

/** Одна строка «что делать на этом этапе» для карточки на «Сегодня». plan — дефицитный план (для расхода). */
export function phaseLine(p: Program | null, plan: FatPlan): string {
  const deficit = Math.max(0, round10(plan.tdee - plan.calories))
  if (!p || inDeficit(p)) return `Дефицит ${deficit} ккал, белок ${plan.protein} г`
  const m = maintenancePlan(plan, p.female)
  if (p.pausedSince) return `Пауза: ешь на уровне расхода ≈ ${m.calories} ккал — этапы стоят и подождут`
  if (p.completedDate) return `Удержание: ешь на уровне расхода ≈ ${m.calories} ккал, белок ${m.protein} г`
  if (currentPhase(p).type === 'break') return `Ешь на уровне расхода (≈ ${m.calories} ккал) — это часть плана, не срыв`
  return `Удержание: ≈ ${m.calories} ккал, белок ${m.protein} г — закрепляем результат`
}

/** Сколько недель до цели осталось по плану (без удержания); null — цель уже позади. */
export function remainingWeeks(p: Program, today: string): number | null {
  if (p.completedDate) return null
  const cur = currentPhase(p)
  if (cur.type === 'maintenance') return null
  const days = activeDays(p, p.currentPhase, today)
  let w = cur.type === 'break' ? Math.max(0, PROGRAM.breakDays - days) / 7 : Math.max(1, cur.plannedWeeks - days / 7)
  for (const ph of p.phases.slice(p.currentPhase + 1)) if (ph.type !== 'maintenance') w += ph.plannedWeeks
  return w
}

const MONTHS = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь']

/** «март – апрель 2027», «декабрь 2026 – февраль 2027», «март 2027». */
export function monthRange(a: string, b: string): string {
  const [ya, ma] = a.split('-').map(Number), [yb, mb] = b.split('-').map(Number)
  if (ya === yb && ma === mb) return `${MONTHS[ma - 1]} ${ya}`
  if (ya === yb) return `${MONTHS[ma - 1]} – ${MONTHS[mb - 1]} ${yb}`
  return `${MONTHS[ma - 1]} ${ya} – ${MONTHS[mb - 1]} ${yb}`
}

export interface Forecast { loWeeks: number; hiWeeks: number; loDate: string; hiDate: string; text: string }

/** Прогноз достижения цели — вилкой, а не одной датой: оценка × [0,9; 1,3], в неделях. */
export function forecast(p: Program, today: string): Forecast | null {
  const w = remainingWeeks(p, today)
  if (w == null) return null
  const lo = Math.max(1, Math.round(w * PROGRAM.rangeLo))
  const hi = Math.max(lo, Math.round(w * PROGRAM.rangeHi))
  const loDate = addIsoDays(today, lo * 7), hiDate = addIsoDays(today, hi * 7)
  return { loWeeks: lo, hiWeeks: hi, loDate, hiDate, text: monthRange(loDate, hiDate) }
}

export const phaseName = (ph: Phase): string =>
  ph.type === 'block' ? `Блок ${ph.index}` : ph.type === 'break' ? 'Перерыв' : 'Удержание'

/** Итог завершённой программы. */
export interface ProgramSummary { startFat: number; fatNow: number | null; weeks: number; fatKgLost: number | null; avgScore: number | null }

export function programSummary(p: Program, today: string, fatNow: number | null, weightNow: number | null, avgScore: number | null): ProgramSummary {
  const end = p.completedDate ?? today
  const fatKgLost = fatNow != null && weightNow != null
    ? Math.round((p.startWeight * p.startFat / 100 - weightNow * fatNow / 100) * 10) / 10
    : null
  return { startFat: p.startFat, fatNow, weeks: Math.max(1, Math.round(daysBetween(p.startDate, end) / 7)), fatKgLost, avgScore }
}

/** Строка для ИИ-помощника. */
export function programContext(p: Program, today: string): string {
  const cur = currentPhase(p)
  const parts = [`программа с ${p.startDate}: старт ${fmtPct(p.startFat)} → цель ${p.target}%, ${blockCount(p)} ${plural(blockCount(p), 'блок', 'блока', 'блоков')} по ≤8 недель с обязательными перерывами 2 недели на уровне расхода`]
  if (p.pausedSince) parts.push(`сейчас пауза с ${p.pausedSince} (питание на уровне расхода)`)
  else if (p.completedDate) parts.push(`программа завершена ${p.completedDate}, сейчас удержание`)
  else if (cur.type === 'block') parts.push(`сейчас блок ${cur.index}, неделя ${phaseWeek(p, today)} из ${cur.plannedWeeks}, цель блока ${cur.targetFat}%`)
  else if (cur.type === 'break') parts.push(`сейчас перерыв, осталось ${Math.max(0, PROGRAM.breakDays - activeDays(p, p.currentPhase, today))} дн. — есть на уровне расхода это часть плана, не срыв`)
  else parts.push(`сейчас удержание, неделя ${phaseWeek(p, today)} из ${cur.plannedWeeks}`)
  return parts.join('; ') + '.'
}

// ---------- Бэкап ----------

const isDate = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v)
const optNum = (v: unknown): number | null => (v == null || !Number.isFinite(Number(v)) ? null : Number(v))

function parsePhase(v: unknown): Phase | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  const type = o.type === 'block' || o.type === 'break' || o.type === 'maintenance' ? o.type : null
  if (!type) return null
  const reasons: EndReason[] = ['time', 'target', 'plateau', 'final', 'done']
  return {
    type,
    index: Math.max(0, Math.round(optNum(o.index) ?? 1)),
    startDate: isDate(o.startDate) ? o.startDate : null,
    plannedWeeks: Math.max(1, optNum(o.plannedWeeks) ?? (type === 'break' ? 2 : type === 'maintenance' ? PROGRAM.maintenanceWeeks : PROGRAM.blockWeeks)),
    targetFat: optNum(o.targetFat),
    startFat: optNum(o.startFat),
    endedDate: isDate(o.endedDate) ? o.endedDate : null,
    endReason: reasons.includes(o.endReason as EndReason) ? (o.endReason as EndReason) : null,
    pausedDays: Math.max(0, optNum(o.pausedDays) ?? 0),
    checkins: Math.max(0, optNum(o.checkins) ?? 0),
    flatWeeks: Math.max(0, optNum(o.flatWeeks) ?? 0)
  }
}

/** Бэкап и старые записи: проверяем форму; мусор — null (программа создастся заново из данных). */
export function parseProgram(v: unknown): Program | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  const phases = Array.isArray(o.phases) ? o.phases.map(parsePhase).filter((x): x is Phase => x != null) : []
  const startFat = optNum(o.startFat), startWeight = optNum(o.startWeight), target = optNum(o.target)
  if (!isDate(o.startDate) || !phases.length || startFat == null || startWeight == null || target == null) return null
  const cp = Math.round(optNum(o.currentPhase) ?? 0)
  const n = o.notice as Record<string, unknown> | null | undefined
  return {
    startDate: o.startDate, startFat, startWeight, target, female: !!o.female,
    phases, currentPhase: clamp(cp, 0, phases.length - 1),
    pausedSince: isDate(o.pausedSince) ? o.pausedSince : null,
    completedDate: isDate(o.completedDate) ? o.completedDate : null,
    stayMaintenance: !!o.stayMaintenance,
    notice: n && typeof n.title === 'string' ? { title: n.title, text: typeof n.text === 'string' ? n.text : '' } : null
  }
}
