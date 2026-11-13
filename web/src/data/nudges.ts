// Коуч пишет первым (3.25): одна короткая подсказка в день, когда в данных видно,
// что программа пошла не так или вот-вот сменится этап. Шаблоны, а не вызов ИИ:
// бесплатно, мгновенно и одинаково на Android и в вебе (там же — Nudges.kt).
// Тон — без упрёков: что случилось и что сделать дальше.

export type NudgeType = 'nolog' | 'protein' | 'breakEnd' | 'sleep'

export interface Nudge {
  /** Уникален для эпизода: одна подсказка не повторяется. */
  key: string
  type: NudgeType
  title: string
  text: string
}

export interface NudgeDay {
  date: string
  kcal: number
  protein: number
  /** Галочка «поспал 8 часов»: null — не отвечал. */
  sleptWell: boolean | null
}

export interface NudgeInput {
  today: string
  /** Законченные дни до сегодня, по возрастанию даты (хватает 7). */
  days: NudgeDay[]
  proteinTarget: number
  /** Текущий этап; null — программы нет, она на паузе или завершена. */
  phase: { type: 'block' | 'break' | 'maintenance'; startDate: string; daysLeft: number | null; nextCalories: number | null } | null
}

export const NUDGE = {
  /** Меньше этого за день — считаем, что еду не записывали. */
  noLogKcal: 300,
  /** День «записан полностью» — для вывода про белок. */
  loggedKcal: 800,
  proteinShare: 0.9,
  /** Сколько дней одна и та же подсказка не повторяется. */
  cooldown: { nolog: 2, protein: 3, breakEnd: 14, sleep: 4 } as Record<NudgeType, number>
}

const lastN = <T,>(a: T[], n: number): T[] => a.slice(Math.max(0, a.length - n))

/** Все подходящие подсказки по приоритету — вызывающий берёт первую не показанную. */
export function candidates(i: NudgeInput): Nudge[] {
  if (!i.phase) return []
  const out: Nudge[] = []
  const days = lastN(i.days, 7)

  // 1. Два дня подряд без записей — самое важное: без дневника недельный пересчёт не работает.
  const two = lastN(days, 2)
  if (two.length === 2 && two.every((d) => d.kcal < NUDGE.noLogKcal)) {
    out.push({
      key: `nolog-${two[0].date}`, type: 'nolog',
      title: 'Два дня без записей',
      text: 'Ничего страшного — запиши сегодня хотя бы один приём пищи. План тот же, а неделю чек-ин всё равно разберёт.'
    })
  }

  // 2. Белок три записанных дня подряд ниже 90% — главный риск потерять мышцы.
  const three = lastN(days, 3)
  if (i.proteinTarget > 0 && three.length === 3 && three.every((d) => d.kcal >= NUDGE.loggedKcal && d.protein < i.proteinTarget * NUDGE.proteinShare)) {
    const avg = Math.round(three.reduce((a, d) => a + d.protein, 0) / 3)
    out.push({
      key: `protein-${three[2].date}`, type: 'protein',
      title: 'Белка не хватает третий день',
      text: `В среднем ${avg} из ${Math.round(i.proteinTarget)} г. На дефиците это первое, что бережёт мышцы: добавь творог, яйца, курицу или рыбу в ближайший приём.`
    })
  }

  // 3. Перерыв заканчивается завтра — предупредить, что калории снова станут ниже.
  if (i.phase.type === 'break' && i.phase.daysLeft != null && i.phase.daysLeft <= 1) {
    out.push({
      key: `breakEnd-${i.phase.startDate}`, type: 'breakEnd',
      title: 'Завтра снова блок сжигания',
      text: `Перерыв сделал своё: голод и силы восстановились.${i.phase.nextCalories ? ` С завтра план ≈ ${i.phase.nextCalories} ккал` : ' С завтра снова дефицит'} — продукты на первые дни лучше купить заранее.`
    })
  }

  // 4. Недосып 3 ночи из 4 — усиливает голод и уводит потери в мышцы.
  const four = lastN(days, 4)
  if (four.filter((d) => d.sleptWell === false).length >= 3) {
    out.push({
      key: `sleep-${four[four.length - 1].date}`, type: 'sleep',
      title: 'Мало сна три ночи из четырёх',
      text: 'Недосып усиливает голод и мешает сохранить мышцы. Попробуй сегодня лечь на 30 минут раньше — это проще, чем терпеть голод завтра.'
    })
  }
  return out
}

/** Что уже показывали: key → дата показа. Хранит вызывающий (localStorage, SharedPreferences). */
export type NudgeHistory = Record<string, string>

const daysBetween = (a: string, b: string) => Math.round((Date.parse(b) - Date.parse(a)) / 86_400_000)

/** Первая подсказка, которую ещё не показывали и чей тип не на «перерыве» (cooldown). */
export function nextNudge(i: NudgeInput, history: NudgeHistory): Nudge | null {
  for (const n of candidates(i)) {
    if (history[n.key]) continue
    const recent = Object.entries(history).some(([k, d]) => k.startsWith(`${n.type}-`) && daysBetween(d, i.today) < NUDGE.cooldown[n.type])
    if (!recent) return n
  }
  return null
}
