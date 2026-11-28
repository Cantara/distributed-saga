import { NavLink } from '../../components/nav-link'
import { docLinks } from '../../lib/krantho'

/** The documentation: the sidebar of every page, by the groups the pages' frontmatter names; beside it, the page */
export default async function DocsLayout({ children }: { children: React.ReactNode }): Promise<React.ReactElement> {
  const links = await docLinks()
  const groups = [...new Set(links.map((link) => link.group))]
  return (
    <div className="site-body">
      <nav id="site-nav" className="sidebar" aria-label="Documentation">
        {groups.map((group) => (
          <section key={group}>
            <h2>{group}</h2>
            <ul>
              {links
                .filter((link) => link.group === group)
                .map((link) => (
                  <li key={link.href}>
                    <NavLink href={link.href}>{link.title}</NavLink>
                  </li>
                ))}
            </ul>
          </section>
        ))}
      </nav>
      <main>{children}</main>
    </div>
  )
}
