"""Turn BetterGov.ph's public data (github.com/bettergovph/bettergov, src/data) into Kuya J records.

    python3 tools/records/harvest.py --out ../kuya-j-records/records            # latest BetterGov commit
    python3 tools/records/harvest.py --commit 5ead8c0 --out ../kuya-j-records/records

Writes one JSON array per source (bettergov-guides.json, ...) in the app's record format, plus SOURCES.md listing the
commit and file dates. Pure standard library. Downloads are cached in tools/records/.cache/<commit>/.

Rules (docs/records-pipeline.md):
- Guide text is copied word for word; only Markdown syntax is removed. Structured data (hotlines, holidays, directory,
  visas) is written into sentences by fixed templates, values copied exactly.
- Every record ends with "Source: <url> (checked|listed <date>)." "checked" is a date someone verified the source
  (a guide's review date, a holiday list's verification date, a department directory's check date); "listed" is
  only when BetterGov last changed the file, for data that carries no verification date.
- Every record credits BetterGov.ph (metadata.via, metadata.licence) and pins the BetterGov commit.
"""

import argparse
import datetime as dt
import json
import re
import sys
import urllib.request
from pathlib import Path

REPO = "bettergovph/bettergov"
SITE = "https://bettergov.ph"
LICENCE = "CC BY-NC-ND 4.0, BetterGov.ph; adapted for Kuya J with permission"
CACHE = Path(__file__).resolve().parent / ".cache"
MAX_CHARS = 1150  # the app shows the model the first 1,200 characters of a record

# ---- fetching ---------------------------------------------------------------------------------------------------


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "kuya-j-records-harvester"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def latest_commit() -> str:
    return json.loads(fetch(f"https://api.github.com/repos/{REPO}/commits/main"))["sha"]


def raw(commit: str, path: str, root: str = "src/data") -> str:
    f = CACHE / commit / (path if root == "src/data" else f"{root}/{path}".replace("/", "_"))
    if not f.exists():
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(fetch(f"https://raw.githubusercontent.com/{REPO}/{commit}/{root}/{path}"))
    return f.read_text()


def file_date(commit: str, path: str) -> str:
    """When BetterGov last changed this file, as of the pinned commit (YYYY-MM-DD)."""
    f = CACHE / commit / (path + ".date")
    if not f.exists():
        url = f"https://api.github.com/repos/{REPO}/commits?path=src/data/{path}&sha={commit}&per_page=1"
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.loads(fetch(url))[0]["commit"]["committer"]["date"][:10])
    return f.read_text()


# ---- text helpers -----------------------------------------------------------------------------------------------

CITE = re.compile(r"\[\\\[(\d+)\\\]\]\((\S+?)\)")          # [\[1\]](url)
LINK = re.compile(r"\[([^\]]+)\]\((https?://[^)\s]+)\)")    # [text](url)
ROMAN = {"II", "III", "IV", "V", "VI", "JR.", "SR.", "JR", "SR"}
UPPER = {"II", "III", "IV", "V", "VI", "MD", "PHD", "CPA"}


def plain(md: str) -> tuple[str, list[str]]:
    """Markdown to plain text, words unchanged. Returns the text and the URLs it cited, in order."""
    urls = [u for _, u in CITE.findall(md)] + [u for _, u in LINK.findall(md)]
    s = CITE.sub(r"[\1]", md)
    s = LINK.sub(r"\1", s)
    s = re.sub(r"\*\*(.+?)\*\*", r"\1", s)
    s = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"\1", s)
    s = re.sub(r"^>\s?", "", s, flags=re.M)
    s = re.sub(r"^- \[ \] ", "- ", s, flags=re.M)
    s = table_to_lines(s)
    s = re.sub(r"\n{3,}", "\n\n", s).strip()
    return s, urls


def table_to_lines(s: str) -> str:
    """GFM table rows become 'Header: cell; Header: cell' lines."""
    out, header = [], None
    for line in s.split("\n"):
        if line.startswith("|"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            if all(re.fullmatch(r":?-{3,}:?", c) for c in cells):
                continue
            if header is None:
                header = cells
                continue
            out.append("- " + "; ".join(f"{h}: {c}" for h, c in zip(header, cells) if c))
        else:
            header = None
            out.append(line)
    return "\n".join(out)


def person(name: str) -> str:
    """'DY, FAUSTINO "BOJIE" G. III' or 'KIM ROBERT C. DE LEON' -> 'Faustino "Bojie" G. Dy III' / 'Kim Robert C. De Leon'."""
    name = name.strip()
    if "," in name:
        parts = [p.strip() for p in name.split(",")]
        last, first, rest = parts[0], parts[1], [p for p in parts[2:] if p]
        bits = first.split()
        if bits and bits[-1].upper() in ROMAN:
            rest.insert(0, bits.pop())
        name = " ".join([" ".join(bits), last] + rest)
    words = []
    for w in name.split():
        if w.upper() in UPPER or re.fullmatch(r"(?:[A-Za-z]\.)+", w):  # III, MD, initials like M.V.F.
            words.append(w.upper())
        elif w.upper() in ROMAN or (len(w) <= 2 and w.endswith(".")):
            words.append(w.capitalize())
        else:
            words.append("-".join(p[:1].upper() + p[1:].lower() for p in w.split("-")).replace('"B', '"B'))
    return re.sub(r'"(\w)', lambda m: '"' + m.group(1).upper(), " ".join(words))


def office(name: str) -> str:
    """'DEPARTMENT OF BUDGET AND MANAGEMENT' -> 'Department of Budget and Management'."""
    small = {"of", "and", "the", "for", "on", "in", "sa", "ng", "at"}
    words = name.strip().split()
    out = []
    for i, w in enumerate(words):
        lw = w.lower()
        if w.startswith("(") or (w.isupper() and len(w) <= 5 and w.isalpha() and lw not in small and i > 0 and w in ACRONYMS):
            out.append(w)
        elif i > 0 and lw in small:
            out.append(lw)
        else:
            out.append("-".join(part.capitalize() for part in lw.split("-")))
    return " ".join(out)


ACRONYMS = {"UCPB", "PEZA", "BCDA", "TIEZA", "MIAA", "NAIA", "PITC", "PNR", "PPA", "NEA", "NPC", "TESDA", "PAGCOR", "PCSO", "GSIS", "SSS", "NFA", "NHA", "LTO", "LTFRB", "MMDA", "PNP", "AFP", "BIR", "BOC", "NBI"}


def source_line(url: str, date: str, label: str) -> str:
    return f"Source: {url} ({label} {date})."


def record(rid, category, title, body, keywords, *, source, publisher, as_of, label, agency=None, via, commit, tags=()):
    content = f"{body.strip()}\n{source_line(source, as_of, label)}"
    meta = {"source": source, "publisher": publisher, "asOf": as_of, "dateLabel": label, "via": via,
            "licence": LICENCE, "bettergovCommit": commit[:12]}
    if agency:
        meta["agency"] = agency
    kw = unique_words([k for k in keywords if k])
    return {"id": rid, "category": category, "title": title, "content": content, "keywords": kw,
            "tags": list(tags), "metadata": meta}


def split_chunks(text: str, limit: int) -> list[str]:
    """Split at line breaks (list items), keeping each chunk under the limit."""
    chunks, cur = [], ""
    for line in text.split("\n"):
        if cur and len(cur) + len(line) + 1 > limit:
            chunks.append(cur.strip())
            cur = ""
        cur += line + "\n"
    if cur.strip():
        chunks.append(cur.strip())
    return chunks


def unique_words(phrases: list[str]) -> list[str]:
    """Each search word once, in order: BM25 counts a repeated keyword more, which buries more specific records."""
    seen, out = set(), []
    for w in " ".join(phrases).split():
        k = re.sub(r"[^\w'-]", "", w.lower())
        if k and k not in seen:
            seen.add(k)
            out.append(w)
    return out


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


# ---- guides -----------------------------------------------------------------------------------------------------

# How people name each guide's topic, with Filipino phrasings for search.
TOPICS = {
    "passport": ("Philippine passport", ["passport", "pasaporte", "DFA passport"]),
    "nbi": ("NBI clearance", ["NBI clearance", "NBI"]),
    "tin": ("TIN (Taxpayer Identification Number)", ["TIN", "TIN number", "taxpayer identification number", "BIR TIN"]),
    "umid": ("MySSS Card (UMID replacement)", ["UMID", "MySSS card", "SSS ID", "SSS card"]),
    "psa": ("PSA birth certificate", ["birth certificate", "PSA birth certificate", "birth cert", "PSA"]),
    "drivers": ("driver's license renewal", ["driver's license", "drivers license", "lisensya", "LTO license renewal"]),
    "itr": ("annual ITR (BIR Form 1700)", ["ITR", "income tax return", "BIR Form 1700", "1700"]),
    "bir-1701": ("BIR Form 1701A", ["1701A", "BIR Form 1701A", "self-employed tax", "freelancer tax"]),
    "vat": ("VAT registration", ["VAT", "VAT registration", "value-added tax"]),
    "philhealth": ("PhilHealth registration", ["PhilHealth", "PhilHealth PIN", "PhilHealth member"]),
    "sss-claim": ("SSS pension claim", ["SSS pension", "SSS retirement", "SSS claim", "pension"]),
    "pagibig": ("Pag-IBIG membership", ["Pag-IBIG", "Pagibig", "HDMF", "Pag-IBIG MID"]),
    "dti": ("DTI business name registration", ["DTI", "business name", "sole proprietorship", "DTI registration"]),
    "sec": ("SEC corporation registration", ["SEC", "corporation", "SEC registration", "OPC"]),
    "mayors": ("mayor's permit", ["mayor's permit", "business permit", "permit"]),
    "title-transfer": ("land title transfer", ["land title", "title transfer", "TCT", "transfer of title"]),
    "amilyar": ("real property tax (amilyar)", ["amilyar", "real property tax", "RPT", "property tax"]),
    "marriage": ("getting married", ["marriage", "kasal", "marriage license", "civil wedding"]),
    "cenomar": ("CENOMAR", ["CENOMAR", "certificate of no marriage", "no marriage record"]),
}
AGENCIES = {"DFA": "Department of Foreign Affairs", "NBI": "National Bureau of Investigation", "BIR": "Bureau of Internal Revenue",
            "SSS": "Social Security System", "PSA": "Philippine Statistics Authority", "LTO": "Land Transportation Office",
            "PhilHealth": "PhilHealth", "Pag-IBIG": "Pag-IBIG Fund", "DTI": "Department of Trade and Industry",
            "SEC": "Securities and Exchange Commission", "LGU": "Local government unit"}
SECTIONS = {  # heading -> (category, label in the title, extra search words)
    "before you start": ("requirements", "requirements", ["requirements", "kailangan", "documents", "ano ang kailangan", "requirements sa"]),
    "steps": ("services", "steps", ["how to", "paano", "steps", "proseso", "apply"]),
    "what happens next": ("services", "after you apply", ["release", "how long", "gaano katagal", "after"]),
    "common problems and where to get help": ("services", "problems and where to get help", ["problem", "help", "contact", "hotline", "helpline", "number", "email", "complaint"]),
    "deadlines and penalties": ("services", "deadlines and penalties", ["deadline", "penalty", "multa", "late"]),
}
LEDE = {  # blockquote label -> (category, label, extra search words)
    # "How much" in Tagalog, Cebuano/Hiligaynon, Ilocano, Waray, Kapampangan, Pangasinan, Bikol.
    "what it costs": ("fees", "fees", ["fee", "magkano", "bayad", "how much", "cost", "price", "presyo", "tagpila", "pila",
                                       "mano", "tagpira", "magkanu", "pigara", "gastos", "singil"]),
    "who this is for": ("services", "who can apply", ["who can apply", "sino ang pwede", "eligible", "qualified"]),
    "how long it takes": ("services", "processing time", ["how long", "gaano katagal", "processing time", "release"]),
}


def guides(commit: str) -> list[dict]:
    manifest = json.loads(raw(commit, "guides/manifest.json"))
    out = []
    for g in manifest:
        gid, md = g["id"], raw(commit, f"guides/{g['id']}.md")
        topic, topic_kw = TOPICS.get(gid, (g["title"], [g["title"]]))
        refs = re.findall(r"^\d+\.\s.*?\((https?://[^)\s]+)\)", md.split("## References")[-1], flags=re.M)
        agency = g["agency"]
        publisher = AGENCIES.get(agency.split(" · ")[0], agency)
        via = f"{SITE}/guides/{gid}"

        def add(key, category, label, text, extra):
            body, urls = plain(text)
            if not body:
                return
            nums = [int(n) for n in re.findall(r"\[(\d+)\]", body)]
            src = (refs[nums[0] - 1] if nums and nums[0] <= len(refs) else None) or (urls[0] if urls else None) or (refs[0] if refs else via)
            parts = split_chunks(body, MAX_CHARS - 120)
            for i, part in enumerate(parts, 1):
                suffix = f" (part {i} of {len(parts)})" if len(parts) > 1 else ""
                title = f"{topic[:1].upper() + topic[1:]}: {label}{suffix}"
                # Each word once: repeating the topic in every phrase inflates BM25 and buries more specific records.
                kws = unique_words(topic_kw + extra)
                out.append(record(f"bg-guide-{gid}-{key}" + (f"-{i}" if len(parts) > 1 else ""), category, title, part, kws,
                                  source=src, publisher=publisher, as_of=g["reviewedAt"], label="checked",
                                  agency=agency, via=via, commit=commit, tags=["bettergov", "guide", gid]))

        head, *sections = re.split(r"^## ", md, flags=re.M)
        # The lede: "> **What it costs:** ...", each label with the lines that follow it.
        for m in re.finditer(r"^> \*\*(.+?):\*\*(.*?)(?=^> \*\*|\Z)", head, flags=re.M | re.S):
            label = m.group(1).strip().lower()
            if label in LEDE:
                cat, lab, extra = LEDE[label]
                add(slug(label), cat, lab, f"{m.group(1)}: {m.group(2).strip()}", extra)
        for sec in sections:
            heading, _, text = sec.partition("\n")
            h = heading.strip()
            if h.lower() == "references":
                continue
            key = h.lower()
            base = "steps" if key.startswith("steps") else key
            cat, lab, extra = SECTIONS.get(base, ("services", h[:1].lower() + h[1:], ["how to", "paano"]))
            if base == "steps" and key != "steps":
                lab = h[:1].lower() + h[1:]
            add(slug(h), cat, lab, text, extra)
    return out


# ---- hotlines ---------------------------------------------------------------------------------------------------


def acronym(name: str) -> str | None:
    m = re.search(r"\(([A-Za-z][A-Za-z0-9-]{1,9})\)", name)
    return m.group(1) if m else None


def hotlines(commit: str) -> list[dict]:
    listed = file_date(commit, "philippines_hotlines.json")
    entries = [e for group in json.loads(raw(commit, "philippines_hotlines.json")).values() for e in group]
    entries += json.loads(raw(commit, "critical_hotlines.json"))
    seen, out = set(), []
    for e in entries:
        key = e["name"].lower()
        if key in seen:
            continue
        seen.add(key)
        nums = e["numbers"]
        first = re.sub(r"\s*\(.*?\)\s*$", "", nums[0])
        acr = acronym(e["name"])
        short = re.sub(r"\s*\(.*?\)", "", e["name"]).strip()
        body = (f"As listed by BetterGov.ph on {listed}, the {e['name']} can be reached at: {'; '.join(nums)}. "
                f"{e.get('description', '').strip()}")
        kws = [f"{short} hotline", f"{short} number", f"{short} contact", f"hotline ng {short}", f"numero ng {short}"]
        if acr:
            kws += [f"{acr} hotline", f"{acr} number", f"{acr} contact number", f"hotline ng {acr}", f"numero ng {acr}", acr]
        kws += [f"{e.get('category', '')} hotline", "hotline", "emergency number" if e.get("category") == "Emergency" else ""]
        out.append(record(f"bg-hotline-{slug(acr or short)}", "hotlines", f"{e['name']} hotline: {first}", body, kws,
                          source=f"{SITE}/philippines/hotlines", publisher="BetterGov.ph", as_of=listed, label="listed",
                          agency=acr, via=f"{SITE}/philippines/hotlines", commit=commit, tags=["bettergov", "hotline"]))
    return out


# ---- holidays ---------------------------------------------------------------------------------------------------

HOLIDAY = re.compile(
    r"\{\s*date: '(\d{4}-\d{2}-\d{2})',\s*name: (['\"])(.*?)\2,\s*type: '([a-z-]+)',\s*basis: (['\"])(.*?)\5,"
    r"(?:\s*note:\s*(['\"])(.*?)\7,)?\s*\}", re.S)
TYPES = {"regular": "regular holiday", "special-non-working": "special non-working day",
         "special-working": "special working day"}
TYPES_FIL = {"regular": "regular holiday", "special-non-working": "special non-working holiday", "special-working": "special working"}
# What people call holidays in Filipino or for short, so "pasko", "undas" or "araw ng kalayaan" finds them. The app's
# holiday lookup reads these names back from the record titles.
HOLIDAY_ALIASES = {
    "New Year's Day": ["Bagong Taon", "New Year"], "Christmas Day": ["Pasko", "Christmas"],
    "Christmas Eve": ["Bisperas ng Pasko", "Noche Buena"], "Last Day of the Year": ["Bisperas ng Bagong Taon", "New Year's Eve"],
    "Rizal Day": ["Araw ni Rizal"], "Bonifacio Day": ["Araw ni Bonifacio"], "Labor Day": ["Araw ng Paggawa"],
    "Independence Day": ["Araw ng Kalayaan"], "National Heroes Day": ["Araw ng mga Bayani"],
    "Maundy Thursday": ["Huwebes Santo"], "Good Friday": ["Biyernes Santo"], "Black Saturday": ["Sabado de Gloria"],
    "All Saints' Day": ["Undas", "Todos los Santos"], "All Souls' Day": ["Araw ng mga Kaluluwa", "Araw ng mga Patay"],
    "Chinese New Year": ["Bagong Taon ng mga Tsino"], "Araw ng Kagitingan": ["Day of Valor", "Bataan Day"],
    "Ninoy Aquino Day": ["Araw ni Ninoy Aquino"], "EDSA People Power": ["EDSA anniversary"],
    "Feast of the Immaculate Conception": ["Immaculate Conception"], "Eid'l Fitr": ["Eid al-Fitr", "Hari Raya Puasa"],
    "Eid'l Adha": ["Eid al-Adha", "Hari Raya Haji"], "Iglesia ni Cristo": ["INC anniversary"],
}
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]


def holidays(commit: str) -> list[dict]:
    ts = raw(commit, "holidays.ts")
    verified = re.search(r"HOLIDAYS_VERIFIED_ON = '(\d{4}-\d{2}-\d{2})'", ts).group(1)
    out = []
    blocks = re.split(r"\n  \{\n    year: ", ts)[1:]
    for block in blocks:
        year = int(block[:4])
        sources = re.findall(r"label: (['\"])(.*?)\1,\s*url: '([^']+)'", block, flags=re.S)
        days = HOLIDAY.findall(block)
        for date, _, name, typ, _, basis, _, note in days:
            d = dt.date.fromisoformat(date)
            src = next((u for _, lab, u in sources if lab.startswith(basis.split(" (")[0])), sources[0][2] if sources else f"{SITE}/philippines/holidays")
            long_date = f"{d.strftime('%A')}, {d.day} {MONTHS[d.month - 1]} {year}"
            aliases = next((v for k, v in HOLIDAY_ALIASES.items() if name.startswith(k)), [])
            known_as = f" ({', '.join(aliases)})" if aliases else ""
            body = f"{name}{known_as} falls on {long_date} ({date}). It is a {TYPES[typ]}. Basis: {basis}."
            if note:
                body += f" {note}"
            month = MONTHS[d.month - 1]
            kws = [name, *aliases, f"{name} {year}", f"kailan ang {name}", f"when is {name}", f"{name} date", f"kanus-a ang {name}",
                   f"holiday {month} {year}", f"{month} {year} holiday", f"{TYPES_FIL[typ]} {year}", f"walang pasok {month}"]
            out.append(record(f"bg-holiday-{date}-{slug(name)[:40]}", "holidays", f"{name}{known_as}: {date} ({TYPES[typ]})", body, kws,
                              source=src, publisher="Office of the President (proclamation)", as_of=verified, label="checked",
                              via=f"{SITE}/philippines/holidays", commit=commit, tags=["bettergov", "holiday", str(year)]))
        for typ in ("regular", "special-non-working"):
            picked = [(date, name) for date, _, name, t, *_ in days if t == typ]
            if not picked:
                continue
            lines = "\n".join(f"- {dt.date.fromisoformat(date).day} {MONTHS[int(date[5:7]) - 1]} ({date}): {name}" for date, name in picked)
            label = "Regular holidays" if typ == "regular" else "Special non-working days"
            body = f"{label} in the Philippines for {year}, as proclaimed:\n{lines}"
            src = sources[0][2] if sources else f"{SITE}/philippines/holidays"
            kws = [f"{label.lower()} {year}", f"holidays {year}", f"mga holiday {year}", f"listahan ng holiday {year}",
                   f"{TYPES_FIL[typ]} {year}", f"holiday list {year}", f"walang pasok {year}"]
            out.append(record(f"bg-holidays-{year}-{typ}", "holidays", f"{label} in {year}", body, kws,
                              source=src, publisher="Office of the President (proclamation)", as_of=verified, label="checked",
                              via=f"{SITE}/philippines/holidays", commit=commit, tags=["bettergov", "holiday", str(year)]))
    return out


# ---- directory --------------------------------------------------------------------------------------------------

DEPT_ACR = {"BUDGET AND MANAGEMENT": "DBM", "AGRICULTURE": "DA", "AGRARIAN REFORM": "DAR", "EDUCATION": "DepEd", "ENERGY": "DOE",
            "ENVIRONMENT AND NATURAL RESOURCES": "DENR", "FINANCE": "DOF", "FOREIGN AFFAIRS": "DFA", "HEALTH": "DOH",
            "HUMAN SETTLEMENTS AND URBAN DEVELOPMENT": "DHSUD", "INFORMATION AND COMMUNICATIONS TECHNOLOGY": "DICT",
            "THE INTERIOR AND LOCAL GOVERNMENT": "DILG", "JUSTICE": "DOJ", "LABOR AND EMPLOYMENT": "DOLE", "MIGRANT WORKERS": "DMW",
            "NATIONAL DEFENSE": "DND", "PUBLIC WORKS AND HIGHWAYS": "DPWH", "SCIENCE AND TECHNOLOGY": "DOST",
            "SOCIAL WELFARE AND DEVELOPMENT": "DSWD", "TOURISM": "DOT", "TRADE AND INDUSTRY": "DTI", "TRANSPORTATION": "DOTr",
            "ECONOMY, PLANNING, AND DEVELOPMENT": "DEPDev"}


# Filipino names for roles, so "Sino ang Bise Presidente?" or "sino ang kalihim ng DOH" finds the record.
ROLE_FIL = {"vice president": ["Bise Presidente", "Pangalawang Pangulo"], "president": ["Pangulo", "Presidente"],
            "secretary": ["Kalihim"], "chief justice": ["Punong Mahistrado"], "associate justice": ["Mahistrado"],
            "chairperson": ["Tagapangulo"], "chairman": ["Tagapangulo"], "director": ["Direktor"],
            "administrator": ["Administrador"], "commissioner": ["Komisyoner"], "governor": ["Gobernador"]}


def head_keywords(role: str, name: str, acr: str | None) -> list[str]:
    r = role.lower()
    fil = next((v for k, v in ROLE_FIL.items() if k in r), [])
    kws = [f"who is the {r} of {name}", f"sino ang {r} ng {name}", f"{name} {r}", f"{name} head", f"{name} contact", name, *fil]
    if acr:
        kws += [f"{acr} {r}", f"sino ang {acr} {r}", f"who is the {acr} {r}", f"{acr} head", f"{acr} address", f"{acr} contact number", acr]
    return kws


# What people call some offices, where the name or its initials would miss.
ALIASES = {"PHILIPPINE HEALTH INSURANCE CORPORATION": "PhilHealth", "HOME DEVELOPMENT MUTUAL FUND (PAG-IBIG)": "Pag-IBIG",
           "LAND BANK OF THE PHILIPPINES": "Landbank", "DEVELOPMENT BANK OF THE PHILIPPINES": "DBP",
           "PHILIPPINE AMUSEMENT AND GAMING CORPORATION": "PAGCOR", "PHILIPPINE CHARITY SWEEPSTAKES OFFICE": "PCSO"}


def short_name(name: str) -> str | None:
    """PhilHealth, the acronym in brackets, or the initials of the main words (GSIS, SSS)."""
    if name.upper() in ALIASES:
        return ALIASES[name.upper()]
    if a := acronym(name):
        return a
    words = [w for w in re.split(r"[\s-]+", re.sub(r"\(.*?\)", "", name)) if w and w.lower() not in {"of", "and", "the", "for", "on", "in"}]
    return "".join(w[0] for w in words).upper() if len(words) >= 2 else None


def contacts(d: dict) -> str:
    bits = []
    if d.get("address"):
        bits.append(f"Address: {d['address']}.")
    tl = d.get("trunkline") or "; ".join(d.get("trunklines") or [])
    if tl:
        bits.append(f"Trunkline: {tl}.")
    if d.get("email"):
        bits.append(f"Email: {d['email']}.")
    if d.get("website"):
        bits.append(f"Website: {d['website']}.")
    return " ".join(bits)


def directory(commit: str) -> list[dict]:
    out = []
    via = f"{SITE}/government"

    # Departments carry their own source page and check date.
    for d in json.loads(raw(commit, "directory/departments.json")):
        name = office(d["office_name"])
        acr = DEPT_ACR.get(d["office_name"].upper().removeprefix("DEPARTMENT OF ").strip())
        sec = d.get("secretary") or {}
        srcinfo = d.get("officials_source") or {}
        checked = srcinfo.get("checked") or file_date(commit, "directory/departments.json")
        label = "checked" if srcinfo.get("checked") else "listed"
        body = (f"As of {checked}, the {name}{f' ({acr})' if acr else ''} directory lists {person(sec['name'])} as {sec.get('role', 'Secretary')}. "
                if sec.get("name") else f"{name}. ") + contacts(d)
        out.append(record(f"bg-dept-{slug(acr or name)}", "officials", f"{name}{f' ({acr})' if acr else ''}: head and contacts", body,
                          head_keywords(sec.get("role", "Secretary"), name, acr) + ["secretary"],
                          source=srcinfo.get("url") or f"{SITE}/government/departments", publisher=name, as_of=checked, label=label,
                          agency=acr, via=f"{SITE}/government/departments", commit=commit, tags=["bettergov", "directory", "department"]))

    # Other executive offices: no verification date, so "listed" with the file's date.
    listed = file_date(commit, "directory/executive.json")
    for e in json.loads(raw(commit, "directory/executive.json")):
        name = office(e["office"])
        heads = [o for o in e.get("officials", []) if o.get("name")]
        acr = short_name(e["office"])
        if heads:
            h = heads[0]
            body = f"As listed by BetterGov.ph on {listed}, {person(h['name'])} is {h.get('role', 'head')} of the {name}. " + contacts(e)
            title, kws = f"{name}: {h.get('role', 'head')}", head_keywords(h.get("role", "head"), name, acr)
        else:  # most offices list contacts only
            body = f"As listed by BetterGov.ph on {listed}, the {name}{f' ({acr})' if acr else ''} can be contacted here. " + contacts(e)
            title = f"{name}{f' ({acr})' if acr else ''}: contacts"
            kws = [f"{name} contact", f"{name} address", f"{name} number", name] + (
                [f"{acr} contact number", f"{acr} address", f"{acr} email", f"numero ng {acr}", acr] if acr else [])
        out.append(record(f"bg-exec-{slug(name)[:50]}", "officials", title, body, kws, source=f"{SITE}/government", publisher="BetterGov.ph",
                          as_of=listed, label="listed", agency=acr, via=via, commit=commit, tags=["bettergov", "directory", "executive"]))

    # Congress: one record for each chamber's leaders.
    listed = file_date(commit, "directory/legislative.json")
    for c in json.loads(raw(commit, "directory/legislative.json")):
        chamber = c["chamber"]
        if c.get("officials"):
            leaders = [(o["role"], person(o["name"])) for o in c["officials"] if o.get("name")]
        else:
            hl = c.get("house_leaders") or {}
            leaders = []
            for key, v in hl.items():
                role = key.replace("_", " ").title().replace("Speakers", "Speaker")
                for p in (v if isinstance(v, list) else [v]):
                    if isinstance(p, dict) and p.get("name"):
                        leaders.append((role, person(p["name"])))
        if not leaders:
            continue
        senate = "Senate" in chamber
        short = "Senate" if senate else "House of Representatives"
        heads = [(r, w) for r, w in leaders if r != "Senator"]
        members = [w for r, w in leaders if r == "Senator"]
        lines = "\n".join(f"- {role}: {who}" for role, who in heads[:16])
        body = f"As listed by BetterGov.ph on {listed}, the leaders of the {chamber}:\n{lines}\n" + contacts(c)
        if senate:
            kws = ["Senate President", "sino ang Senate President", "who is the Senate President", "Senate President Pro Tempore", "Pangulo ng Senado",
                   "Senate Majority Leader", "Senate Minority Leader", "Senate leaders", "pangulo ng Senado", "Senado"]
        else:
            kws = ["Speaker of the House", "House Speaker", "sino ang Speaker", "who is the Speaker", "Deputy Speaker", "Ispiker", "Kapulungan",
                   "House Majority Leader", "House Minority Leader", "House leaders", "Kamara", "Congress"]
        out.append(record(f"bg-congress-{slug(short)}", "officials", f"{chamber}: leaders", body, kws,
                          source=f"{SITE}/government", publisher="BetterGov.ph", as_of=listed, label="listed",
                          via=via, commit=commit, tags=["bettergov", "directory", "legislative"]))
        if members:
            body = (f"As listed by BetterGov.ph on {listed}, the senators of the {chamber} besides its officers: "
                    + ", ".join(members) + ".")
            out.append(record("bg-congress-senators", "officials", f"{chamber}: senators", body,
                              ["senators", "list of senators", "mga senador", "sino ang mga senador", "Senate members", "senador"],
                              source=f"{SITE}/government", publisher="BetterGov.ph", as_of=listed, label="listed",
                              via=via, commit=commit, tags=["bettergov", "directory", "legislative"]))

    # Courts.
    listed = file_date(commit, "directory/judicial.json")
    for j in json.loads(raw(commit, "directory/judicial.json")):
        offs = [o for o in j.get("officials", []) if o.get("name")]
        if not offs:
            continue
        head = offs[0]
        others = ", ".join(person(o["name"]) for o in offs[1:16])
        body = (f"As listed by BetterGov.ph on {listed}, {person(head['name'])} is {head['role']} of the {j['name']}"
                f"{', appointed ' + head['date_of_appointment'] if head.get('date_of_appointment') else ''}. "
                + (f"Other members: {others}. " if others else "") + contacts(j))
        out.append(record(f"bg-court-{slug(j['name'])}", "officials", f"{j['name']}: {head['role']}", body[:MAX_CHARS],
                          head_keywords(head["role"], j["name"], None) + ["Chief Justice", "justices", "Supreme Court", "Punong Mahistrado", "Korte Suprema"],
                          source=f"{SITE}/government", publisher="BetterGov.ph", as_of=listed, label="listed",
                          via=via, commit=commit, tags=["bettergov", "directory", "judicial"]))

    # Constitutional bodies and government corporations (state universities are left out for now).
    listed = file_date(commit, "directory/constitutional.json")
    for c in json.loads(raw(commit, "directory/constitutional.json")):
        if "Universities" in (c.get("office_type") or ""):
            continue
        offs = [o for o in c.get("officials", []) if o.get("name")]
        if not offs:
            continue
        h = offs[0]
        acr = short_name(c["name"])
        name = office(re.sub(r"\s*\([^)]*\)\s*$", "", c["name"]).strip())
        full = f"{name}{f' ({acr})' if acr else ''}"
        body = (f"As listed by BetterGov.ph on {listed}, {person(h['name'])} is {h.get('role', 'head')} of the {full}. "
                + (f"{c['description'].strip()} " if c.get("description") else "") + contacts(c))
        out.append(record(f"bg-gov-{slug(name)[:60]}", "officials", f"{full}: {h.get('role', 'head')}", body[:MAX_CHARS],
                          head_keywords(h.get("role", "head"), name, acr), source=f"{SITE}/government", publisher="BetterGov.ph",
                          as_of=listed, label="listed", agency=acr, via=via, commit=commit, tags=["bettergov", "directory", "corporation"]))
    return out


# ---- history ---------------------------------------------------------------------------------------------------


def history(commit: str) -> list[dict]:
    """BetterGov.ph's Philippine history timeline (bettergov.ph/philippines/history), in English with the Filipino
    version, each entry with the sources BetterGov cites."""
    en = json.loads(raw(commit, "en/about-philippines.json", root="public/locales"))["history"]["entries"]
    fil = {e["id"]: e for e in json.loads(raw(commit, "fil/about-philippines.json", root="public/locales")).get("history", {}).get("entries", [])}
    ts = raw(commit, "heritage.ts")
    block = ts[ts.index("export const historySources"):]
    sources = {k: u for k, u in re.findall(r"(\w+): \{\s*title: '[^']*',\s*url: '([^']+)'", block)}
    listed = file_date(commit, "heritage.ts")
    out = []
    for e in en:
        f = fil.get(e["id"], {})
        body = f"{e['yr']}: {e['title']}. {e['body']}"
        if f.get("body") and f["body"] != e["body"]:
            body += f"\nSa Filipino: {f['yr']}: {f['title']}. {f['body']}"
        src = next((sources[s] for s in e.get("sources", []) if s in sources), f"{SITE}/philippines/history")
        kws = unique_words([e["title"], f.get("title", ""), e["yr"], "history", "kasaysayan", "Philippine history"])
        out.append(record(f"bg-history-{e['id']}", "history", f"{e['title']} ({e['yr']})", body[:MAX_CHARS - 150], kws,
                          source=src, publisher="BetterGov.ph", as_of=listed, label="listed",
                          via=f"{SITE}/philippines/history", commit=commit, tags=["bettergov", "history"]))
    return out


# ---- visas ------------------------------------------------------------------------------------------------------


def visas(commit: str) -> list[dict]:
    data = json.loads(raw(commit, "visa/philippines_visa_types.json"))
    updated = data.get("sourceInfo", {}).get("lastUpdated") or file_date(commit, "visa/philippines_visa_types.json")
    out = []
    for cat in data["categories"]:
        for v in cat["visaTypes"]:
            reqs = "; ".join(v.get("minimumRequirements", []))
            body = (f"As listed by BetterGov.ph (from the Bureau of Immigration, updated {updated}), the {v['name']} is one of the "
                    f"{cat['name'].lower()}: {v.get('description', '').strip().rstrip('.')}." + (f" Minimum requirements: {reqs}." if reqs else ""))
            code = v.get("id", "")
            kws = [v["name"], f"{code} visa", f"visa {code}", f"{v['name']} requirements", f"requirements for {v['name']}",
                   f"{cat['name']}", "Philippine visa", "Bureau of Immigration", "visa requirements"]
            out.append(record(f"bg-visa-{slug(code or v['name'])}", "requirements", f"{v['name']}: requirements", body, kws,
                              source=v.get("url") or f"{SITE}/travel/visa-types", publisher="Bureau of Immigration", as_of=updated,
                              label="listed", agency="BI", via=f"{SITE}/travel/visa-types", commit=commit, tags=["bettergov", "visa"]))
    return out


# ---- main -------------------------------------------------------------------------------------------------------


def check(records: list[dict]) -> list[str]:
    problems, ids = [], set()
    for r in records:
        if r["id"] in ids:
            problems.append(f"duplicate id {r['id']}")
        ids.add(r["id"])
        if len(r["content"]) > 1200:
            problems.append(f"{r['id']}: {len(r['content'])} characters (the app shows the model 1,200)")
        if not re.search(r"Source: https?://\S+ \((checked|listed) \d{4}-\d{2}-\d{2}\)\.$", r["content"]):
            problems.append(f"{r['id']}: no source line")
        if not r["keywords"] or not r["title"]:
            problems.append(f"{r['id']}: missing title or keywords")
    return problems


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--commit", help="BetterGov commit to read (default: latest main)")
    p.add_argument("--out", required=True)
    a = p.parse_args()
    commit = a.commit or latest_commit()
    if len(commit) < 40:
        commit = json.loads(fetch(f"https://api.github.com/repos/{REPO}/commits/{commit}"))["sha"]
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    sources = {"bettergov-guides": guides, "bettergov-hotlines": hotlines, "bettergov-holidays": holidays,
               "bettergov-directory": directory, "bettergov-visas": visas, "bettergov-history": history}
    total, problems, summary = 0, [], []
    for name, fn in sources.items():
        recs = fn(commit)
        problems += check(recs)
        (out / f"{name}.json").write_text(json.dumps(recs, ensure_ascii=False, indent=1) + "\n")
        summary.append(f"| {name}.json | {len(recs)} |")
        total += len(recs)
    (out / "SOURCES.md").write_text(
        f"# Sources\n\nHarvested from [{REPO}](https://github.com/{REPO}/tree/{commit}/src/data) at commit `{commit}` "
        f"on {dt.date.today().isoformat()}.\n\nContent: {LICENCE}. Facts come from the government pages linked in each record.\n\n"
        "| File | Records |\n|---|---|\n" + "\n".join(summary) + f"\n| **total** | **{total}** |\n")
    print(f"{total} records from {REPO}@{commit[:12]} -> {out}")
    for s in summary:
        print(" ", s)
    if problems:
        print(f"{len(problems)} problems:", *problems[:30], sep="\n  ")
        sys.exit(1)


if __name__ == "__main__":
    assert person('DY, FAUSTINO "BOJIE" G. III') == 'Faustino "Bojie" G. Dy III', person('DY, FAUSTINO "BOJIE" G. III')
    assert person("KIM ROBERT C. DE LEON") == "Kim Robert C. De Leon", person("KIM ROBERT C. DE LEON")
    assert person("ORTEGA, FRANCISCO PAOLO P., V") == "Francisco Paolo P. Ortega V", person("ORTEGA, FRANCISCO PAOLO P., V")
    assert plain("> **What it costs:** ₱160 total. [\\[1\\]](https://nbi.gov.ph/x.pdf)")[0] == "What it costs: ₱160 total. [1]"
    assert table_to_lines("| A | B |\n| --- | --- |\n| 1 | 2 |") == "- A: 1; B: 2"
    assert person("MARVIC M.V.F. LEONEN") == "Marvic M.V.F. Leonen" and person("FERJENEL G. BIRON MD") == "Ferjenel G. Biron MD"
    assert short_name("ANTI-RED TAPE AUTHORITY") == "ARTA" and office("ANTI-RED TAPE AUTHORITY") == "Anti-Red Tape Authority"
    assert short_name("GOVERNMENT SERVICE INSURANCE SYSTEM") == "GSIS" and short_name("PHILIPPINE HEALTH INSURANCE CORPORATION") == "PhilHealth"
    assert office("DEPARTMENT OF BUDGET AND MANAGEMENT") == "Department of Budget and Management"
    main()
