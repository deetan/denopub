import { defineConfig, type Plugin } from 'vite';
import preact from '@preact/preset-vite';

/**
 * The TV (Chromium 63, packaged app served from the file system) gets one classic script, not an
 * ES module: Vite's `<script type="module" crossorigin>` was never proven there, the spike's
 * classic scripts were.
 */
function classicScript(): Plugin {
  return {
    name: 'natapub-classic-script',
    apply: 'build',
    enforce: 'post',
    transformIndexHtml(html) {
      return html
        .replace(/<script type="module" crossorigin src=/g, '<script defer src=')
        .replace(/<link rel="stylesheet" crossorigin href=/g, '<link rel="stylesheet" href=');
    },
  };
}

export default defineConfig({
  base: './',
  plugins: [preact(), classicScript()],
  build: {
    target: 'chrome63',
    cssTarget: 'chrome63',
    modulePreload: false,
    cssCodeSplit: false,
    assetsInlineLimit: 0,
    rollupOptions: {
      output: {
        format: 'iife',
        inlineDynamicImports: true,
        entryFileNames: 'app.js',
        assetFileNames: 'assets/[name][extname]',
      },
    },
  },
  server: {
    // Desktop Chrome enforces CORS (the TV app does not): kino.watch goes through the dev server.
    proxy: {
      '/kw': {
        target: 'https://kino.watch',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/kw/, ''),
      },
      // Sensor.Community sends no CORS headers (the TV app doesn't need them).
      '/sc': {
        target: 'https://data.sensor.community',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/sc/, ''),
      },
    },
  },
});
