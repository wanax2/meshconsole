# MeshNet v1 — store-and-forward between Meshtastic BBSes

MeshNet lets bulletin boards on a Meshtastic mesh exchange bulletins and route mail, WWIVnet-style, using nothing but
direct messages. It is deliberately tiny: four message types, one line each, no binary, no extra ports. Any BBS that can
receive a DM, send a DM and keep a small list of seen ids can join. This document is the whole protocol.

## Transport

* Every MeshNet message is one Meshtastic **direct message** (TEXT_MESSAGE_APP, to a specific node) of at most 200 bytes UTF-8.
* Messages are only accepted from **configured peers** (an allow-list of node ids). Anything else that starts with `NET ` is ignored.
* Optional per-peer shared secret: when set, every line ends with ` s=XXXXXX`, the first 6 hex characters of
  HMAC-SHA256(secret, line-without-the-suffix). Lines with a missing or wrong signature are dropped. Secrets are exchanged out of band.
* Lines are case-sensitive in payload, case-insensitive in the `NET` and op words.

## Identifiers

* A board is identified by its **node id** (`!xxxxxxxx`).
* Every post or mail has a **global id** `<origin>:<n>` — the originating board's node id (without `!`) and a token unique on that
  board (posts use the board's post numbers, mail uses `m`-prefixed numbers, e.g. `435c3ff4:17` and `435c3ff4:m4`).
  Boards deduplicate on the global id, so a message may arrive by several routes and is filed once.

## Messages

```
NET HELLO v1 <board name> caps=<cap,cap,…>
NET POST <origin>:<n>:<hops> <author>: <text>
NET MAIL <origin>:<n> <user> <from>: <text>
NET ACK <origin>:<n>
NET PING            → the peer answers  NET PONG
```

* **HELLO** — sent at the start of every exchange. `caps` lists what the sender supports: `post` (bulletins), `mail`, `fwd` (forwards other boards' posts). A board that lacks a capability simply never receives those messages.
* **POST** — a bulletin headline. `hops` starts at 0 and is incremented by each forwarder; forward only while `hops < 3`. `author` is free text (no colon). File it as "`author` via `board name`".
* **MAIL** — mail for a user on the receiving board (`user` = that board's name for the node: short name, long name or `!id`). If the user is unknown the receiver may drop it or hold it; it must still ACK.
* **ACK** — confirms receipt of a POST or MAIL by global id. The sender retries an un-ACKed message after 10 minutes, up to 3 times, then gives up quietly.
* **PING/PONG** — liveness check; optional.

## Rules of the road

1. **Rate**: no more than 5 POST/MAIL lines per peer per exchange, no more than one scheduled exchange per 6 hours per peer, and a 2–5 s gap between lines. The mesh is shared.
2. **No loops**: never send a post back to the board it came from, and never forward a post to a peer whose origin it is.
3. **Dedupe forever** (or at least 30 days) on global id.
4. **Fail quietly**: an unknown op, a malformed line or an unexpected message is ignored, never answered with an error on the air.
5. **Text length**: the whole line including the prefix must fit 200 bytes. Truncate the text; never split a post across lines.

## Example exchange

```
A→B  NET HELLO v1 Lyon's Den caps=post,mail,fwd
A→B  NET POST 435c3ff4:17:0 bob: Antenna swap meet Sat 10am Lyon Village park
B→A  NET ACK 435c3ff4:17
B→C  NET POST 435c3ff4:17:1 bob: Antenna swap meet Sat 10am Lyon Village park     (B forwards; hops=1)
C→B  NET ACK 435c3ff4:17
B→A  NET MAIL 6965c51d:m4 bob alice: see you at the meet
A→B  NET ACK 6965c51d:m4
```

## Minimal implementation checklist

* [ ] peer list (node id → name, optional secret)
* [ ] on DM from a peer starting `NET `: verify signature if a secret is set, parse, dispatch
* [ ] POST: if global id unseen → file bulletin, remember id; always ACK
* [ ] MAIL: deliver or hold; always ACK
* [ ] outbound queue with (line, peer, attempts, last_sent); resend after 10 min, max 3
* [ ] daily exchange: HELLO + up to 5 new POSTs per peer
* [ ] (optional) forward received posts with hops+1 to other peers that advertised `fwd`

A reference implementation in Python (`meshnet.py`, ~150 lines, no dependencies) accompanies this document; Mesh Console's
Java BBS implements the same protocol.
