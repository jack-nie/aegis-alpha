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
};

export default nextConfig;
