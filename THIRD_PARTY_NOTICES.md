# Third-party notices

Kuya J's own code is licensed under the MIT License in `LICENSE`. The parts below keep their own terms and are
not relicensed by this repository.

## Airgap (MIT)

Kuya J's request path, safety layer, record format, search weights and signed-sync design are ported from
[Airgap](https://github.com/xmpuspus/airgap), and the 27 built-in records in
`androidApp/src/main/assets/kb/` are copied from Airgap's government-services example.

```
MIT License

Copyright (c) 2026 Xavier Puspus

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Gemma chat template

`androidApp/src/main/assets/gemma4_tool_template.jinja` is Gemma 4's chat template, extracted from the model and
patched to pass tool results through (see the comment marked "Kuya J patch"). Gemma is provided under the
[Gemma Terms of Use](https://ai.google.dev/gemma/terms). The model itself is not in this repository.

## Figtree font (SIL Open Font License 1.1)

`shared/src/commonMain/composeResources/font/figtree.ttf` is [Figtree](https://github.com/erikdkennedy/figtree)
by Erik Kennedy, licensed under the SIL Open Font License, Version 1.1 (https://openfontlicense.org).

## BetterGov.ph brand assets

The BetterGov.ph logos and the Kuya J mascot (`bettergov_horizontal*.png`, `kuya_j_mascot.png`) identify the
project. The software licence does not grant trademark rights or permission to imply endorsement. Contact
BetterGov.ph for brand reuse; forks should use their own branding.

## Libraries

Dependencies (Compose Multiplatform, Compose Unstyled, LiteRT-LM, Bouncy Castle, kotlinx, AndroidX) are fetched by
Gradle and keep their own licences; see each package's metadata.

## Public records

Facts in the records come from Philippine government websites, linked in each record's `source`. Government
works are not subject to copyright in the Philippines (Republic Act 8293, section 176).
