# --- builder ---
FROM node:20-alpine AS builder
ARG VITE_API_URL=/api/v1
ARG VITE_APP_VERSION=dev
ENV VITE_API_URL=${VITE_API_URL} VITE_APP_VERSION=${VITE_APP_VERSION}
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY frontend/ .
RUN npm run build

# --- runtime ---
FROM nginx:1.27-alpine
COPY infra/nginx/frontend.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
