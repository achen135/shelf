# Reddit fixtures — hand-built to the documented API shape, NOT recorded

These are not recordings. Reddit's Responsible Builder Policy (2025-11-11) gates every Data API
client behind a manual access request, and no approved client existed when M8 was written
(docs/Sessions.md, M8). The files follow the Data API's documented `Listing` / `t3` / `t1`
shapes — the same ones every Reddit client library reads — with the fields the ingester uses
populated and the rest omitted. Usernames and ids are invented.

What each file is:

- `token.json` — `POST /api/v1/access_token` (`grant_type=client_credentials`).
- `new-page1.json` — `GET /r/MechanicalKeyboards/new?limit=100&raw_json=1`: two posts, `after` set.
  The second post has a `[deleted]` author, which the ingester must not store.
- `new-page2.json` — the next page: one post inside the window, one from before it (ends the
  window), `after` null.
- `comments-<postid>.json` — `GET /comments/<id>?limit=50&depth=1&sort=top&raw_json=1`: the
  two-listing array; `abc123`'s carries a `[removed]` comment and a `more` stub, both skipped.

To replace them with recordings once a client is approved: run the three requests with the
approved credentials, save the bodies here under the same names, and scrub — replace every
`author` with a placeholder, drop any field the ingester does not read (`author_fullname`,
`subreddit_id`, media, awards), and check nothing that looks like a token or a client id is left.
Then delete this paragraph and say "recorded on <date>" instead.
