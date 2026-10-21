# Local owned-item requests

The existing local WebSocket server (127.0.0.1, ports 37767–37776) supports two
additional requests for browser tools that need the player's owned equipment.
The existing `GetPlayer` request and its response are unchanged.

Enable **Share owned items locally** in WikiSync settings, then open/reopen the
bank. This option defaults off and also requires **Enable local WebSocket server**.
The existing WebSocket origin checks apply. Owned items stay in session memory;
they are returned only to the requesting local WebSocket connection. They are
never added to WikiSync's public upload data, manifest, shortlinks or disk storage.

## Requests and responses

The existing `UsernameChanged` greeting/broadcast includes an additive
`capabilities` array: `["GetPlayer","GetOwnedItems","GetOwnedItemsStatus"]`.
Clients should check it before making owned-item requests. Older versions do
not advertise these capabilities and should not receive the new request types.

```json
{"_wsType":"GetOwnedItemsStatus","sequenceId":1}
```

```json
{
  "_wsType":"GetOwnedItemsStatus",
  "sequenceId":1,
  "payload":{
    "schemaVersion":1,
    "sessionId":"opaque-session-token",
    "revision":4,
    "enabled":true,
    "loggedIn":true,
    "ready":true,
    "bankScannedAt":123456789
  }
}
```

`GetOwnedItemsStatus` contains no item IDs, names or levels and works while sharing
is disabled or the bank is unavailable. `ready` requires sharing enabled, logged
in, and a bank observed in the current session.

```json
{"_wsType":"GetOwnedItems","sequenceId":2}
```

`GetOwnedItems` returns the same metadata plus these maps in `payload`:

```json
{
  "bank":{"4151":2},
  "inventory":{"4151":1},
  "equipped":{"6585":1},
  "levels":{"attack":75,"strength":99,"combat":110},
  "names":{"4151":"Abyssal whip","6585":"Amulet of fury"}
}
```

Map keys are item IDs and values are observed quantities. A tool can combine the
three containers to compute ownership. Notes use the unnoted identity;
placeholders and zero quantities are ignored. Charged/uncharged identities remain
distinct. Levels use lowercase RuneLite skill names, plus `combat`. Names cover
the returned items. No private RuneScape profile key is exposed.

Unavailable item requests return an `error` and preserve the request sequence ID:

```json
{"_wsType":"GetOwnedItems","sequenceId":2,"error":"Enable local owned-item sharing in WikiSync settings."}
```

Clients should bound their request waits. They should verify `sessionId`, `revision` and `ready` again
before accepting a result computed from a snapshot. The session token changes
when state is cleared. Revisions change with ownership/real levels; duplicate
unchanged refreshes do not invalidate results.

## Capture and lifecycle

RuneLite item/stat events mark state dirty and capture bank events. Pending work
is coalesced on a game tick or flushed before responding, on the client thread.
Serialization and socket sending use the existing WebSocket response path. There
is no polling of game input and nothing is withdrawn or equipped automatically.

Logout, hopping, login transitions, character/profile changes, changing the
sharing option, disabling the WebSocket server or shutting down the plugin clear
the snapshot. A cached bank container from before enabling sharing is not assumed
fresh; reopen the bank. A disconnected client is not permitted to read item data.

Automated tests cover normalization, privacy gating, freshness, revision/session
invalidation, copied inputs, response envelopes and `GetPlayer` compatibility.
Manual checks still needed: enable sharing and open the bank, change quantities,
withdraw/equip items, hop, log out, switch profiles and toggle either setting.
