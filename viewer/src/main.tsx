import React from "react";
import ReactDOM from "react-dom/client";
import ErdApp from "./ErdApp";
import { setLocale } from "./i18n";
import { applyTheme } from "./theme";

// 플러그인(BridgeInjector)이 주입한 언어를 초기 적용. 주입 전이면 ErdApp 가
// jpa3d:bridge-ready 시점에 다시 적용한다(스탠드얼론은 영어 기본).
setLocale((window as unknown as { __JPA3D_LOCALE__?: string }).__JPA3D_LOCALE__);

// 플러그인이 주입한 IDE 테마(다크/라이트)와 패널 배경색을 초기 적용. 주입 전이면 ErdApp 가
// jpa3d:bridge-ready / jpa3d:theme-change 시점에 다시 적용한다(스탠드얼론은 다크 기본).
const themeGlobals = window as unknown as {
  __JPA3D_THEME__?: string;
  __JPA3D_THEME_BG__?: string;
  __JPA3D_TRANSPARENT__?: string;
};
applyTheme(
  themeGlobals.__JPA3D_THEME__,
  themeGlobals.__JPA3D_THEME_BG__,
  themeGlobals.__JPA3D_TRANSPARENT__ === "true"
);

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ErdApp />
  </React.StrictMode>
);
