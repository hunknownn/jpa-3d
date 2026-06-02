package com.jpa3d.export

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jpa3d.Jpa3dBrowserHolder
import com.jpa3d.analyzer.Jpa3dAnalysisCache
import com.jpa3d.model.GraphData
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 옵션에 따라 ExportModel 을 만들고 선택된 포맷별 파일을 출력 폴더에 쓴다.
 *
 * 호출 측은 백그라운드 (Task.Backgroundable) 안에서 실행해야 한다.
 * read action 은 [Jpa3dAnalysisCache] 내부에서 이미 처리한다.
 */
class ExportRunner(private val project: Project) {

    /** 실행 결과 — 다이얼로그가 사용자에게 알림으로 띄울 정보. */
    data class Result(
        val writtenFiles: List<Path>,
        val skippedFormats: List<String>
    )

    fun run(options: ExportOptions): Result {
        require(options.anyFormatSelected()) { "최소 한 가지 포맷을 선택해야 합니다." }
        Files.createDirectories(options.outputDir)

        val graph: GraphData = project.service<Jpa3dAnalysisCache>().getGraphData()
        val model = ExportConverter.toExportModel(graph, options.scope, options.seed, options.depth, options.seedType)

        val written = mutableListOf<Path>()
        val skipped = mutableListOf<String>()

        if (options.formatJson) {
            written.add(writeText(options.outputDir.resolve("model.json"), JsonExporter.render(model)))
        }
        if (options.formatDdl) {
            val ddl = DdlExporter(
                dialect = options.ddlDialect,
                snakeCase = options.ddlSnakeCase,
                dropExisting = options.ddlDropExisting
            ).render(model)
            val fileName = "schema-${options.ddlDialect.fileSuffix}.sql"
            written.add(writeText(options.outputDir.resolve(fileName), ddl))
        }
        if (options.formatMermaid) {
            written.add(writeText(options.outputDir.resolve("diagram.mmd"), MermaidExporter.render(model)))
        }

        // === Viewer snapshot ===
        // 브라우저 핸들이 없으면(ToolWindow 닫힘) 스킵.
        if (options.snapshotPng || options.snapshotSvg) {
            val browser = project.service<Jpa3dBrowserHolder>().browser
            if (browser == null) {
                if (options.snapshotPng) skipped.add("Snapshot PNG (viewer 가 열려있지 않음)")
                if (options.snapshotSvg) skipped.add("Snapshot SVG (viewer 가 열려있지 않음)")
            } else {
                val snapshot = ViewerSnapshot(browser)
                if (options.snapshotPng) {
                    val r = snapshot.capture("png")
                    if (r.error != null) skipped.add("Snapshot PNG (${r.error})")
                    else written.add(writeBytes(options.outputDir.resolve("snapshot.png"), r.bytes))
                }
                if (options.snapshotSvg) {
                    val r = snapshot.capture("svg")
                    if (r.error != null) skipped.add("Snapshot SVG (${r.error})")
                    else written.add(writeBytes(options.outputDir.resolve("snapshot.svg"), r.bytes))
                }
            }
        }

        return Result(written, skipped)
    }

    private fun writeText(path: Path, content: String): Path {
        Files.writeString(path, content, StandardCharsets.UTF_8)
        return path
    }

    private fun writeBytes(path: Path, bytes: ByteArray): Path {
        Files.write(path, bytes)
        return path
    }
}
