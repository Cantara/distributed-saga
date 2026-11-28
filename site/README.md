# Documentation site

The site at <https://cantara.github.io/distributed-saga/>, built with [krantho](https://krantho.com) and Next.js to
static files: a landing page, and the documentation under `/docs/`.

| Address | Page | Source |
|---|---|---|
| `/` | The landing page | `content/landing/home.md`, `app/page.tsx` |
| `/docs/` | The documentation's home page | `../docs/README.md` |
| `/docs/<file name>/` | A documentation page | `../docs/<file name>.md` |
| `/docs/modules/<file name>/` | A module page | `../docs/modules/<file name>.md` |

## The documentation

The pages are the markdown files in [docs/](../docs/README.md). The files read the same on GitHub; links between them
become links between pages, and every heading gets the id GitHub gives it, so `using.md#7-recover-unfinished-sagas`
leads to the same place on both. Each file starts with frontmatter that the site reads:

```yaml
---
title: "Topology"
description: "One sentence: the page's summary for search engines and link previews."
group: Overview          # the sidebar group: Overview, Guides or Reference (module pages: Modules)
order: 3                 # the page's place in its group
---
```

## Figures

The figures of the documentation are SVG files in `docs/images/`, drawn by `site/diagrams/generate.mjs` in one style:
the topology figures are laid out in that script, and the sequence diagrams of Sequence flows are drawn from their
source in `site/diagrams/sequences/*.mmd`, in Mermaid's sequence syntax. A page shows one with an image link,
`![The shape of a saga](images/saga-shape.svg)`. Change a figure in the script or its source, never in the SVG, and
draw them again:

```bash
node site/diagrams/generate.mjs
```

Each SVG carries its own light and dark palette, so GitHub shows it in the reader's system theme. The site puts it
into the page instead, without that palette, and colours it by the theme the reader picked (`.diagram` in
`globals.css`); a click opens it large. A ```mermaid block in a page still works, drawn in the browser; Mermaid ends a
statement at `;`, so its text must not contain one.

Other code blocks are coloured as GitHub colours them, and get a copy button. A comment `<!-- label: ... -->` right
before a block labels it.

## The landing page

`content/landing/home.md` holds it. Its frontmatter is the hero (`eyebrow`, `headline`, `subline` and the two
buttons). Each `##` section is a row below it, in the order of `sections` in `krantho.collections.ts`, and a section
missing from that list is not shown. How a section is laid out follows from what it holds (`app/page.tsx`):

- text and code blocks: the text beside the code;
- `###` headings: the text over the width, then a column per heading;
- a section named in `figureOf`: the text beside its animated figure (`components/figures.tsx`);
- a pair named in `pairOf`: two sections side by side.

The figures are stories, told one tick at a time: each tick is one write to the saga log, or one event that writes
nothing (the process stops, or starts again), with a caption that says what happened and why. A step's card is drawn
from its last entry in the log, so the steps and the log always agree. A figure plays when it comes into view, and
its numbered buttons go to a tick. A story is data in `components/figures.tsx`: its nodes, and per tick the entry
written, the caption and any words under a card. Keep the stories true to [Protocols](../docs/protocols.md) and
to the code when they change.

## Layout and themes

`app/globals.css` starts with the tokens, and says which viewports the layout is made for: phones (the sidebar behind
the menu button, the header's links on a row of their own), tablets, laptops (the page's contents beside it from
1344 px) and large screens (the type grows from 1920 px, so 4K and 5K windows show the same page, larger).

The header's switch picks the theme: as the system says, light or dark. The choice is kept in the browser
(`localStorage`) and applied before the page is painted (`components/theme-switch.tsx`).

## Logo and icon

Cantara's mark in the header is traced from Cantara's GitHub avatar into two inks, `public/cantara-mark-paths.svg`.
The header draws each ink with `<use>`, coloured by `--cantara-red` and `--cantara-maroon` in `globals.css`, once for
light and once for dark. `public/cantara-mark.svg` and `public/cantara-logo.svg` (with the taglines) are the same
traces as standalone files, with their palette in their own `<style>`.

The icons are drawn from the same traces. Their sources are in `icons/`:
- `icon.svg`: Cantara's arcs on white, thickened a little to hold at 16 px. It is the browser tab's icon
  (`public/icon.svg`) and, at 16, 32 and 48 px, `public/favicon.ico`.
- `icon-full.svg`: the whole mark. It is the home-screen icon of a phone (`public/apple-touch-icon.png`, 180 px) and
  the web app's icons (`public/icons/icon-192.png`, `icon-512.png`).
- `icon-maskable.svg`: the whole mark inside the safe circle, for Android's shaped icons
  (`public/icons/icon-maskable-512.png`).

The PNG files are those SVG files rendered at their sizes, in a browser. `app/layout.tsx` links them, and
`app/manifest.ts` lists them in `manifest.webmanifest`.

## Build

Node 24 and pnpm (the version in `package.json`):

```bash
cd site
pnpm install
pnpm dev                 # http://localhost:3000
pnpm build               # the site, in out/
```

`KRANTHO_SITE_BASE_URL=https://cantara.github.io/distributed-saga pnpm build` builds it as Pages serves it, under
`/distributed-saga`.

## Publish

`.github/workflows/pages.yml` builds the site on a pull request that changes `docs/` or `site/`, and builds and
publishes it to GitHub Pages when they change on `main`. The repository's Pages setting publishes from GitHub
Actions, and only `main` may deploy.
