import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  root: '.',
  publicDir: 'public',

  build: {
    outDir: 'dist',
    rollupOptions: {
      input: {
        // Admin panel
        admin: resolve(__dirname, 'admin_panel/index.html'),
        // 3D Preview
        preview: resolve(__dirname, 'preview_tool/frontend/index.html'),
      },
    },
    minify: 'terser',
    sourcemap: true,
  },

  server: {
    port: 3000,
    open: '/admin_panel/index.html',
    proxy: {
      // Proxy WebSocket connections to Python server
      '/ws': {
        target: 'ws://localhost:8766',
        ws: true,
        changeOrigin: true,
      },
    },
  },

  preview: {
    port: 4173,
  },

  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
      '@admin': resolve(__dirname, 'admin_panel/js'),
      '@preview': resolve(__dirname, 'preview_tool/frontend/js'),
    },
  },
});
