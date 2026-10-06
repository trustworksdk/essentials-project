import { defineConfig } from 'orval'

export default defineConfig({ api: {
  input:  { target: '../contracts/openapi.json' },
  output: { target: './src/shared/api/generated', schemas: './src/shared/api/model',
            override: { mutator: { path: './src/shared/api/custom-fetch.ts', name: 'customFetch' } } } } })
