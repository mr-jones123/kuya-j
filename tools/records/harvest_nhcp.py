"""Turn the National Historical Commission of the Philippines' registry of historical markers into Kuya J records.

    python3 tools/records/harvest_nhcp.py --out ../kuya-j-records/records          # fetch (cached) and write
    python3 tools/records/harvest_nhcp.py --out ../kuya-j-records/records --offline  # only use the cache

Source: https://philhistoricsites.nhcp.gov.ph (the NHCP's Philippine Registry of Heritage). The list comes from the
site's WordPress API; each marker's text, location, date and installer from its page. Pages are fetched one every
1.5 seconds and cached in tools/records/.cache/nhcp/, so re-runs only fetch what's new.

Marker texts are works of the Philippine government (no copyright, RA 8293 section 176) and are copied word for word.
Writes nhcp-markers.json: one record per marker that has text, category "history".
"""

import argparse
import datetime as dt
import html
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

SITE = "https://philhistoricsites.nhcp.gov.ph"
CACHE = Path(__file__).resolve().parent / ".cache" / "nhcp"
UA = {"User-Agent": "Mozilla/5.0 (compatible; kuya-j-records-harvester)"}
DELAY = 1.5
MAX_CHARS = 1150


def get(url: str) -> bytes:
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read()


def listing(offline: bool) -> list[dict]:
    f = CACHE / "list.json"
    if f.exists() and (offline or time.time() - f.stat().st_mtime < 7 * 86400):
        return json.loads(f.read_text())
    out, page = [], 1
    while True:
        batch = json.loads(get(f"{SITE}/wp-json/wp/v2/registry_database?per_page=100&page={page}&_fields=id,slug,link,title,modified"))
        if not batch:
            break
        out += batch
        page += 1
        time.sleep(DELAY)
        if len(batch) < 100:
            break
    CACHE.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(out))
    return out


def page(item: dict, offline: bool) -> str | None:
    f = CACHE / f"{item['id']}.html"
    if not f.exists():
        if offline:
            return None
        try:
            f.write_bytes(get(item["link"]))
        except Exception as e:  # a failed page is skipped this run and retried next time
            print(f"  skip {item['link']}: {e}", file=sys.stderr)
            return None
        time.sleep(DELAY)
    return f.read_text(encoding="utf-8", errors="ignore")


FIELDS = ("Location", "Category", "Type", "Status", "Marker date", "Installed by")


def parse(doc: str) -> dict | None:
    """The fields and marker text from a registry page, or None if it has no marker text."""
    body = re.sub(r"<script.*?</script>|<style.*?</style>", "", doc, flags=re.S)
    i = body.find("Location:")
    if i < 0:
        return None
    seg = body[i:i + 20000]
    text = html.unescape(re.sub(r"<br\s*/?>|</p>|</h\d>|</div>|</li>", "\n", seg))
    text = re.sub(r"<[^>]+>", "", text)
    lines = [re.sub(r"\s+", " ", l).strip() for l in text.split("\n")]
    lines = [l for l in lines if l]
    out, marker = {}, []
    in_marker = False
    for l in lines:
        if in_marker:
            if re.match(r"^(Regions|Provinces|Related|Share|Gallery|Categories|Labels|Search for|Back to)\b", l) or l.startswith("-->"):
                break
            marker.append(l)
            continue
        for f in FIELDS:
            if l.startswith(f + ":"):
                out[f] = l[len(f) + 1:].strip()
        if l.startswith("Marker text:"):
            in_marker = True
            rest = l[len("Marker text:"):].strip()
            if rest:
                marker.append(rest)
    text = "\n".join(marker).strip()
    if len(text) < 20:
        return None
    out["text"] = text
    return out


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def unique_words(phrases: list[str]) -> list[str]:
    seen, out = set(), []
    for w in " ".join(phrases).split():
        k = re.sub(r"[^\w'-]", "", w.lower())
        if k and k not in seen:
            seen.add(k)
            out.append(w)
    return out


YEARS = re.compile(r"\b(1[5-9]\d\d|20\d\d)\b")
# "Jose Rizal (1861-1896)", "Librada Avelino (1873–1934)": a person's marker, with life years.
PERSON = re.compile(r"^(?:Dr\.\s+|Heneral\s+|General\s+|Gat\s+)?([A-ZÑÁÉÍÓÚ][\w.'’ -]+?)\s*\(?(1[5-9]\d\d)\s*[–-]\s*(1[5-9]\d\d|20\d\d)\)?")
NOT_PERSON = re.compile(r"\b(church|simbahan|bahay|house|school|paaralan|labanan|battle|pook|site|building|gusali|monument|bantayog|"
                        r"shrine|dambana|lungsod|bayan|fountain|college|kolehiyo|university|pamantasan|hospital|ospital|bridge|tulay|"
                        r"cathedral|katedral|parish|parokya|town|city|municipal|plaza|park|liwasan|libingan|cemetery|hall|bulwagan|"
                        r"kapitolyo|capitol|market|pamilihan|station|estasyon|fort|kuta|light|parola|seminary|academy|club)\b", re.I)


def person(title: str) -> tuple[str, str] | None:
    """('Jose Rizal', '1861–1896') for a person's marker, else None."""
    m = PERSON.match(title)
    if not m or NOT_PERSON.search(m.group(1)) or len(m.group(1).split()) < 2:
        return None
    return m.group(1).strip(" ,"), f"{m.group(2)}–{m.group(3)}"


def title_of(item: dict) -> str:
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", item["title"]["rendered"]))).strip()


def record(item: dict, m: dict, checked: str, markers_of: dict) -> list[dict]:
    title = title_of(item)
    where = m.get("Location", "")
    intro = (f"Historical marker \"{title}\"" + (f" in {where}" if where else "")
             + (f", installed by the {m['Installed by']}" if m.get("Installed by") else "")
             + (f" in {m['Marker date']}" if m.get("Marker date") else "") + ". Marker text:")
    body = m["text"]
    # Long markers are split at line breaks so each record fits what the app shows the model.
    chunks, cur = [], ""
    # Markers that are one long paragraph are split at sentence ends too.
    pieces = [p for line in body.split("\n") for p in re.split(r"(?<=[.;])\s+(?=[A-Z0-9“\"])", line) if p]
    for line in pieces:
        if cur and len(intro) + len(cur) + len(line) > MAX_CHARS - 120:
            chunks.append(cur.strip())
            cur = ""
        cur += line + "\n"
    chunks.append(cur.strip())
    people = re.sub(r"\(.*?\)|\d{4}[–-]\d{4}|\d{4}", "", title).strip(" ,-–")
    life = []  # only markers that record a birth or a death get those search words
    if re.search(r"ISINILANG|IPINANGANAK|BORN|SUMILANG|NATAWO|KAPANGANAKAN", body, re.I):
        life += "born birth birthday ipinanganak isinilang kapanganakan kaarawan".split()
    if re.search(r"NAMATAY|BINARIL|PINATAY|DIED|DEATH|EXECUTED|NAMATÁY|KAMATAYAN|PUMANAW", body, re.I):
        life += "died death namatay kamatayan pumanaw binaril".split()
    kws = unique_words([title, people, where, "history", "kasaysayan", "historical marker", "NHCP", *life, *YEARS.findall(title)])
    out = []
    for i, c in enumerate(chunks, 1):
        part = f" (part {i} of {len(chunks)})" if len(chunks) > 1 else ""
        content = f"{intro}\n{c}\nSource: {item['link']} (checked {checked})."
        out.append({"id": f"nhcp-{item['id']}" + (f"-{i}" if len(chunks) > 1 else ""), "category": "history",
                    "title": f"{title}{part}", "content": content, "keywords": kws, "tags": ["nhcp", "history", "marker"],
                    "metadata": {"source": item["link"], "publisher": "National Historical Commission of the Philippines",
                                 "asOf": checked, "dateLabel": "checked", "agency": "NHCP",
                                 "markerDate": m.get("Marker date"), "location": where}})
    who = person(title)
    if who:  # read by the app's people lookup ("Kailan ipinanganak si Rizal?")
        for r in out:
            r["metadata"].update({"person": who[0], "lifeYears": who[1], "personMarkers": markers_of.get(who[0], 1)})
    return out


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--out", required=True)
    p.add_argument("--offline", action="store_true", help="use only cached pages")
    p.add_argument("--limit", type=int, default=0, help="fetch at most this many pages (testing)")
    a = p.parse_args()
    items = listing(a.offline)
    print(f"{len(items)} registry entries")
    checked = dt.date.today().isoformat()
    # Fetch what's missing, people with life dates first ("Jose Rizal (1861-1896)"), three at a time: the site takes
    # about 5 seconds to build each page, so one at a time would take hours.
    todo = [i for i in items if not (CACHE / f"{i['id']}.html").exists()]
    todo.sort(key=lambda i: not re.search(r"\d{4}\s*[–-]\s*\d{4}", i["title"]["rendered"]))
    if a.limit:
        todo = todo[:a.limit]
    if todo and not a.offline:
        print(f"fetching {len(todo)} pages")
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(3) as ex:
            for n, _ in enumerate(ex.map(lambda i: page(i, False), todo), 1):
                if n % 50 == 0:
                    print(f"  {n}/{len(todo)} fetched", flush=True)
    # How many markers each person has: "si Rizal" means José Rizal (dozens of markers), not a relative with one.
    markers_of: dict[str, int] = {}
    for item in items:
        who = person(title_of(item))
        if who:
            markers_of[who[0]] = markers_of.get(who[0], 0) + 1
    records, no_text, seen = [], 0, set()
    for n, item in enumerate(items, 1):
        doc = page(item, True)
        m = parse(doc) if doc else None
        if not m:
            no_text += 1
            continue
        # The same text stands on many markers (Rizal's, worldwide): keep the first, so repeats don't fill search results.
        key = re.sub(r"[^a-z0-9]", "", m["text"].lower())
        if key in seen:
            continue
        seen.add(key)
        records += record(item, m, checked, markers_of)
        if n % 100 == 0:
            print(f"  {n}/{len(items)} pages, {len(records)} records")
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "nhcp-markers.json").write_text(json.dumps(records, ensure_ascii=False, indent=1) + "\n")
    too_long = [r["id"] for r in records if len(r["content"]) > 1200]
    print(f"{len(records)} records from {len(items) - no_text} markers with text ({no_text} without) -> {out / 'nhcp-markers.json'}")
    if too_long:
        sys.exit(f"{len(too_long)} records over 1,200 characters: {too_long[:5]}")


if __name__ == "__main__":
    sample = ('<p>Location: Calamba, Laguna</p><p>Marker date: 1960</p><p>Installed by: Philippines Historical Committee (PHC)</p>'
              '<p>Marker text:</p><p>JOSE RIZAL<br>1861–1896</p><p>SI JOSÉ RIZAL AY BININYAGAN NOONG IKA-22 NG HUNYO NG 1861.</p>'
              '<div>Regions</div>')
    m = parse(sample)
    assert m["Location"] == "Calamba, Laguna" and m["Marker date"] == "1960", m
    assert m["text"].startswith("JOSE RIZAL\n1861–1896\nSI JOSÉ RIZAL AY BININYAGAN"), m["text"]
    assert person("Jose Rizal (1861-1896)") == ("Jose Rizal", "1861–1896")
    assert person("Librada Avelino (1873–1934)") == ("Librada Avelino", "1873–1934")
    assert person("Jose Rizal 1861-1896 (Calamba Church)") == ("Jose Rizal", "1861–1896")
    assert person("Philippine Normal University (1901–2001)") is None and person("Ang Bayan ng Taal (1572)") is None
    main()
