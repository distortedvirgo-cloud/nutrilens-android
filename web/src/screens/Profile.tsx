// «Профиль»: кто я и цели + настройки по разделам (каждый — свой лист).
import { useRef, useState } from 'react'
import { type Settings, getSettings, latestWeight, saveSettings } from '../data/db'
import { exportBackup, importBackup } from '../data/actions'
import { macroGoals } from '../data/insights'
import { checkKey } from '../ai/gemini'
import { Badge, Sheet, useData, useSheetClose, useToast } from '../ui/kit'
import { IcChevronR, IcDownload, IcInfo, IcKey, IcShare, IcTarget, IcUpload, IcUser } from '../ui/icons'

type Section = 'goals' | 'about' | 'ai' | 'data' | 'install' | null

export function ProfileScreen() {
  const [open, setOpen] = useState<Section>(null)
  const data = useData(async () => ({ s: await getSettings(), w: (await latestWeight())?.weight ?? null }), [], null)
  if (!data) return <div className="screen" />
  const { s, w } = data
  const g = macroGoals(s.proteinGoal, s.fatGoal, s.carbsGoal, s.dailyGoal, w)

  const groups: [string, [Section, React.ReactNode, string, string, string][]][] = [
    ['Питание', [
      ['goals', <IcTarget size={19} />, 'var(--primary)', 'Цели и макросы', `${Math.round(s.dailyGoal)} ккал`],
      ['about', <IcUser size={19} />, 'var(--protein)', 'О себе', s.nickname || '']
    ]],
    ['ИИ и данные', [
      ['ai', <IcKey size={19} />, 'var(--secondary)', 'Нейросеть', s.apiKey ? 'Gemini подключён' : 'нужен ключ'],
      ['data', <IcDownload size={19} />, 'var(--carbs)', 'Экспорт и импорт', '']
    ]],
    ['Приложение', [
      ['install', <IcShare size={19} />, 'var(--warn-to)', 'Установка на iPhone', ''],
    ]]
  ]

  return (
    <div className="screen screen-enter">
      <div className="col gap-16 stagger">
        <button className="card pad-18 pressable row gap-16" style={{ textAlign: 'left', marginTop: 4 }} onClick={() => setOpen('about')}>
          <span className="badge" style={{ width: 60, height: 60, borderRadius: 30, fontSize: 24, fontWeight: 800, background: 'linear-gradient(135deg, var(--brand-from), var(--secondary))', color: 'var(--on-brand)' }}>
            {(s.nickname.trim()[0] ?? 'N').toUpperCase()}
          </span>
          <div className="grow">
            <div className="h2 ellipsis">{s.nickname.trim() || 'Без ника'}</div>
            <div className="small num">Цель {Math.round(s.dailyGoal)} ккал{w != null ? ` · ${w.toFixed(1).replace(".", ",")} кг` : ''}</div>
          </div>
        </button>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
          {([['Белки', g.protein, 'var(--protein)'], ['Жиры', g.fat, 'var(--fat)'], ['Углеводы', g.carbs, 'var(--carbs)']] as const).map(([l, v, c]) => (
            <button key={l} className="card r22 pressable col" onClick={() => setOpen('goals')} style={{ alignItems: 'center', padding: '14px 0' }}>
              <span className="h2 num" style={{ color: c }}>{Math.round(v)} г</span>
              <span className="label">{l}</span>
            </button>
          ))}
        </div>
        {groups.map(([title, rows]) => (
          <div key={title} className="col gap-8">
            <div className="overline" style={{ margin: '10px 4px 0' }}>{title}</div>
            <div className="card r24" style={{ overflow: 'hidden' }}>
              {rows.map(([key, icon, tint, label, value], i) => (
                <button key={label} className="row gap-14" onClick={() => setOpen(key)}
                  style={{ width: '100%', minHeight: 60, padding: '0 14px', textAlign: 'left', borderTop: i ? '1px solid var(--hairline)' : 'none' }}>
                  <Badge tint={tint} size={36}>{icon}</Badge>
                  <span className="grow" style={{ fontWeight: 700 }}>{label}</span>
                  {value && <span className="small">{value}</span>}
                  <span className="muted"><IcChevronR size={18} /></span>
                </button>
              ))}
            </div>
          </div>
        ))}
        <div className="small" style={{ textAlign: 'center', marginTop: 8 }}>NutriLens для iPhone · данные хранятся только на этом устройстве</div>
      </div>

      {open === 'goals' && <Sheet label="Цели" onClose={() => setOpen(null)}><GoalsForm s={s} /></Sheet>}
      {open === 'about' && <Sheet label="О себе" onClose={() => setOpen(null)}><AboutForm s={s} /></Sheet>}
      {open === 'ai' && <Sheet label="Нейросеть" onClose={() => setOpen(null)}><AiForm s={s} /></Sheet>}
      {open === 'data' && <Sheet label="Данные" onClose={() => setOpen(null)}><DataForm /></Sheet>}
      {open === 'install' && <Sheet label="Установка" onClose={() => setOpen(null)}><InstallHelp /></Sheet>}
    </div>
  )
}

function NumField({ id, label, value, onChange, placeholder }: { id: string; label: string; value: string; onChange: (v: string) => void; placeholder?: string }) {
  return (
    <div className="grow">
      <label className="field-label" htmlFor={id}>{label}</label>
      <input id={id} className="field num" inputMode="decimal" value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
    </div>
  )
}

const toNum = (v: string): number | null => {
  const n = parseFloat(v.replace(',', '.'))
  return Number.isFinite(n) && n > 0 ? n : null
}

function GoalsForm({ s }: { s: Settings }) {
  const close = useSheetClose()
  const toast = useToast()
  const [kcal, setKcal] = useState(String(s.dailyGoal))
  const [p, setP] = useState(s.proteinGoal ? String(s.proteinGoal) : '')
  const [f, setF] = useState(s.fatGoal ? String(s.fatGoal) : '')
  const [c, setC] = useState(s.carbsGoal ? String(s.carbsGoal) : '')
  const ok = (toNum(kcal) ?? 0) >= 800
  return (
    <div className="col gap-14">
      <div className="h1">Цели и макросы</div>
      <NumField id="g-kcal" label="Дневная норма, ккал" value={kcal} onChange={setKcal} />
      <div className="small">Белки, жиры и углеводы можно оставить пустыми — посчитаю сам от веса и нормы калорий.</div>
      <div className="row gap-8">
        <NumField id="g-p" label="Белки, г" value={p} onChange={setP} placeholder="авто" />
        <NumField id="g-f" label="Жиры, г" value={f} onChange={setF} placeholder="авто" />
        <NumField id="g-c" label="Углев., г" value={c} onChange={setC} placeholder="авто" />
      </div>
      <button className="btn btn-brand full" disabled={!ok} onClick={async () => {
        await saveSettings({ dailyGoal: toNum(kcal)!, proteinGoal: toNum(p), fatGoal: toNum(f), carbsGoal: toNum(c) })
        close()
        toast('Цели сохранены')
      }}>Сохранить</button>
    </div>
  )
}

function AboutForm({ s }: { s: Settings }) {
  const close = useSheetClose()
  const toast = useToast()
  const [nick, setNick] = useState(s.nickname)
  const [ctx, setCtx] = useState(s.userContext)
  return (
    <div className="col gap-14">
      <div className="h1">О себе</div>
      <div>
        <label className="field-label" htmlFor="a-nick">Имя или ник</label>
        <input id="a-nick" className="field" value={nick} onChange={(e) => setNick(e.target.value)} />
      </div>
      <div>
        <label className="field-label" htmlFor="a-ctx">Контекст для ИИ</label>
        <textarea id="a-ctx" className="field" value={ctx} onChange={(e) => setCtx(e.target.value)}
          placeholder="Например: мужчина, 85 кг, жарю на 5 г масла, не ем свинину" />
      </div>
      <button className="btn btn-brand full" onClick={async () => {
        await saveSettings({ nickname: nick.trim(), userContext: ctx.trim() })
        close()
        toast('Сохранено')
      }}>Сохранить</button>
    </div>
  )
}

function AiForm({ s }: { s: Settings }) {
  const close = useSheetClose()
  const toast = useToast()
  const [key, setKey] = useState(s.apiKey)
  const [checking, setChecking] = useState(false)
  return (
    <div className="col gap-14">
      <div className="h1">Нейросеть</div>
      <div className="small" style={{ fontSize: 14 }}>
        Анализ еды и чат работают через Google Gemini — тот же «Бесплатный» режим, что в Android-приложении.
        Ключ бесплатный и хранится только на этом телефоне.
      </div>
      <a className="chip primary pressable" href="https://aistudio.google.com/apikey" target="_blank" rel="noreferrer" style={{ alignSelf: 'flex-start', textDecoration: 'none' }}>
        Получить ключ в Google AI Studio
      </a>
      <div>
        <label className="field-label" htmlFor="ai-key">Ключ Gemini</label>
        <input id="ai-key" className="field" value={key} onChange={(e) => setKey(e.target.value)}
          autoCapitalize="off" autoCorrect="off" spellCheck={false} placeholder="AIza…" type="password" />
      </div>
      <button className="btn btn-brand full" disabled={!key.trim() || checking} onClick={async () => {
        setChecking(true)
        const ok = await checkKey(key)
        setChecking(false)
        if (!ok) { toast('Ключ не подошёл — проверь, что скопирован целиком'); return }
        await saveSettings({ apiKey: key.trim() })
        close()
        toast('Gemini подключён')
      }}>{checking ? <span className="spinner" /> : 'Проверить и сохранить'}</button>
    </div>
  )
}

function DataForm() {
  const toast = useToast()
  const fileRef = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  return (
    <div className="col gap-14">
      <div className="h1">Экспорт и импорт</div>
      <div className="small" style={{ fontSize: 14 }}>
        Формат совместим с Android-версией: экспортируй дневник на Android (Профиль → Экспорт и импорт) и импортируй
        файл сюда — и наоборот. Импорт заменяет данные на этом телефоне.
      </div>
      <button className="btn btn-soft full" onClick={async () => {
        const blob = await exportBackup()
        const name = `nutrilens-${new Date().toISOString().slice(0, 10)}.json`
        const file = new File([blob], name, { type: 'application/json' })
        if (navigator.canShare?.({ files: [file] })) {
          try { await navigator.share({ files: [file], title: name }) } catch { /* отменили */ }
        } else {
          const a = document.createElement('a')
          a.href = URL.createObjectURL(blob)
          a.download = name
          a.click()
          setTimeout(() => URL.revokeObjectURL(a.href), 2000)
        }
      }}><IcDownload size={19} />Экспортировать</button>
      <input ref={fileRef} type="file" accept="application/json,.json" hidden onChange={async (e) => {
        const f = e.target.files?.[0]
        e.target.value = ''
        if (!f) return
        setBusy(true)
        try {
          const n = await importBackup(f)
          toast(`Импортировано записей: ${n}`)
        } catch (err) {
          toast((err as Error).message)
        } finally {
          setBusy(false)
        }
      }} />
      <button className="btn btn-soft full" disabled={busy} onClick={() => fileRef.current?.click()}>
        {busy ? <span className="spinner" /> : <><IcUpload size={19} />Импортировать файл</>}
      </button>
    </div>
  )
}

export function InstallHelp() {
  return (
    <div className="col gap-14">
      <div className="h1">Установка на iPhone</div>
      {[
        ['1', 'Открой эту страницу в Safari'],
        ['2', 'Нажми «Поделиться» внизу экрана'],
        ['3', 'Выбери «На экран „Домой“» → «Добавить»']
      ].map(([n, t]) => (
        <div key={n} className="row gap-12">
          <span className="badge num" style={{ width: 34, height: 34, borderRadius: 17, background: 'var(--primary-cont)', color: 'var(--on-primary-cont)', fontWeight: 800 }}>{n}</span>
          <span style={{ fontWeight: 600 }}>{t}</span>
        </div>
      ))}
      <div className="row gap-10 small" style={{ padding: 14, borderRadius: 18, background: 'var(--surface-high)', alignItems: 'flex-start' }}>
        <span style={{ color: 'var(--secondary)', marginTop: 1 }}><IcInfo size={18} /></span>
        <span>После установки NutriLens открывается как обычное приложение: на весь экран, с иконкой, работает без интернета (кроме ИИ).</span>
      </div>
    </div>
  )
}
