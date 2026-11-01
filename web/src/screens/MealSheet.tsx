// Детали блюда: фото, плашки КБЖУ, оценка состава, продукты, мысли ИИ, действия.
import { useState } from 'react'
import { type Meal, addFavorite, deleteMeal, favorites, getMeal, putMeal } from '../data/db'
import { healthLabel, healthScoreOf } from '../data/insights'
import { Photo, Sheet, useData, useSheetClose, useToast, Md } from '../ui/kit'
import { IcEdit, IcSpark, IcStar, IcTrash } from '../ui/icons'

export function MealSheet({ mealId, onClose, onRefine }: { mealId: string; onClose: () => void; onRefine: (m: Meal) => void }) {
  return (
    <Sheet onClose={onClose} label="Блюдо">
      <MealBody mealId={mealId} onRefine={onRefine} />
    </Sheet>
  )
}

function MealBody({ mealId, onRefine }: { mealId: string; onRefine: (m: Meal) => void }) {
  const close = useSheetClose()
  const toast = useToast()
  const meal = useData(() => getMeal(mealId), [mealId], undefined as Meal | undefined)
  const isFav = useData(async () => {
    const m = await getMeal(mealId)
    return !!m && (await favorites()).some((f) => f.name === m.name && Math.abs(f.calories - m.calories) < 0.5)
  }, [mealId], false)
  const [editing, setEditing] = useState(false)
  if (!meal) return <div style={{ height: 200 }} />

  const hs = healthScoreOf(meal)
  const hc = hs >= 75 ? 'var(--primary)' : hs >= 50 ? 'var(--water-deep)' : hs >= 25 ? 'var(--tertiary)' : 'var(--error)'

  if (editing) return <EditForm meal={meal} onDone={() => setEditing(false)} />

  return (
    <div className="col gap-16">
      {meal.photoIds[0] && (
        <Photo id={meal.photoIds[0]} kind="full" alt={meal.name}
          style={{ width: '100%', height: 230, borderRadius: 24, animation: 'rise 420ms var(--ease-emph) both' }} />
      )}
      <div>
        <div className="overline num">{meal.time}</div>
        <div className="h1">{meal.name}</div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr 1fr 1fr', gap: 8 }}>
        <Tile v={meal.calories} l="ккал" c="var(--primary)" />
        <Tile v={meal.protein} l="белки" c="var(--protein)" />
        <Tile v={meal.fat} l="жиры" c="var(--fat)" />
        <Tile v={meal.carbs} l="углев." c="var(--carbs)" />
      </div>
      <div className="row gap-12" style={{ padding: 14, borderRadius: 18, background: `color-mix(in srgb, ${hc} 11%, transparent)` }}>
        <span className="num" style={{ fontSize: 22, fontWeight: 800, color: hc }}>{hs}</span>
        <div className="grow">
          <div className="title">{healthLabel(hs)}</div>
          {meal.healthNote && <div className="small">{meal.healthNote}</div>}
        </div>
      </div>
      {meal.items.length > 0 && (
        <div className="col">
          <div className="title" style={{ marginBottom: 6 }}>Состав</div>
          {meal.items.map((it, i) => (
            <div key={i} className="row gap-12" style={{ padding: '10px 0', borderTop: i ? '1px solid var(--hairline)' : 'none', alignItems: 'flex-start' }}>
              <div className="grow">
                <div style={{ fontWeight: 700 }}>{it.name}</div>
                {it.breakdown && <div className="small">{it.breakdown}</div>}
              </div>
              <div style={{ textAlign: 'right' }}>
                <div className="num" style={{ fontWeight: 800 }}>{Math.round(it.calories)} ккал</div>
                <div className="label num">{Math.round(it.weightG)} г</div>
              </div>
            </div>
          ))}
        </div>
      )}
      {meal.aiThoughts && (
        <div>
          <div className="title" style={{ marginBottom: 6 }}>Мысли ИИ</div>
          <div className="small" style={{ fontSize: 14 }}><Md text={meal.aiThoughts} /></div>
        </div>
      )}
      <div className="row gap-10">
        <button className="btn btn-soft grow" onClick={() => setEditing(true)}><IcEdit size={18} />Изменить</button>
        <button className="btn btn-brand grow" onClick={() => { close(); setTimeout(() => onRefine(meal), 220) }}><IcSpark size={18} />Уточнить</button>
      </div>
      <div className="row gap-10">
        <button className="btn btn-soft grow sm" disabled={isFav} onClick={async () => { await addFavorite(meal); toast('Добавлено в избранное') }}>
          <IcStar size={18} />{isFav ? 'В избранном' : 'В избранное'}
        </button>
        <button className="btn btn-danger grow sm" onClick={async () => {
          const removed = await deleteMeal(meal.id)
          close()
          if (removed) toast('Запись удалена', { label: 'Вернуть', run: () => putMeal(removed) })
        }}><IcTrash size={18} />Удалить</button>
      </div>
    </div>
  )
}

const Tile = ({ v, l, c }: { v: number; l: string; c: string }) => (
  <div style={{ padding: '12px 6px', borderRadius: 18, textAlign: 'center', background: `color-mix(in srgb, ${c} 11%, transparent)` }}>
    <div className="num" style={{ fontSize: 20, fontWeight: 800, color: c }}>{Math.round(v)}</div>
    <div className="label">{l}</div>
  </div>
)

function EditForm({ meal, onDone }: { meal: Meal; onDone: () => void }) {
  const toast = useToast()
  const [f, setF] = useState({
    name: meal.name, calories: String(meal.calories), protein: String(meal.protein), fat: String(meal.fat), carbs: String(meal.carbs)
  })
  const n = (s: string) => parseFloat(s.replace(',', '.'))
  const valid = f.name.trim() && [f.calories, f.protein, f.fat, f.carbs].every((x) => Number.isFinite(n(x)) && n(x) >= 0)
  const field = (key: keyof typeof f, label: string, numeric = true) => (
    <div className="grow">
      <label className="field-label" htmlFor={`e-${key}`}>{label}</label>
      <input id={`e-${key}`} className="field num" inputMode={numeric ? 'decimal' : 'text'} value={f[key]}
        onChange={(e) => setF({ ...f, [key]: e.target.value })} />
    </div>
  )
  return (
    <div className="col gap-14">
      <div className="h1">Изменить запись</div>
      {field('name', 'Название', false)}
      {field('calories', 'Калории, ккал')}
      <div className="row gap-8">{field('protein', 'Белки')}{field('fat', 'Жиры')}{field('carbs', 'Углев.')}</div>
      <div className="row gap-10">
        <button className="btn btn-soft grow" onClick={onDone}>Отмена</button>
        <button className="btn btn-brand grow" disabled={!valid} onClick={async () => {
          await putMeal({ ...meal, name: f.name.trim(), calories: n(f.calories), protein: n(f.protein), fat: n(f.fat), carbs: n(f.carbs) })
          toast('Сохранено')
          onDone()
        }}>Сохранить</button>
      </div>
    </div>
  )
}
