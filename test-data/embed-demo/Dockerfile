# Mock DMV site for demoing the embeddable chat widget from a separate origin.
# Built on the GPU machine and pushed to GHCR alongside aigre-app -- see docs/RUNNING.md.
FROM nginx:1.27-alpine

# Origin of the AIGRE app this page embeds; override per deployment.
ENV AIGRE_API_ORIGIN=http://localhost:8085
# Department the widget is scoped to; empty = agency-wide (dmv profile).
ENV AIGRE_EMBED_DEPARTMENT=DMV

COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY index.html /usr/share/nginx/html/index.html
