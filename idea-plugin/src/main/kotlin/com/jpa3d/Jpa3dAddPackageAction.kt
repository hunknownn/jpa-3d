package com.jpa3d

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPackage

/**
 * 프로젝트 트리에서 패키지를 우클릭 → "JPA 3D: 이 패키지 추가".
 *
 * 선택한 패키지를 JPA 3D 뷰어의 **중심(시드)** 으로 추가한다(다중 시드라 누적).
 * 툴윈도우 activate + 시드 push 흐름은 [ViewerSeeder] 가 담당한다.
 *
 * DumbAware — 인덱싱 중에도 메뉴는 뜬다. 뷰어 분석은 자체적으로 dumb mode 를 처리.
 */
class Jpa3dAddPackageAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val pkg = if (e.project != null) resolvePackageName(e) else null
        e.presentation.isEnabledAndVisible = pkg != null
        if (pkg != null) {
            // 짧은 패키지명을 텍스트에 노출 (full 은 description).
            e.presentation.text = Jpa3dBundle.message("action.addPackage.text", pkg.substringAfterLast('.'))
            e.presentation.description = Jpa3dBundle.message("action.addPackage.description")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pkg = resolvePackageName(e) ?: return
        ViewerSeeder.addPackage(project, pkg)
    }

    /**
     * 선택에서 패키지 FQN 을 해석한다. 패키지가 아니거나 기본(root) 패키지면 null.
     * PSI 접근이므로 read action 안에서 수행.
     */
    private fun resolvePackageName(e: AnActionEvent): String? {
        val project = e.project ?: return null
        val element = e.getData(CommonDataKeys.PSI_ELEMENT)
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        return ReadAction.compute<String?, RuntimeException> {
            when (element) {
                is PsiPackage -> return@compute element.qualifiedName.takeIf { it.isNotEmpty() }
                is PsiDirectory -> packageOf(element)?.let { return@compute it }
            }
            if (virtualFile != null && virtualFile.isDirectory) {
                val dir = PsiManager.getInstance(project).findDirectory(virtualFile)
                if (dir != null) return@compute packageOf(dir)
            }
            null
        }
    }

    private fun packageOf(dir: PsiDirectory): String? =
        JavaDirectoryService.getInstance().getPackage(dir)?.qualifiedName?.takeIf { it.isNotEmpty() }
}
