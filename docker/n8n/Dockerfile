ARG N8N_VERSION=2.41.3
ARG N8N_IMAGE_REPOSITORY=ghcr.io/n8n-io/n8n
FROM ${N8N_IMAGE_REPOSITORY}:${N8N_VERSION}
USER root
RUN mkdir -p /control && chown node:node /control
COPY process.mjs /opt/windlass/process.mjs
RUN chmod 0755 /opt/windlass && chmod 0644 /opt/windlass/process.mjs
USER node
RUN node --check /opt/windlass/process.mjs
ENTRYPOINT ["tini", "--", "node", "/opt/windlass/process.mjs"]
