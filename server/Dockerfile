FROM node:22-bookworm-slim
WORKDIR /app
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/browsers
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npx playwright install --with-deps --only-shell chromium \
    && rm -rf /var/lib/apt/lists/* /root/.npm
COPY --chown=node:node *.mjs ./
COPY --chown=node:node public ./public
USER node
ENV PORT=8787
EXPOSE 8787
CMD ["node", "relay.mjs"]
