package com.jpa3d.export

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * 거터(gutter)용 "SQL" 글자 아이콘 — 그래픽 심볼 대신 텍스트만 그린다.
 *
 * SVG `<text>` 의 렌더러 의존성을 피하려고 Graphics2D 로 직접 그린다. 크기/폰트는 [JBUIScale] 로
 * 스케일해 HiDPI·폰트 확대에 대응하고, 색은 [JBColor] 로 라이트/다크 모두에서 읽히게 한다.
 */
object SqlTextIcon : Icon {

    private const val TEXT = "SQL"
    private const val BASE_WIDTH = 16
    private const val BASE_HEIGHT = 14
    private const val BASE_FONT = 8.5f

    // 실린더 SVG 아이콘과 동일한 보라 — 라이트 #8A4FD8 / 다크 #C4A2F0.
    private val COLOR = JBColor(Color(0x8A4FD8), Color(0xC4A2F0))

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = COLOR
            g2.font = Font(Font.SANS_SERIF, Font.BOLD, JBUIScale.scale(BASE_FONT).toInt())
            val fm = g2.fontMetrics
            val tx = x + (iconWidth - fm.stringWidth(TEXT)) / 2
            val ty = y + (iconHeight - fm.height) / 2 + fm.ascent
            g2.drawString(TEXT, tx, ty)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth(): Int = JBUIScale.scale(BASE_WIDTH)
    override fun getIconHeight(): Int = JBUIScale.scale(BASE_HEIGHT)
}
