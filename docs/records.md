# Records

Kuya J answers only from **records**: short, dated, sourced statements of fact, each written from an official
page.

## The record format

Records live in JSON arrays, one file per category (`androidApp/src/main/assets/kb/fees.json` and so on). The
format is the same as an [Airgap](https://github.com/xmpuspus/airgap) bundle document.

```json
{
  "id": "fee-002",
  "category": "fees",
  "title": "Fees and validity for an NBI clearance",
  "content": "As of 2026-10-07, the NBI how-to page states that the basic clearance fee is PHP 130. The page adds an e-payment service charge. The official page does not state the validity period of the clearance. Check the amount on the portal before you pay. Source: https://nbi.gov.ph/how-to-apply-nbi-clearance/ (checked 2026-10-07).",
  "keywords": ["NBI clearance fee", "magkano ang NBI clearance", "bayad sa NBI", "how much is NBI clearance"],
  "tags": ["nbi", "clearance", "fees"],
  "metadata": {
    "source": "https://nbi.gov.ph/how-to-apply-nbi-clearance/",
    "publisher": "National Bureau of Investigation",
    "asOf": "2026-10-07",
    "agency": "NBI"
  }
}
```

| Field | Required | Used for |
|---|---|---|
| `id` | yes | Stable identity, used in logs, tests and evals |
| `category` | yes | The answer card's label, icon and colour; which key value it shows |
| `title` | yes | Shown to the model and searched (weight 2) |
| `content` | yes | What the model reads (first 1,200 characters) and what's shown when no model answers; searched (weight 1) |
| `keywords` | yes | Search phrases in English and Filipino, never shown (weight 3) |
| `tags` | no | Grouping |
| `metadata.source` | no | The source link on the answer card |
| `metadata.publisher` | no | "Source · National Bureau of Investigation" |
| `metadata.asOf` | no | "checked 2026-10-07" on the card |
| `metadata.agency` | no | "Fee · NBI" on the card |
| `metadata.verbatim` | no | `true` = always show as written, never reworded by the model |
| `metadata.dateLabel` | no | `checked` (default) or `listed`: the word before the date on the card |

`KbRecord.parseArray()` rejects a record that is missing a required field or has the wrong type, so a broken
file fails loudly instead of answering wrongly.

### Writing a good record

- **Start with "As of <date>,"** and name the page: the model copies the date, so people know how fresh the fact is.
- **State what the page doesn't say** ("The official page does not state the validity period"): that lets the model
  say "no record" instead of guessing.
- **End with `Source: <url> (checked <date>).`** The model ends its answer with this line; the app hides it from the
  text because the card already shows it.
- **Keywords are how people ask**, including Tagalog phrasing: "magkano ang NBI clearance", "bayad sa NBI".
- **Keep it under 1,200 characters**; the model sees only that much.

## The built-in records

**2,114 records** ship inside the app, so everything works offline from the moment it's installed:

| Files | From | Records |
|---|---|---|
| `bettergov-guides.json` | BetterGov.ph's 19 step-by-step guides, one record per section, text copied word for word | 158 |
| `bettergov-directory.json` | Department heads, executive offices, Congress leaders, the Supreme Court, government corporations | 182 |
| `bettergov-holidays.json` | Every holiday for 2025-2027 from the proclamations (with Filipino names), plus yearly lists | 69 |
| `bettergov-visas.json` | Visa types and their requirements | 23 |
| `bettergov-hotlines.json` | Emergency and agency hotlines | 19 |
| `bettergov-history.json` | BetterGov.ph's Philippine history timeline, early peoples to BARMM | 17 |
| `nhcp-markers.json` | The NHCP registry of historical markers: marker text word for word, with location, year and installer; person markers tagged with the person and life years | 1,635 |
| `fees.json`, `hotlines.json`, `officials.json`, `services.json` | Airgap's government-services example, below | 11 |
| `VERSION` | The records' publish version ([why](sync.md#built-in-records-come-first-when-theyre-newer)) | |

The BetterGov.ph records are made by the [records pipeline](records-pipeline.md), which also publishes them for
[record sync](sync.md). Their extra metadata: `dateLabel` ("checked" or "listed", see the pipeline page), `via`
(the BetterGov.ph page), `licence` and `bettergovCommit`.

### Airgap's records

Kuya J started with 27 records from Airgap, each written from an official page. On 2026-11-26 the 16 that
BetterGov.ph's data covers, usually newer and in more detail, were retired: the NBI and passport fees, requirements
and how-tos; the four holiday records; the 911 hotline; and the President, Vice President, Senate President, Speaker
and Chief Justice. The 11 kept have no BetterGov.ph equivalent:

| Category | Records kept |
|---|---|
| fees, services | SS number fee and online registration |
| hotlines | 8888, PSAHelpline (02) 8737-1111, SSS 1455, BIR, NBI (dedicated hotline cards with a Call button) |
| officials | DICT Secretary's resignation (dated release), Executive Secretary, how the President is elected and the term's end, a dated Palace statement |

They were last checked in October 2026. Their search keywords were trimmed to each word once, like the harvested
records'.

## Where records come from at runtime

```mermaid
flowchart LR
    A[(assets/kb/*.json<br/>built in)] --> S[KnowledgeStore]
    B[(Verified bundle<br/>files/kb/bundle-current.json)] -- replaces --> S
    S --> I[SearchIndex]
```

`KnowledgeStore` holds either the built-in set or the last verified bundle (a bundle replaces the whole set, it
doesn't merge). A swap is a single write, so a search
never sees half of one and half of the other. If a bundle fails to load, the store falls back to the built-in
records.
