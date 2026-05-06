# syntax=docker/dockerfile:1

FROM node:22-alpine
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY server.mjs ./server.mjs
COPY scripts ./scripts

# Container-only adaptation: allow Docker to bind outside loopback without
# changing application source in this workstream.
RUN node -e "const fs=require('fs'); const p='server.mjs'; const s=fs.readFileSync(p,'utf8'); fs.writeFileSync(p, s.replace('app.listen(port, \"127.0.0.1\"', 'app.listen(port, process.env.MARKETMIND_LANGGRAPH_HOST || \"0.0.0.0\"'));"

ENV MARKETMIND_LANGGRAPH_PORT=8787
ENV MARKETMIND_LANGGRAPH_HOST=0.0.0.0
ENV MARKETMIND_LANGCHAIN_MOCK=true

EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8787/health >/dev/null || exit 1

CMD ["npm", "run", "start"]
