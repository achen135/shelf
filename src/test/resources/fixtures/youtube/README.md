# YouTube fixtures — recorded 2026-09-18 from the Keybored channel (`@Keybored`)

Recorded with `scripts/record-youtube-fixtures.sh @Keybored` against the live YouTube Data API
v3 (a plain API key; the key travelled in a header and reached no file — the script checks),
then trimmed and scrubbed by hand. They replace the hand-built shapes M8 shipped with before a
key existed. Field values are verbatim except where this README says otherwise.

What each file is, and what was done to it:

- `channels-forhandle.json` — `GET /channels?part=contentDetails&forHandle=@Keybored`. Verbatim
  minus `etag`s.
- `playlist-items-page1.json` — `GET /playlistItems?part=snippet,contentDetails&playlistId=
  UUzqmTtRqjBgQ_cybekKVGHA&maxResults=50`: the first **3** of the 50 items recorded (uploads of
  2026-08-15, 2026-07-18, 2026-06-19), the real `nextPageToken`. `thumbnails` dropped,
  descriptions cut to 300 characters, `etag`s dropped.
- `playlist-items-page2.json` — the next page (`pageToken=` the token above): the first **2** of
  its 50 items (2024-10-20, 2024-10-12), same trimming; its own `nextPageToken` and
  `prevPageToken` dropped so the fixture ends here. With the tests' clock (2026-09-18) and a
  120-day window, page 1 is read whole, page 2 is fetched and its first item ends the window.
- `comment-threads-4APvQf436YM.json` — `GET /commentThreads?part=snippet&videoId=4APvQf436YM&
  maxResults=100&order=relevance&textFormat=plainText`: the first **2** of 6 threads.
- `comment-threads-75-H8kz5QbY.json` — the same for the third upload: the first **1** of 25.
- `comments-disabled.json` — **not recorded**: the documented 403 body for a video whose
  comments are off, which the tests script for the second upload (`aRouyD0wdWI`; its real
  threads were recorded and are not used).

Scrubbing, applied to every comment: `authorDisplayName` → `viewer N`, `authorChannelId.value`
→ `UCscrubbedviewerN` (one N per distinct real author, so two comments by one person stay one
person), `authorChannelUrl` / `authorProfileImageUrl` / `viewerRating` / `canRate` dropped. The
comment text itself is verbatim — public comments, kept as `raw_mentions` keeps them. The
channel id, playlist id, video ids and comment ids are real and public.

To re-record: run the script again, apply the same trimming, and expect the tests that pin
titles, dates and counts (`YoutubeIngesterTest`, `IngestRunIntegrationTest`) to need the new
values — a diff here is the channel posting, not the API changing, unless the shape moved.
