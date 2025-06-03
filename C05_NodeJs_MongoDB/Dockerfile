FROM node:18-alpine

WORKDIR /app

COPY server/package*.json ./
RUN npm ci

COPY server/ .

EXPOSE 5050

CMD ["node", "--loader", "esm", "index.mjs"]
