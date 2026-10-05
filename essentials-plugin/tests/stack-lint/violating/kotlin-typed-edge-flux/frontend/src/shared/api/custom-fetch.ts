// Embedded mode: the base URL is empty and every path stays relative. Reading the variable with
// a '' fallback is the documented shape for both modes, so it is not an embedded-mode finding.
const BASE = import.meta.env.VITE_API_BASE_URL ?? ''

export const customFetch = async <T>(url: string, options: RequestInit): Promise<T> => {
  const response = await fetch(`${BASE}${url}`, options)
  return (await response.json()) as T
}
