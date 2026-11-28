'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'

/** A link of the sidebar, marked as the current page when it is the page shown */
export function NavLink({ href, children }: { href: string; children: React.ReactNode }): React.ReactElement {
  const current = usePathname() === href
  return (
    <Link href={href} aria-current={current ? 'page' : undefined}>
      {children}
    </Link>
  )
}
