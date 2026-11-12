// Адаптивный план режима «Сжигание жира»: калории и БЖУ считаются сами и
// подстраиваются под реальные данные (вес + дневник). Чистый модуль — без
// IndexedDB, React и дат «сейчас»: всё приходит во входе, поэтому алгоритм
// легко крутить и проверять скриптом. Все пороги — в PLAN, чтобы тюнить в одном месте.

export const PLAN = {
  /** Окно наблюдения, дней. */
  windowDays: 21,
  /** День считается «записанным», если съедено не меньше — иначе дневник явно неполный. */
  minDayKcal: 800,
  minLogDays: 10,
  minWeighIns: 4,
  minSpanDays: 10,
  /** «Дневник полный» — для вывода «вес стоит» нужно почти каждый день окна. */
  completeLogDays: 17,
  kcalPerKg: 7700,
  defaultActivity: 1.4,
  /** Темп, % веса в неделю: обычный и бережный (близко к цели или уже сухо). */
  rate: 0.6,
  slowRate: 0.4,
  deficitMin: 0.15,
  deficitMax: 0.25,
  /** Наблюдаемый расход не уходит дальше этих долей от формулы — защита от кривых данных. */
  tdeeLo: 0.75,
  tdeeHi: 1.3,
  fastLoss: 1.0,
  stallLoss: 0.2,
  guardKcal: 100,
  /** Шаг пересчёта: не дёргаем калории больше чем на 150 за раз. */
  maxStep: 150,
  recalcDays: 7,
  proteinPerLean: 2.6,
  fatPerKg: 0.8,
  minFat: 45,
  minCarbs: 50
} as const

export interface FatPlan {
  calories: number
  protein: number
  fat: number
  carbs: number
  /** Расход энергии, ккал/день. */
  tdee: number
  bmr: number
  /** true — расход по весам и дневнику, false — по формуле. */
  observed: boolean
  /** Целевой и фактический темп, % веса в неделю (плюс — вес уходит). */
  targetRate: number
  actualRate: number | null
  /** Коротко по-русски: почему план такой / что изменилось. */
  note: string
  /** Дата расчёта и следующего пересчёта, YYYY-MM-DD. */
  date: string
  nextDate: string
  /** Целевой % жира на момент расчёта: сменили цель — пересчитываем сразу. */
  target: number
  /** Процент жира, по которому считали; null — замера не было, взяли типичный. */
  fatPercent: number | null
  weight: number
}

export interface PlanInput {
  female: boolean
  /** Последний вес, кг. */
  weight: number
  /** Взвешивания (можно больше окна — лишнее отбросится). */
  weights: { date: string; weight: number }[]
  /** Калории по дням (сумма приёмов пищи). */
  days: { date: string; kcal: number }[]
  /** Последний замер; null — замеров нет. */
  fatPercent: number | null
  targetFat: number
  /** Множитель активности из профиля; null — 1,4. */
  activity: number | null
  prev: FatPlan | null
  /** Сегодня, YYYY-MM-DD. */
  today: string
}

export interface DataStatus {
  enough: boolean
  logDays: number
  weighIns: number
  spanDays: number
  /** Средние ккал за записанные дни окна. */
  meanKcal: number | null
  /** Наклон веса, кг/день (минус — худеет); null — меньше двух взвешиваний. */
  slopeKgPerDay: number | null
  /** Дневник почти без пропусков — можно делать вывод «вес стоит». */
  complete: boolean
  /** Чего не хватает для расчёта по данным: «нужно ещё 3 взвешивания…»; null — всего хватает. */
  missing: string | null
}

// ---------- Даты без часовых поясов (UTC-полдень не ломается на переводе часов) ----------

const dayNum = (s: string): number => {
  const [y, m, d] = s.split('-').map(Number)
  return Math.round(Date.UTC(y, m - 1, d) / 86400000)
}
const fromDayNum = (n: number): string => new Date(n * 86400000).toISOString().slice(0, 10)
export const daysBetween = (a: string, b: string): number => dayNum(b) - dayNum(a)
export const addIsoDays = (s: string, n: number): string => fromDayNum(dayNum(s) + n)

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))
const round5 = (v: number) => Math.round(v / 5) * 5
const round10 = (v: number) => Math.round(v / 10) * 10

export function plural(n: number, one: string, few: string, many: string): string {
  const m100 = n % 100, m10 = n % 10
  if (m100 >= 11 && m100 <= 14) return many
  if (m10 === 1) return one
  if (m10 >= 2 && m10 <= 4) return few
  return many
}
const dn = (n: number) => `${n} ${plural(n, 'день', 'дня', 'дней')}`

/** Наклон по методу наименьших квадратов: x — дни от первого взвешивания. */
function slope(points: { x: number; y: number }[]): number | null {
  if (points.length < 2) return null
  const n = points.length
  const mx = points.reduce((a, p) => a + p.x, 0) / n
  const my = points.reduce((a, p) => a + p.y, 0) / n
  let num = 0, den = 0
  for (const p of points) { num += (p.x - mx) * (p.y - my); den += (p.x - mx) ** 2 }
  return den > 0 ? num / den : null
}

/**
 * Хватает ли данных за окно, чтобы считать расход по факту. Сегодняшние калории
 * не берём — день ещё не закончился; сегодняшнее взвешивание берём.
 */
export function assessData(input: Pick<PlanInput, 'weights' | 'days' | 'today'>): DataStatus {
  const t = dayNum(input.today)
  const logged = input.days.filter((d) => {
    const n = dayNum(d.date)
    return n >= t - PLAN.windowDays && n < t && d.kcal >= PLAN.minDayKcal
  })
  const ws = input.weights
    .filter((w) => { const n = dayNum(w.date); return n > t - PLAN.windowDays && n <= t && w.weight > 0 })
    .sort((a, b) => a.date.localeCompare(b.date))
  const spanDays = ws.length >= 2 ? daysBetween(ws[0].date, ws.at(-1)!.date) : 0
  const s = ws.length >= 2 ? slope(ws.map((w) => ({ x: daysBetween(ws[0].date, w.date), y: w.weight }))) : null
  const enough = logged.length >= PLAN.minLogDays && ws.length >= PLAN.minWeighIns && spanDays >= PLAN.minSpanDays

  const parts: string[] = []
  const needW = PLAN.minWeighIns - ws.length
  const needSpan = PLAN.minSpanDays - spanDays
  if (needW > 0) parts.push(`ещё ${needW} ${plural(needW, 'взвешивание', 'взвешивания', 'взвешиваний')}${needSpan > 0 ? `, растянутых хотя бы на ${dn(needSpan)}` : ''}`)
  else if (needSpan > 0) parts.push(`взвешиваться ещё ${dn(needSpan)}`)
  const needD = PLAN.minLogDays - logged.length
  if (needD > 0) parts.push(`ещё ${dn(needD)} с записями еды`)

  return {
    enough,
    logDays: logged.length,
    weighIns: ws.length,
    spanDays,
    meanKcal: logged.length ? logged.reduce((a, d) => a + d.kcal, 0) / logged.length : null,
    slopeKgPerDay: s,
    complete: logged.length >= PLAN.completeLogDays,
    missing: enough ? null : `нужно ${parts.join(' и ')}`
  }
}

/** Пора ли пересчитывать: нет плана, сменили цель, появился первый замер или прошла неделя. */
export function planDue(prev: FatPlan | null, p: { today: string; targetFat: number; fatPercent: number | null }): boolean {
  if (!prev) return true
  if (prev.target !== p.targetFat) return true
  if (prev.fatPercent == null && p.fatPercent != null) return true
  return daysBetween(prev.date, p.today) >= PLAN.recalcDays
}

export function computePlan(input: PlanInput): FatPlan {
  const { female, weight, targetFat, prev, today } = input
  // 1. Сухая масса: без замера — типичный процент, чтобы план был сразу.
  const fp = input.fatPercent ?? (female ? 32 : 25)
  const lean = weight * (1 - fp / 100)
  // 2. Katch–McArdle: от сухой массы точнее Миффлина для тех, кто сушится.
  const bmr = 370 + 21.6 * lean
  const baseTdee = bmr * (input.activity ?? PLAN.defaultActivity)

  // 3. Расход по факту: съедено − изменение веса × 7700. Зажат около формулы.
  const st = assessData(input)
  let tdee = baseTdee
  let observed = false
  if (st.enough && st.meanKcal != null && st.slopeKgPerDay != null) {
    tdee = clamp(st.meanKcal - st.slopeKgPerDay * PLAN.kcalPerKg, baseTdee * PLAN.tdeeLo, baseTdee * PLAN.tdeeHi)
    observed = true
  }

  // 4. Темп: ближе к цели (или уже сухо) — медленнее, иначе уходят мышцы.
  const slow = input.fatPercent != null && (input.fatPercent - targetFat <= 3 || input.fatPercent < (female ? 23 : 15))
  const targetRate = slow ? PLAN.slowRate : PLAN.rate
  const deficit = clamp((weight * (targetRate / 100) * PLAN.kcalPerKg) / 7, tdee * PLAN.deficitMin, tdee * PLAN.deficitMax)

  // 5. Не ниже основного обмена и бытового минимума.
  const floor = Math.max(bmr, female ? 1200 : 1500)
  let calories = Math.max(tdee - deficit, floor)

  // 6. Страховка по фактическому темпу (только когда данных хватает).
  const actualRate = observed ? (-st.slopeKgPerDay! * 7 / weight) * 100 : null
  let note = ''
  if (actualRate != null && actualRate > PLAN.fastLoss) {
    calories += PLAN.guardKcal
    note = 'Вес уходит слишком быстро — добавил калорий, чтобы сберечь мышцы'
  } else if (actualRate != null && actualRate < PLAN.stallLoss && st.complete) {
    // Расход по факту уже снижает план; если этого не хватило — ещё −100.
    if (prev && calories >= prev.calories) calories = prev.calories - PLAN.guardKcal
    note = 'Вес стоит — немного снизил калории'
  }

  // 7. Стабильность: после первого плана — не больше ±150 за пересчёт. Пол — важнее.
  // Первый план по данным не поднимаем резко выше того, что человек реально ел:
  // «быстрое похудение» чаще значит неполный дневник, а не огромный расход.
  if (!prev && observed && st.meanKcal != null) calories = Math.min(calories, st.meanKcal + PLAN.maxStep)
  if (prev) calories = clamp(calories, prev.calories - PLAN.maxStep, prev.calories + PLAN.maxStep)
  calories = Math.max(round10(calories), Math.ceil(floor / 10) * 10)

  // 8. БЖУ: белок от сухой массы, жир — не меньше 45 г (гормоны), остальное — углеводы.
  const protein = round5(lean * PLAN.proteinPerLean)
  const fat = Math.max(PLAN.minFat, round5(weight * PLAN.fatPerKg))
  const carbs = Math.max(PLAN.minCarbs, round5((calories - protein * 4 - fat * 9) / 4))

  if (!note) {
    const delta = prev ? calories - prev.calories : 0
    if (!prev) note = observed ? 'Расход посчитал по твоим весам и дневнику' : 'Первый план — расход по формуле. Уточню, когда накопятся взвешивания и дневник'
    else if (prev.target !== targetFat) note = `Новая цель ${targetFat}% — пересчитал план`
    else if (slow && prev.targetRate !== targetRate) note = 'До цели близко — темп снизил до 0,4% в неделю, чтобы сберечь мышцы'
    else if (prev.fatPercent == null && input.fatPercent != null) note = 'Учёл первый замер — белок теперь от твоей сухой массы'
    else if (delta !== 0) note = `${delta > 0 ? '+' : '−'}${Math.abs(delta)} ккал: ${observed ? 'уточнил расход по твоим весам и дневнику' : 'обновил по весу'}`
    else note = 'План без изменений — всё идёт по курсу'
  }

  return {
    calories, protein, fat, carbs,
    tdee: round10(tdee), bmr: Math.round(bmr), observed,
    targetRate, actualRate: actualRate == null ? null : Math.round(actualRate * 100) / 100,
    note,
    date: today, nextDate: addIsoDays(today, PLAN.recalcDays),
    target: targetFat, fatPercent: input.fatPercent, weight
  }
}

/** Бэкап и старые записи: проверяем форму, иначе null (план пересчитается). */
export function parseFatPlan(v: unknown): FatPlan | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  const n = (k: string) => Number(o[k])
  const isDate = (k: string) => typeof o[k] === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(o[k] as string)
  if (![n('calories'), n('protein'), n('fat'), n('carbs'), n('tdee')].every((x) => Number.isFinite(x) && x > 0) || !isDate('date')) return null
  const date = o.date as string
  return {
    calories: n('calories'), protein: n('protein'), fat: n('fat'), carbs: n('carbs'), tdee: n('tdee'),
    bmr: Number.isFinite(n('bmr')) ? n('bmr') : 0,
    observed: !!o.observed,
    targetRate: Number.isFinite(n('targetRate')) && n('targetRate') > 0 ? n('targetRate') : PLAN.rate,
    actualRate: o.actualRate == null || !Number.isFinite(n('actualRate')) ? null : n('actualRate'),
    note: typeof o.note === 'string' ? o.note : '',
    date,
    nextDate: isDate('nextDate') ? (o.nextDate as string) : addIsoDays(date, PLAN.recalcDays),
    target: Number.isFinite(n('target')) ? n('target') : 0,
    fatPercent: o.fatPercent == null || !Number.isFinite(n('fatPercent')) ? null : n('fatPercent'),
    weight: Number.isFinite(n('weight')) ? n('weight') : 0
  }
}

/**
 * Бэкап Android хранит план плоскими полями настроек (planCalories, planDate…).
 * Переводим в наш вид; чего Android не пишет (темп, следующая дата) — по умолчанию.
 */
export function fatPlanFromFlat(s: Record<string, unknown>): FatPlan | null {
  return parseFatPlan({
    calories: s.planCalories, protein: s.planProtein, fat: s.planFat, carbs: s.planCarbs, tdee: s.planTdee,
    note: s.planNote, date: s.planDate, observed: s.planObserved, target: s.planTargetFat
  })
}

/** Те же плоские поля для экспорта — чтобы Android-версия подхватила план из веб-бэкапа. */
export function fatPlanToFlat(p: FatPlan | null): Record<string, unknown> {
  return {
    planCalories: p?.calories ?? null, planProtein: p?.protein ?? null, planFat: p?.fat ?? null, planCarbs: p?.carbs ?? null,
    planTdee: p?.tdee ?? null, planNote: p?.note ?? '', planDate: p?.date ?? null, planObserved: p?.observed ?? false,
    planTargetFat: p?.target ?? null
  }
}
