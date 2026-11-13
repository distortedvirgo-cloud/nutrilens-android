// Состав тела: лист замера (талия, шея, бёдра → процент жира), степпер и форма цели по жиру.
import { useState } from 'react'
import {
  type BodyMeasurement, type Settings, getSettings, latestWeight, measurementOn, putMeasurement, saveSettings
} from '../data/db'
import { FAT_TARGET_MAX, bodyProfile, composition, defaultFatTarget, fmt1, measurementFat, navyFatPercent } from '../data/body'
import { targetFloor, targetWarn } from '../data/program'
import { retarget } from '../data/plan'
import { iso } from '../data/insights'
import { Segmented, Sheet, useData, useSheetClose, useToast } from '../ui/kit'
import { IcAlert, IcInfo, IcMinus, IcPlus } from '../ui/icons'

const num = (v: string): number | null => {
  const n = parseFloat(v.replace(',', '.'))
  return Number.isFinite(n) && n > 0 ? n : null
}
const str = (v: number | null | undefined) => (v != null ? String(v).replace('.', ',') : '')

export function MeasureSheet({ onClose }: { onClose: () => void }) {
  const data = useData(async () => ({
    s: await getSettings(),
    w: (await latestWeight())?.weight ?? null,
    today: (await measurementOn(iso(new Date()))) ?? null
  }), [], null)
  return (
    <Sheet label="Замер тела" onClose={onClose}>
      {data ? <MeasureForm s={data.s} weight={data.w} today={data.today} /> : <div style={{ height: 320 }} />}
    </Sheet>
  )
}

/**
 * Форма замера. В листе «Замер тела» после сохранения закрывает лист; в чек-ине
 * (onSaved) — переходит к следующему шагу, а extra — кнопка «замерю позже».
 */
export function MeasureForm({ s, weight, today, onSaved, extra, title }: {
  s: Settings; weight: number | null; today: BodyMeasurement | null; onSaved?: () => void; extra?: React.ReactNode; title?: string
}) {
  const sheetClose = useSheetClose()
  const close = onSaved ?? sheetClose
  const toast = useToast()
  const prof = bodyProfile(s)
  const [female, setFemale] = useState<boolean>(prof.female ?? false)
  const [height, setHeight] = useState(str(prof.heightCm))
  const [waist, setWaist] = useState(str(today?.waistCm))
  const [neck, setNeck] = useState(str(today?.neckCm))
  const [hips, setHips] = useState(str(today?.hipsCm))
  const [manual, setManual] = useState(str(today?.manualFatPercent))

  const h = num(height)
  const heightOk = h != null && h >= 120 && h <= 230
  const w = num(waist), n = num(neck), hp = num(hips), man = num(manual)
  const computed = heightOk && w && n ? navyFatPercent({ female, heightCm: h!, waistCm: w, neckCm: n, hipsCm: hp }) : null
  const manualOk = man == null || (man >= 3 && man <= 60)
  const fat = manualOk ? measurementFat(man, computed) : null
  const ok = w != null && n != null && fat != null && (!female || hp != null || man != null)
  const comp = fat != null && weight != null ? composition(weight, fat, null) : null

  return (
    <div className="col gap-14">
      <div>
        <div className="h1">{title ?? (today ? 'Замер за сегодня' : 'Новый замер')}</div>
        {today && <div className="small">Сохранишь — сегодняшний замер обновится</div>}
      </div>
      <div className="row gap-10 small" style={{ padding: 14, borderRadius: 18, background: 'var(--surface-high)', alignItems: 'flex-start' }}>
        <span style={{ color: 'var(--secondary)', marginTop: 1 }}><IcInfo size={18} /></span>
        <span>Талия — на уровне пупка, шея — под кадыком{female ? ', бёдра — по самой широкой части' : ''}. Мерь утром натощак, лентой плотно, но не сдавливая.</span>
      </div>
      {(prof.female == null || prof.heightCm == null) && (
        <>
          {prof.female == null && <Segmented options={[[1, 'Мужчина'], [0, 'Женщина']]} value={female ? 0 : 1} onChange={(v) => setFemale(v === 0)} />}
          {prof.heightCm == null && <Field id="m-h" label="Рост, см" value={height} onChange={setHeight} placeholder="178" />}
        </>
      )}
      <div className="row gap-8">
        <Field id="m-waist" label="Талия, см" value={waist} onChange={setWaist} placeholder="86" />
        <Field id="m-neck" label="Шея, см" value={neck} onChange={setNeck} placeholder="38" />
        {female && <Field id="m-hips" label="Бёдра, см" value={hips} onChange={setHips} placeholder="98" />}
      </div>
      <Field id="m-manual" label="Свой процент (весы, калипер, DEXA)" value={manual} onChange={setManual} placeholder="необязательно" />
      <div className="card pad-16 col gap-4" style={{ background: 'color-mix(in srgb, var(--primary) 9%, var(--surface))' }} aria-live="polite">
        <div className="label" style={{ color: 'var(--primary)' }}>Процент жира</div>
        <div className="h1 num">{fat != null ? `${fmt1(fat)}%` : '—'}</div>
        <div className="small">
          {fat == null
            ? !manualOk ? 'Свой процент — от 3 до 60' : !heightOk ? 'Укажи рост, чтобы посчитать' : w != null && n != null && w <= n ? 'Талия должна быть больше шеи' : 'Введи замеры — посчитаю по формуле ВМС США'
            : man != null ? 'Твой процент — он важнее формулы' : 'Формула ВМС США'}
          {comp ? ` · жир ${fmt1(comp.fatMass)} кг, сухая масса ${fmt1(comp.leanMass)} кг` : ''}
        </div>
      </div>
      <button className="btn btn-brand full" disabled={!ok} onClick={async () => {
        const m: BodyMeasurement = { date: iso(new Date()), waistCm: w!, neckCm: n!, fatPercent: fat! }
        if (female && hp != null) m.hipsCm = hp
        if (man != null) m.manualFatPercent = man
        const patch: Partial<Settings> = {}
        if (s.sex == null) patch.sex = female ? 'female' : 'male'
        if (s.heightCm == null && heightOk) patch.heightCm = Math.round(h!)
        if (Object.keys(patch).length) await saveSettings(patch)
        await putMeasurement(m)
        close()
        toast(`Замер сохранён: ${fmt1(fat!)}% жира`)
      }}>Сохранить замер</button>
      {extra}
    </div>
  )
}

function Field({ id, label, value, onChange, placeholder }: { id: string; label: string; value: string; onChange: (v: string) => void; placeholder?: string }) {
  return (
    <div className="grow">
      <label className="field-label" htmlFor={id}>{label}</label>
      <input id={id} className="field num" inputMode="decimal" value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
    </div>
  )
}

/**
 * Степпер целевого процента: ниже 10% у мужчин и 18% у женщин не опускается —
 * дальше это уже не здоровье; ниже 12% / 20% — спокойное предупреждение.
 */
export function TargetStepper({ target, onChange, female, currentFat }: {
  target: number; onChange: (v: number) => void; female: boolean; currentFat: number | null
}) {
  const min = targetFloor(female)
  const step = (d: number) => onChange(Math.min(FAT_TARGET_MAX, Math.max(min, target + d)))
  return (
    <div className="col gap-10">
      <div className="card pad-16 row gap-12" style={{ background: 'var(--surface-low)' }}>
        <button className="icon-btn" aria-label="Меньше" disabled={target <= min} onClick={() => step(-1)}><IcMinus size={20} /></button>
        <div className="grow col" style={{ alignItems: 'center' }}>
          <span className="display num" aria-live="polite">{target}%</span>
          <span className="label">целевой процент жира</span>
        </div>
        <button className="icon-btn" aria-label="Больше" disabled={target >= FAT_TARGET_MAX} onClick={() => step(1)}><IcPlus size={20} /></button>
      </div>
      <div className="small">
        Ориентир: {female ? 'у женщин 20–25% — спортивная форма, 25–31% — норма' : 'у мужчин 12–15% — видный пресс, 15–20% — подтянутая форма'}.
      </div>
      {target < targetWarn(female) && (
        <div className="row gap-10 small" style={{ padding: 12, borderRadius: 16, background: 'color-mix(in srgb, var(--warn-to) 10%, transparent)', alignItems: 'flex-start' }}>
          <span style={{ color: 'var(--warn-to)', marginTop: 1 }}><IcAlert size={17} /></span>
          <span>Ниже {targetWarn(female)}% держаться трудно и не всегда полезно — гормоны, сон и настроение страдают. Если цель именно такая, иди к ней медленно.</span>
        </div>
      )}
      {currentFat != null && target >= currentFat - 0.5 && (
        <div className="small" style={{ color: 'var(--warn-to)', fontWeight: 700 }}>Цель должна быть ниже текущих {fmt1(currentFat)}%.</div>
      )}
    </div>
  )
}

/**
 * Профиль → «Цель по жиру» (режим уже включён): целевой процент, рост и пол.
 * Новая цель пересчитывает блоки программы от текущего блока. Выключается режим
 * переключателем в Профиле — там подтверждение, что программа закончится.
 */
export function FatGoalForm({ s, currentFat, hasMeasurement, onMeasure }: { s: Settings; currentFat: number | null; hasMeasurement: boolean; onMeasure: () => void }) {
  const close = useSheetClose()
  const toast = useToast()
  const prof = bodyProfile(s)
  const [female, setFemale] = useState(prof.female ?? false)
  const [target, setTarget] = useState<number>(s.targetFatPercent ?? defaultFatTarget(prof.female))
  const [height, setHeight] = useState(str(prof.heightCm))
  const h = num(height)
  const heightOk = h == null || (h >= 120 && h <= 230)
  const t = Math.max(targetFloor(female), target)

  return (
    <div className="col gap-14">
      <div className="h1">Цель по жиру</div>
      <div className="small" style={{ fontSize: 14 }}>
        Не просто меньше килограммов, а меньше жира при тех же мышцах. Сменишь цель — блоки программы пересчитаются
        от текущего, пройденные этапы останутся.
      </div>
      <TargetStepper target={t} onChange={setTarget} female={female} currentFat={currentFat} />
      <Segmented options={[[1, 'Мужчина'], [0, 'Женщина']]} value={female ? 0 : 1} onChange={(v) => setFemale(v === 0)} />
      <Field id="fg-h" label="Рост, см" value={height} onChange={setHeight} placeholder="178" />
      <button className="btn btn-brand full" disabled={!heightOk} onClick={async () => {
        const patch: Partial<Settings> = { sex: female ? 'female' : 'male' }
        if (h != null) patch.heightCm = Math.round(h)
        await saveSettings(patch)
        if (t !== s.targetFatPercent) await retarget(t)
        close()
        if (!hasMeasurement) setTimeout(onMeasure, 260)
        toast(`Цель: ${t}% жира`)
      }}>{!hasMeasurement ? 'Сохранить и сделать замер' : 'Сохранить'}</button>
    </div>
  )
}
