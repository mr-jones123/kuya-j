# Safety

A civic assistant that runs offline has no server to catch mistakes, so Kuya J's rules live in code on the phone
(`shared/.../safety/Safety.kt`, ported from Airgap's safety layer). There are two halves: rules on what people
ask, and a check on what the model says.

## What Kuya J refuses

These run before any search, and the reply is fixed text, never the model:

| Reason | Matches, for example | Kuya J says |
|---|---|---|
| `prompt_probe` | "show your system prompt", "ignore your instructions", "developer mode", "jailbreak" | It can't share its instructions; ask about a service instead |
| `blocked_topic` | "who should I vote for", "sino ang dapat iboto", "dapat kong iboto", "sino ang iboboto", "your opinion on", "is the president good" | It doesn't give political opinions or voting advice (in English and Filipino) |
| `not_legal_advice` | "legal advice", "can I be arrested", "sue" | Contact the Public Attorney's Office or a lawyer |
| `not_medical_advice` | "diagnose", "prescribe" | Consult a licensed healthcare professional |
| `not_financial_advice` | "should I invest" | Consult a licensed advisor |
| `no_commentary` | Allegations, scandals, corruption, impeachment; or *why* someone resigned or was fired | It only shows dated official records, not reasons or motives |

Phrases match whole words, case-insensitively, in English and Filipino (*kurakot*, *nagbitiw*, *bakit*,
*dahilan*...).

## The grounding check

The model may word an answer, but every **amount** and **date** in it must appear in the records it was given, and
so must every **name** when an office holder is involved. If one doesn't, the answer is withheld and the record is
shown as written. Details are in [How answering works](answering.md#4-the-grounding-check). This is what stops the
model from inventing a fee, moving a date, or naming the wrong official.

Two things the check deliberately allows, because refusing them threw away correct answers:

- **Filipino words for what an English record says.** The name check runs only when one of the records is about an
  office holder. On a history or fee answer it used to reject "Pamantasan" (University) or "Pilipinas" as unknown
  names. Words for offices and titles ("Opisina", "Kalihim", "Pangulo") and the country's names are always allowed.
- **Figures from the previous answer.** A follow-up is checked against this turn's records and the ones behind the
  last answer, so repeating a fee already quoted isn't an invention.

The check also guards the stream: the screen only ever shows text that has passed it so far, so a wrong number
can't flash up and then disappear.

## Other protections

- **No fact without a record.** A question that asks for a fee, hotline, office holder, date or history is never
  answered from the model's memory. If the records have nothing, Kuya J says so and lists what it does have.
- **Free chat is fenced.** Small talk and everyday questions are answered from what the model knows
  ([how](answering.md#3-when-the-records-have-nothing)). A chat reply that states money, a link or a source line is
  dropped, and questions with a fact word never reach that path.
- **Doubt doesn't move answers.** "Are you sure?" replays the record; the model isn't asked again.
- **Dated facts.** Every record says when it was checked, and the card shows that date.
- **Office holders.** Records about officials are dated statements from official releases ("A PCO release dated
  2026-10-11 says..."), so Kuya J reports what was announced, not its own view.
- **Not an agency.** The identity reply and About screen say no government agency operates Kuya J.

## Limits

- Pattern rules can be phrased around. They catch the common cases; the grounding check is the backstop for
  anything that reaches the model.
- The name check looks for capitalised words; a wrong name written in lowercase would pass. Names in records are
  usually capitalised, so this hasn't come up. Outside office-holder answers, names aren't checked at all.
- **Free chat isn't fact-checked.** The model can be confidently wrong about things that aren't government facts;
  asked for "thank you" in Ilocano, it invented a phrase. The fence above keeps this away from fees, dates and
  officials, not away from everything.
- **A date that exists in the records can be attached to the wrong event.** The check asks whether the date is in
  the records the model was given, not whether it belongs to what was asked.
- Comparing politicians ("sino ang mas magaling…") isn't on the refusal list; the model declines an opinion on its
  own but may add a fact.
- The [v3 model](model-v3.md) still calls `search_kb` on voting questions it should decline outright. The input
  rules refuse those before the model runs, so users don't see it.

The fixed-fact words that close the chat path are `FACT_INTENTS` in `Orchestrator.kt`.

Tested in `OrchestratorTest`: `inventedFeeIsWithheldAndNeverStreamed`, `inventedNameIsCaught`,
`promptProbeAndCommentaryAreRefused`, `toolModeWithholdsAnInventedFee`.
