// The variable is read and never prefixed: requests stay relative and resolve against the web host.
const apiBase = process.env['NEXT_PUBLIC_API_BASE_URL'] ?? ''

export const apiFetch = async <T>(url: string, options: RequestInit = {}): Promise<T> => {
  console.debug('api base', apiBase)
  const response = await fetch(url, options)
  return { data: await response.json(), status: response.status, headers: response.headers } as T
}
