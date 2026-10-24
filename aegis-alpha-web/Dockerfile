# syntax=docker/dockerfile:1

FROM node:22-alpine AS deps
WORKDIR /app
# npm registry is configurable at build time. Defaults to the official registry so the
# image builds anywhere; docker-compose.aegis-alpha.yml passes npmmirror by default.
# (npm rewrites registry.npmjs.org "resolved" URLs in package-lock.json to this registry.)
ARG NPM_REGISTRY=https://registry.npmjs.org/
# Dependency layer first: only re-runs when package*.json (or the registry ARG) changes.
COPY package.json package-lock.json ./
RUN npm ci --registry="$NPM_REGISTRY" --fetch-retries=5 --fetch-retry-mintimeout=20000 \
      --fetch-retry-maxtimeout=120000 --no-audit --no-fund

FROM node:22-alpine AS build
WORKDIR /app
ARG AEGIS_ALPHA_BACKEND_INTERNAL_URL=http://backend:5178
ENV AEGIS_ALPHA_BACKEND_INTERNAL_URL=${AEGIS_ALPHA_BACKEND_INTERNAL_URL}
ENV NEXT_TELEMETRY_DISABLED=1

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Container-only adaptation: the current source rewrite points at 127.0.0.1.
# Build the image with the service DNS name used by docker-compose.
RUN node -e "const fs=require('fs'); const p='next.config.mjs'; const s=fs.readFileSync(p,'utf8'); fs.writeFileSync(p, s.replace('http://127.0.0.1:5178', process.env.AEGIS_ALPHA_BACKEND_INTERNAL_URL));"
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV HOSTNAME=0.0.0.0
ENV PORT=5174

COPY --from=build /app/package.json ./package.json
COPY --from=build /app/package-lock.json ./package-lock.json
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/next.config.mjs ./next.config.mjs

EXPOSE 5174
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD wget -qO- http://127.0.0.1:5174/ >/dev/null || exit 1

CMD ["npx", "next", "start", "--hostname", "0.0.0.0", "--port", "5174"]
