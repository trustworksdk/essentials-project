// Left from an earlier client; orval.config.ts no longer names it.
const BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? ''
export const customFetch = async <T>(url: string, options: RequestInit): Promise<T> =>
  (await (await fetch(`${BASE}${url}`, options)).json()) as T
