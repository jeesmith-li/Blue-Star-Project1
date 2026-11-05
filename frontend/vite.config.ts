import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // The local backend allows the original development origin on port 3000.
        // Keep alternate Vite preview ports compatible with that CORS policy.
        configure(proxy) {
          proxy.on('proxyReq', (proxyReq, req) => {
            if (req.headers.origin) {
              proxyReq.setHeader('Origin', 'http://localhost:3000')
            }
          })
        }
      }
    }
  }
})
