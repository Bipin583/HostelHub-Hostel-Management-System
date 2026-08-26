# Build and run the Next console.
#
# Three stages: install, build, run. The runtime image gets the standalone output
# and nothing else -- no npm, no lockfile, no dev dependencies, no source.
#
# The one thing to understand before changing this file: NEXT_PUBLIC_* variables
# are inlined into the JavaScript bundle at BUILD time. They are build arguments
# here, not runtime environment, because setting them in `docker run` would be
# silently ignored -- the strings are already compiled into the chunks the browser
# downloads. That also means every NEXT_PUBLIC_* value is public. Pass a URL;
# never pass a secret.

# ---------- dependencies ----------
FROM node:22-alpine AS deps
WORKDIR /app

# `npm ci` from the lockfile, in its own layer keyed on the manifests only, so
# editing a component does not reinstall node_modules.
COPY package.json package-lock.json ./
RUN npm ci

# ---------- build ----------
FROM node:22-alpine AS build
WORKDIR /app

# The origin the BROWSER will use to reach Spring. In compose this is the
# host-published address (http://localhost:8080), not the service name -- see the
# comment in docker-compose.yml, because getting this wrong is the single easiest
# way to make the stack look broken.
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}

# Deliberately not declared as an ARG. It exists so a developer can sign a mock
# gateway callback locally with `npm run dev`; baking it into an image would
# publish a signing secret to every visitor of that build. See
# src/app/student/fees/[id]/page.tsx.

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# `next build` typechecks, so a type error fails the image build. That is the
# same gate CI applies, kept here so a local `docker compose build` cannot
# produce an image CI would reject.
RUN npm run build

# ---------- runtime ----------
FROM node:22-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production
# Bind all interfaces: the default is fine for a host process and useless in a
# container, where 127.0.0.1 would refuse the published port.
ENV HOSTNAME=0.0.0.0
ENV PORT=3000

RUN addgroup -S nodejs -g 1001 && adduser -S nextjs -u 1001 -G nodejs

# `standalone` carries the server and the modules it actually imports. `static`
# and `public` are NOT inside it -- Next expects them copied alongside, and the
# app 404s its own chunks if you forget. public/ holds only a .gitkeep today; it
# is kept non-empty precisely because COPY cannot skip a path that is absent.
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 3000

HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
    CMD node -e "fetch('http://127.0.0.1:3000/login').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

# server.js is what `output: 'standalone'` generates. There is no `next start`
# in this image, because there is no `next` in this image.
CMD ["node", "server.js"]
