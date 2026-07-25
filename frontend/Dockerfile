# 1단계: 빌드 — Vite로 정적 파일(dist)을 만든다.
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm install

COPY . .
RUN npm run build

# 2단계: 실행 — nginx로 정적 파일을 서빙하고 /api는 spring으로 프록시한다.
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
