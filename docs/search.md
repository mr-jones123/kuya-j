# Search

`SearchIndex` (`shared/.../kb/SearchIndex.kt`) is a small full-text search engine that runs in memory on the phone.
It finds the records for a question in milliseconds, with no network and no database.

## How a record is scored

Each record has three searchable fields, weighted like Airgap's MiniSearch setup:

| Field | Weight | Why |
|---|---|---|
| `keywords` | 3 | Written to match how people ask |
| `title` | 2 | Says what the record is about |
| `content` | 1 | The full text |

Scores use **BM25**, the standard ranking formula: a word counts more when it's rare across records (IDF) and less
as a field gets longer. A record's score is the sum over the question's words and the three fields.

## Typos and partial words

People type fast and in many spellings, so each question word also matches:

| Match | Example | Counts as |
|---|---|---|
| Exact | `nbi` → `nbi` | 1.0 |
| Prefix | `clear` → `clearance` | 0.375 × (query length / term length) |
| Typo | `pasport` → `passport` | 0.45 × (query length / (query length + edits)); up to 1 edit per 5 letters |

Words are lowercased and split on anything that isn't a letter or digit, so "NBI-clearance?" becomes `nbi`,
`clearance`.

## When a match counts

Prefix and typo matching find *something* for almost any text ("pila" matches "pilipinas"), so the orchestrator
drops weak results (`Orchestrator.search()`):

- Questions of one or two words ("sss hotline") need a top score of at least **5**.
- Longer questions need at least **20**.

These thresholds were measured on the 27 built-in records: real questions scored 23 or more, off-topic ones 18 or
less. BM25 scores shift as the number of records grows, so re-measure them when bundles get bigger.

## In tool mode

When the [v3 model](model-v3.md) searches, it writes English keywords ("NBI clearance fee") and the app runs this
same search on them. If the keywords find nothing, the app searches the original question too. In the v3
evaluation, the model's keywords found the right record for 89 of 99 answerable questions.

## Limits

- It matches words, not meaning: "how much to get cleared by the NBI" works because of shared words, but a question
  with none of a record's words won't find it. Keywords in each record cover the common phrasings.
- Each query word is compared against the whole vocabulary for typos. That's fine for a few thousand records; a
  trie would be needed well beyond that.

Tested in `OrchestratorTest.searchFindsTaglishQuestion` and `offTopicGetsFallbackButShortQueriesStillMatch`.
