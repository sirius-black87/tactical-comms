FROM node:22-alpine
# openssl creates the self-signed certificate used when the server is reached by IP
RUN apk add --no-cache openssl && mkdir -p /data && chown node:node /data
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
ENV PORT=8080 DATA_DIR=/data
VOLUME /data
EXPOSE 8080
USER node
CMD ["node", "src/server.js"]
