# Known limitations

Written to match the code as it is. Each item says what happens and what to do.

## Sources
- **YouTube files cannot be fetched by the app or the server.** The Discover tab uses the official API, which gives
  metadata only. The file comes from the user (Share → Fábrica de Clipes). The project does not include a downloader
  that bypasses YouTube's protection, and will not.
- **Discover language filter:** the API's region and language hints do not filter the language; the app keeps videos
  that declare Portuguese or declare nothing. Videos with a wrong declared language slip through.
- **Discover growth** is measured only between the user's own lookups (tap ⟳ after 30 min). One lookup says nothing.
- **The opportunity score is a formula, not a prediction.** Factors are labelled observed or estimate.

## Clipping lives on the phone (Clipar live)
- The live must be on screen: phone on, Twitch app in full screen, landscape. Whatever is on screen is recorded,
  notifications included.
- The sound is captured from what the phone plays. If the Twitch app refuses playback capture, clips have no sound;
  the app warns after 8 s (no audio track) or 45 s (muted audio). Not verified with the real Twitch app.
- The first 2 minutes are a warm-up (the detector learns that live's normal); the ✂ button works from the start.
- Battery and heat: hours of recording and encoding. Keep the phone charging.
- Android asks for the screen-recording permission each time.

## Rendering on the phone
- The webcam is small in the source and is enlarged for the top of the clip; sharpening and a 12 Mbps export help,
  but enlarging cannot add detail that is not there. A 1080p source gives a much better webcam than 720p.
- Each project records how long each step took (analysis and rendering) in its notes.
- One clip renders at a time; live clips wait in a queue.
- A multi-hour recorded live takes a long time to analyse.

## Server bot (Oracle)
- Oracle's free ARM capacity is often unavailable; its free tier was reported reduced in 2026.
- Not tested on a real Oracle machine or a real Twitch live.

## Not built
- Automatic posting through official APIs (TikTok requires an app audit; posts stay private until then).
- Kick, cloud database, task orchestrator, posting schedules.
- Multi-file import works from the app's picker; sharing several files at once from the gallery is not supported yet.
