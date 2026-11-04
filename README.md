# Подключение фронта и бэкенда

- `backend/`: Spring Boot + PostgreSQL + Gradle + симулятор Вани (папка `backend/simulator/`). Запуск: `docker compose up --build`.
- `front/`: React + Vite, подключён к бэкенду. Запуск: `npm install`, `npm run dev`, затем http://localhost:5173.

Подробности: `backend/README.md`, `front/CHANGES-backend-integration.md`.
