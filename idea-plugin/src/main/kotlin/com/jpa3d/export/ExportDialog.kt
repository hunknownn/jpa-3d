package com.jpa3d.export

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.jpa3d.Jpa3dBrowserHolder
import com.jpa3d.Jpa3dBundle
import com.jpa3d.model.GraphScope
import com.intellij.ui.components.panels.VerticalLayout
import java.awt.BorderLayout
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Export 옵션 입력 다이얼로그.
 *
 * 한 화면에 포맷/방언/범위/저장경로를 모두 모아 받는다.
 * 사용자가 OK 를 누르면 [collectOptions] 가 [ExportOptions] 를 반환.
 */
class ExportDialog(private val project: Project) : DialogWrapper(project, true) {

    // DDL 관련 초기값은 설정(설정 → 도구 → JPA 3D)의 Export 기본값에서 가져온다.
    private val settings = com.jpa3d.settings.Jpa3dSettings.getInstance().state

    private val cbJson = JCheckBox(Jpa3dBundle.message("export.format.json"), true)
    private val cbDdl = JCheckBox(Jpa3dBundle.message("export.format.ddl"), true)
    private val cbMermaid = JCheckBox(Jpa3dBundle.message("export.format.mermaid"), true)
    private val cbSnapshotPng = JCheckBox(Jpa3dBundle.message("export.format.png"), false)
    private val cbSnapshotSvg = JCheckBox(Jpa3dBundle.message("export.format.svg"), false)

    private val cbSnakeCase = JCheckBox(Jpa3dBundle.message("settings.export.snakeCase"), settings.ddlSnakeCase)
    private val cbDropExisting = JCheckBox(Jpa3dBundle.message("settings.export.dropExisting"), settings.ddlDropExisting)

    private val dialectCombo = JComboBox(DdlDialect.values()).apply {
        setRenderer { list, value, _, isSelected, _ ->
            JBLabel((value as? DdlDialect)?.displayName ?: "").apply {
                isOpaque = true
                background = if (isSelected) list.selectionBackground else list.background
                foreground = if (isSelected) list.selectionForeground else list.foreground
                border = JBUI.Borders.empty(2, 6)
            }
        }
        selectedItem = settings.ddlDialect
    }

    private val rbAll = JRadioButton(Jpa3dBundle.message("export.scope.all"), true)
    private val rbCurrent = JRadioButton(Jpa3dBundle.message("export.scope.current"), false)
    // seed 해석 방식. 표시 라벨 → GraphScope seedType 값.
    private val seedTypeLabels = arrayOf(
        Jpa3dBundle.message("export.seedType.fqn"),
        Jpa3dBundle.message("export.seedType.package")
    )
    private val seedTypeValues = arrayOf(GraphScope.SEED_TYPE_FQN, GraphScope.SEED_TYPE_PACKAGE)
    private val seedTypeCombo = JComboBox(seedTypeLabels)
    private val seedField = JBTextField()
    private val depthSpinner = JSpinner(SpinnerNumberModel(2, 0, 10, 1))

    private val outputField = TextFieldWithBrowseButton().apply {
        val desc = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle(Jpa3dBundle.message("export.folderChooser.title"))
        addBrowseFolderListener(project, desc)
        text = defaultOutputDir(project)
    }

    private val warnLabel = JBLabel(" ").apply {
        foreground = JBColor.RED
    }

    init {
        title = Jpa3dBundle.message("export.dialog.title")
        setOKButtonText(Jpa3dBundle.message("export.dialog.okButton"))
        init()

        // 방언 콤보 + DDL 옵션은 DDL 체크가 켜진 경우만, seed 입력은 '현재 뷰' 범위일 때만 활성화.
        val refreshEnabled = {
            dialectCombo.isEnabled = cbDdl.isSelected
            cbSnakeCase.isEnabled = cbDdl.isSelected
            cbDropExisting.isEnabled = cbDdl.isSelected
            val curr = rbCurrent.isSelected
            seedTypeCombo.isEnabled = curr
            seedField.isEnabled = curr
            depthSpinner.isEnabled = curr
        }
        cbDdl.addActionListener { refreshEnabled() }
        rbAll.addActionListener { refreshEnabled() }
        rbCurrent.addActionListener { refreshEnabled() }
        refreshEnabled()

        // viewer 현재 뷰 모드를 백그라운드에서 조회 → 3D 면 SVG 스냅샷 비활성화.
        // 조회 실패 시(viewer 미준비 등) 둘 다 활성 상태 유지하고 실행 시 에러로 안내.
        probeViewMode()
    }

    private fun probeViewMode() {
        val browser = project.service<Jpa3dBrowserHolder>().browser ?: run {
            cbSnapshotPng.isEnabled = false
            cbSnapshotSvg.isEnabled = false
            cbSnapshotPng.text = Jpa3dBundle.message("export.format.png.noViewer")
            cbSnapshotSvg.text = Jpa3dBundle.message("export.format.svg.noViewer")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val mode = ViewerSnapshot(browser).queryViewMode()
            ApplicationManager.getApplication().invokeLater {
                if (mode == "3d") {
                    cbSnapshotSvg.isSelected = false
                    cbSnapshotSvg.isEnabled = false
                    cbSnapshotSvg.text = Jpa3dBundle.message("export.format.svg.no3d")
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val dialectPanel = JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel(Jpa3dBundle.message("settings.export.dialect")), BorderLayout.WEST)
            add(dialectCombo, BorderLayout.CENTER)
        }

        // DDL 종속 옵션 — cbDdl 아래 들여써서 종속 관계를 시각적으로 드러낸다.
        // GridLayout(균등분할) 대신 VerticalLayout — 각 행이 자연 높이로 쌓여 불필요한 세로 stretch 가 없다.
        val ddlOptionsPanel = JPanel(VerticalLayout(4)).apply {
            border = JBUI.Borders.empty(2, 24, 4, 0)
            add(dialectPanel)
            add(cbSnakeCase)
            add(cbDropExisting)
        }

        // 데이터 export — 파일로 떨어지는 산출물(JSON/DDL/Mermaid).
        val dataFormatPanel = JPanel(VerticalLayout(4)).apply {
            add(cbJson)
            add(cbDdl)
            add(ddlOptionsPanel)
            add(cbMermaid)
        }

        // 현재 뷰 스냅샷 — viewer 화면 캡처. 데이터 export 와 성격이 달라 분리.
        val snapshotPanel = JPanel(VerticalLayout(4)).apply {
            add(cbSnapshotPng)
            add(cbSnapshotSvg)
        }

        ButtonGroup().apply { add(rbAll); add(rbCurrent) }

        val scopePanel = JPanel(VerticalLayout(4)).apply {
            add(rbAll)
            add(rbCurrent)
            add(buildSubField(Jpa3dBundle.message("export.field.seedType"), seedTypeCombo))
            add(buildSubField(Jpa3dBundle.message("export.field.seed"), seedField))
            add(buildSubField(Jpa3dBundle.message("export.field.depth"), depthSpinner))
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(Jpa3dBundle.message("export.section.data"), dataFormatPanel, 1, false)
            .addSeparator()
            .addLabeledComponent(Jpa3dBundle.message("export.section.snapshot"), snapshotPanel, 1, false)
            .addSeparator()
            .addLabeledComponent(Jpa3dBundle.message("export.section.scope"), scopePanel, 1, false)
            .addSeparator()
            .addLabeledComponent(Jpa3dBundle.message("export.section.output"), outputField, 1, false)
            .addComponentFillVertically(warnLabel, 0)
            .panel.also {
                it.border = JBUI.Borders.empty(8)
            }
    }

    private fun buildSubField(label: String, comp: JComponent): JPanel = JPanel(BorderLayout(8, 0)).apply {
        border = JBUI.Borders.emptyLeft(24)
        add(JBLabel(label), BorderLayout.WEST)
        add(comp, BorderLayout.CENTER)
    }

    override fun doValidate(): ValidationInfo? {
        if (!cbJson.isSelected && !cbDdl.isSelected && !cbMermaid.isSelected &&
            !cbSnapshotPng.isSelected && !cbSnapshotSvg.isSelected
        ) {
            return ValidationInfo(Jpa3dBundle.message("export.validation.noFormat"), cbJson)
        }
        val out = outputField.text.trim()
        if (out.isEmpty()) return ValidationInfo(Jpa3dBundle.message("export.validation.noOutput"), outputField)
        if (rbCurrent.isSelected && seedField.text.trim().isEmpty()) {
            return ValidationInfo(Jpa3dBundle.message("export.validation.noSeed"), seedField)
        }
        return null
    }

    fun collectOptions(): ExportOptions = ExportOptions(
        outputDir = Paths.get(outputField.text.trim()),
        scope = if (rbCurrent.isSelected) ExportScope.CURRENT_VIEW else ExportScope.ALL,
        seed = seedField.text.trim(),
        seedType = seedTypeValues[seedTypeCombo.selectedIndex.coerceIn(0, seedTypeValues.size - 1)],
        depth = (depthSpinner.value as Number).toInt(),
        formatJson = cbJson.isSelected,
        formatDdl = cbDdl.isSelected,
        formatMermaid = cbMermaid.isSelected,
        snapshotPng = cbSnapshotPng.isSelected,
        snapshotSvg = cbSnapshotSvg.isSelected,
        ddlDialect = dialectCombo.selectedItem as DdlDialect,
        ddlSnakeCase = cbSnakeCase.isSelected,
        ddlDropExisting = cbDropExisting.isSelected
    )

    private fun defaultOutputDir(project: Project): String {
        val base = project.basePath?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.home"))
        return base.resolve("jpa3d-export").toString()
    }
}
