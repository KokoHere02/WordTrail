FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Build against the same-origin nginx proxy, regardless of the repository's .env.
ENV VITE_API_BASE_URL=/api VITE_ENABLE_MOCK=false VITE_UI_PREVIEW=false
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
