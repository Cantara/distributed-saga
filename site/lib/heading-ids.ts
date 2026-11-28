const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', '#39': "'", apos: "'" }

/** The text of a heading's HTML: tags removed, entities decoded */
function textOf(html: string): string {
  return html
    .replace(/<[^>]*>/g, '')
    .replace(/&(amp|lt|gt|quot|#39|apos);/g, (_, name: string) => ENTITIES[name] ?? '')
}

/**
 * A heading's anchor as GitHub makes it: lower case, punctuation removed,
 * spaces as hyphens. `## 7. Recover unfinished sagas` → `7-recover-unfinished-sagas`
 */
export function slugOf(text: string): string {
  return text
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\p{M}\s_-]/gu, '')
    .replace(/\s/g, '-')
}

/**
 * Gives each heading in rendered markdown the id GitHub gives it, so a link
 * such as using.md#7-recover-unfinished-sagas lands on the same section on the
 * site as on GitHub. A repeated heading gets -1, -2, … as on GitHub.
 */
export function withHeadingIds(html: string): string {
  const seen = new Map<string, number>()
  return html.replace(/<h([1-6])>([\s\S]*?)<\/h\1>/g, (_, level: string, inner: string) => {
    const base = slugOf(textOf(inner))
    const count = seen.get(base) ?? 0
    seen.set(base, count + 1)
    const id = count === 0 ? base : `${base}-${count}`
    return `<h${level} id="${id}">${inner}</h${level}>`
  })
}
