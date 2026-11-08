[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

# Sporelink Plugin (Paper)

Плагин, связывающий Minecraft-сервер с федерацией.

## Команды
* `/pay <ник> <сумма>` — перевод монет другому игроку (через 2PC).
* `/shop` — GUI-магазин для траты монет.

## Установка
1. Скомпилируйте: `mvn clean package`
2. Поместите `.jar` в папку `plugins/`.
3. Настройте `config.yml` (укажите `node-url`).