# syntax=docker/dockerfile:1

# Build the SPA, then serve the static output from nginx
# (FRONTEND_MODULE.md §8). Two stages so node and the dependency tree never
# reach the runtime image - the thing that ships is HTML, CSS and JS.

FROM node:22-alpine AS builder
WORKDIR /app

# package.json + lockfile first, so `npm ci` is only re-run when dependencies
# actually change rather than on every source edit.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# `npm run build` runs tsc -b before vite build, so a type error fails the image
# build rather than shipping.
RUN npm run build

FROM nginx:1.27-alpine AS runtime

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Run as the non-root user the image already provides, matching the container
# hardening applied to the Java services (SECURITY_HARDENING_MODULE.md §8).
# nginx needs its cache/run directories to be writable by that user.
RUN chown -R nginx:nginx /usr/share/nginx/html /var/cache/nginx \
    && touch /var/run/nginx.pid \
    && chown nginx:nginx /var/run/nginx.pid
USER nginx

EXPOSE 3000

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -q --spider http://127.0.0.1:3000/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
