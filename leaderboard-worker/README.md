# Прокси лидерборда для веб-версии

Лидерборд лежит в приватном репозитории `nutrilens-leaderboard`. Веб-версия —
публичный сайт, поэтому токен GitHub в неё класть нельзя: его достанет любой
посетитель. Этот Cloudflare Worker держит токен у себя и даёт сайту только два
действия:

- `GET /entries` — прочитать таблицу (служебные поля вырезаны);
- `PUT /entries/<id>` — записать **свой** файл `data/p_<id>.json`.

Писать в файл может только устройство, которое его создало: при первой записи
в файл кладётся хеш секрета устройства. Файлы Android-приложения через прокси
не перезаписать. Бесплатного тарифа Cloudflare (100 000 запросов в день) с
запасом хватает.

## Запуск (один раз, ~10 минут)

Названия пунктов в интерфейсах Cloudflare и GitHub могут немного отличаться.

### 1. Токен GitHub

1. github.com → аватар → **Settings** → **Developer settings** →
   **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
2. **Repository access**: *Only select repositories* → `nutrilens-leaderboard`.
3. **Permissions** → **Repository permissions** → **Contents**: *Read and write*.
4. Срок действия — на ваш выбор. Когда он истечёт, лидерборд в вебе перестанет
   обновляться: создайте новый токен и замените секрет из шага 2.
5. Скопируйте токен (он показывается один раз).

### 2. Worker на Cloudflare

1. Зарегистрируйтесь на dash.cloudflare.com (бесплатно).
2. **Workers & Pages** → **Create** → **Create Worker**, имя
   `nutrilens-leaderboard` → **Deploy**.
3. **Edit code** → удалите пример, вставьте целиком файл `worker.js` из этой
   папки → **Deploy**.
4. В Worker откройте **Settings** → **Variables and Secrets** и добавьте:
   - `REPO` (Text): `distortedvirgo-cloud/nutrilens-leaderboard`
   - `ALLOWED_ORIGINS` (Text): `https://distortedvirgo-cloud.github.io`
   - `GITHUB_TOKEN` (**Secret**): токен из шага 1
5. Скопируйте адрес Worker вида
   `https://nutrilens-leaderboard.<ваш-поддомен>.workers.dev`.
   Проверка: адрес + `/entries` в браузере должен показать JSON с участниками.

Вместо интерфейса можно через CLI из этой папки: `npx wrangler deploy` и
`npx wrangler secret put GITHUB_TOKEN`.

### 3. Подключить к сайту

1. Репозиторий `nutrilens-android` → **Settings** → **Secrets and variables** →
   **Actions** → вкладка **Variables** → **New repository variable**:
   `LEADERBOARD_API_URL` = адрес Worker из шага 2.
2. **Actions** → **Web (iPhone PWA)** → **Run workflow**. После сборки
   лидерборд в веб-версии заработает.

## Хранилище роликов для NanoGPT (KV «VIDEOS»)

NanoGPT принимает видео внутри запроса только до ~4,5 МБ. Ролики крупнее приложение
кладёт в Worker на 15 минут и отдаёт NanoGPT ссылкой (до 20 МБ). Для этого:

1. dash.cloudflare.com → **Storage & databases** → **Workers KV** → **Create** → имя `nutrilens-videos`.
2. Worker `nutrilens-leaderboard` → **Settings** → **Bindings** → **Add** → **KV namespace**:
   Variable name `VIDEOS`, namespace `nutrilens-videos` → **Deploy**.

Бесплатного тарифа хватает (1000 роликов в день); файлы удаляются сами через 15 минут.
