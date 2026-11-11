import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    const internalTarget = process.env.INTERNAL_API_URL;
    if (!internalTarget) {
      return [];
    }
    return [
      {
        source: "/api/:path*",
        destination: `${internalTarget.replace(/\/api\/?$/, "")}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;

