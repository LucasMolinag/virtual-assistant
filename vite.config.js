import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  server: { port: 5173 },
  preview: { port: 4173 },
  optimizeDeps: {
    exclude: ['@huggingface/transformers'],
  },
});
