// «Профиль»: кто я и цели + настройки по разделам (каждый — свой лист).
import { useEffect, useRef, useState } from 'react'
import { type Settings, getSettings, latestMeasurement, latestWeight, saveSettings } from '../data/db'
import { exportBackup, importBackup } from '../data/actions'
import { effectiveGoals, fatGoalOn, fmt1, livePlan } from '../data/body'
import { FatGoalForm, MeasureSheet } from './Body'
import { setProgramPaused } from '../data/plan'
import { EndProgramSheet, LaunchSheet, RhythmSettings } from './Program'
import { SCORE, clampStepGoal, fmtInt } from '../data/dayscore'
import { dailyResetTime, usageToday } from '../ai/gemini'
import { activeProvider, hasSelectedKey } from '../ai'
import { KeyField, ProviderCards } from '../ui/AiSetup'
import { Avatar, avatarFromFile } from '../ui/Avatar'
import { schedulePush } from '../data/leaderboard'
import { Badge, Segmented, Sheet, useData, useSheetClose, useToast } from '../ui/kit'
import { IcCamera, IcChevronL, IcChevronR, IcDownload, IcInfo, IcKey, IcMinus, IcPause, IcPlay, IcPlus, IcRuler, IcShare, IcSteps, IcTarget, IcTrophy, IcUpload, IcUser } from '../ui/icons'

type Section = 'goals' | 'steps' | 'fat' | 'measure' | 'about' | 'ai' | 'data' | 'install' | 'leaderboard' | 'launch' | 'endProgram' | null

export function ProfileScreen({ openAi = 0, onOpenLeaderboard, onBack }: { openAi?: number; onOpenLeaderboard: () => void; onBack: () => void }) {
  const [open, setOpen] = useState<Section>(openAi ? 'ai' : null)
  const avatarRef = useRef<HTMLInputElement>(null)
  const toast = useToast()
  useEffect(() => { if (openAi) setOpen('ai') }, [openAi])
  const data = useData(async () => ({
    s: await getSettings(), w: (await latestWeight())?.weight ?? null, body: (await latestMeasurement()) ?? null
  }), [], null)
  if (!data) return <div className="screen" />
  const { s, w, body } = data
  const fatOn = fatGoalOn(s)
  const pickAvatar = async (f?: File) => {
    if (!f) return
    try { await saveSettings({ avatar: await avatarFromFile(f) }); schedulePush(500) } catch { /* не картинка */ }
  }
  const g = effectiveGoals(s, w, body)
  // Переключатель режима. Включение — сначала предупреждение «продвинутый режим»
  // и мастер запуска (режим включится только на «Начать программу»). Выключение —
  // с подтверждением: программа закончится; ручные цели не трогались и снова действуют.
  const setMode = (fat: boolean) => {
    if (fat === fatOn) return
    setOpen(fat ? 'launch' : 'endProgram')
  }

  const groups: [string, [Section, React.ReactNode, string, string, string][]][] = [
    ['Питание', [
      ...(fatOn ? [['fat', <IcRuler size={19} />, 'var(--secondary)', 'Цель по жиру', `${s.targetFatPercent}%${body ? ` · сейчас ${fmt1(body.fatPercent)}%` : ''}`] as [Section, React.ReactNode, string, string, string]] : []),
      ['goals', <IcTarget size={19} />, 'var(--primary)', 'Цели и макросы', `${Math.round(g.calories)} ккал${g.plan ? ' · авто' : ''}`],
      ['steps', <IcSteps size={19} />, 'var(--carbs)', 'Шаги', s.stepsEnabled ? `${fmtInt(s.stepGoal || SCORE.stepGoalDefault)} в день` : 'выключены'],
      ...(fatOn || body ? [['measure', <IcRuler size={19} />, 'var(--carbs)', 'Замер тела', body ? 'добавить' : 'первый замер'] as [Section, React.ReactNode, string, string, string]] : []),
      ['about', <IcUser size={19} />, 'var(--protein)', 'О себе', s.nickname || ''],
      ['leaderboard', <IcTrophy size={19} />, 'var(--warn-to)', 'Лидерборд', s.nickname.trim() || 'нет ника']
    ]],
    ['ИИ и данные', [
      ['ai', <IcKey size={19} />, 'var(--secondary)', 'Нейросеть', !hasSelectedKey(s) ? 'добавить ключ' : activeProvider(s) === 'gemini' ? 'Gemini' : 'NanoGPT'],
      ['data', <IcDownload size={19} />, 'var(--carbs)', 'Экспорт и импорт', '']
    ]],
    ['Приложение', [
      ['install', <IcShare size={19} />, 'var(--warn-to)', 'Установка на iPhone', ''],
    ]]
  ]

  return (
    <div className="screen screen-enter">
      <input ref={avatarRef} type="file" accept="image/*" hidden onChange={(e) => { pickAvatar(e.target.files?.[0]); e.target.value = '' }} />
      <div className="col gap-16 stagger">
        <div className="row gap-12">
          <button className="icon-btn" aria-label="Назад" onClick={onBack}><IcChevronL size={20} /></button>
          <div className="h1">Профиль</div>
        </div>
        {!hasSelectedKey(s) && (
          <button className="card pad-16 pressable row gap-14" onClick={() => setOpen('ai')}
            style={{ textAlign: 'left', marginTop: 4, border: '1.5px solid color-mix(in srgb, var(--primary) 50%, transparent)', background: 'color-mix(in srgb, var(--primary) 12%, var(--surface))' }}>
            <span className="badge" style={{ width: 44, height: 44, borderRadius: 14, background: 'var(--primary)', color: 'var(--on-brand)' }}><IcKey size={22} /></span>
            <span className="col grow"><span className="title">Подключи нейросеть</span><span className="small">Вставь ключ NanoGPT или Gemini — без него ИИ не работает</span></span>
            <span className="muted"><IcChevronR size={18} /></span>
          </button>
        )}
        <button className="card pad-18 pressable row gap-16" style={{ textAlign: 'left', marginTop: 4 }} onClick={() => setOpen('about')}>
          <span style={{ position: 'relative', flex: 'none' }} role="button" aria-label="Сменить фото"
            onClick={(e) => { e.stopPropagation(); avatarRef.current?.click() }}>
            <Avatar src={s.avatar} nickname={s.nickname} size={60} />
            <span style={{ position: 'absolute', right: -2, bottom: -2, width: 22, height: 22, borderRadius: 11, display: 'grid', placeItems: 'center', background: 'var(--primary)', color: 'var(--on-brand)' }}><IcCamera size={13} /></span>
          </span>
          <div className="grow">
            <div className="h2 ellipsis">{s.nickname.trim() || 'Без ника'}</div>
            <div className="small num">Цель {Math.round(g.calories)} ккал{w != null ? ` · ${w.toFixed(1).replace(".", ",")} кг` : ''}</div>
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
        {fatOn && !body && (
          <button className="card pad-16 pressable row gap-14" onClick={() => setOpen('measure')}
            style={{ textAlign: 'left', border: '1.5px solid color-mix(in srgb, var(--secondary) 45%, transparent)', background: 'color-mix(in srgb, var(--secondary) 10%, var(--surface))' }}>
            <span className="badge" style={{ width: 44, height: 44, borderRadius: 14, background: 'var(--secondary)', color: 'var(--on-brand)' }}><IcRuler size={22} /></span>
            <span className="col grow"><span className="title">Сделай первый замер</span><span className="small">Талия и шея — и я посчитаю процент жира и норму белка</span></span>
            <span className="muted"><IcChevronR size={18} /></span>
          </button>
        )}
        {groups.map(([title, rows]) => (
          <div key={title} className="col gap-8">
            <div className="overline" style={{ margin: '10px 4px 0' }}>{title}</div>
            {title === 'Питание' && (
              <div className="card r24 pad-14 col gap-10">
                <Segmented options={[[0, 'Похудение'], [1, 'Сжигание жира']]} value={fatOn ? 1 : 0} onChange={(v) => setMode(v === 1)} />
                <div className="small" style={{ padding: '0 4px' }}>
                  {fatOn
                    ? 'Меньше жира при тех же мышцах: программа из блоков дефицита и обязательных перерывов. Калории и БЖУ считаются сами и меняются на недельном чек-ине.'
                    : 'Свои цели по калориям и БЖУ. «Сжигание жира» посчитает план сам — от процента жира и сухой массы.'}
                </div>
                {fatOn && <ModeSettings s={s} toast={toast} />}
              </div>
            )}
            <div className="card r24" style={{ overflow: 'hidden' }}>
              {rows.map(([key, icon, tint, label, value], i) => (
                <button key={label} className="row gap-14" onClick={() => key === 'leaderboard' ? onOpenLeaderboard() : setOpen(key)}
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
      {open === 'steps' && <Sheet label="Шаги" onClose={() => setOpen(null)}><StepsSettings s={s} /></Sheet>}
      {open === 'fat' && <Sheet label="Цель по жиру" onClose={() => setOpen(null)}><FatGoalForm s={s} currentFat={body?.fatPercent ?? null} hasMeasurement={!!body} onMeasure={() => setOpen('measure')} /></Sheet>}
      {open === 'measure' && <MeasureSheet onClose={() => setOpen(null)} />}
      {open === 'about' && <Sheet label="О себе" onClose={() => setOpen(null)}><AboutForm s={s} /></Sheet>}
      {open === 'ai' && <Sheet label="Нейросеть" onClose={() => setOpen(null)}><AiForm s={s} /></Sheet>}
      {open === 'data' && <Sheet label="Данные" onClose={() => setOpen(null)}><DataForm /></Sheet>}
      {open === 'install' && <Sheet label="Установка" onClose={() => setOpen(null)}><InstallHelp /></Sheet>}
      {open === 'launch' && <LaunchSheet onClose={() => setOpen(null)} />}
      {open === 'endProgram' && <EndProgramSheet onClose={() => setOpen(null)} />}
    </div>
  )
}

/**
 * Режим «Сжигание жира»: день чек-ина, цель по силовым и пауза программы.
 * Пауза: тема зелёная, план на уровне расхода, чек-ин не ждёт; «Продолжить» —
 * текущий этап идёт дальше, а дни паузы в его недели не считаются.
 */
function ModeSettings({ s, toast }: { s: Settings; toast: (t: string) => void }) {
  const paused = !!s.program?.pausedSince
  return (
    <div className="col gap-12" style={{ borderTop: '1px solid var(--hairline)', paddingTop: 12 }}>
      <RhythmSettings s={s} />
      {s.program && !s.program.completedDate && (
        <button className="btn btn-soft full sm" onClick={async () => {
          await setProgramPaused(!paused)
          toast(paused ? 'Программа продолжается' : 'Программа на паузе — питание на уровне расхода')
        }}>
          {paused ? <><IcPlay size={18} />Продолжить программу</> : <><IcPause size={18} />Приостановить программу</>}
        </button>
      )}
    </div>
  )
}

/** Шаги: включить плитку на «Сегодня» и цель. Меняется сразу, без кнопки «Сохранить». */
function StepsSettings({ s }: { s: Settings }) {
  const close = useSheetClose()
  const goal = s.stepGoal || SCORE.stepGoalDefault
  const setGoal = (d: number) => saveSettings({ stepGoal: clampStepGoal(goal + d) })
  return (
    <div className="col gap-14">
      <div>
        <div className="h1">Шаги</div>
        <div className="small">Плитка на «Сегодня»: нажми и впиши, сколько прошёл за день.{fatGoalOn(s) ? ' В режиме шаги идут в оценку дня — как и силовая.' : ''}</div>
      </div>
      <Segmented options={[[0, 'Выключены'], [1, 'Включены']]} value={s.stepsEnabled ? 1 : 0} onChange={(v) => saveSettings({ stepsEnabled: v === 1 })} />
      <div className="row gap-12" style={{ padding: '0 4px', opacity: s.stepsEnabled ? 1 : 0.5 }}>
        <span className="col grow">
          <span style={{ fontWeight: 700 }}>Цель в день</span>
          <span className="label num">от {fmtInt(SCORE.stepGoalMin)} до {fmtInt(SCORE.stepGoalMax)}</span>
        </span>
        <button className="icon-btn" aria-label="Меньше шагов" disabled={!s.stepsEnabled || goal <= SCORE.stepGoalMin} onClick={() => setGoal(-SCORE.stepGoalStep)}><IcMinus size={18} /></button>
        <span className="h2 num" aria-live="polite" style={{ minWidth: 64, textAlign: 'center' }}>{fmtInt(goal)}</span>
        <button className="icon-btn" aria-label="Больше шагов" disabled={!s.stepsEnabled || goal >= SCORE.stepGoalMax} onClick={() => setGoal(SCORE.stepGoalStep)}><IcPlus size={18} /></button>
      </div>
      <div className="row gap-10 small" style={{ padding: 14, borderRadius: 18, background: 'var(--surface-high)', alignItems: 'flex-start' }}>
        <span style={{ color: 'var(--secondary)', marginTop: 1 }}><IcInfo size={18} /></span>
        <span>Шаги не прибавляют калории: расход уже учтён в недельном пересчёте.</span>
      </div>
      <button className="btn btn-soft full" onClick={close}>Готово</button>
    </div>
  )
}

function NumField({ id, label, value, onChange, placeholder, disabled }: { id: string; label: string; value: string; onChange: (v: string) => void; placeholder?: string; disabled?: boolean }) {
  return (
    <div className="grow">
      <label className="field-label" htmlFor={id}>{label}</label>
      <input id={id} className="field num" inputMode="decimal" value={value} placeholder={placeholder} disabled={disabled}
        style={disabled ? { opacity: 0.55, background: 'var(--surface-low)' } : undefined} onChange={(e) => onChange(e.target.value)} />
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
  // В режиме сжигания жира поля только показывают план: ручные цели не меняем,
  // чтобы при возврате в «Похудение» они остались как были.
  const auto = fatGoalOn(s)
  const plan = auto ? livePlan(s) : null
  const shown = (manual: string, v: number | undefined) => (plan && v != null ? String(v) : manual)
  return (
    <div className="col gap-14">
      <div className="h1">Цели и макросы</div>
      {auto && (
        <div className="row gap-10 small" style={{ padding: 14, borderRadius: 18, background: 'color-mix(in srgb, var(--primary) 10%, var(--surface))', alignItems: 'flex-start' }}>
          <span style={{ color: 'var(--primary)', marginTop: 1 }}><IcInfo size={18} /></span>
          <span><b style={{ color: 'var(--text)' }}>В режиме сжигания жира считается автоматически</b> — по весу, замерам и дневнику, пересчёт на недельном чек-ине.
            {plan ? '' : ' Запиши вес — и план появится.'} Твои цели сохранены и вернутся в режиме «Похудение».</span>
        </div>
      )}
      <NumField id="g-kcal" label="Дневная норма, ккал" value={shown(kcal, plan?.calories)} onChange={setKcal} disabled={auto} />
      {!auto && <div className="small">Белки, жиры и углеводы можно оставить пустыми — посчитаю сам от веса и нормы калорий.</div>}
      <div className="row gap-8">
        <NumField id="g-p" label="Белки, г" value={shown(p, plan?.protein)} onChange={setP} placeholder="авто" disabled={auto} />
        <NumField id="g-f" label="Жиры, г" value={shown(f, plan?.fat)} onChange={setF} placeholder="авто" disabled={auto} />
        <NumField id="g-c" label="Углев., г" value={shown(c, plan?.carbs)} onChange={setC} placeholder="авто" disabled={auto} />
      </div>
      {auto
        ? <button className="btn btn-soft full" onClick={close}>Понятно</button>
        : <button className="btn btn-brand full" disabled={!ok} onClick={async () => {
            await saveSettings({ dailyGoal: toNum(kcal)!, proteinGoal: toNum(p), fatGoal: toNum(f), carbsGoal: toNum(c) })
            close()
            toast('Цели сохранены')
          }}>Сохранить</button>}
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

/** Бесплатный тир: сколько запросов сделано сегодня и когда сбросится лимит. */
function FreeTierCard() {
  const u = usageToday()
  return (
    <div className="card pad-14 col gap-6" style={{ background: 'var(--surface-low)' }}>
      <div className="title">Бесплатный режим</div>
      <div className="small">
        Google даёт бесплатный ключ с лимитами: несколько запросов в минуту и несколько сотен в сутки —
        это десятки приёмов пищи в день. Упрёмся в минутный лимит — приложение подождёт и повторит,
        в дневной — перейдёт на резервную модель.
      </div>
      <div className="small num" style={{ color: u.exhausted ? 'var(--warn-to)' : 'var(--primary)', fontWeight: 700 }}>
        Сегодня запросов: {u.count}{u.exhausted ? ` · основная модель до ${dailyResetTime()} на паузе, работает резервная` : ''}
      </div>
    </div>
  )
}

function AiForm({ s }: { s: Settings }) {
  const close = useSheetClose()
  const toast = useToast()
  const [provider, setProvider] = useState<Settings['provider']>(s.provider)
  return (
    <div className="col gap-14">
      <div className="h1">Нейросеть</div>
      {!hasSelectedKey(s) && <div className="small" style={{ fontSize: 14, color: 'var(--tertiary)', fontWeight: 700 }}>Ключ ещё не добавлен — без него ИИ не посчитает калории. Выбери сервис, получи ключ по ссылке и вставь его ниже.</div>}
      <ProviderCards value={provider} onChange={setProvider} />
      {provider === 'gemini' && <FreeTierCard />}
      <KeyField key={provider} s={s} provider={provider} onError={(m) => toast(m)}
        onSaved={() => { close(); toast(provider === 'gemini' ? 'Gemini подключён' : 'NanoGPT подключён') }} />
      <div className="small">
        Ключи хранятся только на этом телефоне. Работает только выбранный сервис — без тихих переключений на другой.
      </div>
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
