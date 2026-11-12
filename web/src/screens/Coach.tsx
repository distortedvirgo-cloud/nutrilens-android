// «Коуч»: план на ближайший приём, вход в чат, быстрые вопросы, подсказки,
// тренировка и привычки дня.
import { useEffect, useRef, useState } from 'react'
import {
  type ChatMessage, allMealDates, chatHistory, clearChat, getSettings, habitsOn, latestMeasurement, latestWeight, mealsOn,
  toggleHabit, toggleWorkout, waterOn, workoutOn, addWater
} from '../data/db'
import { buildInsights, calcStreak, dayTotals, greeting, iso, nextMealFocus, waterNorm } from '../data/insights'
import { sendChat } from '../data/actions'
import { effectiveGoals } from '../data/body'
import { Badge, Bar, Md, useData, useToast } from '../ui/kit'
import { IcBulb, IcCheck, IcChevronL, IcChevronR, IcDumbbell, IcSend, IcSpark, IcTrash } from '../ui/icons'

const HABITS: [string, string][] = [
  ['water', 'Выпить норму воды'],
  ['protein', 'Белок в каждом приёме'],
  ['vegs', 'Овощи или фрукты'],
  ['move', '30 минут активности'],
  ['no_late', 'Не есть за 3 ч до сна']
]

const QUICK = [
  'Что съесть на ужин под мой остаток?',
  'Чем добрать белок сегодня?',
  'Идеи перекуса до 200 ккал',
  'Оцени мой рацион за неделю'
]

export function CoachScreen({ onOpenChat }: { onOpenChat: (q?: string) => void }) {
  const toast = useToast()
  const today = iso(new Date())
  const data = useData(async () => {
    const s = await getSettings()
    const meals = await mealsOn(today)
    const w = (await latestWeight())?.weight ?? null
    const workout = await workoutOn(today)
    const totals = dayTotals(meals)
    const goals = effectiveGoals(s, w, await latestMeasurement())
    const water = await waterOn(today)
    const norm = waterNorm(w, workout)
    const streak = calcStreak(await allMealDates(), new Date())
    return {
      s, totals, goals, workout,
      habits: await habitsOn(today),
      insights: buildInsights({ hour: new Date().getHours(), totals, dailyGoal: goals.calories, goals, waterMl: water, waterNormMl: norm, streak, workout }),
      focus: nextMealFocus(totals, goals),
      chat: (await chatHistory()).slice(-2)
    }
  }, [today], null)
  if (!data) return <div className="screen" />
  const remaining = Math.round(data.goals.calories - data.totals.calories)
  const done = HABITS.filter(([id]) => data.habits.has(id)).length

  return (
    <div className="screen screen-enter">
      <div className="col gap-16 stagger">
        <div className="row gap-14">
          <span className="badge" style={{ width: 48, height: 48, borderRadius: 16, background: 'linear-gradient(135deg, var(--brand-from), var(--secondary))', color: 'var(--on-brand)' }}><IcSpark size={24} /></span>
          <div>
            <div className="display">Коуч</div>
            <div className="small">{greeting(new Date().getHours())} · знает твой дневник и цели</div>
          </div>
        </div>

        <div className="card pad-18 col gap-12">
          <div className="overline">{remaining >= 0 ? `Осталось ${remaining} ккал` : `Перебор ${-remaining} ккал`}</div>
          <div className="h2" style={{ fontSize: 17 }}>{data.focus?.[0] ?? 'Запиши первый приём — подскажу, что дальше'}</div>
          {data.focus && <div className="small" style={{ fontSize: 14 }}>{data.focus[1]}</div>}
          <div className="row gap-8" style={{ flexWrap: 'wrap', marginTop: 2 }}>
            <button className="chip primary pressable" onClick={() => onOpenChat('Предложи 3 идеи на следующий приём под мой остаток калорий и БЖУ')}>Идеи еды</button>
            <button className="chip pressable" onClick={() => onOpenChat('Что приготовить из того, что обычно есть дома, под мой остаток?')}>Что приготовить?</button>
          </div>
        </div>

        <button className="card pad-16 pressable col gap-10" style={{ textAlign: 'left' }} onClick={() => onOpenChat()}>
          <div className="row between">
            <span className="overline">Диалог с диетологом</span>
            <span className="muted"><IcChevronR size={20} /></span>
          </div>
          {data.chat.length === 0
            ? <div className="small" style={{ fontSize: 14 }}>Спроси что угодно о питании — диетолог видит твой дневник.</div>
            : data.chat.map((m) => (
              <div key={m.id} className="row" style={{ justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start' }}>
                <span style={{
                  maxWidth: '85%', padding: '8px 12px', borderRadius: 16, fontSize: 13.5, fontWeight: 500,
                  display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical', overflow: 'hidden',
                  background: m.role === 'user' ? 'linear-gradient(135deg, var(--brand-from), var(--brand-to))' : 'var(--surface-high)',
                  color: m.role === 'user' ? 'var(--on-brand)' : 'var(--text)'
                }}>{m.text.replace(/[*_#`]+/g, "")}</span>
              </div>
            ))}
          <div className="row gap-8" style={{ padding: '6px 6px 6px 16px', borderRadius: 999, background: 'var(--surface-high)' }}>
            <span className="grow muted" style={{ fontSize: 15 }}>Спроси о питании…</span>
            <span className="badge" style={{ width: 38, height: 38, borderRadius: 19, background: 'linear-gradient(135deg, var(--brand-from), var(--brand-to))', color: 'var(--on-brand)' }}><IcSend size={18} /></span>
          </div>
        </button>

        <div>
          <div className="overline" style={{ margin: '4px 4px 10px' }}>Быстрые вопросы</div>
          <div className="row gap-10" style={{ overflowX: 'auto', margin: '0 -20px', padding: '0 20px 4px', scrollbarWidth: 'none' }}>
            {QUICK.map((q) => (
              <button key={q} className="card r22 pressable col gap-10" onClick={() => onOpenChat(q)}
                style={{ flexShrink: 0, width: 150, padding: 14, textAlign: 'left' }}>
                <Badge tint="var(--warn-to)" size={36}><IcBulb size={19} /></Badge>
                <span style={{ fontSize: 13.5, fontWeight: 700, lineHeight: 1.3 }}>{q}</span>
              </button>
            ))}
          </div>
        </div>

        {data.insights.length > 0 && (
          <div className="col gap-10">
            <div className="overline" style={{ margin: '4px 4px 0' }}>Подсказки на сейчас</div>
            {data.insights.map((i) => (
              <div key={i.id} className="card r22 row gap-12" style={{ padding: '12px 12px 12px 14px' }}>
                <span className="badge" style={{ width: 40, height: 40, borderRadius: 14, background: 'var(--surface-high)', fontSize: 18 }}>{i.emoji}</span>
                <div className="grow">
                  <div className="title" style={{ fontSize: 14.5 }}>{i.title}</div>
                  <div className="small">{i.text}</div>
                </div>
                {i.id === 'water-low' && (
                  <button className="chip primary pressable" onClick={async () => { const ml = await addWater(today, 250); toast(`Вода: ${ml} мл`) }}>+250</button>
                )}
              </div>
            ))}
          </div>
        )}

        <button className="card pad-16 pressable row gap-14" style={{ textAlign: 'left' }} onClick={() => toggleWorkout(today)}
          role="switch" aria-checked={data.workout}>
          <Badge tint="var(--secondary)" size={42}><IcDumbbell size={21} /></Badge>
          <div className="grow">
            <div className="title" style={{ fontSize: 15 }}>Тренировка сегодня</div>
            <div className="small">Добавляет +500 мл к норме воды</div>
          </div>
          <span style={{
            width: 52, height: 32, borderRadius: 16, padding: 3, flexShrink: 0,
            background: data.workout ? 'var(--primary)' : 'var(--surface-highest)', transition: 'background 280ms'
          }}>
            <span style={{
              display: 'block', width: 26, height: 26, borderRadius: 13, background: '#fff', boxShadow: '0 2px 6px rgba(0,0,0,0.2)',
              transform: data.workout ? 'translateX(20px)' : 'none', transition: 'transform 320ms var(--ease-spring)'
            }} />
          </span>
        </button>

        <div className="card pad-18 col gap-10">
          <div className="row between">
            <div className="title">Привычки дня</div>
            <span className="num" style={{ fontWeight: 800, color: 'var(--primary)' }}>{done}/{HABITS.length}</span>
          </div>
          <Bar fraction={done / HABITS.length} color="var(--primary)" height={8} track="var(--surface-high)" />
          {HABITS.map(([id, label]) => {
            const on = data.habits.has(id)
            return (
              <button key={id} className="row gap-12" role="checkbox" aria-checked={on} onClick={() => toggleHabit(today, id)}
                style={{ minHeight: 46, textAlign: 'left' }}>
                <span className="badge" style={{
                  width: 26, height: 26, borderRadius: 13, transition: 'background 280ms',
                  background: on ? 'var(--primary)' : 'var(--surface-high)', color: 'var(--surface)'
                }}>
                  <span style={{ transform: on ? 'scale(1)' : 'scale(0)', transition: 'transform 320ms var(--ease-spring)', display: 'flex' }}><IcCheck size={16} /></span>
                </span>
                <span style={{ fontWeight: 600, color: on ? 'var(--text-2)' : 'var(--text)', textDecoration: on ? 'line-through' : 'none' }}>{label}</span>
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}

export function ChatScreen({ initial, onBack }: { initial?: string; onBack: () => void }) {
  const messages = useData(chatHistory, [], [] as ChatMessage[])
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const sentInitial = useRef(false)

  const send = async (q: string) => {
    if (!q.trim() || busy) return
    setBusy(true)
    setError(null)
    setText('')
    try {
      await sendChat(q.trim())
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }
  useEffect(() => {
    if (initial && !sentInitial.current) {
      sentInitial.current = true
      send(initial)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initial])
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages.length, busy])

  return (
    <div className="screen detail screen-push col" style={{ padding: 0, overflow: 'hidden' }}>
      <div className="row gap-12" style={{ padding: 'calc(var(--safe-top) + 10px) 16px 10px' }}>
        <button className="icon-btn" aria-label="Назад" onClick={onBack}><IcChevronL size={20} /></button>
        <span className="badge" style={{ width: 40, height: 40, borderRadius: 20, background: 'linear-gradient(135deg, var(--brand-from), var(--brand-to))', color: 'var(--on-brand)' }}><IcSpark size={20} /></span>
        <div className="grow">
          <div className="title" style={{ fontSize: 17, fontWeight: 800 }}>Диетолог</div>
          <div className="label" style={{ color: busy ? 'var(--primary)' : undefined }}>{busy ? 'печатает…' : 'Знает твой дневник'}</div>
        </div>
        {messages.length > 0 && <button className="icon-btn" aria-label="Очистить чат" onClick={() => clearChat()}><IcTrash size={18} /></button>}
      </div>
      <div ref={listRef} className="grow col gap-10" style={{ overflowY: 'auto', padding: '8px 16px 12px' }}>
        {messages.length === 0 && !busy && (
          <div className="col" style={{ alignItems: 'center', textAlign: 'center', padding: '48px 24px', gap: 8 }}>
            <Badge tint="var(--primary)" size={64}><IcSpark size={30} /></Badge>
            <div className="title" style={{ marginTop: 8 }}>Спроси что угодно о питании</div>
            <div className="small">Например: «чем добрать белок сегодня?»</div>
          </div>
        )}
        {messages.map((m) => (
          <div key={m.id} className="row" style={{ justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start', animation: 'rise 320ms var(--ease-emph) both' }}>
            <div style={{
              maxWidth: '84%', padding: '11px 14px', fontSize: 15, fontWeight: 500,
              borderRadius: m.role === 'user' ? '22px 22px 8px 22px' : '22px 22px 22px 8px',
              background: m.role === 'user' ? 'linear-gradient(135deg, var(--brand-from), var(--brand-to))' : 'var(--surface)',
              color: m.role === 'user' ? 'var(--on-brand)' : 'var(--text)',
              border: m.role === 'user' ? 'none' : '1px solid var(--hairline)'
            }}>
              {m.role === 'user' ? m.text : <Md text={m.text} />}
            </div>
          </div>
        ))}
        {busy && (
          <div className="row"><div className="dots" style={{ padding: '14px 16px', borderRadius: '22px 22px 22px 8px', background: 'var(--surface)', border: '1px solid var(--hairline)' }}><span /><span /><span /></div></div>
        )}
        {error && <div className="small" style={{ color: 'var(--error)', padding: '0 4px' }}>{error}</div>}
      </div>
      <form className="row gap-8" onSubmit={(e) => { e.preventDefault(); send(text) }}
        style={{ margin: '0 12px calc(var(--safe-bottom) + 10px)', padding: 6, paddingLeft: 16, borderRadius: 28, background: 'var(--surface)', border: '1px solid var(--hairline)', boxShadow: 'var(--shadow-2)' }}>
        <label htmlFor="chat-in" className="sr-only">Сообщение</label>
        <input id="chat-in" className="grow" value={text} onChange={(e) => setText(e.target.value)} placeholder="Спроси о питании…"
          enterKeyHint="send" style={{ border: 0, outline: 'none', background: 'transparent', minWidth: 0 }} />
        <button type="submit" aria-label="Отправить" disabled={!text.trim() || busy} className="badge pressable"
          style={{ width: 44, height: 44, borderRadius: 22, background: 'linear-gradient(135deg, var(--brand-from), var(--brand-to))', color: 'var(--on-brand)', opacity: !text.trim() || busy ? 0.45 : 1 }}>
          {busy ? <span className="spinner" /> : <IcSend size={20} />}
        </button>
      </form>
    </div>
  )
}
