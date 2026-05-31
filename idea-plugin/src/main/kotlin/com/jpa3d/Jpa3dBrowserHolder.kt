package com.jpa3d

import com.intellij.openapi.components.Service
import com.intellij.ui.jcef.JBCefBrowser

/**
 * 현재 프로젝트의 활성 ToolWindow viewer 브라우저 핸들을 보관한다.
 *
 * [Jpa3dViewerPanel] 이 생성 시 등록, dispose 시 초기화.
 * [com.jpa3d.export.ViewerSnapshot] 등 외부 컴포넌트가 viewer JS 를 호출할 때 이 핸들을 조회한다.
 *
 * 단일 프로젝트 단일 ToolWindow 가정. 다중 인스턴스를 지원하게 되면 List 로 확장 필요.
 */
@Service(Service.Level.PROJECT)
class Jpa3dBrowserHolder {

    @Volatile
    var browser: JBCefBrowser? = null
}
