#!/usr/bin/env python3
"""Build table/sentence_ipa.json: espeak-ng citation IPA per word, with a checked-in overrides file for
true heteronyms (gruut's in-context reading, resolved once offline) and the two hand rules.
    python3 tools/make_table.py <sentences.xlsx|.json|.txt> [out.json]
Input: the 필터 sheet of maxai_pre_study_sentences.xlsx (English rows, column 발화문), or a JSON list /
text file of sentences. Needs espeak-ng on PATH. Only heteronyms and the rules come from
tools/heteronym_overrides.json; everything else is espeak."""
import json, os, re, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
OVR = json.load(open(os.path.join(HERE, "heteronym_overrides.json")))
WORD = re.compile(r"[a-zA-Z0-9']+")   # digits stay as words: espeak reads "30" as thirty

def sentences(path):
    if path.endswith(".xlsx"):
        import openpyxl
        wb = openpyxl.load_workbook(path, read_only=True); out, seen = [], set()
        for r in wb["필터"].iter_rows(min_row=2, values_only=True):
            s = r[7]
            if not s or (r[3] and r[3] != "en"): continue
            s = re.sub(r"[{}]", "", str(s)).strip()
            if s and s not in seen: seen.add(s); out.append(s)
        return out
    if path.endswith(".json"): return json.load(open(path))
    return [l.strip() for l in open(path) if l.strip()]

_cache = {}
def espeak(word):
    if word not in _cache:
        _cache[word] = subprocess.run(["espeak-ng", "-v", "en-us", "--ipa", "-q", word],
                                      capture_output=True, text=True).stdout.strip().replace("‍", "")
    return _cache[word]

def table_for(sent):
    words = WORD.findall(sent); ovr = OVR["sentences"].get(sent, {}); out = []
    for i, w in enumerate(words):
        lw = w.lower(); nxt = words[i + 1].lower() if i + 1 < len(words) else ""
        if str(i) in ovr: ipa = ovr[str(i)]                                   # true heteronym, resolved offline
        elif lw == "used": ipa = "jˈust" if nxt == "to" else "jˈuzd"           # "used to" vs participle
        elif lw == "won" and re.search(r"\d", sent): ipa = "wˈɑn"               # currency next to digits
        else: ipa = espeak(w)
        out.append({"word": w, "wordIndex": i, "ipa": ipa})
    return out

if __name__ == "__main__":
    src = sys.argv[1]; dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "..", "table", "sentence_ipa.json")
    sents = sentences(src); tbl = {s: table_for(s) for s in sents}
    empty = [w for w, p in _cache.items() if not p]
    json.dump(tbl, open(dst, "w", encoding="utf-8"), ensure_ascii=False)
    n_ovr = sum(1 for s in tbl for i in range(len(tbl[s])) if str(i) in OVR["sentences"].get(s, {}))
    print(f"{len(tbl)} sentences, {sum(len(v) for v in tbl.values())} words, {len(_cache)} distinct through espeak, "
          f"{n_ovr} heteronym overrides applied, {len(empty)} words with no espeak output {empty[:5]} -> {dst}")
