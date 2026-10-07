// Next.js inlines NEXT_PUBLIC_* at build time where the code reads process.env.NEXT_PUBLIC_… literally.
const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL ?? ''

export const apiFetch = async <T>(url: string, options: RequestInit = {}): Promise<T> => {
  const response = await fetch(apiBase + url, options)
  const text = await response.text()
  return { data: text ? JSON.parse(text) : undefined, status: response.status, headers: response.headers } as T
}
