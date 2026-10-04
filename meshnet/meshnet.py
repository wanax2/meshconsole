"""
MeshNet v1 reference implementation (Python 3.8+, no dependencies).

Drop into any Meshtastic BBS:

    net = MeshNet(my_node_id="!435c3ff4", board_name="Lyon's Den",
                  send_dm=lambda node_id, text: my_bbs.send_dm(node_id, text),
                  on_post=lambda author, text, via: my_bbs.add_bulletin(f"{author} via {via}", text),
                  on_mail=lambda user, sender, text, via: my_bbs.add_mail(user, f"{sender}@{via}", text),
                  state_file="meshnet.json")
    net.add_peer("!6965c51d", name="Rat Radio BBS", secret=None)

    # when a DM arrives:                 if net.handle_dm(from_node_id, text): return   # it was MeshNet traffic
    # when a local bulletin is posted:   net.queue_post(local_id, author, text)
    # once a minute:                     net.tick()
    # once a day (or on demand):         net.exchange()
"""
import hashlib, hmac, json, os, time

VERSION = "v1"
MAX_LINE = 200
RETRY_SECONDS = 600
MAX_ATTEMPTS = 3
MAX_PER_EXCHANGE = 5
MAX_HOPS = 3


class MeshNet:
    def __init__(self, my_node_id, board_name, send_dm, on_post, on_mail=None, state_file=None, caps=("post", "mail", "fwd")):
        self.me = my_node_id.lstrip("!").lower()
        self.name = board_name
        self.send_dm = send_dm
        self.on_post = on_post
        self.on_mail = on_mail
        self.caps = set(caps)
        self.state_file = state_file
        self.peers = {}          # node -> {"name":..., "secret":..., "caps": set(), "last": ts, "last_post_sent": n}
        self.seen = {}           # global id -> ts
        self.outbox = []         # {"peer":..., "line":..., "gid":..., "attempts":..., "last":...}
        self.local_posts = []    # (local_id, author, text) not yet sent to some peer
        self.next_id = 1
        self._load()

    # ---- peers ---------------------------------------------------------------------------------
    def add_peer(self, node_id, name="", secret=None):
        node = node_id.lstrip("!").lower()
        self.peers.setdefault(node, {"name": name, "secret": secret, "caps": set(), "last": 0, "last_post_sent": 0})
        if name:
            self.peers[node]["name"] = name
        self.peers[node]["secret"] = secret
        self._save()

    def peer_name(self, node):
        p = self.peers.get(node)
        return (p and p["name"]) or ("!" + node)

    # ---- inbound ---------------------------------------------------------------------------------
    def handle_dm(self, from_node_id, text):
        """Returns True if the DM was MeshNet traffic (handled or dropped), False if it is an ordinary message."""
        node = from_node_id.lstrip("!").lower()
        if not text.upper().startswith("NET "):
            return False
        peer = self.peers.get(node)
        if not peer:
            return True                                   # not a peer: drop silently
        line = text.strip()
        if peer["secret"]:
            if " s=" not in line:
                return True
            body, sig = line.rsplit(" s=", 1)
            if not hmac.compare_digest(self._sig(peer["secret"], body), sig.strip().lower()):
                return True
            line = body
        parts = line.split(None, 2)                       # NET, OP, rest
        op = parts[1].upper() if len(parts) > 1 else ""
        rest = parts[2] if len(parts) > 2 else ""
        if op == "HELLO":
            toks = rest.split()
            caps = set()
            name_toks = []
            for t in toks:
                if t.startswith("caps="):
                    caps = set(t[5:].split(","))
                elif t != VERSION:
                    name_toks.append(t)
            if name_toks:
                peer["name"] = " ".join(name_toks)
            peer["caps"] = caps
            peer["last"] = time.time()
        elif op == "POST":
            head, _, payload = rest.partition(" ")
            try:
                origin, n, hops = head.split(":")
                hops = int(hops)
            except ValueError:
                return True
            gid = f"{origin}:{n}"
            self._send(node, f"NET ACK {gid}")
            if gid not in self.seen and origin != self.me:
                self.seen[gid] = time.time()
                author, _, body = payload.partition(":")
                self.on_post(author.strip(), body.strip(), self.peer_name(node))
                if "fwd" in self.caps and hops + 1 < MAX_HOPS:
                    for other, op_ in self.peers.items():
                        if other not in (node, origin) and "fwd" in op_["caps"]:
                            self._queue(other, f"NET POST {origin}:{n}:{hops + 1} {author.strip()}: {body.strip()}", gid)
            peer["last"] = time.time()
        elif op == "MAIL":
            head, _, payload = rest.partition(" ")
            self._send(node, f"NET ACK {head}")
            if head not in self.seen:
                self.seen[head] = time.time()
                user, _, payload2 = payload.partition(" ")
                sender, _, body = payload2.partition(":")
                if self.on_mail:
                    self.on_mail(user, sender.strip(), body.strip(), self.peer_name(node))
        elif op == "ACK":
            gid = rest.strip()
            self.outbox = [o for o in self.outbox if not (o["peer"] == node and o["gid"] == gid)]
            peer["last"] = time.time()
        elif op == "PING":
            self._send(node, "NET PONG")
        self._save()
        return True

    # ---- outbound ----------------------------------------------------------------------------
    def queue_post(self, local_id, author, text):
        """Call when a bulletin is posted locally; it goes to every peer at the next exchange."""
        self.local_posts.append((int(local_id), author.replace(":", " "), text))
        self._save()

    def queue_mail(self, peer_node_id, user, sender, text):
        node = peer_node_id.lstrip("!").lower()
        gid = f"{self.me}:m{self.next_id}"          # mail ids are m-prefixed so they never collide with post ids
        self.next_id += 1
        self._queue(node, f"NET MAIL {gid} {user} {sender.replace(':', ' ')}: {text}", gid)
        self._save()

    def exchange(self):
        """Daily (or on demand): HELLO every peer and send up to MAX_PER_EXCHANGE unsent posts each."""
        for node, peer in self.peers.items():
            self._send(node, f"NET HELLO {VERSION} {self.name} caps={','.join(sorted(self.caps))}")
            if "post" in peer["caps"] or not peer["caps"]:
                n = 0
                for lid, author, text in sorted(self.local_posts):
                    if lid <= peer["last_post_sent"]:
                        continue
                    gid = f"{self.me}:{lid}"
                    self._queue(node, f"NET POST {gid}:0 {author}: {text}", gid)
                    peer["last_post_sent"] = lid
                    n += 1
                    if n >= MAX_PER_EXCHANGE:
                        break
        self._save()

    def tick(self):
        """Once a minute: send queued lines (with a gap), retry un-ACKed ones."""
        now = time.time()
        sent = 0
        for o in list(self.outbox):
            if o["attempts"] >= MAX_ATTEMPTS and now - o["last"] > RETRY_SECONDS:
                self.outbox.remove(o)
                continue
            if o["attempts"] == 0 or now - o["last"] >= RETRY_SECONDS:
                if sent >= 2:
                    break                                 # pace: at most 2 lines per tick
                self._send(o["peer"], o["line"])
                o["attempts"] += 1
                o["last"] = now
                sent += 1
        self._save()

    # ---- internals ---------------------------------------------------------------------------
    def _queue(self, node, line, gid):
        if any(o["peer"] == node and o["gid"] == gid for o in self.outbox):
            return
        self.outbox.append({"peer": node, "line": line, "gid": gid, "attempts": 0, "last": 0})

    def _send(self, node, line):
        peer = self.peers.get(node)
        if peer and peer["secret"]:
            line = line + " s=" + self._sig(peer["secret"], line)
        line = line.encode("utf-8")[:MAX_LINE].decode("utf-8", "ignore")
        self.send_dm("!" + node, line)

    @staticmethod
    def _sig(secret, body):
        return hmac.new(secret.encode("utf-8"), body.encode("utf-8"), hashlib.sha256).hexdigest()[:6]

    def _save(self):
        if not self.state_file:
            return
        cutoff = time.time() - 30 * 86400
        self.seen = {k: v for k, v in self.seen.items() if v > cutoff}
        data = {"peers": {k: {**v, "caps": sorted(v["caps"])} for k, v in self.peers.items()}, "seen": self.seen,
                "outbox": self.outbox, "local_posts": self.local_posts, "next_id": self.next_id}
        tmp = self.state_file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f)
        os.replace(tmp, self.state_file)

    def _load(self):
        if not self.state_file or not os.path.exists(self.state_file):
            return
        with open(self.state_file, encoding="utf-8") as f:
            data = json.load(f)
        self.peers = {k: {**v, "caps": set(v.get("caps", []))} for k, v in data.get("peers", {}).items()}
        self.seen = data.get("seen", {})
        self.outbox = data.get("outbox", [])
        self.local_posts = [tuple(x) for x in data.get("local_posts", [])]
        self.next_id = data.get("next_id", 1)


if __name__ == "__main__":
    # self-test: two boards talking through an in-memory "mesh"
    boards = {}
    log = []
    def mk(node, name):
        b = MeshNet(node, name, send_dm=lambda to, text, _n=node: boards[to.lstrip('!')].handle_dm(_n, text) or log.append((_n, to, text)),
                    on_post=lambda a, t, via, _n=name: print(f"[{_n}] bulletin: {a} via {via}: {t}"),
                    on_mail=lambda u, s, t, via, _n=name: print(f"[{_n}] mail for {u} from {s}@{via}: {t}"))
        boards[node.lstrip('!')] = b
        return b
    A = mk("!00000001", "Alpha"); B = mk("!00000002", "Beta"); C = mk("!00000003", "Gamma")
    A.add_peer("!00000002", "Beta", secret="s3cret"); B.add_peer("!00000001", "Alpha", secret="s3cret"); B.add_peer("!00000003", "Gamma"); C.add_peer("!00000002", "Beta")
    B.exchange(); C.exchange(); B.tick()           # HELLOs so caps are known
    A.queue_post(1, "bob", "Swap meet Saturday 10am"); A.exchange(); A.tick()
    B.tick(); B.tick()                              # B forwards to C (hops=1)
    A.queue_mail("!00000002", "alice", "bob", "see you there"); A.tick()
    print("A outbox after ACKs:", len(A.outbox), "| B seen:", list(B.seen), "| C seen:", list(C.seen))
