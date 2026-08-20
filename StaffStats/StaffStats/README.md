# StaffStats 1.4.0
### Purpur 1.21.10 • GUI • LuckPerms • EssentialsX AFK (lub wbudowany detektor) • Discord Webhook

**Jedna komenda:** `/staff` (aliasy: `/kadra`, `/staffstats`, `/ss`)

Otwiera GUI z główkami administracji (paginowane, live-refresh). Najedź – zobaczysz:
- ⏱ czas online łącznie
- 💤 AFK
- ⚡ czas aktywny
- sesje, ostatnie logowanie/wylogowanie
- status ONLINE / AFK live

**Klik główkę → pełny raport na chacie.** Ranking: `/staff top [ranga]`.

---

## Funkcje
- Event-driven (0 ticków w tle)
- SQLite async WAL + blokada współbieżnego dostępu (jedno połączenie, pełna bezpieczność wątków)
- Okresowy zapis co 15 min (ochrona przed utratą danych przy crash) + bezpieczny zapis przy stopie serwera
- LuckPerms – rangi, kolory, priorytet sortowania
- EssentialsX AFK – `/afk` + auto AFK; **lub wbudowany detektor AFK** (`integrations.internal-afk-detector`) gdy brak EssentialsX
- Zabezpieczenia: min_session 30s, max_session 24h, bypass perm, command cooldown, **log anomalii (anti-cheat-logging)**
- Discord Webhook: join/quit + dzienny summary
- Live overlay – dolicza aktualną sesję (GUI odświeża się co kilka sekund)
- Interwały AFK zapisywane osobno → **dokładny** AFK w raportach dziennych (bez szacunków)

## Co nowego w 1.4.0
- **FIX:** zamykanie bazy czeka na zapisy async (`awaitTermination`) – ostatnie sesje kadry nie giną przy stopie/restart
- **FIX:** zombie-sesje (wejście + kick < 1s → gracz wiecznie „ONLINE") – guard `isOnline()` + defensywne domykanie sesji
- **FIX:** wszystkie odczyty/zapisy SQLite serializowane jedną blokadą – koniec sporadycznych błędów współbieżnych
- **FIX:** okresowy zapis nie „ucinał" już początku sesji w raportach dziennych (pełny rekord login→logout)
- **NEW:** `/staff top [ranga]` – ranking w grze (wcześniej tylko w konsoli)
- **NEW:** paginacja GUI (dolny rząd ◀ ▶) – kadra > 45 osób bez problemu
- **NEW:** live-refresh otwartego GUI (`gui.live-refresh-seconds`, 0 = off)
- **NEW:** wbudowany detektor AFK bez EssentialsX (`integrations.internal-afk-detector`)
- **NEW:** `security.anti-cheat-logging` – logowanie anomalii statystyk do konsoli
- **NEW:** działające opcje `gui.refresh-on-open` i `gui.show-offline-heads`
- Tab-complete z 30s cache (bez zapytań DB przy każdym TAB) + sprzątanie cooldownów
- Default `storage.periodic-save-minutes: 15` (wcześniej 0 = brak ochrony przed crashem)

## Instalacja
1. `mvn clean package`
2. `target/StaffStats-1.4.0.jar` → `plugins/`
3. Uruchom serwer, edytuj `plugins/StaffStats/config.yml`:
   - `tracked-groups:` ustaw swoje rangi LP
   - `group-colors:`/`group-priority:` kolory/priorytety w GUI
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
5. Test: `/staff webhook test`, wymuszony raport: `/staff webhook daily`

Embed zawiera: rangę, długość sesji, AFK, czas aktywny.
Daily summary o 22:00 wysyła TOP aktywności z 24h + TOP all-time + listę nieobecnych >3 dni.

Możesz też testowo odpalić:
```
/staff reset <nick>   # admin – czyści statystyki
```

## Uprawnienia
- `staffstats.view` – GUI /staff (default op)
- `staffstats.admin` – /staff reload / reset / webhook / top dla wszystkich
- `staffstats.bypass` – nie licz gracza
- `staffstats.notify` – powiadomienia in-game

## Struktura bazy
`plugins/StaffStats/staff_activity.db` – SQLite
- `staff_stats(uuid, name, group_name, total_playtime_ms, total_afk_ms, last_login, last_logout, session_count, ...)`
- `staff_sessions(...)` – pełne sesje (login→logout) do raportów dziennych
- `staff_afk(uuid, start_ts, end_ts)` – interwały AFK (dokładne liczenie AFK w oknach czasowych)

## Komendy
```
/staff              – otwórz GUI (paginowane, live-refresh)
/staff <nick>       – szybki raport w chacie
/staff top [ranga]  – ranking kadry (czas aktywny)
/staff reload       – admin
/staff reset <nick> – admin
/staff webhook <test|daily|schedule|<nick>> – admin
```

---

MIT – KadraStats
