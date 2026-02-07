#!/usr/bin/env bash
# Records the YouTube Data API responses the ingester reads, for one channel, so the golden
# fixtures under src/test/resources/fixtures/youtube/ can be real recordings rather than
# hand-built shapes. Needs YOUTUBE_API_KEY in the environment; the key travels in a header and
# never lands in a file or a log line. Output goes under data/raw/ (gitignored); the scrub step
# that turns it into committed fixtures is separate and by hand — see fixtures/youtube/README.md.
#
#   scripts/record-youtube-fixtures.sh @Keybored            # a handle
#   scripts/record-youtube-fixtures.sh UCXlDgfWY2JbsYEam2m68Hyw   # or a channel id
set -euo pipefail

: "${YOUTUBE_API_KEY:?set YOUTUBE_API_KEY first}"
channel="${1:-@Keybored}"
out="data/raw/youtube-fixtures/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$out"
api="https://www.googleapis.com/youtube/v3"

get() { curl -sS -H "X-Goog-Api-Key: $YOUTUBE_API_KEY" -H "User-Agent: ShelfBot/0.1 (+fixture recording)" "$1"; }

if [[ "$channel" == @* ]]; then
  sel="forHandle=$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$channel")"
else
  sel="id=$channel"
fi
get "$api/channels?part=contentDetails&$sel" > "$out/channels.json"
# An error body (a bad key, a spent quota, an unknown handle) is JSON too; say what it said.
uploads=$(python3 - "$out/channels.json" <<'PY'
import json, sys
body = json.load(open(sys.argv[1]))
if "error" in body:
    sys.exit("YouTube API error %s: %s" % (body["error"].get("code"), body["error"].get("message")))
items = body.get("items") or sys.exit("no channel found for that handle or id")
print(items[0]["contentDetails"]["relatedPlaylists"]["uploads"])
PY
)
echo "uploads playlist: $uploads"

get "$api/playlistItems?part=snippet,contentDetails&playlistId=$uploads&maxResults=50" > "$out/playlist-items-page1.json"
next=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("nextPageToken",""))' "$out/playlist-items-page1.json")
if [[ -n "$next" ]]; then
  get "$api/playlistItems?part=snippet,contentDetails&playlistId=$uploads&maxResults=50&pageToken=$next" > "$out/playlist-items-page2.json"
fi

# Comment threads for the three newest uploads — enough for a fixture, cheap on quota.
python3 -c 'import json,sys; [print(i["snippet"]["resourceId"]["videoId"]) for i in json.load(open(sys.argv[1]))["items"][:3]]' "$out/playlist-items-page1.json" |
while read -r vid; do
  get "$api/commentThreads?part=snippet&videoId=$vid&maxResults=100&order=relevance&textFormat=plainText" > "$out/comment-threads-$vid.json" || true
done

echo "recorded under $out:"
ls -la "$out"
grep -l "$YOUTUBE_API_KEY" "$out"/* 2>/dev/null && echo "!! the key leaked into a file above — do not commit" || echo "key not present in any recorded file"
