// Запрос с тайм-аутом. Без него зависший ответ (iPhone приостановил сайт в фоне,
// VPN оборвал соединение, медленная очередь провайдера) держал крутилку вечно:
// промис fetch просто никогда не завершался. Тайм-аут покрывает и чтение тела.
export async function fetchText(url: string, init: RequestInit, timeoutMs: number, who: string): Promise<{ status: number; ok: boolean; text: string }> {
  const ctrl = new AbortController()
  const outer = init.signal
  const onOuter = () => ctrl.abort()
  outer?.addEventListener('abort', onOuter)
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; ctrl.abort() }, timeoutMs)
  try {
    const resp = await fetch(url, { ...init, signal: ctrl.signal })
    const text = await resp.text()
    return { status: resp.status, ok: resp.ok, text }
  } catch (e) {
    if (timedOut) throw new Error(`${who} не ответил за ${Math.round(timeoutMs / 60000) || 1} мин. Проверь VPN и интернет и попробуй ещё раз`)
    if (outer?.aborted) throw new Error('Отменено')
    throw new Error(`Нет связи с ${who} — проверь интернет и VPN`, { cause: e })
  } finally {
    clearTimeout(timer)
    outer?.removeEventListener('abort', onOuter)
  }
}
