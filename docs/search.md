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

| Question length (after dropping filler words) | Top score needed |
|---|---|
| One word ("pasko", "amilyar") | 10, and an exact or prefix match (a typo match alone doesn't count) |
| Two words ("sss hotline") | 15 |
| Three or more | 25, or 34 if the top record shares only one of the question's words (exactly or as a prefix) |

The last rule stops a single shared word from carrying a long question: "who won the basketball game last night?"
only shares "last" with "Last Day of the Year", so it finds nothing.

Measured on 462 records, before the history records were added: **227 of 228 real questions** (the capri questions, v3's keywords for them, and
common one-word topics) pass. Off-topic questions that still match share a real word with a record: "rice" finds the
Philippine Rice Research Institute, "weather" finds PAGASA. BM25 scores shift as the record set grows, so these are
re-measured when it changes a lot ([records pipeline](records-pipeline.md#re-measuring-search)).

### What the question asks for

Some question words say which kind of record is wanted. Among the candidates, records of that category get their score
multiplied by 1.5 (`SearchIndex.intents()`):

| Words in the question | Favours |
|---|---|
| magkano, tagpila, pila, mano, tagpira, magkanu, pigara, fee, bayad, presyo, price, cost, how much | fee records |
| hotline, helpline, number, numero, contact, tawag, call, phone | hotline records |
| requirements, kailangan, kinahanglan, kasapulan, documents | requirements records |
| born, ipinanganak, died, namatay, history, kasaysayan, founded, itinatag | history records |
| sino, who, kinsa, siasino | office-holder records |

So "magkano ang NBI clearance" finds the fee, not the NBI hotline or the steps, even though all three mention
"NBI clearance". And "Sino ang Pangulo ng Pilipinas?" finds who holds the office now, not the marker of a past
President whose title also says "Pangulo ng Republika ng Pilipinas".

The same words decide two other things: fee, hotline, office-holder and history words keep a question out of
[free chat](answering.md#3-when-the-records-have-nothing), and a "magkano" question lists fee records first when
Kuya J offers what it has.

### Historical markers are searched by title and keywords

The 1,635 NHCP marker texts are long and full of everyday Tagalog and Cebuano words ("araw", "tuig", "bayan"), which
caught unrelated questions. Markers are found by their title (a person's name, a place, an event) and keywords; the
answer still quotes the full text. Birth and death words in a question favour history records.

### Filler words

Words that say nothing about the topic are dropped before searching, in English and Filipino: *the, is, what, who,
how, about, ang, ng, sa, mga, ba, po, ko, mo...* (`SearchIndex.STOPWORDS`), as are one-letter words. Question words
(*magkano, tagpila, pila, kailan, sino, ano, unsa, paano...*) are dropped too, since they say what kind of answer is
wanted, not the topic ("pila" is also a town in Laguna); they still set the intent above. Accents fold to plain
letters, so "Jose" finds "JOSÉ". Without this, "the"
or "ng" added score to unrelated records.

## In tool mode

When the [v3 model](model-v3.md) searches, it writes English keywords ("NBI clearance fee") and the app runs this
same search on them. If the keywords find nothing, the app searches the original question too. With v3's keywords,
a record containing the answer is in the top 3 for **98 of 99** answerable test questions on all 2,114 records.

## Limits

- It matches words, not meaning: "how much to get cleared by the NBI" works because of shared words, but a question
  with none of a record's words won't find it. Keywords in each record cover the common phrasings.
- Each query word is compared against the whole vocabulary for typos. That's fine for a few thousand records; a
  trie would be needed well beyond that.

Tested in `OrchestratorTest.searchFindsTaglishQuestion` and `offTopicGetsFallbackButShortQueriesStillMatch`.
