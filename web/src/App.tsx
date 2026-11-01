// Корень NutriLens Web: вкладки «Сегодня · Прогресс · [+] · Коуч · Профиль»,
// листы добавления и деталей, чат, онбординг для iPhone.
import { useEffect, useState } from 'react'
import { type Meal, getSettings, saveSettings } from './data/db'
import { runQueue } from './data/actions'
import { TodayScreen } from './screens/Today'
import { ProgressScreen } from './screens/Progress'
import { CoachScreen, ChatScreen } from './screens/Coach'
import { ProfileScreen, InstallHelp } from './screens/Profile'
import { ComposeSheet, QuickAddSheet, WeightSheet } from './screens/AddFlow'
import { MealSheet } from './screens/MealSheet'
import { Sheet, ToastHost, useSheetClose, useToast } from './ui/kit'
import { IcChart, IcHome, IcPlus, IcSpark, IcUser } from './ui/icons'

type Tab = 'today' | 'progress' | 'coach' | 'profile'
type Overlay =
  | { kind: 'add' }
  | { kind: 'compose'; photos: string[]; focusText?: boolean; refine?: Meal }
  | { kind: 'meal'; id: string }
  | { kind: 'weight' }
  | { kind: 'welcome' }
  | null

const isStandalone = () =>
  window.matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true

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

  useEffect(() => {
    getSettings().then((s) => { if (!s.onboarded) setOverlay({ kind: 'welcome' }) })
  }, [])

  const close = () => setOverlay(null)

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
            />
          )}
          {tab === 'progress' && <ProgressScreen />}
          {tab === 'coach' && <CoachScreen onOpenChat={(q) => setChat({ q })} />}
          {tab === 'profile' && <ProfileScreen />}
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
          onClose={close} onSent={() => setTab('today')} />
      )}
      {overlay?.kind === 'meal' && (
        <MealSheet mealId={overlay.id} onClose={close} onRefine={(m) => setOverlay({ kind: 'compose', photos: [], refine: m })} />
      )}
      {overlay?.kind === 'weight' && <WeightSheet onClose={close} />}
      {overlay?.kind === 'welcome' && (
        <Sheet label="Добро пожаловать" onClose={() => { saveSettings({ onboarded: true }); close() }}>
          <Welcome onSetupKey={() => { saveSettings({ onboarded: true }); setTab('profile') }} />
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

function Welcome({ onSetupKey }: { onSetupKey: () => void }) {
  const close = useSheetClose()
  const standalone = isStandalone()
  return (
    <div className="col gap-16">
      <img src="./icons/icon-192.png" alt="" style={{ width: 72, height: 72, borderRadius: 18, boxShadow: 'var(--glow)' }} />
      <div>
        <div className="h1">NutriLens на iPhone</div>
        <div className="small" style={{ fontSize: 14, marginTop: 4 }}>
          Сфотографируй еду — ИИ посчитает калории и БЖУ. Дневник, прогресс и коуч — как в Android-версии. Данные хранятся только на телефоне.
        </div>
      </div>
      {!standalone && <InstallHelp />}
      <button className="btn btn-brand full" onClick={() => { close(); setTimeout(onSetupKey, 220) }}>Подключить ИИ</button>
      <button className="btn btn-soft full" onClick={close}>Позже</button>
    </div>
  )
}
