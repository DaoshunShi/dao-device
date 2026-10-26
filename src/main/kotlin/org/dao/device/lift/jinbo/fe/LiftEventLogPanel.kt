package org.dao.device.lift.jinbo.fe

import org.apache.commons.lang3.time.DateFormatUtils
import org.dao.device.lift.jinbo.JinBoFrame
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.JToggleButton
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import java.util.Date

/**
 * 事件日志及其操作行。
 *
 * 负责实时日志缓冲、事件计数、回放日志分色和清除操作；回放控制状态由 LiftReplayPanel 管理。
 */
class LiftEventLogPanel(
  private val configId: String,
  private val replayPanel: LiftReplayPanel,
  private val onShowLiftStatusChanged: (Boolean) -> Unit,
) : JPanel(BorderLayout()) {

  private val logArea = JTextPane().apply {
    isEditable = false
  }
  private val eventCounter = mutableMapOf<String, Int>()
  private val statusLabel = JLabel("状态: 等待事件...")

  // 回放日志三色
  private val colorPlayed = Color(140, 140, 140) // 已播放：灰
  private val colorCurrent = Color(20, 20, 20) // 当前帧：高亮（近黑加粗）
  private val colorFuture = Color(200, 200, 200) // 未播放：浅灰

  init {
    add(
      JScrollPane(logArea).apply {
        // JTextPane 无首选行数，显式给出高度，避免被 BorderLayout 压成细缝
        preferredSize = Dimension(0, 180)
      },
      BorderLayout.CENTER,
    )

    // 底部：操作行 + 回放控制行（垂直堆叠）
    val bottom = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)

      // 操作行：清除日志 | 状态 | 回放开关 | 打印电梯状态
      add(
        JPanel().apply {
          layout = BoxLayout(this, BoxLayout.X_AXIS)
          alignmentX = LEFT_ALIGNMENT
          maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height.coerceAtLeast(32))
          add(
            JButton("清除日志").apply {
              addActionListener {
                logArea.text = ""
                JinBoEventBus.fire(configId, LiftEvent("清除日志", "清除日志按钮被点击"))
                eventCounter.clear()
                updateStatus()
              }
            },
          )
          add(Box.createHorizontalStrut(10))
          add(statusLabel)
          add(Box.createHorizontalGlue()) // 添加弹性空间
          add(
            JToggleButton("回放").apply {
              addActionListener { replayPanel.setReplayEnabled(isSelected) }
            },
          )
          add(Box.createHorizontalStrut(10))
          add(
            JCheckBox("打印电梯状态").apply {
              addActionListener { onShowLiftStatusChanged(isSelected) }
            },
          )
        },
      )

      // 回放控制行（默认隐藏，开启回放后展开）
      add(replayPanel)
    }
    add(bottom, BorderLayout.SOUTH)
    border = BorderFactory.createTitledBorder("事件日志")
  }

  /** 追加一条实时日志并更新各 topic 的事件计数。 */
  fun appendLog(event: LiftEvent) {
    // 更新事件计数器
    eventCounter[event.topic] = eventCounter.getOrDefault(event.topic, 0) + 1

    // 添加时间戳和日志消息
    val timestamp = DateFormatUtils.format(Date(), "HH:mm:ss.SSS")
    val text = "$timestamp| ${event.topic}:${event.msg}\n"
    val doc = logArea.styledDocument
    doc.insertString(doc.length, text, null)
    logArea.caretPosition = doc.length

    // 更新状态
    updateStatus()
  }

  fun clearLog() {
    logArea.text = ""
  }

  /**
   * 按帧渲染回放日志并三色分色：
   * - 已播放帧(0..index-1)：灰
   * - 当前帧(index)：高亮加粗
   * - 未播放帧(index+1..)：浅灰
   * 每行前缀该帧时间戳，与实时日志格式一致。
   */
  fun renderReplayLogs(frames: List<JinBoFrame>, index: Int) {
    val doc = logArea.styledDocument
    doc.remove(0, doc.length)

    val played = SimpleAttributeSet().apply { StyleConstants.setForeground(this, colorPlayed) }
    val current = SimpleAttributeSet().apply {
      StyleConstants.setForeground(this, colorCurrent)
      StyleConstants.setBold(this, true)
    }
    val future = SimpleAttributeSet().apply { StyleConstants.setForeground(this, colorFuture) }

    var currentLineOffset = -1
    frames.forEachIndexed { i, frame ->
      if (frame.logs.isEmpty()) return@forEachIndexed
      val style = when {
        i < index -> played
        i == index -> current
        else -> future
      }
      if (i == index) currentLineOffset = doc.length
      val ts = DateFormatUtils.format(Date(frame.ts), "HH:mm:ss.SSS")
      for (line in frame.logs) {
        doc.insertString(doc.length, "$ts| $line\n", style)
      }
    }

    // 滚动到当前帧位置（无当前帧日志时滚到底部）
    logArea.caretPosition = if (currentLineOffset >= 0) currentLineOffset else doc.length
  }

  private fun updateStatus() {
    val totalEvent = eventCounter.values.sum()
    val eventSummary = eventCounter.entries.joinToString("，") { "${it.key}：${it.value}" }
    statusLabel.text = "事件总数：$totalEvent | 详情：$eventSummary"
  }
}
