import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Запросы /api/... уходят на бэкенд (Spring Boot, порт 8080),
    // поэтому в коде фронта адрес бэкенда писать не нужно, а CORS не мешает.
    proxy: { '/api': 'http://localhost:8080' },
  },
  // то же для `npm run preview`
  preview: {
    proxy: { '/api': 'http://localhost:8080' },
  },
})
