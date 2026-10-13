export const accessMode = (import.meta.env.VITE_CONSOLE_AUTH_MODE || 'access') === 'access'

/** Only navigate within this app; query parameters must never become external login redirects. */
export function consoleReturnPath(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')
      || /[\\\\\p{Cc}]/u.test(value)) return '/'
  const url = new URL(value, window.location.origin)
  if (url.origin !== window.location.origin || url.pathname === '/login'
      || url.pathname.startsWith('/cdn-cgi/')) return '/'
  return url.pathname + url.search + url.hash
}

export function accessLogoutPath() { return '/cdn-cgi/access/logout' }
