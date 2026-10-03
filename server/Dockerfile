FROM node:22-alpine
WORKDIR /app
COPY --chown=node:node relay.mjs package.json ./
USER node
ENV PORT=8787
EXPOSE 8787
CMD ["node", "relay.mjs"]
