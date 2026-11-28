import Link from 'next/link'

// 404.html: GitHub Pages serves it for an address the site has no page for
export default function NotFound(): React.ReactElement {
  return (
    <main className="not-found">
      <h1>Page not found</h1>
      <p className="lead">There is no page at this address.</p>
      <p>
        <Link href="/">Go to the home page</Link> or <Link href="/docs/">read the documentation</Link>.
      </p>
    </main>
  )
}
