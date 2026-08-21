# StaffStats 1.5.0
### Purpur 1.21.10 • GUI • LuckPerms • EssentialsX AFK / wbudowany detektor • LibertyBans (kary kadry) • Discord Webhook • Cykl tygodniowy z auto-resetem

**Jedna komenda:** `/staff` (aliasy: `/kadra`, `/staffstats`, `/ss`)

Otwiera GUI z główkami administracji (paginowane, live-refresh). Najedź – zobaczysz:
- ⏱ czas online łącznie, 💤 AFK, ⚡ czas aktywny
- sesje, ostatnie logowanie/wylogowanie, status ONLINE / AFK live
- ⚖ **kary wydane** (LibertyBans): 🚫 bany, 🔇 mute, 👢 kicke, ⚠ warny — wg uprawnień rangi

**Klik główkę → pełny raport na chacie.** Ranking: `/staff top [ranga]`.

---

## LibertyBans – statystyki kar (1.5.0)

Plugin podpina się pod [LibertyBans](https://github.com/A248/LibertyBans) (event `PostPunishEvent`
przez oficjalny API/omnibus) i zlicza kary **wydane przez śledzoną kadrę**.

**Kluczowa zasada:** liczniki są zapisywane **niezależnie od rangi** (per typ: mute/kick/warn/ban),
a ranga filtruje dopiero **wyświetlanie** (`libertybans.group-punishment-view`). Dzięki temu:

- zmiana rangi (np. helper → admin) **nie psuje statystyk** – stare mute/kick zostają, bany liczą się od zera (bo wcześniej nie miał do nich dostępu)
- admin → helper: bany zostają w bazie, po prostu przestają być pokazywane (spójnie z permisjami)
- kary konsoli nie są liczone; liczone są tylko kary osób ze śledzonej kadry

```yaml
libertybans:
  enabled: true
  group-punishment-view:
    helper: ["mute", "kick"]
    kidmod: ["mute", "kick", "ban"]
    moderator: ["mute", "kick", "ban"]
    admin: ["mute", "kick", "ban"]
    headadmin: ["mute", "kick", "ban", "warn"]
    wlasciciel: ["mute", "kick", "ban", "warn"]
    default: []
```

Kary pokazywane są w: lore główek GUI, `/staff <nick>`, pełnym raporcie (klik w GUI)
i raporcie na Discord (`/staff webhook <nick>`).

## Funkcje
- Event-driven (0 ticków w tle)
- SQLite async WAL + blokada współbieżnego dostępu (pełna bezpieczność wątków)
- Okresowy zapis co 15 min + bezpieczny zapis przy stopie serwera
- LuckPerms – rangi, priorytet sortowania
- EssentialsX AFK – `/afk` + auto AFK; **lub wbudowany detektor AFK** gdy brak EssentialsX
- Zabezpieczenia: min_session 30s, max_session 24h, bypass perm, cooldown, log anomalii
- Discord Webhook: join/quit + dzienny summary
- Interwały AFK zapisywane osobno → dokładny AFK w raportach dziennych

## Instalacja
1. `mvn clean package` → `target/StaffStats-1.5.0.jar` → `plugins/` (razem z LibertyBans)
2. `tracked-groups:` ustaw swoje rangi LP; `group-punishment-view:` widok kar per ranga
3. `/staff reload`

## Cykl tygodniowy – auto-reset (1.6.0)

Co `interval-days` dni (domyślnie 7) plugin automatycznie:
1. dolicza trwające sesje i czeka na zapis kolejki do bazy,
2. wysyła na Discord **podsumowanie tygodnia** (TOP aktywność, TOP kary, nieobecni, suma kadry),
3. archiwizuje tydzień w tabeli `staff_weeks` (historia zostaje!),
4. czyści CAŁY postęp (czasy, sesje, AFK, kary),
5. opcjonalnie restartuje serwer (`restart-command`, domyślnie `restart`).

Kotwica cyklu (`last_weekly_reset`) jest w bazie – jeśli serwer był wyłączony
w terminie resetu, dogoni go po starcie (catch-up). Godzina docelowa
(`at-hour`/`at-minute`, strefa Europe/Warsaw) przesuwa reset na najbliższe
okno po upływie interwału (domyślnie 04:00).

```yaml
weekly-reset:
  enabled: true
  interval-days: 7      # co ile dni (min. 1)
  at-hour: 4            # docelowa godzina resetu
  at-minute: 0
  restart-server: true  # restart serwera po resecie
  restart-command: "restart"
  send-webhook: true    # podsumowanie tygodnia na Discord PRZED resem
```

## Uprawnienia
- `staffstats.view` – GUI /staff (default op)
- `staffstats.admin` – reload / reset / webhook
- `staffstats.bypass` – nie licz gracza
- `staffstats.notify` – powiadomienia in-game

## Struktura bazy
`plugins/StaffStats/staff_activity.db` – SQLite
- `staff_stats(...)` – statystyki zbiorcze
- `staff_sessions(...)` – pełne sesje (login→logout)
- `staff_afk(uuid, start_ts, end_ts)` – interwały AFK
- `staff_punishments(uuid, ptype, cnt)` – liczniki kar (LibertyBans)
- `staff_meta(key, value)` – metadane (kotwica cyklu tygodniowego)
- `staff_weeks(...)` – archiwum zamkniętych tygodni (historia po resecie)

## Komendy
```
/staff              – otwórz GUI (paginowane, live-refresh, kary)
/staff <nick>       – szybki raport w chacie
/staff top [ranga]  – ranking kadry (czas aktywny)
/staff weekly status– kiedy następny reset tygodnia (admin)
/staff weekly reset  – wymuś reset tygodnia TERAZ (admin)
/staff reload       – admin
/staff reset <nick> – admin
/staff webhook <test|daily|schedule|<nick>> – admin
```

---

MIT – KadraStats
