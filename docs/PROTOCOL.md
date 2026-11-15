# Tactical Comms protocol

One WebSocket per device at `wss://<host>/ws`, plus an HTTP file API on the same host.

## Connection

The first message must be `hello`; anything else closes the socket.

```json
→ {"t":"hello","uid":"ANDROID-1234","callsign":"Alpha","key":"<ACCESS_KEY>"}
← {"t":"welcome","token":"<http bearer token>","you":{...},"users":[User],"groups":[Group],
   "limits":{"maxFileBytes":209715200,"pttMaxMs":90000}}
```

Close codes: `4003` wrong access key (do not retry), `4004` the same uid connected from somewhere else.
Messages queued while a user was offline are delivered right after `welcome`.

`User = {uid, callsign, online, lastSeen, pos: {lat, lon, hae?, ce?, speed?, course?, battery?, ts} | null}`
`Group = {id, name, owner, members: [uid], created}`
`Target = {"type":"user","id":uid} | {"type":"group","id":gid}`

## Presence & positions

```json
← {"t":"presence","uid":"…","callsign":"…","online":true}
→ {"t":"pos","lat":32.08,"lon":34.78,"hae":20,"ce":5,"speed":1.2,"battery":80}
← {"t":"pos","uid":"…","callsign":"…","lat":…,"lon":…,"ts":…}      (sent to every other user)
→ {"t":"users"}   ← {"t":"users","users":[User]}
```

## Chat & files

```json
→ {"t":"chat","mid":"<client id>","to":Target,"text":"…","file":{"id":"<fileId>","kind":"file"}?}
← {"t":"ack","mid":"…","ts":…}
← {"t":"chat","mid":"…","from":uid,"callsign":"…","to":Target,"text":"…","file":{id,name,size,mime,kind}|null,"ts":…}
```

Files: `POST /files?name=<urlencoded>` with `Authorization: Bearer <token>` and the raw bytes as the body →
`{"id","name","size","mime"}`. Then send a `chat` that references the id. Recipients of that chat (and the
uploader) may `GET /files/<id>` with their own bearer token. Files expire after `FILE_TTL_HOURS`.

## Groups (created by users, no admin)

```json
→ {"t":"group_create","name":"Team 1","members":[uid,…]}        ← {"t":"group","group":Group} to every member
→ {"t":"group_update","gid":"…","name"?:"…","add"?:[uid],"remove"?:[uid]}
→ {"t":"group_leave","gid":"…"}                                  ← {"t":"group_removed","gid":"…"}
```

Any member may add people; only the owner may rename or remove others. If the owner leaves, ownership passes on.

## Push-to-talk (live)

```json
→ {"t":"ptt_start","sid":"<client id>","to":Target}
← {"t":"ptt_granted","sid":"…","listeners":2}   or   {"t":"ptt_denied","sid":"…","busyBy":uid}
   recipients ← {"t":"ptt_start","sid":"…","from":uid,"callsign":"…","to":Target}
→ binary frames (kind 1)
→ {"t":"ptt_end","sid":"…"}           recipients ← {"t":"ptt_end","sid":"…","from":uid,"reason":"released|timeout|disconnected"}
```

One talker at a time per group / per user pair. Transmissions are cut after `PTT_MAX_SECONDS`.

## Voice calls (1:1)

```json
→ {"t":"call_invite","cid":"<client id>","to":uid}    ← {"t":"call_ringing","cid"}  callee ← {"t":"call_invite","cid","from","callsign"}
callee → {"t":"call_accept","cid"}                     caller ← {"t":"call_accepted","cid"}
either → {"t":"call_reject","cid","reason"?} / {"t":"call_end","cid"}     other ← {"t":"call_end","cid","reason"}
```

Reasons: `offline`, `busy`, `no_answer` (after `RING_TIMEOUT_SECONDS`), `rejected`, `hangup`, `disconnected`.
Audio frames (kind 2) are relayed only while the call is active.

## Binary audio frames

```
byte 0     kind: 1 = PTT, 2 = call
byte 1     N = length of the stream id (sid / cid)
2..2+N     stream id, ASCII
rest       G.711 µ-law, 8 kHz mono, 20 ms per frame (160 bytes)  ≈ 64 kbit/s
```

The server forwards frames unchanged and never decodes audio.
