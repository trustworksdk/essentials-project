export const customFetch = async <T>(url: string, options: RequestInit): Promise<T> =>
  (await (await fetch(url, options)).json()) as T
