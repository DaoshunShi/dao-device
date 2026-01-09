package org.dao.device.lv.fe.main

import org.dao.device.common.FileHelper
import org.dao.device.common.GuiEventListener
import org.dao.device.lift.jinbo.fe.LiftEvent
import org.dao.device.lv.LvEventBus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.text.*

class LvCenterPanel :
  JPanel(),
  GuiEventListener {

  // 使用 JTextPane 替代 JTextArea
  val logArea = JTextPane().apply {
    isEditable = false
    contentType = "text/plain" // 设置为纯文本模式
  }

  init {
    background = Color.LIGHT_GRAY
    initUi()
    loadDefaultLog()
    LvEventBus.register("换行", this)
  }

  private fun initUi() {
    layout = BorderLayout()
    add(JScrollPane(logArea), BorderLayout.CENTER)
    border = BorderFactory.createTitledBorder("合并的日志")

    // 设置文本样式
    setupTextStyles()
  }

  private fun setupTextStyles() {
    // 获取默认样式
    val doc = logArea.styledDocument
    val style = logArea.getStyle(StyleContext.DEFAULT_STYLE)

    // 设置字体
    StyleConstants.setFontFamily(style, "Monospaced") // 等宽字体
    StyleConstants.setFontSize(style, 14) // 字体大小

    // 设置行间距
    StyleConstants.setLineSpacing(style, 0.5f) // 行间距

    // 设置前景色（文字颜色）
    StyleConstants.setForeground(style, Color.BLACK)

    // 设置背景色
    logArea.background = Color.WHITE

    // 应用样式
    doc.setParagraphAttributes(0, doc.length, style, false)
  }

  private fun loadDefaultLog() {
    val path = "/Users/shidaoshun/Downloads/m4-logs-2025100915-2025100916/system/sys-mon-2025-10-09-15-1.log"
    val content = FileHelper.readFileToString(path) ?: return

    // 清空现有内容
    logArea.text = ""

    // 按行处理日志
    content.lines().forEach { line ->
      appendLog(line, getLogStyle(line))
    }
  }

  // 根据日志内容获取样式
  private fun getLogStyle(logLine: String): Style {
    val style = logArea.addStyle("logStyle", null)

    // 根据日志级别设置不同颜色
    when {
      logLine.contains("ERROR", ignoreCase = true) -> {
        StyleConstants.setForeground(style, Color.RED)
      }

      logLine.contains("WARN", ignoreCase = true) -> {
        StyleConstants.setForeground(style, Color.ORANGE)
      }

      logLine.contains("INFO", ignoreCase = true) -> {
        StyleConstants.setForeground(style, Color.BLUE)
        StyleConstants.setBackground(style, Color.YELLOW)
      }

      else -> {
        StyleConstants.setForeground(style, Color.BLACK)
      }
    }

    return style
  }

  // 添加带样式的日志
  private fun appendLog(text: String, style: Style) {
    val doc = logArea.styledDocument
    doc.insertString(doc.length, text + "\n", style)
  }

  override fun paintComponent(g: Graphics?) {
    super.paintComponent(g)
  }

  override fun onEvent(event: LiftEvent) {
    // 切换自动换行
    logArea.editorKit = if (logArea.editorKit is WrappedEditorKit) {
      DefaultEditorKit()
    } else {
      WrappedEditorKit()
    }
  }

  // FIXME
  // 自定义 EditorKit 实现自动换行
  private class WrappedEditorKit : StyledEditorKit() {
    override fun getViewFactory() = ViewFactory { elem -> WrappedParagraphView(elem) }
  }

  // 自定义 ParagraphView 实现自动换行
  private class WrappedParagraphView(elem: Element) : ParagraphView(elem) {
    override fun getMinimumSpan(axis: Int): Float = if (axis == X_AXIS) 0f else super.getMinimumSpan(axis)
  }
}