import React from "react";
import ReactDOM from "react-dom/client";
import ErdApp from "./ErdApp";
import { setLocale } from "./i18n";

// 플러그인(BridgeInjector)이 주입한 언어를 초기 적용. 주입 전이면 ErdApp 가
// jpa3d:bridge-ready 시점에 다시 적용한다(스탠드얼론은 영어 기본).
setLocale((window as unknown as { __JPA3D_LOCALE__?: string }).__JPA3D_LOCALE__);

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ErdApp />
  </React.StrictMode>
);
