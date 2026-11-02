// Выбор сервиса ИИ и ввод ключа — общий для первого запуска и Профиля → Нейросеть.
import { useState } from 'react'
import type { Settings } from '../data/db'
import { saveSettings } from '../data/db'
import { checkKey } from '../ai/gemini'
import { checkNanoKey } from '../ai/nano'
import { IcCheck } from './icons'

type Provider = Settings['provider']

export const KEY_URL: Record<Provider, string> = {
  nanogpt: 'https://nano-gpt.com/api',
  gemini: 'https://aistudio.google.com/apikey'
}

const INFO: Record<Provider, { title: string; badge: string; text: string }> = {
  nanogpt: { title: 'NanoGPT', badge: 'Рекомендуем', text: 'Точнее на сложных блюдах и без дневных лимитов. Платно по факту: копейки за фото, баланс пополняется на сайте.' },
  gemini: { title: 'Gemini', badge: 'Бесплатно', text: 'Бесплатный ключ Google. Хватает на обычный день, но есть лимиты запросов в минуту и в сутки.' }
}

export function ProviderCards({ value, onChange }: { value: Provider; onChange: (p: Provider) => void }) {
  return (
    <div className="col gap-10" role="radiogroup" aria-label="Сервис ИИ">
      {(['nanogpt', 'gemini'] as Provider[]).map((p) => {
        const on = value === p
        return (
          <button key={p} role="radio" aria-checked={on} className="card pad-14 pressable row gap-12"
            style={{ textAlign: 'left', alignItems: 'flex-start', border: `2px solid ${on ? 'var(--primary)' : 'var(--hairline)'}`, background: on ? 'color-mix(in srgb, var(--primary) 9%, var(--surface))' : undefined }}
            onClick={() => onChange(p)}>
            <span style={{ width: 22, height: 22, borderRadius: 11, flex: 'none', marginTop: 2, display: 'grid', placeItems: 'center',
              background: on ? 'var(--primary)' : 'transparent', border: on ? 'none' : '2px solid var(--text-3)', color: 'var(--on-brand)' }}>
              {on && <IcCheck size={14} strokeWidth={3} />}
            </span>
            <span className="col gap-4 grow">
              <span className="row gap-8">
                <span className="title">{INFO[p].title}</span>
                <span className="chip" style={{ height: 22, fontSize: 11, padding: '0 8px', color: p === 'nanogpt' ? 'var(--primary)' : 'var(--secondary)',
                  background: `color-mix(in srgb, ${p === 'nanogpt' ? 'var(--primary)' : 'var(--secondary)'} 15%, transparent)` }}>{INFO[p].badge}</span>
              </span>
              <span className="small">{INFO[p].text}</span>
            </span>
          </button>
        )
      })}
    </div>
  )
}

/** Поле ключа для выбранного сервиса + «Получить ключ». onSaved вызывается после успешной проверки. */
export function KeyField({ s, provider, onSaved, onError, cta = 'Проверить и сохранить' }: {
  s: Settings; provider: Provider; onSaved: () => void; onError: (m: string) => void; cta?: string
}) {
  const [key, setKey] = useState(provider === 'gemini' ? s.apiKey : s.nanoApiKey)
  const [checking, setChecking] = useState(false)
  const label = provider === 'gemini' ? 'Ключ Gemini' : 'Ключ NanoGPT'
  const save = async () => {
    setChecking(true)
    const k = key.trim()
    let problem: string | null = null
    if (provider === 'gemini') { if (!(await checkKey(k))) problem = 'Ключ Gemini не подошёл — проверь, что скопирован целиком' }
    else { const r = await checkNanoKey(k, s.nanoEndpoint); if (r !== true) problem = r }
    setChecking(false)
    if (problem) { onError(problem); return }
    await saveSettings(provider === 'gemini' ? { apiKey: k, provider } : { nanoApiKey: k, provider })
    onSaved()
  }
  return (
    <div className="col gap-12">
      <div>
        <label className="field-label" htmlFor="ai-key">{label}</label>
        <input id="ai-key" className="field" value={key} onChange={(e) => setKey(e.target.value)} autoCapitalize="off" autoCorrect="off"
          spellCheck={false} placeholder={provider === 'gemini' ? 'AIza…' : 'sk-nano-…'} type="password" />
      </div>
      <a className="chip primary pressable" href={KEY_URL[provider]} target="_blank" rel="noreferrer" style={{ alignSelf: 'flex-start', textDecoration: 'none' }}>
        {provider === 'gemini' ? 'Получить бесплатный ключ в Google AI Studio' : 'Получить ключ на nano-gpt.com'}
      </a>
      <button className="btn btn-brand full" disabled={!key.trim() || checking} onClick={save}>
        {checking ? <span className="spinner" /> : cta}
      </button>
    </div>
  )
}
