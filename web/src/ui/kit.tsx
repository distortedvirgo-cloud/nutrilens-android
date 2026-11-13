// UI-кит NutriLens Web: лист, сегментный переключатель, кольцо, плашки, тосты.
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { getPhoto, onDataChanged } from '../data/db'

// ---------- Данные: перечитываем при любом изменении ----------

export function useData<T>(load: () => Promise<T>, deps: unknown[], initial: T): T {
  const [value, setValue] = useState<T>(initial)
  const loadRef = useRef(load)
  loadRef.current = load
  useEffect(() => {
    let alive = true
    const run = () => loadRef.current().then((v) => alive && setValue(v)).catch(() => {})
    run()
    const off = onDataChanged(run)
    return () => {
      alive = false
      off()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  return value
}

// ---------- Нижний лист ----------

/**
 * Высота экранной клавиатуры, px. На iPhone fixed-лист остаётся под клавиатурой:
 * окно не сжимается, сжимается только visualViewport — по нему и считаем.
 */
export function useKeyboardInset(enabled = true): number {
  const [kb, setKb] = useState(0)
  useEffect(() => {
    const vv = window.visualViewport
    if (!enabled || !vv) return
    const update = () => setKb(Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop)))
    update()
    vv.addEventListener('resize', update)
    vv.addEventListener('scroll', update)
    return () => { vv.removeEventListener('resize', update); vv.removeEventListener('scroll', update) }
  }, [enabled])
  return kb
}

export function Sheet({ onClose, children, label, keyboardAware }: {
  onClose: () => void; children: ReactNode; label: string
  /** Поднимать лист над клавиатурой — для листов, где главное — поле ввода. */
  keyboardAware?: boolean
}) {
  const [closing, setClosing] = useState(false)
  const sheetRef = useRef<HTMLDivElement>(null)
  const drag = useRef<{ y0: number; dy: number } | null>(null)
  const kb = useKeyboardInset(!!keyboardAware)

  const close = useCallback(() => {
    setClosing(true)
    setTimeout(onClose, 210)
  }, [onClose])

  // Свайп вниз за верх листа закрывает его, как системный лист iOS.
  const onTouchStart = (e: React.TouchEvent) => {
    const el = sheetRef.current
    if (!el || el.scrollTop > 0) return
    drag.current = { y0: e.touches[0].clientY, dy: 0 }
  }
  const onTouchMove = (e: React.TouchEvent) => {
    const d = drag.current, el = sheetRef.current
    if (!d || !el) return
    d.dy = Math.max(0, e.touches[0].clientY - d.y0)
    if (d.dy > 0) {
      el.style.transition = 'none'
      el.style.transform = `translateY(${d.dy}px)`
    }
  }
  const onTouchEnd = () => {
    const d = drag.current, el = sheetRef.current
    drag.current = null
    if (!d || !el) return
    el.style.transition = 'transform 280ms cubic-bezier(0.2, 0, 0, 1)'
    if (d.dy > 110) close()
    else el.style.transform = ''
  }

  return (
    <div className="sheet-root" role="dialog" aria-modal="true" aria-label={label}>
      <div className={`sheet-scrim${closing ? ' closing' : ''}`} onClick={close} />
      <div
        ref={sheetRef}
        className={`sheet${closing ? ' closing' : ''}`}
        style={kb > 40 ? {
          bottom: kb, maxHeight: `calc(100% - var(--safe-top) - 24px - ${kb}px)`,
          // Над клавиатурой домашней полоски нет — нижний отступ не нужен.
          paddingBottom: 16, borderRadius: 30
        } : undefined}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
      >
        <div className="grabber" />
        <SheetCloseContext.Provider value={close}>{children}</SheetCloseContext.Provider>
      </div>
    </div>
  )
}
const SheetCloseContext = createContext<() => void>(() => {})
/** Закрыть текущий лист с анимацией (изнутри его содержимого). */
export const useSheetClose = () => useContext(SheetCloseContext)

// ---------- Сегментный переключатель ----------

export function Segmented<T extends string | number>({ options, value, onChange }: {
  options: [T, string][]; value: T; onChange: (v: T) => void
}) {
  const idx = Math.max(0, options.findIndex(([v]) => v === value))
  return (
    <div className="segmented" role="tablist" style={{ gridTemplateColumns: `repeat(${options.length}, 1fr)` }}>
      <div className="thumb" style={{ width: `calc((100% - 8px) / ${options.length})`, transform: `translateX(${idx * 100}%)` }} />
      {options.map(([v, label]) => (
        <button key={String(v)} role="tab" aria-selected={v === value} onClick={() => onChange(v)}>{label}</button>
      ))}
    </div>
  )
}

// ---------- Кольцо прогресса ----------

export function Ring({ size, stroke, fraction, from, to, track = 'var(--surface-high)', children, glow }: {
  size: number; stroke: number; fraction: number; from: string; to: string; track?: string; children?: ReactNode; glow?: boolean
}) {
  const [shown, setShown] = useState(0)
  useEffect(() => {
    const id = requestAnimationFrame(() => setShown(Math.max(0, Math.min(1, fraction))))
    return () => cancelAnimationFrame(id)
  }, [fraction])
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const gid = useRef(`g${Math.random().toString(36).slice(2)}`).current
  return (
    <div style={{ position: 'relative', width: size, height: size, flexShrink: 0 }}>
      <svg width={size} height={size} aria-hidden="true">
        <defs>
          <linearGradient id={gid} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor={from} />
            <stop offset="1" stopColor={to} />
          </linearGradient>
        </defs>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={track} strokeWidth={stroke} />
        <circle
          cx={size / 2} cy={size / 2} r={r} fill="none" stroke={`url(#${gid})`} strokeWidth={stroke}
          strokeLinecap="round" strokeDasharray={c} strokeDashoffset={c * (1 - shown)}
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
          style={{ transition: 'stroke-dashoffset 1100ms cubic-bezier(0.05, 0.7, 0.1, 1)', opacity: shown > 0.003 ? 1 : 0 }}
        />
        {glow && (
          // «Головка» дуги: точка вращается вместе с концом дуги той же кривой.
          <g style={{
            transform: `rotate(${shown * 360}deg)`, transformOrigin: `${size / 2}px ${size / 2}px`,
            transition: 'transform 1100ms cubic-bezier(0.05, 0.7, 0.1, 1), opacity 300ms', opacity: shown > 0.02 ? 1 : 0
          }}>
            <circle cx={size / 2} cy={stroke / 2} r={stroke * 0.95} fill={to} opacity={0.25} />
            <circle cx={size / 2} cy={stroke / 2} r={stroke * 0.22} fill="#fff" />
          </g>
        )}
      </svg>
      <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        {children}
      </div>
    </div>
  )
}

// ---------- Числа, которые «бегут» ----------

export function useCountUp(target: number, ms = 900): number {
  const [v, setV] = useState(target)
  const from = useRef(target)
  useEffect(() => {
    const start = performance.now(), a = from.current, b = target
    if (a === b || matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setV(b)
      from.current = b
      return
    }
    let id = 0
    const step = (t: number) => {
      const k = Math.min(1, (t - start) / ms)
      const e = 1 - Math.pow(1 - k, 4)
      setV(a + (b - a) * e)
      if (k < 1) id = requestAnimationFrame(step)
      else from.current = b
    }
    id = requestAnimationFrame(step)
    return () => cancelAnimationFrame(id)
  }, [target, ms])
  return v
}

// ---------- Полоса ----------

export function Bar({ fraction, color, track, height = 6 }: { fraction: number; color: string; track?: string; height?: number }) {
  const [w, setW] = useState(0)
  useEffect(() => {
    const id = requestAnimationFrame(() => setW(Math.max(0, Math.min(1, fraction))))
    return () => cancelAnimationFrame(id)
  }, [fraction])
  return (
    <div className="bar" style={{ height, background: track ?? `color-mix(in srgb, ${color} 16%, transparent)` }}>
      <i style={{ width: `${w * 100}%`, background: color }} />
    </div>
  )
}

// ---------- Плашка-иконка ----------

export function Badge({ children, tint, size = 40 }: { children: ReactNode; tint: string; size?: number }) {
  return (
    <span className="badge" style={{
      width: size, height: size, borderRadius: size * 0.34, color: tint,
      background: `color-mix(in srgb, ${tint} 14%, transparent)`
    }}>{children}</span>
  )
}

// ---------- Фото из IndexedDB ----------

export function Photo({ id, kind = 'thumb', className, style, alt = '' }: {
  id: string; kind?: 'thumb' | 'full'; className?: string; style?: React.CSSProperties; alt?: string
}) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    let u: string | null = null
    getPhoto(id).then((p) => {
      if (!p) return
      u = URL.createObjectURL(kind === 'full' ? p.full : p.thumb)
      setUrl(u)
    })
    return () => {
      if (u) URL.revokeObjectURL(u)
    }
  }, [id, kind])
  return url
    ? <img src={url} alt={alt} className={className} style={{ objectFit: 'cover', ...style }} />
    : <div className={className} style={{ background: 'var(--surface-high)', ...style }} />
}

// ---------- Тосты ----------

interface ToastMsg { id: number; text: string; action?: { label: string; run: () => void } }
const ToastContext = createContext<(text: string, action?: ToastMsg['action']) => void>(() => {})
export const useToast = () => useContext(ToastContext)

export function ToastHost({ children }: { children: ReactNode }) {
  const [msg, setMsg] = useState<ToastMsg | null>(null)
  const timer = useRef<number>()
  const show = useCallback((text: string, action?: ToastMsg['action']) => {
    window.clearTimeout(timer.current)
    setMsg({ id: Date.now(), text, action })
    timer.current = window.setTimeout(() => setMsg(null), action ? 5000 : 3000)
  }, [])
  return (
    <ToastContext.Provider value={show}>
      {children}
      {msg && (
        <div className="toast" key={msg.id} role="status">
          <span className="grow">{msg.text}</span>
          {msg.action && (
            <button onClick={() => { msg.action!.run(); setMsg(null) }}>{msg.action.label}</button>
          )}
        </div>
      )}
    </ToastContext.Provider>
  )
}

// ---------- Markdown (безопасный, без HTML) ----------

function inline(text: string): ReactNode[] {
  const out: ReactNode[] = []
  const re = /\*\*(.+?)\*\*|\*(.+?)\*/g
  let last = 0, m: RegExpExecArray | null, k = 0
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index))
    out.push(m[1] ? <strong key={k++}>{m[1]}</strong> : <em key={k++}>{m[2]}</em>)
    last = m.index + m[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

export function Md({ text }: { text: string }) {
  const blocks: ReactNode[] = []
  let list: { ordered: boolean; items: string[] } | null = null
  const flush = () => {
    if (!list) return
    const items = list.items.map((t, i) => <li key={i}>{inline(t)}</li>)
    blocks.push(list.ordered ? <ol key={blocks.length}>{items}</ol> : <ul key={blocks.length}>{items}</ul>)
    list = null
  }
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    const bullet = /^[-*•]\s+(.*)/.exec(line), numbered = /^\d+[.)]\s+(.*)/.exec(line), head = /^#{1,4}\s+(.*)/.exec(line)
    if (bullet || numbered) {
      const ordered = !!numbered
      if (!list || list.ordered !== ordered) {
        flush()
        list = { ordered, items: [] }
      }
      list.items.push((bullet ?? numbered)![1])
      continue
    }
    flush()
    if (!line) continue
    if (head) blocks.push(<h4 key={blocks.length}>{inline(head[1])}</h4>)
    else blocks.push(<p key={blocks.length}>{inline(line)}</p>)
  }
  flush()
  return <div className="md">{blocks}</div>
}
