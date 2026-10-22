import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  base: '/',
  build: {
    outDir: '../target/workbench-static',
    emptyOutDir: true,
    // Isolated state-chart layout resources are packaged by the server module.
    copyPublicDir: false,
  },
  server: {
    // The minimal templates are canonical XML files shared with other tools.
    fs: {allow: ['..']},
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/vendor': 'http://127.0.0.1:8080',
      '/state-chart-renderer.html': 'http://127.0.0.1:8080',
      '/state-chart-renderer.js': 'http://127.0.0.1:8080',
    },
  },
});
