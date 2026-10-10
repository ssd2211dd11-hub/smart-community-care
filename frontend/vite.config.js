import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// 开发服务器端口：避开常用的 5173，防止与其他项目冲突
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 5273,
    // 端口被占用时直接报错，不静默换到 5274。
    // 静默换端口会让"以为在访问 5273 其实访问的是另一个端口"这类问题极难排查。
    strictPort: true,
    // 明确绑定回环地址，与本地访问方式一致
    host: '127.0.0.1',
    proxy: {
      // 后端服务端口，与 application.yml 中的 server.port 保持一致
      '/api': {
        target: 'http://127.0.0.1:8890',
        changeOrigin: true
      }
    }
  }
})
