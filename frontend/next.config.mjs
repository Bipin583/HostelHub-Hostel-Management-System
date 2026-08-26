/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,

  // Emits .next/standalone: a self-contained server plus only the node_modules it
  // actually imports. That is what the Dockerfile copies, and it is why the runtime
  // image carries no npm, no lockfile and no dev dependencies. Harmless for
  // `next dev` and `next start`, which ignore it.
  output: 'standalone',

  // No rewrites, no proxy. The browser talks to Spring directly, which is what
  // the backend's CorsConfigurationSource and its SameSite=Strict refresh cookie
  // were written for. Proxying through Next would put the access token on this
  // server, and there is deliberately no server-side session here to put it in.
  // See src/lib/session.ts for the whole argument.
};

export default nextConfig;
