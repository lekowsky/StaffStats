# StaffStats 1.1.0
### Purpur 1.21.10 • GUI • LuckPerms • EssentialsX AFK • Discord Webhook

**Jedna komenda:** `/staff` (aliasy: `/kadra`, `/staffstats`, `/ss`)

Otwiera GUI 6x9 z główkami administracji. Najedź – zobaczysz:
- ⏱ czas online łącznie
- 💤 AFK
- ⚡ czas aktywny
- sesje, ostatnie logowanie/wylogowanie
- status ONLINE / AFK live

**Klik główkę → pełny raport na chacie.**

---

## Funkcje
- Event-driven (0 ticków w tle)
- SQLite async WAL
- LuckPerms – rangi, kolory, priorytet sortowania
- EssentialsX AFK – `/afk` + auto AFK
- Zabezpieczenia: min_session 30s, max_session 24h, bypass perm, command cooldown
- Discord Webhook: join/quit + dzienny summary
- Live overlay – dolicza aktualną sesję

## Instalacja
1. `mvn clean package`
2. `target/StaffStats-1.1.0.jar` → `plugins/`
3. Uruchom serwer, edytuj `plugins/StaffStats/config.yml`:
   - `tracked-groups:` ustaw swoje rangi LP
   - `group-colors:` kolory w GUI
4. `/staff reload`

## Webhook Discord – jak podpiąć
1. Wejdź na swój serwer Discord → Ustawienia kanału → **Integracje → Webhooki → Nowy Webhook**
2. Wybierz kanał np. `#logi-kadra`, skopiuj **URL webhooka**:
   ```
   https://discord.com/api/webhooks/1234567890/AbCdeFg...
   ```
3. W `plugins/StaffStats/config.yml`:
```yaml
webhook:
  enabled: true
  url: "https://discord.com/api/webhooks/TWOJE_ID/TWOJ_TOKEN"
  send-on-staff-join: true
  send-on-staff-quit: true
  daily-summary-hour: 22   # 22:00 Europe/Warsaw, -1 = off
```
4. `/staff reload`
5. Test: wejdź kontem z rangą admina – na Discordzie pojawi się embed 🟢 join, po wyjściu 🔴 quit.

Embed zawiera: rangę, długość sesji, AFK, czas aktywny.
Daily summary o 22:00 wysyła TOP10.

Możesz też testowo odpalić:
```
/staff reset <nick>   # admin – czyści statystyki
```

## Uprawnienia
- `staffstats.view` – GUI /staff (default op)
- `staffstats.admin` – /staff reload / reset
- `staffstats.bypass` – nie licz gracza
- `staffstats.notify` – powiadomienia in-game

## Struktura bazy
`plugins/StaffStats/staff_activity.db` – SQLite
Tabela `staff_stats(uuid, name, group_name, total_playtime_ms, total_afk_ms, last_login, last_logout, session_count, ...)`

## Komendy (minimalnie)
```
/staff              – otwórz GUI
/staff <nick>       – szybki raport w chacie
/staff reload       – admin
/staff reset <nick> – admin
```
Wszystko w jednym.

---
MIT – KadraStats
