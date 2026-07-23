import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3005,
    strictPort: true,
    proxy: {
      '/api': {
        // BFF 端口由 .env 的 VITE_BFF_PORT 控制(默认 3004),与 server/index.ts 的 PORT 保持一致
        target: `http://localhost:${process.env.VITE_BFF_PORT || 3004}`,
        changeOrigin: true,
      },
    },
  },
})
