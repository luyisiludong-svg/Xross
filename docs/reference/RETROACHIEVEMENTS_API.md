# RetroAchievements Web API: what Xross uses

Notes on the RetroAchievements Web API (`https://retroachievements.org/API/`) as Xross calls it.
Every call takes the user's Web API key in `y`, so request URLs are secrets: never logged, never in
an error message, never in a file (`RetroAchievementsClient`). Official reference:
<https://api-docs.retroachievements.org>. Observations below are from live calls with the project's
test account on **2026-11-14** (Phase 2b.7c's verify step).

## Endpoints

| Endpoint | Used for | Since |
|---|---|---|
| `API_GetUserProfile.php?u=` | The linked profile (points, avatar, motto) | 2b.1 |
| `API_GetUserSummary.php?u=&g=0&a=0` | The linked user's rank | 2b.1 |
| `API_GetUserRecentAchievements.php?u=&m=` | Unlock toasts, the HUD's badges | 2b.1 |
| `API_GetGameInfoAndUserProgress.php?u=&g=&a=1` | One game's achievements and the user's unlocks | 2b.2 |
| `API_GetGameList.php?i=&h=1` | A console's games and ROM hashes (cached 7 days) | 2b.2 |
| `API_GetUsersIFollow.php?c=&o=` | Who the linked account follows | 2b.7c |
| `API_GetUserSummary.php?u=&g=1&a=1` | A followed user's latest game and last activity | 2b.7c |

## `API_GetUsersIFollow`

The **key's** account's following list. There is no `u` parameter: whose list comes back is decided
by the key alone.

| Parameter | |
|---|---|
| `c` | Count, default 100, **max 500**. `c=600` answers **422** `{"message":"The c must not be greater than 500.", "errors":{…}}` |
| `o` | Offset, default 0 |

```json
{"Count": 20, "Total": 120, "Results": [
  {"User": "zuliman92", "ULID": "00003EMFWR7XB8SDPEHB3K56ZQ", "Points": 1882, "PointsSoftcore": 258, "IsFollowingMe": true}
]}
```

- Each entry carries the name, the **ULID** (stable; names can change since 2025), hardcore and
  softcore points, and whether they follow back. **No avatar and no activity**: the avatar is
  `media.retroachievements.org/UserPic/<User>.png`, the activity needs one more call per friend.
- The test account follows nobody: `{"Count":0,"Total":0,"Results":[]}` (and
  `API_GetUsersFollowingMe` the same). The entry shape above is from the docs; the parser accepts
  numbers as strings and `IsFollowingMe` as `true`/`1`, and skips entries without a `User`.
- Paging: Xross asks for `c=200` (its cap) in one call. It keeps paging past a short page while
  `Total` says there are more, up to 4 pages.

## A friend's activity: `API_GetUserSummary` with `g=1&a=1`

`u` takes the name **or the ULID**; Xross sends the ULID when it has one, so a renamed friend still
answers. `u` must be letters and digits only: anything else answers **422** `{"message":"The u may
only contain letters and numbers."}`, and an unknown user answers `{}` or 422.

What it carries (observed):

| Field | Meaning |
|---|---|
| `User`, `ULID`, `UserPic`, `TotalPoints` | Identity |
| `LastGameID` | The game played last |
| `RecentlyPlayed[0]` | That game: `GameID`, `Title`, `ConsoleName`, `ImageIcon`, **`LastPlayed`** ("2026-11-14 16:22:04", UTC) |
| `LastGame` | The same game in more detail (only with `g` ≥ 1): `ID`, `Title`, `ConsoleName`, `ImageIcon`, developer, genre… |
| `RichPresenceMsg` | The last rich presence line ("Exploring Clay's house \| ❤️ 100 / 100 \| 💰 351 G") |
| `RichPresenceMsgDate` | When it last changed: the latest sign of life the API gives |
| `Status` | `"Online"` / `"Offline"` |
| `LastActivity` | **Dead**: `{"ID":0,"timestamp":null,…}` for everyone. Not used |

- `g=0` leaves out `LastGame` and returns `RecentlyPlayed: []`; `LastGameID` and
  `RichPresenceMsgDate` are still there.
- `a=0` doesn't mean none: it falls back to the default (10 achievements, a 17 KB answer). `a=1`
  keeps it near 2 KB.
- `API_GetUserProfile` (494 B) has `LastGameID` and `RichPresenceMsg` but no date and no game title,
  so it can't say "how long ago" on its own.

Xross keeps `gameId`, title, console, icon, `LastPlayed` and `RichPresenceMsgDate` (the later of
the two is "last active"), `Status`, and the rich presence line (cut at 200 characters).

## Rate limits

Nothing documented, and no `X-RateLimit-*` or `Retry-After` headers. Cloudflare in front answers
**429** with a small HTML page. Measured with `API_GetUserProfile`:

| Pattern | Result |
|---|---|
| 15 calls back to back (~75 ms each), after a 2-minute rest | 7 pass, then mostly 429 (2 of the next 8 pass) |
| 3 calls 20 s later | All pass |
| 30 calls ~330 ms apart (start to start), after a rest | All pass |
| 30 calls ~1.6 s apart | All pass |

So it behaves like a short burst allowance (about 7) that refills at around 3 calls a second, not
like a per-minute quota. What Xross does:

- A friends refresh is sequential, **350 ms between call starts**: the following list (when stale)
  plus at most 20 friends is about 7 s.
- On a 429 it waits **2 s** and retries that call once; a second 429 ends the refresh (everyone not
  reached keeps their cached copy). Any other failure (no network, a refused key) ends it at once.
- The profile's three calls go out together, before the friends refresh, not during it.

## Caching (2b.7c)

`Xross/data/ra/friends.json` (public data only; the A.1 test checks no key is in any file):

- The following list for 6 hours (the profile's staleness).
- Each friend's activity for 30 minutes. A refresh asks about at most 20: the 15 most recently
  active, then the rest in turn, least recently fetched first, so a large list is covered over a
  few refreshes.
- What the social stack last showed (each friend's game and last activity) and when it was opened,
  for the unread badge. The first refresh of an account records the current state: what friends
  already did isn't news.

Avatars go to `Xross/media/avatars/friends/<user>.png`, refreshed after a day, for the 20 friends
the stack and panel can show. Friend data is fetched on refresh only (the hub's Primary action, the
profile's own refresh, opening the social stack when stale, turning the stack on in Settings, and
once at startup when nothing was ever fetched), never on focus, hover, composition or a timer.
