// Лидерборд: общий с Android-приложением. Очки дня — насколько близко к своей
// цели (100 — ровно в цель), за неделю/месяц — среднее по дням с данными.
import { useEffect, useState } from 'react'
import { getSettings, saveSettings } from '../data/db'
import { fetchEntries, leaderboardConfigured, pushOwn, rows, type LbEntry, type LbPeriod } from '../data/leaderboard'
import { Badge, Segmented, useToast } from '../ui/kit'
import { Avatar } from '../ui/Avatar'
import { IcChevronL, IcRefresh, IcTrophy } from '../ui/icons'

const MEDAL = ['#F5B83D', '#B8C2CC', '#D08B5B']

export function LeaderboardScreen({ onBack }: { onBack: () => void }) {
  const toast = useToast()
  const [period, setPeriod] = useState<LbPeriod>('day')
  const [entries, setEntries] = useState<LbEntry[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selfId, setSelfId] = useState('')
  const [nick, setNick] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [loading, setLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      await pushOwn().catch(() => false)
      setSelfId((await getSettings()).playerId)
      setEntries(await fetchEntries())
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    getSettings().then((s) => { setSelfId(s.playerId); setNick(s.nickname.trim()) })
    if (leaderboardConfigured()) load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const list = entries ? rows(entries, period, selfId) : []
  const daysInMonth = new Date(new Date().getFullYear(), new Date().getMonth() + 1, 0).getDate()

  return (
    <div className="screen detail screen-push">
      <div className="row gap-12" style={{ marginBottom: 16 }}>
        <button className="icon-btn" aria-label="Назад" onClick={onBack}><IcChevronL size={20} /></button>
        <div className="grow">
          <div className="h1">Лидерборд</div>
          <div className="label">Кто ближе всех к своей цели</div>
        </div>
        {leaderboardConfigured() && nick && (
          <button className="icon-btn" aria-label="Обновить" onClick={load} disabled={loading}>
            {loading ? <span className="spinner" /> : <IcRefresh size={18} />}
          </button>
        )}
      </div>

      {!leaderboardConfigured() ? (
        <Empty title="Лидерборд пока не подключён" text="Владелец приложения ещё не запустил сервер лидерборда для веб-версии." />
      ) : nick === null ? null : !nick ? (
        <div className="card pad-18 col gap-12">
          <Badge tint="var(--warn-to)" size={52}><IcTrophy size={26} /></Badge>
          <div className="h2">Придумай ник</div>
          <div className="small">Под ним тебя увидят другие участники. Ник можно менять — место в таблице сохранится.</div>
          <input className="field" value={draft} onChange={(e) => setDraft(e.target.value)} maxLength={32} placeholder="Например: Аня" />
          <button className="btn btn-brand full" disabled={!draft.trim()} onClick={async () => {
            await saveSettings({ nickname: draft.trim() })
            setNick(draft.trim())
            toast('Ник сохранён')
            load()
          }}>Участвовать</button>
        </div>
      ) : (
        <div className="col gap-14">
          <Segmented options={[['day', 'День'], ['week', 'Неделя'], ['month', 'Месяц']]} value={period} onChange={setPeriod} />
          {error && <div className="card r22 pad-14 small" style={{ background: 'var(--tertiary-cont)', color: 'var(--on-tertiary-cont)' }}>{error}</div>}
          {!entries && !error && <div className="row" style={{ justifyContent: 'center', padding: 40 }}><span className="spinner" /></div>}
          {entries && list.length === 0 && <Empty title="Пока никого" text="Запиши еду — и появишься в таблице первым." />}
          {list.length > 0 && (
            <div className="card r24 stagger" style={{ overflow: 'hidden' }}>
              {list.map((r, i) => (
                <div key={r.id || r.nickname} className="row gap-12"
                  style={{ padding: '12px 14px', borderTop: i ? '1px solid var(--hairline)' : 'none', background: r.isSelf ? 'color-mix(in srgb, var(--primary) 10%, transparent)' : undefined }}>
                  <span className="num" style={{ width: 30, height: 30, borderRadius: 15, flex: 'none', display: 'grid', placeItems: 'center', fontWeight: 800, fontSize: 14,
                    background: i < 3 ? MEDAL[i] : 'var(--surface-high)', color: i < 3 ? '#1a1a1a' : 'var(--text-2)' }}>{i + 1}</span>
                  <Avatar src={r.avatar} nickname={r.nickname} size={40} />
                  <div className="grow" style={{ minWidth: 0 }}>
                    <div className="title ellipsis">{r.nickname || 'Без ника'}{r.isSelf ? ' · ты' : ''}</div>
                    <div className="small num">
                      {Math.round(r.calories)} / {Math.round(r.goal)} ккал
                      {period === 'week' ? ` · ${r.daysCounted}/7 дн.` : period === 'month' ? ` · ${r.daysCounted}/${daysInMonth} дн.` : ''}
                    </div>
                  </div>
                  <div className="col" style={{ alignItems: 'flex-end' }}>
                    <span className="h2 num" style={{ color: r.score >= 90 ? 'var(--primary)' : r.score >= 70 ? 'var(--text)' : 'var(--text-2)' }}>{r.score}</span>
                    <span className="label num" style={{ color: r.isOver ? 'var(--tertiary)' : undefined }}>{r.percent}%</span>
                  </div>
                </div>
              ))}
            </div>
          )}
          <div className="small" style={{ textAlign: 'center' }}>Очки: 100 — ровно в цель, меньше — дальше от неё в любую сторону</div>
        </div>
      )}
    </div>
  )
}

const Empty = ({ title, text }: { title: string; text: string }) => (
  <div className="card col" style={{ alignItems: 'center', textAlign: 'center', padding: '36px 24px', gap: 8 }}>
    <Badge tint="var(--warn-to)" size={60}><IcTrophy size={30} /></Badge>
    <div className="h2" style={{ marginTop: 6 }}>{title}</div>
    <div className="small">{text}</div>
  </div>
)
