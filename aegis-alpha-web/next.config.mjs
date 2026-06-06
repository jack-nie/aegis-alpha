const nextConfig = {
  devIndicators: false,
  async rewrites() {
    return [
      {
        source: "/_backend/:path*",
        destination: "http://127.0.0.1:5178/_backend/:path*",
      },
    ];
  },
  // Workflow execution (deep_dive = 17 LLM nodes) can take 2-3 minutes
  experimental: {
    proxyTimeout: 300000,
  },
};

export default nextConfig;
