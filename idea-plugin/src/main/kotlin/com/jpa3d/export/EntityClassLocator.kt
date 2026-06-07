package com.jpa3d.export

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jpa3d.analyzer.JpaAnnotations
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UFile
import org.jetbrains.uast.toUElementOfType

/**
 * 에디터에 열린 파일이 JPA `@Entity` 클래스인지 판별하고, 맞으면 FQN/단순명을 돌려준다.
 *
 * 에디터 상단 배너([EntitySqlBannerProvider])와 SQL 추출([EntitySqlExporter]) 양쪽이 같은
 * 규칙으로 "현재 파일의 엔티티" 를 해석하도록 한 곳에 모았다.
 *
 * @Entity 만 대상으로 한다 — @MappedSuperclass / @Embeddable 은 단독 테이블이 없어 SQL 추출 의미가
 * 없기 때문. (상속 부모로서의 합류는 [ExportConverter.toSingleEntityModel] 가 따로 처리한다.)
 */
object EntityClassLocator {

    data class Found(val fqn: String, val simpleName: String)

    /**
     * [file] 의 최상위 `@Entity` 클래스를 찾는다. 자바/코틀린 소스가 아니거나 엔티티가 없으면 null.
     * 인덱싱 중(dumb)에는 null — 배너는 인덱싱 완료 후 자동 갱신된다.
     */
    fun find(project: Project, file: VirtualFile): Found? {
        if (!isJvmSource(file)) return null
        if (DumbService.isDumb(project)) return null
        return ReadAction.compute<Found?, RuntimeException> {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@compute null
                val uFile = psiFile.toUElementOfType<UFile>() ?: return@compute null
                uFile.classes.firstNotNullOfOrNull { it.toFoundIfEntity() }
            } catch (e: IndexNotReadyException) {
                null // 인덱싱 레이스 — 다음 갱신에서 다시 시도됨
            }
        }
    }

    private fun UClass.toFoundIfEntity(): Found? {
        val fqn = qualifiedName ?: return null
        val isEntity = uAnnotations.any { it.qualifiedName in JpaAnnotations.ENTITY }
        return if (isEntity) Found(fqn, fqn.substringAfterLast('.')) else null
    }

    private fun isJvmSource(file: VirtualFile): Boolean =
        file.extension == "java" || file.extension == "kt"
}
