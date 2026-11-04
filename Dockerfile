# Этап 1: сборка фронта (Vite)
FROM node:22-slim AS build
WORKDIR /app

COPY package.json package-lock.json ./
# npm ci строго по lock-файлу; если lock собран на другой ОС и не подошёл — обычная установка
RUN npm ci || (rm -rf node_modules && npm install)

COPY . .
RUN npm run build

# Этап 2: раздача готовой статики через nginx (+ прокси запросов /api на бэкенд)
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
