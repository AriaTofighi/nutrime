import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development, proxy API calls to a sync server (a local one by default; override with NUTRIME_API).
const api = process.env.NUTRIME_API ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": { target: api, changeOrigin: true },
    },
  },
});
