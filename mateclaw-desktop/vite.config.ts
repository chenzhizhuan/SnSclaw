import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import electron from 'vite-plugin-electron'
import renderer from 'vite-plugin-electron-renderer'
import { resolve } from 'path'
import { brandingPlugin } from './scripts/branding.cjs'

export default defineConfig(({ command }) => {
  const isServe = command === 'serve'
  const isBuild = command === 'build'

  // Shared branding plugin instance — applied to the renderer build as well
  // as the electron main/preload builds so brand strings are replaced
  // everywhere without touching source code.
  const brand = brandingPlugin()

  return {
    plugins: [
      vue(),
      // White-label branding: replaces "SnSclaw" with the configured brand
      // name at build time. Source code stays untouched. Configure via
      // branding.config.json or BRAND_* env vars.
      brand,
      electron([
        {
          entry: 'electron/main/index.ts',
          onstart(args) {
            args.startup()
          },
          vite: {
            plugins: [brand],
            build: {
              sourcemap: isServe,
              minify: isBuild,
              outDir: 'dist-electron/main',
              rollupOptions: {
                // ⚠️ IMPORTANT — 以下模块必须标记为 external (从 node_modules require)，
                // 绝对不能让 Vite/Rollup 进行 tree-shake / minify 重写：
                //   - ws：内部 require('./buffer-util') 的 exports.mask 会被 Vite
                //     破坏，导致 "l.mask is not a function"（在 WebSocket 发送数据帧
                //     时由 Sender.frame 触发）
                //   - electron / electron-updater：Electron 官方标准 external
                //   - bufferutil / utf-8-validate：ws 的原生 peer dependencies，
                //     即使未安装，ws 也会 fallback 到 JS 实现，打包它们会导致
                //     .node native addon 查找路径出错
                external: [
                  'electron',
                  'electron-updater',
                  'ws',
                  'bufferutil',
                  'utf-8-validate',
                ],
              },
            },
          },
        },
        {
          entry: 'electron/preload/index.ts',
          onstart(args) {
            args.reload()
          },
          vite: {
            plugins: [brand],
            build: {
              sourcemap: isServe ? 'inline' : undefined,
              minify: isBuild,
              outDir: 'dist-electron/preload',
              rollupOptions: {
                external: ['electron'],
              },
            },
          },
        },
      ]),
      renderer(),
    ],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
    },
    build: {
      outDir: 'dist',
      emptyOutDir: true,
    },
  }
})
