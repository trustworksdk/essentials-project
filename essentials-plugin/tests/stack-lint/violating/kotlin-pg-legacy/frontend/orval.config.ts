import { defineConfig } from 'orval'

export default defineConfig({ api: {
  input:  { target: '../contracts/openapi.json' },
  output: { target: './src/shared/api/generated', schemas: './src/shared/api/model' } } })
