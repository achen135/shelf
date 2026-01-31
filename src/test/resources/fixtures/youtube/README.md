# YouTube fixtures — hand-built to the documented resource shapes, NOT recorded

No YouTube Data API key existed when M8 was written, so these follow the resource
representations in Google's reference (`channels`, `playlistItems`, `commentThreads`) with the
fields the ingester reads populated and the rest omitted. Channel, playlist, video and comment
ids are invented and the wrong length on purpose, so nothing here can be mistaken for a real
recording.

What each file is:

- `channels-forhandle.json` — `GET /channels?part=contentDetails&forHandle=%40FixtureKeys`:
  one item with the uploads playlist.
- `playlist-items-page1.json` — `GET /playlistItems?...&maxResults=50`: two videos, `nextPageToken`.
- `playlist-items-page2.json` — the next page: one video inside the window, one from before it
  (ends the window), no `nextPageToken`.
- `comment-threads-<videoid>.json` — `GET /commentThreads?part=snippet&videoId=<id>&...`: top-level
  comments in `plainText`. `vid-002` answers 403 (comments disabled) in the tests instead.

To record real ones: create a Google Cloud project, enable "YouTube Data API v3", make an API
key (no billing account is needed), export it as `YOUTUBE_API_KEY`, run the three requests
with `-H "X-Goog-Api-Key: $YOUTUBE_API_KEY"`, save the bodies here under the same names, and
scrub — replace `authorDisplayName` / `authorChannelId` with placeholders, drop `etag` and any
field the ingester does not read, and make sure the key is nowhere in the file. Then delete
this paragraph and say "recorded on <date>" instead.
