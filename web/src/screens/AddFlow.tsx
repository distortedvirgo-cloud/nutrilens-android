// Добавление: лист «+» (фото, галерея, текст, вода, вес, тренировка,
// избранное) и лист составления запроса к ИИ.
import { useEffect, useRef, useState } from 'react'
import { hasSelectedKey } from '../ai'
import { type Favorite, type Meal, addWater, favorites, getSettings, putMeal, setWeight, toggleWorkout, uid } from '../data/db'
import { enqueueAnalysis, runQueue, storePhoto } from '../data/actions'
import { iso, nowTime } from '../data/insights'
import { Badge, Photo, Sheet, useData, useSheetClose, useToast } from '../ui/kit'
import { IcCamera, IcChevronR, IcClose, IcDrop, IcDumbbell, IcGallery, IcScale, IcSpark, IcText } from '../ui/icons'

/** Скрытые input'ы: камера и галерея iOS открываются только из жеста пользователя. */
export function usePhotoPickers(onPicked: (ids: string[]) => void) {
  const camRef = useRef<HTMLInputElement>(null)
  const galRef = useRef<HTMLInputElement>(null)
  const toast = useToast()
  const handle = async (files: FileList | null) => {
    if (!files?.length) return
    try {
      const ids: string[] = []
      for (const f of Array.from(files).slice(0, 6)) ids.push(await storePhoto(f))
      onPicked(ids)
    } catch {
      toast('Не удалось открыть фото')
    }
  }
  const inputs = (
    <>
      <input ref={camRef} type="file" accept="image/*" capture="environment" hidden
        onChange={(e) => { handle(e.target.files); e.target.value = '' }} />
      <input ref={galRef} type="file" accept="image/*" multiple hidden
        onChange={(e) => { handle(e.target.files); e.target.value = '' }} />
    </>
  )
  return { inputs, camera: () => camRef.current?.click(), gallery: () => galRef.current?.click() }
}

export function QuickAddSheet({ onClose, onCompose, onWeigh }: {
  onClose: () => void
  onCompose: (photoIds: string[], focusText?: boolean) => void
  onWeigh: () => void
}) {
  const favs = useData(favorites, [], [] as Favorite[])
  return (
    <Sheet onClose={onClose} label="Добавить">
      <QuickAddBody favs={favs} onCompose={onCompose} onWeigh={onWeigh} />
    </Sheet>
  )
}

function QuickAddBody({ favs, onCompose, onWeigh }: {
  favs: Favorite[]; onCompose: (ids: string[], focusText?: boolean) => void; onWeigh: () => void
}) {
  const close = useSheetClose()
  const toast = useToast()
  const { inputs, camera, gallery } = usePhotoPickers((ids) => { close(); setTimeout(() => onCompose(ids), 220) })

  const addFav = async (f: Favorite) => {
    const s = await getSettings()
    const meal: Meal = {
      id: uid(), date: iso(new Date()), time: nowTime(), name: f.name, calories: f.calories, protein: f.protein,
      fat: f.fat, carbs: f.carbs, aiThoughts: 'Добавлено из избранного', reasoning: '', confidenceScore: 10,
      healthScore: null, healthNote: null, dailyGoalSnapshot: s.dailyGoal, createdAt: Date.now(), items: [], photoIds: []
    }
    await putMeal(meal)
    close()
    toast(`«${f.name}» записано`)
  }

  return (
    <div className="col gap-16">
      {inputs}
      <div className="h1">Что добавим?</div>
      <button className="btn-brand pressable row gap-16" onClick={camera}
        style={{ borderRadius: 26, padding: 18, textAlign: 'left', boxShadow: 'var(--glow)', background: 'linear-gradient(135deg, var(--brand-from), var(--brand-to))', color: 'var(--on-brand)' }}>
        <span className="badge" style={{ width: 56, height: 56, borderRadius: 18, background: 'rgba(255,255,255,0.18)' }}><IcCamera size={28} /></span>
        <span className="grow col">
          <span style={{ fontSize: 18, fontWeight: 800 }}>Сфотографировать</span>
          <span style={{ fontSize: 13, fontWeight: 600, opacity: 0.85 }}>ИИ посчитает калории и БЖУ</span>
        </span>
        <IcChevronR />
      </button>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
        <Tile icon={<IcGallery size={22} />} label="Галерея" tint="var(--protein)" onClick={gallery} />
        <Tile icon={<IcText size={22} />} label="Текстом" tint="var(--carbs)" onClick={() => { close(); setTimeout(() => onCompose([], true), 220) }} />
        <Tile icon={<IcDrop size={22} />} label="+250 мл" tint="var(--water-deep)" onClick={async () => {
          const ml = await addWater(iso(new Date()), 250)
          close()
          toast(`Вода: ${ml} мл за сегодня`)
        }} />
        <Tile icon={<IcScale size={22} />} label="Вес" tint="var(--warn-to)" onClick={() => { close(); setTimeout(onWeigh, 220) }} />
        <Tile icon={<IcDumbbell size={22} />} label="Тренировка" tint="var(--primary)" onClick={async () => {
          const done = await toggleWorkout(iso(new Date()))
          close()
          toast(done ? 'Тренировка отмечена: +500 мл к норме воды' : 'Отметка тренировки снята')
        }} />
      </div>
      {favs.length > 0 && (
        <div className="col gap-10">
          <div className="overline">Избранное — одним тапом</div>
          <div className="row gap-8" style={{ flexWrap: 'wrap' }}>
            {favs.slice(0, 10).map((f) => (
              <button key={f.id} className="chip pressable" onClick={() => addFav(f)} style={{ height: 42 }}>
                <span className="ellipsis" style={{ maxWidth: 190 }}>{f.name}</span>
                <span className="muted num" style={{ fontWeight: 600, fontSize: 13 }}>{Math.round(f.calories)}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

const Tile = ({ icon, label, tint, onClick }: { icon: React.ReactNode; label: string; tint: string; onClick: () => void }) => (
  <button className="pressable col" onClick={onClick}
    style={{ alignItems: 'center', gap: 8, padding: '14px 6px', borderRadius: 22, background: 'var(--surface-high)', border: '1px solid var(--hairline)' }}>
    <Badge tint={tint} size={42}>{icon}</Badge>
    <span style={{ fontSize: 13.5, fontWeight: 700 }}>{label}</span>
  </button>
)

/** Фото + описание → задача в очередь ИИ. Можно закрыть приложение: анализ продолжится при следующем открытии. */
export function ComposeSheet({ initialPhotos, focusText, refineMeal, onClose, onSent, onSetupAi }: {
  initialPhotos: string[]; focusText?: boolean; refineMeal?: Meal; onClose: () => void; onSent: () => void; onSetupAi?: () => void
}) {
  return (
    <Sheet onClose={onClose} label={refineMeal ? 'Поправить блюдо' : 'Новый приём пищи'}>
      <ComposeBody initialPhotos={initialPhotos} focusText={focusText} refineMeal={refineMeal} onSent={onSent} onSetupAi={onSetupAi} />
    </Sheet>
  )
}

function ComposeBody({ initialPhotos, focusText, refineMeal, onSent, onSetupAi }: {
  initialPhotos: string[]; focusText?: boolean; refineMeal?: Meal; onSent: () => void; onSetupAi?: () => void
}) {
  const close = useSheetClose()
  const toast = useToast()
  const [photos, setPhotos] = useState(initialPhotos)
  const [note, setNote] = useState('')
  const [hasKey, setHasKey] = useState(true)
  const textRef = useRef<HTMLTextAreaElement>(null)
  const { inputs, camera, gallery } = usePhotoPickers((ids) => setPhotos((p) => [...p, ...ids].slice(0, 6)))

  useEffect(() => { getSettings().then((s) => setHasKey(hasSelectedKey(s))) }, [])
  useEffect(() => {
    if (focusText || refineMeal) setTimeout(() => textRef.current?.focus(), 350)
  }, [focusText, refineMeal])

  const canSend = hasKey && (refineMeal ? note.trim().length > 0 : photos.length > 0 || note.trim().length > 0)
  const send = async () => {
    await enqueueAnalysis(note.trim(), refineMeal ? [] : photos, refineMeal?.id)
    close()
    onSent()
    toast(refineMeal ? 'Уточнение отправлено — пересчитываю' : 'Анализ запущен — результат появится в дневнике')
    runQueue()
  }

  return (
    <div className="col gap-16">
      {inputs}
      <div className="h1">{refineMeal ? 'Поправить блюдо' : 'Новый приём пищи'}</div>
      {refineMeal && <div className="small">«{refineMeal.name}» — напиши, что исправить. ИИ пересчитает КБЖУ.</div>}

      {!refineMeal && (
        <div className="row gap-10" style={{ overflowX: 'auto', paddingBottom: 2 }}>
          {photos.map((id) => (
            <div key={id} style={{ position: 'relative', flexShrink: 0, animation: 'rise 360ms var(--ease-emph) both' }}>
              <Photo id={id} style={{ width: 96, height: 96, borderRadius: 20 }} />
              <button aria-label="Убрать фото" onClick={() => setPhotos((p) => p.filter((x) => x !== id))}
                style={{ position: 'absolute', top: 4, right: 4, width: 28, height: 28, borderRadius: 14, background: 'rgba(0,0,0,0.55)', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <IcClose size={15} />
              </button>
            </div>
          ))}
          <button className="pressable col" onClick={camera} aria-label="Сфотографировать"
            style={{ width: 96, height: 96, flexShrink: 0, borderRadius: 20, border: '1.5px dashed color-mix(in srgb, var(--text-3) 50%, transparent)', alignItems: 'center', justifyContent: 'center', gap: 4, color: 'var(--primary)' }}>
            <IcCamera size={24} /><span style={{ fontSize: 12, fontWeight: 700 }}>Камера</span>
          </button>
          <button className="pressable col" onClick={gallery} aria-label="Из галереи"
            style={{ width: 96, height: 96, flexShrink: 0, borderRadius: 20, border: '1.5px dashed color-mix(in srgb, var(--text-3) 50%, transparent)', alignItems: 'center', justifyContent: 'center', gap: 4, color: 'var(--protein)' }}>
            <IcGallery size={24} /><span style={{ fontSize: 12, fontWeight: 700 }}>Галерея</span>
          </button>
        </div>
      )}

      <div>
        <label className="field-label" htmlFor="note">{refineMeal ? 'Что исправить' : 'Описание (необязательно)'}</label>
        <textarea id="note" ref={textRef} className="field" value={note} onChange={(e) => setNote(e.target.value)}
          placeholder={refineMeal ? 'Например: вес не 170 г, а 125 г' : 'Например: борщ со сметаной, порция ~300 г'} />
      </div>

      {!hasKey && (
        <div className="card r22 pad-14 small" style={{ background: 'var(--tertiary-cont)', color: 'var(--on-tertiary-cont)' }}>
          <div>Нужен ключ NanoGPT или Gemini — без него ИИ не посчитает калории.</div>
          {onSetupAi && <button className="btn btn-soft" style={{ height: 40, marginTop: 10 }} onClick={() => { close(); onSetupAi() }}>Подключить нейросеть</button>}
        </div>
      )}

      <button className="btn btn-brand full" disabled={!canSend} onClick={send}>
        <IcSpark size={20} />{refineMeal ? 'Пересчитать' : 'Проанализировать'}
      </button>
      {!refineMeal && <div className="small" style={{ textAlign: 'center' }}>Можно закрыть приложение — анализ продолжится при следующем открытии</div>}
    </div>
  )
}

export function WeightSheet({ onClose }: { onClose: () => void }) {
  return (
    <Sheet onClose={onClose} label="Записать вес">
      <WeightBody />
    </Sheet>
  )
}

function WeightBody() {
  const close = useSheetClose()
  const toast = useToast()
  const [v, setV] = useState('')
  const kg = parseFloat(v.replace(',', '.'))
  const ok = Number.isFinite(kg) && kg >= 20 && kg <= 400
  return (
    <div className="col gap-16">
      <div className="h1">Записать вес</div>
      <div>
        <label className="field-label" htmlFor="kg">Вес, кг</label>
        <input id="kg" className="field num" inputMode="decimal" autoFocus value={v} onChange={(e) => setV(e.target.value)} placeholder="72,4" />
      </div>
      <button className="btn btn-brand full" disabled={!ok} onClick={async () => {
        await setWeight(iso(new Date()), Math.round(kg * 10) / 10)
        close()
        toast(`Вес ${kg.toFixed(1).replace(".", ",")} кг записан`)
      }}>Сохранить</button>
    </div>
  )
}
