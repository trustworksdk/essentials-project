import { defineConfig } from 'orval'

// Trap: the mutator is not called custom-fetch.ts here, and the client is not under src/shared/api.
export default defineConfig({
  api: {
    input: { target: '../contracts/openapi.json' },
    output: {
      mode: 'tags-split',
      target: './lib/api/generated',
      schemas: './lib/api/model',
      client: 'react-query',
      httpClient: 'fetch',
      override: {
        mutator: { path: './lib/api/mutator.ts', name: 'apiFetch' },
      },
    },
  },
})
