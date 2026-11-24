# Подготовка сервера на 50 игроков (Paper 1.21.11, Pterodactyl)

## 1. Ресурсы
- RAM контейнера 8–10 ГБ, `-Xms` = `-Xmx` (например 8G). Java 21.
- Флаги Aikar (Pterodactyl → Startup → Java flags):
  `-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1`

## 2. server.properties
- `max-players=50`, `view-distance=8`, `simulation-distance=6`, `network-compression-threshold=256`, `sync-chunk-writes=false`.
- Белый список: `white-list=true` + `enforce-whitelist=true` (если закрытый сервер).

## 3. Мир
- Заранее нагенерируйте мир (Chunky): `/chunky radius 3000`, `/chunky start` (до открытия), `/worldborder set 6000`.
- Алтари и данжи расставляйте после генерации: `/legendary altar random all`, `/legendary dungeon random`.

## 4. paper-global / paper-world
- `entities-per-chunk` / `spawn-limits` умеренно: monsters 40, animals 8, water-animals 3, ambient 1.
- `item-despawn-rate` оставьте по умолчанию; `max-auto-save-chunks-per-tick: 8`.

## 5. Права (LuckPerms)
- Админам: `lp user <ник> permission set legendary.admin true`. Остальным ничего не выдавайте.
- Обычному игроку доступна только `/stats`. Звезда управления и Топор привата у него не работают и изымаются.

## 6. Ресурспак
- Залейте `resource_pack` на хостинг (raw.githubusercontent.com подходит), в config.yml: `resource_pack.enabled: true`, `url`, `sha1`.

## 7. Резервные копии
- Pterodactyl → Backups: расписание каждые 3–6 ч. Плагин пишет `plugins/LegendaryWeapons/data.db` (SQLite, WAL) — входит в бэкап.

## 8. Перед открытием
1. `/legendary doctor` — все пункты зелёные.
2. Проверьте рецепты у алтарей, зайдите вторым аккаунтом без прав: команда `/legendary` недоступна, звезда не работает.
3. Тест данжа: плиты не взрываются от мобов, босс, сундуки, чертёж.
4. Следите за `/tps` и `/mspt` под нагрузкой.
