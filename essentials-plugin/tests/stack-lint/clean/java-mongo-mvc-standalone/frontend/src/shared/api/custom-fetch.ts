// Standalone: the base URL is read at build time through a function and prefixes every request.
const baseUrl = (): string => import.meta.env.VITE_API_BASE_URL ?? ''

export const customFetch = async <T>(url: string, options: RequestInit): Promise<T> => {
  const response = await fetch(`${baseUrl()}${url}`, options)
  return (await response.json()) as T
}
