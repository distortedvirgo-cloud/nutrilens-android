// Корень NutriLens Web: вкладки «Сегодня · Прогресс · [+] · Коуч · Профиль»,
// листы добавления и деталей, чат, онбординг для iPhone.
import { useEffect, useState } from 'react'
import { type Meal, type Settings, getSettings, onDataChanged } from './data/db'
import { runQueue } from './data/actions'
import { applyUpdate, checkUpdate, remindLater, skipVersion, type UpdateInfo } from './data/update'
import { TodayScreen } from './screens/Today'
import { ProgressScreen } from './screens/Progress'
import { CoachScreen, ChatScreen } from './screens/Coach'
import { ProfileScreen } from './screens/Profile'
import { ComposeSheet, QuickAddSheet, WeightSheet } from './screens/AddFlow'
import { MealSheet } from './screens/MealSheet'
import { Onboarding } from './screens/Onboarding'
import { hasAnyKey } from './ai'
import { Sheet, ToastHost, useSheetClose, useToast } from './ui/kit'
import { IcChart, IcHome, IcPlus, IcSpark, IcUser } from './ui/icons'

type Tab = 'today' | 'progress' | 'coach' | 'profile'
type Overlay =
  | { kind: 'add' }
  | { kind: 'compose'; photos: string[]; focusText?: boolean; refine?: Meal }
  | { kind: 'meal'; id: string }
  | { kind: 'weight' }
  | { kind: 'update'; info: UpdateInfo }
  | null

export default function App() {
  return (
    <ToastHost>
      <Shell />
    </ToastHost>
  )
}

function Shell() {
  const [tab, setTab] = useState<Tab>('today')
  const [chat, setChat] = useState<{ q?: string } | null>(null)
  const [overlay, setOverlay] = useState<Overlay>(null)
  const toast = useToast()

  // Очередь ИИ: при запуске, возврате в приложение и появлении сети.
  useEffect(() => {
    const run = () => runQueue((meal) => toast(`Готово: ${meal.name} — ${Math.round(meal.calories)} ккал`))
    run()
    const onVis = () => document.visibilityState === 'visible' && run()
    document.addEventListener('visibilitychange', onVis)
    window.addEventListener('online', run)
    return () => {
      document.removeEventListener('visibilitychange', onVis)
      window.removeEventListener('online', run)
    }
  }, [toast])

  // Настройки: первый запуск → онбординг; плашка «подключи нейросеть», пока нет ключа.
  const [settings, setSettings] = useState<Settings | null>(null)
  const [profileAi, setProfileAi] = useState(0)
  useEffect(() => {
    const load = () => getSettings().then(setSettings)
    load()
    return onDataChanged(load)
  }, [])
  const openAiSetup = () => { setChat(null); setTab('profile'); setProfileAi((n) => n + 1) }

  // Новая версия на сервере — окно со сводкой. Проверяем при входе и при
  // возвращении в приложение (на iPhone PWA часто не перезапускается неделями).
  useEffect(() => {
    let last = 0
    const check = () => {
      if (Date.now() - last < 30 * 60 * 1000) return
      last = Date.now()
      checkUpdate().then((info) => {
        if (info) setOverlay((o) => o ?? { kind: 'update', info })
      })
    }
    const t = setTimeout(check, 1500)
    const onVis = () => document.visibilityState === 'visible' && check()
    document.addEventListener('visibilitychange', onVis)
    return () => { clearTimeout(t); document.removeEventListener('visibilitychange', onVis) }
  }, [])

  const close = () => setOverlay(null)

  if (settings && !settings.onboarded) {
    return (
      <div className="app">
        <div className="aurora" />
        <Onboarding settings={settings} onDone={(openAi) => { if (openAi) openAiSetup() }} />
      </div>
    )
  }

  return (
    <div className="app">
      <div className="aurora" />
      {chat ? (
        <ChatScreen initial={chat.q} onBack={() => setChat(null)} />
      ) : (
        <>
          {tab === 'today' && (
            <TodayScreen
              onOpenMeal={(m) => setOverlay({ kind: 'meal', id: m.id })}
              onAddPhoto={() => setOverlay({ kind: 'add' })}
              onOpenCoach={() => setTab('coach')}
              onWeigh={() => setOverlay({ kind: 'weight' })}
              onOpenProgress={() => setTab('progress')}
              needsKey={!!settings && !hasAnyKey(settings)}
              onSetupAi={openAiSetup}
            />
          )}
          {tab === 'progress' && <ProgressScreen />}
          {tab === 'coach' && <CoachScreen onOpenChat={(q) => setChat({ q })} />}
          {tab === 'profile' && <ProfileScreen openAi={profileAi} />}
          <TabBar tab={tab} onTab={setTab} onAdd={() => setOverlay({ kind: 'add' })} />
        </>
      )}

      {overlay?.kind === 'add' && (
        <QuickAddSheet onClose={close}
          onCompose={(photos, focusText) => setOverlay({ kind: 'compose', photos, focusText })}
          onWeigh={() => setOverlay({ kind: 'weight' })} />
      )}
      {overlay?.kind === 'compose' && (
        <ComposeSheet initialPhotos={overlay.photos} focusText={overlay.focusText} refineMeal={overlay.refine}
          onClose={close} onSent={() => setTab('today')} onSetupAi={openAiSetup} />
      )}
      {overlay?.kind === 'meal' && (
        <MealSheet mealId={overlay.id} onClose={close} onRefine={(m) => setOverlay({ kind: 'compose', photos: [], refine: m })} />
      )}
      {overlay?.kind === 'weight' && <WeightSheet onClose={close} />}
      {overlay?.kind === 'update' && (
        <Sheet label="Обновление" onClose={() => { remindLater(); close() }}>
          <UpdatePrompt info={overlay.info} onSkip={() => { skipVersion(overlay.info.version); close() }} />
        </Sheet>
      )}
    </div>
  )
}

function TabBar({ tab, onTab, onAdd }: { tab: Tab; onTab: (t: Tab) => void; onAdd: () => void }) {
  const item = (t: Tab, label: string, icon: React.ReactNode) => (
    <button className="tab" role="tab" aria-selected={tab === t} onClick={() => onTab(t)}>{icon}{label}</button>
  )
  return (
    <nav className="tabbar" role="tablist" aria-label="Основное меню">
      {item('today', 'Сегодня', <IcHome size={24} />)}
      {item('progress', 'Прогресс', <IcChart size={24} />)}
      <button className="fab" aria-label="Добавить" onClick={onAdd}><IcPlus size={28} strokeWidth={2.6} /></button>
      {item('coach', 'Коуч', <IcSpark size={24} />)}
      {item('profile', 'Профиль', <IcUser size={24} />)}
    </nav>
  )
}

function UpdatePrompt({ info, onSkip }: { info: UpdateInfo; onSkip: () => void }) {
  const close = useSheetClose()
  const [busy, setBusy] = useState(false)
  return (
    <div className="col gap-16">
      <img src="./icons/icon-192.png" alt="" style={{ width: 64, height: 64, borderRadius: 16, boxShadow: 'var(--glow)' }} />
      <div>
        <div className="label" style={{ color: 'var(--primary)' }}>Вышло обновление · {__APP_VERSION__} → {info.version}</div>
        <div className="h1" style={{ marginTop: 4 }}>{info.title || `NutriLens ${info.version}`}</div>
      </div>
      {info.items.length > 0 && (
        <div className="card pad-16 col gap-10" style={{ background: 'color-mix(in srgb, var(--primary) 9%, var(--surface))' }}>
          {info.items.map((it, i) => (
            <div key={i} className="row gap-10" style={{ alignItems: 'flex-start' }}>
              <span style={{ width: 6, height: 6, borderRadius: 3, background: 'var(--primary)', marginTop: 8, flex: 'none' }} />
              <span style={{ fontSize: 15, lineHeight: 1.45 }}>{it}</span>
            </div>
          ))}
        </div>
      )}
      <button className="btn btn-brand full" disabled={busy} onClick={() => { setBusy(true); applyUpdate() }}>
        {busy ? <span className="spinner" /> : null}{busy ? 'Обновляем…' : 'Обновить'}
      </button>
      <button className="btn btn-soft full" onClick={close}>Напомнить позже</button>
      <button className="btn full" style={{ height: 44, color: 'var(--text-2)', fontWeight: 700 }} onClick={onSkip}>Не напоминать об этой версии</button>
    </div>
  )
}
