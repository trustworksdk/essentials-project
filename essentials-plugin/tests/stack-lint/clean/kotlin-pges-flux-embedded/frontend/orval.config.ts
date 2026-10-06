import { defineConfig } from 'orval'

export default defineConfig({ api: {
  input:  { target: '../contracts/openapi.json' },
  output: { mode: 'tags-split', target: './src/shared/api/generated',
            schemas: './src/shared/api/model', client: 'react-query', httpClient: 'fetch',
            clean: true, override: { mutator: {
              path: './src/shared/api/custom-fetch.ts', name: 'customFetch' } } } } })
