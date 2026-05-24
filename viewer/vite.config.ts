import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";

// JCEF 가 `jar:file://...!/web/index.html` 로 로드하는데, CEF 는 jar URL 안의
// sub-resource(js/css) 를 가져오지 못한다. 그래서 vite-plugin-singlefile 로
// 모든 자산을 index.html 한 파일에 인라인 → plugin 은 그 HTML 을 그대로
// `loadHTML()` 로 띄우면 외부 리소스 요청이 0건이 된다.
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  base: "./",
  server: {
    port: 5173
  },
  build: {
    // singlefile 권장 설정 — 모든 청크 인라인, asset 분리 비활성
    cssCodeSplit: false,
    assetsInlineLimit: 100_000_000,
    chunkSizeWarningLimit: 100_000_000,
    rollupOptions: {
      output: {
        inlineDynamicImports: true
      }
    }
  }
});
