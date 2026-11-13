// Фото прогресса (3.25): по желанию, только на этом устройстве. Первое и последнее
// фото рядом — сравнение «до и сейчас». Оценка жира по фото — грубый ориентир ИИ,
// в план не идёт (план считается по замерам лентой).
import { useEffect, useRef, useState } from 'react'
import { type BodyPhoto, bodyPhotos, deleteBodyPhoto, getSettings, latestWeight, putBodyPhoto } from '../data/db'
import { blobToBase64, storeBodyPhoto } from '../data/actions'
import { bodyProfile, fmt1 } from '../data/body'
import { iso, parseIso } from '../data/insights'
import { estimateBodyFat } from '../ai/bodyfat'
import { Badge, useData, useToast } from '../ui/kit'
import { IcCamera, IcSpark, IcTrash } from '../ui/icons'

const dateLabel = (d: string) => parseIso(d).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' })

function useBlobUrl(b: Blob | null): string | null {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!b) { setUrl(null); return }
    const u = URL.createObjectURL(b)
    setUrl(u)
    return () => URL.revokeObjectURL(u)
  }, [b])
  return url
}

function Shot({ p, label }: { p: BodyPhoto; label: string }) {
  const url = useBlobUrl(p.thumb)
  return (
    <div className="col gap-6" style={{ minWidth: 0 }}>
      <div style={{ aspectRatio: '3 / 4', borderRadius: 16, overflow: 'hidden', background: 'var(--surface-high)' }}>
        {url && <img src={url} alt={`Фото ${label}`} style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />}
      </div>
      <div className="label">{label} · {dateLabel(p.date)}</div>
      {p.estimate && <div className="small num">ИИ: ≈ {fmt1(p.estimate.low)}–{fmt1(p.estimate.high)}%</div>}
    </div>
  )
}

export function BodyPhotosCard() {
  const toast = useToast()
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState<'add' | 'ai' | null>(null)
  const data = useData(async () => ({ photos: await bodyPhotos() }), [], null)
  if (!data) return null
  const list = data.photos
  const first = list[0]
  const last = list.length > 1 ? list[list.length - 1] : null
  const newest = last ?? first

  const add = async (f: File | undefined) => {
    if (!f) return
    setBusy('add')
    try { await storeBodyPhoto(f, iso(new Date())); toast('Фото сохранено') } catch { toast('Не получилось открыть фото') }
    setBusy(null)
  }

  const estimate = async () => {
    if (!newest) return
    setBusy('ai')
    try {
      const s = await getSettings()
      const prof = bodyProfile(s)
      const w = await latestWeight()
      const e = await estimateBodyFat(s, await blobToBase64(newest.full), { female: prof.female, heightCm: prof.heightCm, weight: w?.weight ?? null })
      await putBodyPhoto({ ...newest, estimate: { ...e, at: Date.now() } })
      if (e.comment) toast(e.comment)
    } catch (err) {
      toast((err as Error).message || 'ИИ не ответил — попробуй позже')
    }
    setBusy(null)
  }

  const remove = async (p: BodyPhoto) => {
    if (!confirm('Удалить это фото?')) return
    await deleteBodyPhoto(p.id)
  }

  return (
    <div className="card pad-18 col gap-12">
      <div className="row gap-12">
        <Badge tint="var(--secondary)" size={40}><IcCamera size={20} /></Badge>
        <div className="grow col">
          <span className="title">Фото прогресса</span>
          <span className="label">по желанию</span>
        </div>
      </div>
      {!first ? (
        <div className="small">Фото в одной позе и при одном свете раз в 2 недели — лучший способ увидеть то, что не видно на весах.</div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: last ? '1fr 1fr' : 'minmax(0, 60%)', gap: 12 }}>
          <Shot p={first} label={last ? 'До' : 'Первое'} />
          {last && <Shot p={last} label="Сейчас" />}
        </div>
      )}
      {newest?.estimate?.comment && <div className="small">{newest.estimate.comment}</div>}
      <input ref={input} type="file" accept="image/*" hidden onChange={(e) => { add(e.target.files?.[0]); e.target.value = '' }} />
      <button className="btn btn-soft full sm" disabled={busy !== null} onClick={() => input.current?.click()}>
        {busy === 'add' ? <span className="spinner" /> : <IcCamera size={18} />}Добавить фото
      </button>
      {newest && (
        <div className="row gap-8">
          <button className="btn btn-soft sm grow" disabled={busy !== null} onClick={estimate}>
            {busy === 'ai' ? <span className="spinner" /> : <IcSpark size={18} />}Оценить жир по фото
          </button>
          <button className="icon-btn" aria-label="Удалить последнее фото" disabled={busy !== null} onClick={() => remove(newest)}><IcTrash size={18} /></button>
        </div>
      )}
      <div className="small">
        Фото хранятся только на этом устройстве — в резервную копию и лидерборд не попадают. В ИИ фото уходит, только когда нажмёшь «Оценить»; оценка грубая (±5%) и в план не идёт.
      </div>
    </div>
  )
}
