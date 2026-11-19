# Media access matrix

What each source really gives the project, and how it was checked. "Metadata" means titles, view counts and similar
information, never the video file. Nothing in this project bypasses a platform's download protection.

| Source | Method | Navigation | Video | Audio | Frames | Recording | Processing | Restrictions | Evidence |
|---|---|---|---|---|---|---|---|---|---|
| YouTube (videos, VODs) | YouTube Data API v3 (official) | Metadata only | No | No | No | No | No (file must come from the user) | 10,000 units/day per key; search = 100 units | Searches and uploads playlist tested with a real key on 2026-11-19 (Discover tab) |
| YouTube from a cloud server | yt-dlp | — | Likely blocked | Likely blocked | — | — | — | Datacenter IPs get "confirm you're not a bot" (2026 reports) | Not tested; reports only |
| YouTube file the user downloaded | Share → Fábrica de Clipes | — | Yes | Yes | Yes | — | Yes | The user's choice of downloader; rights are the user's responsibility | App flow tested on the emulator (3.x) |
| Twitch live, on the phone | Android screen capture (MediaProjection) + playback audio capture, with the user's consent | The Twitch app | Yes (what the screen shows) | Yes, if the Twitch app allows playback capture (not verified) | Yes | Rolling 3-min buffer | Yes (cuts → normal clip render) | Screen must stay on; notifications appear in the recording | Tested end to end on the emulator on 2026-11-19: capture, rolling buffer, bubble, manual cut (45 s clip), render, gallery save (vertical H.264 + AAC), stop. No Twitch app and no audio device there |
| Twitch chat | Public IRC over WebSocket, anonymous, read-only | — | — | — | — | — | Chat signal for detection | Read-only; no login | Same client as the PC bot; detection tested in simulation and unit tests |
| Twitch live, on a server | streamlink + chat | — | Yes | Yes | Yes | Rolling buffer | Yes | Needs a machine on 24 h (Oracle script in deploy/) | Simulated live tested end to end with fake Telegram |
| Kick | — | — | — | — | — | — | — | Not integrated | — |
| Local files / gallery | Android document picker, Share | — | Yes | Yes | Yes | — | Yes | — | Tested on the emulator |
