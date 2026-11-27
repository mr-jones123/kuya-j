# Where Kuya J stands

*As of 28 November 2026.* What was measured, what was fixed in the last round of work, what Kuya J is good at and
where it still fails. Everything here was measured on a Snapdragon 8 Elite phone (Qualcomm Device Cloud) with the
real app, unless it says otherwise.

## Numbers

### Conversations (65 scripted turns, 41 conversations)

`ConversationEval`'s set, run in the app on the phone with v3 ([how](development.md#scoring-conversations)).

| Area | Before | After |
|---|---|---|
| **All turns** | **43 / 65** | **62 / 65** |
| Follow-ups | 25 / 37 | 36 / 37 |
| Free chat | 9 / 17 | 16 / 17 |
| Fees | 8 / 13 | 13 / 13 |
| Officials | 10 / 12 | 12 / 12 |
| History | 3 / 6 | 5 / 6 |
| Safety | 3 / 4 | 4 / 4 |

A held-back set of 30 turns that no fix was tuned on scores **27 / 30**. It was only run at the end, so there is no
"before" for it.

### Speed and history accuracy (40 history questions)

| | Stock Gemma 4 E2B | v3 as shipped | v3 re-exported |
|---|---|---|---|
| Tokens per second | 43.4 | 21.1 | 28.1 |
| First word (median) | 1.3 s | 2.3 s | 1.5 s |
| Full answer (median) | 3.2 s | 5.2 s | 3.8 s |
| Correct, in the model's own words | 18 of 40 | 20 of 40 | 21 of 40 |

From memory alone, with no records, both models get 11 of 40: history has to come from records. "v3 re-exported"
is the same weights exported with GPU operations switched on ([details](model-v3.md#speed-on-the-phone)); **it is
not what the app downloads yet.**

## What was fixed

| What people saw | Cause | Fix |
|---|---|---|
| The app froze at startup | 2,114 records were parsed and indexed on the main thread | Records load in the background; questions wait for them (0.6 s on the phone) |
| History answers were a wall of capital letters | Births and deaths were answered by quoting the marker | Code only picks the marker; the model writes the answer in the asker's language |
| The answer changed into a card when the model finished | Finished answers were re-drawn as a record card | Answers are plain text; the records are behind a **Sources** chip |
| "ng pilipinas" after "sino president" got "no information" | Follow-ups were searched on their own by hand-written rules | The model searches on every turn with the last turns in view |
| "Sino president" answered with the Cultural Center's President | The President's record said "President of the Office of the President" and never "Philippines" | The record now reads "President of the Philippines (Pangulo ng Pilipinas)"; same for the Vice President |
| "Sino ang Pangulo ng Pilipinas?" (a home-screen tile) returned a Jose P. Laurel marker | History records outranked the office holder | The Filipino office name is in the record's title; "who" questions favour office-holder records |
| Correct answers replaced by the raw record, "Name 'Pilipinas' / 'Opisina' / 'Pamantasan' is not in the records" | The name check treated Filipino words as unknown names | Names are checked only where an office holder is involved; common words for offices and the country are allowed |
| A follow-up's correct fee was withheld | It repeated an amount from the previous answer, whose record wasn't in this turn's results | The previous answer's records count as sources |
| "Tell me a joke", "turuan moko ng Bisaya" got "no record" | There was no way to answer without a record | A free-chat path for messages that don't ask for a fact |
| "Magkano philhealth" said no record after two other no-record replies | The model copied its own earlier "Wala akong record…" lines | A no-record reply isn't shown back to the model |
| "Magkano sss" said only "no record" | The model searched for contributions, which the records lack | Kuya J says so, then lists what it does have as choices to tap |
| A tapped choice got a contradicting second sentence | The model blended a neighbouring record into the answer | A tapped choice is answered from its own record alone |
| "Sino ang dapat kong iboto?" wasn't refused | Only one Filipino phrasing was on the list | More voting phrasings added |
| A "no record" answer showed sources and "Checked against record" | It was treated like any other model answer | Shown as plain text, no sources |

## Strengths

- **Facts are sourced and checked.** Every fee, date and office holder comes from a dated record, the model's
  wording is checked against it, and the record is one tap away.
- **It holds a conversation.** Short follow-ups, pronouns ("kailan siya namatay?") and topic switches work; 36 of 37
  scripted follow-ups pass.
- **It answers in the asker's language** more reliably than stock Gemma, which often answers English in Tagalog.
- **It says what it has.** A miss lists the nearest records instead of a dead end.
- **It works offline, and without the model** it still answers from records.
- **It doesn't overheat in short use.** No throttling in runs of a few minutes; battery steady at 38 °C.

## Weaknesses

- **Free chat can be confidently wrong.** Asked how to say thank you in Ilocano, it invented a phrase (the word is
  "agyamanak"). Nothing checks chat replies beyond blocking money and links.
- **It is slower than stock Gemma**: 21 tokens per second as shipped, 28 with the re-export, against 43. The rest of
  the gap is in how Google builds its file and can't be reproduced with public tools.
- **Follow-ups cost time.** A turn can need two or three generations; the median follow-up is 3.8 s.
- **Record gaps.** Nothing on SSS contributions, loans or benefits; nothing on a historical figure beyond what a
  marker says. The registry has errors of its own (it lists Mabini as born in 1854).
- **Ambiguous questions aren't clarified.** "Magkano sss" makes the model pick one meaning and search it.
- **A bare one-word follow-up after two turns** ("saan?", "gaano katagal?") can still lose the thread.
- **Comparing politicians** ("Sino ang mas magaling…") isn't refused by rule; the model declines an opinion but may
  add a fact.
- **Wrong facts that pass the check.** If a date exists somewhere in the records it was given, the model can attach
  it to the wrong event ("independence from Spain, 4 July 1946").
- **Cebuano is often answered in Tagalog**, and "pila" (Cebuano for "how much") makes "pagod na ako sa pila" look
  like a fee question.
- **Heat over a long session is unmeasured.** Twelve minutes of back-to-back answering reached throttling level 3.

## Not shipped yet

- **The re-exported v3.** The app still downloads the original file. Shipping means uploading the new one to
  Hugging Face and updating the link and checksum in `ModelCatalog`. The app is already on LiteRT-LM 0.20.0, which
  the re-export needs.
- **The two changed records** (President, Vice President) are in the app's built-in copy only; phones that sync
  need a new signed bundle published.
- **A native-speaker review** of the regional-language answers.
