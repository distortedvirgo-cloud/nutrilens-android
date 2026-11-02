// Первый запуск: знакомство → данные о себе (норма считается сама) → сервис ИИ и ключ.
// Всё можно пропустить, но без ключа на «Сегодня» и в Профиле висит плашка.
import { useState } from 'react'
import { saveSettings, setWeight } from '../data/db'
import { iso } from '../data/insights'
import { Segmented, useToast } from '../ui/kit'
import { KeyField, ProviderCards } from '../ui/AiSetup'
import { InstallHelp } from './Profile'
import type { Settings } from '../data/db'

const isStandalone = () =>
  window.matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true

/** Миффлин — Сан Жеор × активность, с поправкой на цель; шаг 50 ккал. */
export function calorieNorm(male: boolean, age: number, height: number, weight: number, activity: number, goal: number): number {
  const bmr = 10 * weight + 6.25 * height - 5 * age + (male ? 5 : -161)
  const tdee = bmr * [1.3, 1.5, 1.7][activity]
  const adj = tdee * [0.85, 1.0, 1.1][goal]
  return Math.min(4500, Math.max(1200, Math.round(adj / 50) * 50))
}

const n = (v: string) => { const x = parseFloat(v.replace(',', '.')); return Number.isFinite(x) ? x : NaN }

export function Onboarding({ settings, onDone }: { settings: Settings; onDone: (openAi: boolean) => void }) {
  const toast = useToast()
  const [step, setStep] = useState(0)
  const [male, setMale] = useState(true)
  const [age, setAge] = useState(''); const [height, setHeight] = useState(''); const [weight, setW] = useState('')
  const [goal, setGoal] = useState(1); const [activity, setActivity] = useState(1)
  const [notes, setNotes] = useState(''); const [override, setOverride] = useState(''); const [nick, setNick] = useState('')
  const [provider, setProvider] = useState<Settings['provider']>('nanogpt')

  const a = n(age), h = n(height), w = n(weight)
  const bodyOk = a >= 12 && a <= 100 && h >= 120 && h <= 230 && w >= 30 && w <= 300
  const computed = bodyOk ? calorieNorm(male, a, h, w, activity, goal) : null

  const saveProfile = async () => {
    const patch: Partial<Settings> = { nickname: nick.trim() }
    if (bodyOk) {
      patch.userContext = `${male ? 'Я мужчина' : 'Я женщина'}, ${Math.round(a)} лет, рост ${Math.round(h)} см, вес ${w.toFixed(1)} кг. ` +
        `Цель — ${['похудеть', 'держать вес', 'набрать массу'][goal]}, активность ${['низкая', 'средняя', 'высокая'][activity]}.${notes.trim() ? ' ' + notes.trim() : ''}`
      const o = n(override)
      patch.dailyGoal = Math.min(6000, Math.max(800, Number.isFinite(o) ? o : computed!))
      await setWeight(iso(new Date()), w)
    } else if (notes.trim()) patch.userContext = notes.trim()
    await saveSettings(patch)
  }
  const finish = async (openAi = false) => { await saveSettings({ onboarded: true, provider }); onDone(openAi) }

  return (
    <div className="screen detail screen-enter" style={{ zIndex: 5, background: 'var(--bg)' }}>
      <div className="row gap-6" style={{ marginBottom: 18 }} aria-label={`Шаг ${step + 1} из 3`}>
        {[0, 1, 2].map((i) => <span key={i} className="grow" style={{ height: 4, borderRadius: 2, background: i <= step ? 'var(--primary)' : 'var(--hairline)', transition: 'background 300ms' }} />)}
      </div>

      {step === 0 && (
        <div className="col gap-16 stagger" style={{ paddingTop: 12 }}>
          <img src="./icons/icon-192.png" alt="" style={{ width: 88, height: 88, borderRadius: 24, boxShadow: 'var(--glow)' }} />
          <div>
            <div className="display">Добро пожаловать в NutriLens</div>
            <div className="small" style={{ fontSize: 15, marginTop: 8 }}>
              Сфотографируй еду — ИИ посчитает калории и БЖУ. Настроим всё за минуту: пара вопросов о тебе и ключ нейросети. Данные хранятся только на этом телефоне.
            </div>
          </div>
          {!isStandalone() && <InstallHelp />}
          <button className="btn btn-brand full" onClick={() => setStep(1)}>Начать</button>
        </div>
      )}

      {step === 1 && (
        <div className="col gap-14 stagger">
          <div>
            <div className="display">Расскажи о себе</div>
            <div className="small" style={{ fontSize: 14, marginTop: 4 }}>Посчитаем дневную норму калорий. ИИ учтёт это при разборе еды.</div>
          </div>
          <div><label className="field-label" htmlFor="ob-nick">Как тебя зовут</label>
            <input id="ob-nick" className="field" value={nick} onChange={(e) => setNick(e.target.value)} placeholder="Имя или ник" /></div>
          <Segmented options={[[1, 'Мужчина'], [0, 'Женщина']]} value={male ? 1 : 0} onChange={(v) => setMale(v === 1)} />
          <div className="row gap-8">
            {([['Возраст', age, setAge, 'ob-age'], ['Рост, см', height, setHeight, 'ob-h'], ['Вес, кг', weight, setW, 'ob-w']] as const).map(([l, v, set, id]) => (
              <div key={id} className="grow"><label className="field-label" htmlFor={id}>{l}</label>
                <input id={id} className="field num" inputMode="decimal" value={v} onChange={(e) => set(e.target.value)} /></div>
            ))}
          </div>
          <div className="col gap-8"><div className="label">Цель</div>
            <Segmented options={[[0, 'Похудеть'], [1, 'Держать'], [2, 'Набрать']]} value={goal} onChange={setGoal} /></div>
          <div className="col gap-8"><div className="label">Активность</div>
            <Segmented options={[[0, 'Низкая'], [1, 'Средняя'], [2, 'Высокая']]} value={activity} onChange={setActivity} /></div>
          {computed != null && (
            <div className="card pad-16 col gap-6" style={{ background: 'color-mix(in srgb, var(--primary) 9%, var(--surface))' }}>
              <div className="label" style={{ color: 'var(--primary)' }}>Твоя норма</div>
              <div className="h1 num">{Number.isFinite(n(override)) ? Math.round(n(override)) : computed} ккал в день</div>
              <div className="small">Формула Миффлина — Сан Жеора. Можно поменять:</div>
              <input className="field num" inputMode="numeric" value={override} onChange={(e) => setOverride(e.target.value)} placeholder="Своя норма, ккал (необязательно)" />
            </div>
          )}
          <div><label className="field-label" htmlFor="ob-notes">Особенности (необязательно)</label>
            <textarea id="ob-notes" className="field" value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Например: жарю на 5 г масла, не ем свинину" /></div>
          <button className="btn btn-brand full" disabled={!bodyOk} onClick={async () => { await saveProfile(); setStep(2) }}>Дальше</button>
          <button className="btn full" style={{ height: 44, color: 'var(--text-2)', fontWeight: 700 }} onClick={async () => { await saveProfile(); setStep(2) }}>Пропустить</button>
        </div>
      )}

      {step === 2 && (
        <div className="col gap-14 stagger">
          <div>
            <div className="display">Подключи нейросеть</div>
            <div className="small" style={{ fontSize: 14, marginTop: 4 }}>Она считает калории по фото и отвечает в чате. Нужен ключ — вставь его один раз.</div>
          </div>
          <ProviderCards value={provider} onChange={setProvider} />
          <KeyField key={provider} s={settings} provider={provider} cta="Проверить и готово"
            onError={(m) => toast(m)} onSaved={() => { toast('Нейросеть подключена'); finish() }} />
          <div className="small">Ключ хранится только на этом телефоне. Поменять или добавить второй можно в Профиле → Нейросеть.</div>
          <button className="btn btn-soft full" onClick={() => finish(false)}>Добавлю ключ позже</button>
        </div>
      )}
    </div>
  )
}
