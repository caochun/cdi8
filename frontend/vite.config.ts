import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  base: '/workbench/',
  build: {
    outDir: '../target/workbench-static/workbench',
    emptyOutDir: true,
    // Public runtime pages and vendors are packaged at / by the server module.
    copyPublicDir: false,
    rollupOptions: {
      input: {main: 'index.html', layout: 'src/components/diagrams/layout.ts'},
      preserveEntrySignatures: 'strict',
      output: {
        entryFileNames: chunk => chunk.name === 'layout' ? 'layout.js' : 'assets/[name]-[hash].js',
      },
    },
  },
  server: {
    // The minimal templates are canonical XML files shared with other tools.
    fs: {allow: ['..']},
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/vendor': 'http://127.0.0.1:8080',
      '/runtime.html': 'http://127.0.0.1:8080',
      '/app.js': 'http://127.0.0.1:8080',
      '/style.css': 'http://127.0.0.1:8080',
      '/state-charts.js': 'http://127.0.0.1:8080',
      '/state-chart-renderer.html': 'http://127.0.0.1:8080',
      '/state-chart-renderer.js': 'http://127.0.0.1:8080',
      '/workbench/layout.js': 'http://127.0.0.1:8080',
      '/workbench/assets': 'http://127.0.0.1:8080',
    },
  },
});
