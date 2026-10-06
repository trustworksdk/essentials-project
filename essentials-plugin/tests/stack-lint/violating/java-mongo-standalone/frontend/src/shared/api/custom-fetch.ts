// VITE_API_BASE_URL is declared in .env.example and read nowhere: the requests stay relative
// and resolve against the static host.
export const customFetch = async <T>(url: string, options: RequestInit): Promise<T> => {
  const response = await fetch(url, options)
  return (await response.json()) as T
}
