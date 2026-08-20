# StaffStats 1.5.0
### Purpur 1.21.10 • GUI • LuckPerms • EssentialsX AFK / wbudowany detektor • LibertyBans (kary kadry) • Discord Webhook

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

## Komendy
```
/staff              – otwórz GUI (paginowane, live-refresh, kary)
/staff <nick>       – szybki raport w chacie
/staff top [ranga]  – ranking kadry (czas aktywny)
/staff reload       – admin
/staff reset <nick> – admin
/staff webhook <test|daily|schedule|<nick>> – admin
```

---

MIT – KadraStats
