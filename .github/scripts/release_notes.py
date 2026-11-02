"""Заметки релиза из Changelog.kt: «## Заголовок» и «- пункт» для версии.

Приложение показывает их в окне «Вышло обновление». Нет записи — пусто
(workflow тогда подставит текст коммита).
"""
import re
import sys

version = sys.argv[1]
src = open("app/src/main/java/com/nutrilens/app/update/Changelog.kt", encoding="utf-8").read()
for block in re.findall(r"ChangelogEntry\(\s*(versionCode.*?)\n    \)", src, re.S):
    if re.search(r'versionName\s*=\s*"%s"' % re.escape(version), block):
        title = re.search(r'title\s*=\s*"((?:[^"\\]|\\.)*)"', block).group(1)
        items_src = re.split(r"items\s*=", block, maxsplit=1)[1]
        items = re.findall(r'"((?:[^"\\]|\\.)*)"', items_src)
        print(f"## {title}\n")
        for item in items:
            print(f"- {item}")
        break
