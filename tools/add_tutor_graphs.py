"""Merge the production native graphs into the sentence table.

    python3 tools/add_tutor_graphs.py <repeat-native-mp3-list.csv> [table.json ...]

For every table sentence that has a `graph_value` row in the CSV (matched on `eval_eng`), the
row becomes {"words": [...], "graph": [100 ints], "accent": [ints], "spanMs": n, "wordMs": [[text, start, end], ...]}.
`wordMs` are the Selvas engine's word timings read from the reference .dat (`dat_url`, cached in
tools/dat_cache/), in ms on the same timeline as the graph, which spans 0..spanMs. Other rows stay
plain word arrays. The engine accepts both forms. Default targets: table/sentence_ipa.json and the demo asset.
"""
import csv, json, os, struct, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT = [os.path.join(HERE, "..", "table", "sentence_ipa.json"),
           os.path.join(HERE, "..", "app", "src", "main", "assets", "sentence_ipa.json")]


def norm(s):
    return " ".join(s.replace("{", "").replace("}", "").replace("’", "'").split())


def read_dat_words(path):
    """Selvas reference .dat (both layouts): [(word, start_ms, end_ms)], span_ms (= stored PCM length)."""
    b = open(path, "rb").read(); o = 0
    def i():
        nonlocal o; v = struct.unpack_from("<i", b, o)[0]; o += 4; return v
    def s():
        nonlocal o; e = b.index(b"\0", o); v = b[o:e].decode("utf-8", "replace"); o = e + 1; return v
    for _ in range(4): i()
    s(); i(); s(); i(); i(); n = i()
    words = [s() for _ in range(n)]; [i() for _ in range(n)]
    pos = [(i(), i()) for _ in range(n)]
    # the file ends with n_samples then n_samples int16 PCM; find n_samples by scanning back
    n_samples = 0
    for k in range(1, len(b) // 2):
        off = len(b) - 2 * k - 4
        if off < 0: break
        if struct.unpack_from("<i", b, off)[0] == k:
            n_samples = k; break
    return [[w, a, e] for w, (a, e) in zip(words, pos)], round(n_samples / 16)


def dat_timings(url):
    cache = os.path.join(HERE, "dat_cache"); os.makedirs(cache, exist_ok=True)
    path = os.path.join(cache, url.rsplit("/", 1)[-1])
    if not os.path.exists(path):
        urllib.request.urlretrieve(url, path)
    return read_dat_words(path)


def main():
    src = sys.argv[1]
    targets = sys.argv[2:] or DEFAULT
    graphs = {}
    with open(src, encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            if r["graph_value"].strip():
                graphs.setdefault(norm(r["eval_eng"]), (json.loads(r["graph_value"]), json.loads(r["graph_accent"] or "[]"), r["dat_url"]))
    for path in targets:
        table = json.load(open(path))
        n = 0
        for k, v in table.items():
            words = v["words"] if isinstance(v, dict) else v
            g = graphs.get(norm(k))
            if g:
                row = {"words": words, "graph": g[0], "accent": g[1]}
                try:
                    row["wordMs"], row["spanMs"] = dat_timings(g[2])
                except Exception as e:
                    print(f"  no timings for {k!r}: {e}")
                table[k] = row; n += 1
            else:
                table[k] = words
        json.dump(table, open(path, "w"), ensure_ascii=False)
        print(f"{n}/{len(table)} sentences got a tutor graph -> {path}")


if __name__ == "__main__":
    main()
