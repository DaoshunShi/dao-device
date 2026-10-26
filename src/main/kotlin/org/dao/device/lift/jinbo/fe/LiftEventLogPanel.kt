package org.dao.device.lift.jinbo.fe

import org.apache.commons.lang3.time.DateFormatUtils
import org.dao.device.lift.jinbo.JinBoFrame
import org.dao.device.lift.jinbo.JinBoLiftStatus
import org.dao.device.lift.jinbo.JinBoReplayPosition
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
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
import javax.swing.SwingUtilities
import javax.swing.UIManager
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
  private val onShowLiftStatusChanged: (Boolean) -> Unit,
) : JPanel(BorderLayout()) {

  private val logArea = JTextPane().apply {
    isEditable = false
  }
  private val logScrollPane = JScrollPane(logArea).apply {
    // JTextPane 无首选行数，显式给出高度，避免被 BorderLayout 压成细缝
    preferredSize = Dimension(0, 180)
  }
  private val eventCounter = mutableMapOf<String, Int>()
  private val statusLabel = JLabel("状态: 等待事件...")
  private val directionLabel = JLabel("方向: --")
  private val liftStatusLabel = JLabel("状态: --")
  private val bottom = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
  private val replayToggle = JToggleButton("回放").apply { styleReplayToggle(this, false) }
  private var syncingReplayToggle = false
  private var realtimeLogText = ""
  private var realtimeLiftStatus: JinBoLiftStatus? = null
  private var realtimeDirection: JinBoLiftStatus? = null

  // 回放日志三色
  private val colorPlayed = Color(140, 140, 140) // 已播放：灰
  private val colorCurrent = Color(20, 20, 20) // 当前帧：高亮（近黑加粗）
  private val colorFuture = Color(200, 200, 200) // 未播放：浅灰

  init {
    add(logScrollPane, BorderLayout.CENTER)

    // 底部：操作行 + 稍后挂载的回放控制行（垂直堆叠）
    bottom.add(
      JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height.coerceAtLeast(32))
        add(
          JButton("清除日志").apply {
            addActionListener {
              setRealtimeLogText("")
              clearRealtimeStatus()
              JinBoEventBus.fire(configId, LiftEvent("清除日志", "清除日志按钮被点击"))
              eventCounter.clear()
              updateStatus()
            }
          },
        )
        add(Box.createHorizontalStrut(10))
        add(statusLabel)
        add(Box.createHorizontalGlue()) // 添加弹性空间
        add(directionLabel)
        add(Box.createHorizontalStrut(10))
        add(liftStatusLabel)
        add(Box.createHorizontalStrut(10))
        add(replayToggle)
        add(Box.createHorizontalStrut(10))
        add(
          JCheckBox("打印电梯状态").apply {
            addActionListener { onShowLiftStatusChanged(isSelected) }
          },
        )
      },
    )
    add(bottom, BorderLayout.SOUTH)
    border = BorderFactory.createTitledBorder("事件日志")
  }

  /** 挂载回放控制行并绑定回放开关；面板构造完成后由窗口调用一次。 */
  fun attachReplayPanel(replayPanel: LiftReplayPanel) {
    replayPanel.onFrameRendered { frames, position -> renderReplayLogs(frames, position) }
    replayToggle.addActionListener {
      if (!syncingReplayToggle) replayPanel.setReplayEnabled(replayToggle.isSelected)
    }
    replayPanel.onReplayStateChanged { replaying ->
      // 成功加载后按钮必须保持选中；失败或关闭时由回放面板同步取消。
      syncingReplayToggle = true
      replayToggle.isSelected = replaying
      styleReplayToggle(replayToggle, replaying)
      syncingReplayToggle = false
      if (replaying) {
        bottom.revalidate()
        bottom.repaint()
      } else {
        restoreRealtimeView()
      }
      revalidate()
      repaint()
    }
    bottom.add(replayPanel)
    revalidate()
  }

  internal fun replayToggleForTest(): JToggleButton = replayToggle

  internal fun logTextForTest(): String = logArea.text

  internal fun directionTextForTest(): String = directionLabel.text

  internal fun liftStatusTextForTest(): String = liftStatusLabel.text

  internal fun logStyleForTest(text: String): SimpleAttributeSet? = logStyle(logArea.text.indexOf(text))

  private fun logStyle(offset: Int): SimpleAttributeSet? {
    if (offset < 0) return null
    return logArea.styledDocument.getCharacterElement(offset).attributes as? SimpleAttributeSet
  }

  internal fun timelineHeightForTest(): Int = replayPanelForTest()?.timelineHeight ?: 0

  private fun replayPanelForTest(): LiftReplayPanel? =
    (bottom.components.firstOrNull { it is LiftReplayPanel } as? LiftReplayPanel)

  /** 追加一条实时日志并更新各 topic 的事件计数。 */
  fun appendLog(event: LiftEvent) {
    val isReplayMode = JinBoEventBus.isReplayMode(configId)
    // 更新事件计数器
    eventCounter[event.topic] = eventCounter.getOrDefault(event.topic, 0) + 1

    // 添加时间戳和日志消息
    val timestamp = DateFormatUtils.format(Date(), "HH:mm:ss.SSS")
    val text = "$timestamp| ${event.topic}:${event.msg}\n"
    realtimeLogText += text
    if (!isReplayMode) {
      val doc = logArea.styledDocument
      doc.insertString(doc.length, text, null)
      logArea.caretPosition = doc.length
    }

    // 更新状态
    updateStatus()
  }

  /** 实时运行和回放共用该入口，更新底栏显示的运行状态与方向。 */
  fun updateLiftStatus(status: JinBoLiftStatus, direction: JinBoLiftStatus) {
    if (JinBoEventBus.isReplayMode(configId)) return
    realtimeLiftStatus = status
    realtimeDirection = direction
    updateStatusLabel(status, direction)
  }

  /**
   * 按帧渲染回放日志并三色分色：
   * - 已播放帧(0..index-1)：灰
   * - 当前帧(index)：高亮加粗
   * - 未播放帧(index+1..)：浅灰
   * 每行前缀该帧时间戳，与实时日志格式一致。
   */
  fun renderReplayLogs(frames: List<JinBoFrame>, position: JinBoReplayPosition) {
    val doc = logArea.styledDocument
    doc.remove(0, doc.length)
    if (frames.isEmpty()) return
    val currentFrame = position.frameIndex.coerceIn(0, frames.lastIndex)
    frames.getOrNull(currentFrame)?.let { updateStatusLabel(it.liftStatus, it.direction) }

    val played = SimpleAttributeSet().apply { StyleConstants.setForeground(this, colorPlayed) }
    val current = SimpleAttributeSet().apply {
      StyleConstants.setForeground(this, colorCurrent)
      StyleConstants.setBold(this, true)
    }
    val future = SimpleAttributeSet().apply { StyleConstants.setForeground(this, colorFuture) }

    var currentFrameOffset = -1
    var latestPlayedOffset = -1
    frames.forEachIndexed { i, frame ->
      if (frame.logs.isEmpty()) return@forEachIndexed
      val style = when {
        i < currentFrame -> played
        i == currentFrame -> current
        else -> future
      }
      if (i <= currentFrame) latestPlayedOffset = doc.length
      if (i == currentFrame) currentFrameOffset = doc.length
      val ts = DateFormatUtils.format(Date(frame.ts), "HH:mm:ss.SSS")
      for (line in frame.logs) {
        doc.insertString(doc.length, "$ts| $line\n", style)
      }
    }

    // 当前帧没有日志时，停在已播放区间最后一条日志，便于看出当前帧所处的日志上下文。
    val anchorOffset = currentFrameOffset.takeIf { it >= 0 }
      ?: latestPlayedOffset.takeIf { it >= 0 }
      ?: -1
    if (anchorOffset < 0) return
    scrollLogTo(anchorOffset)
  }

  /** 按文本位置移动日志滚动条，使当前帧日志位于视口中部（边界处自动贴边）。 */
  private fun scrollLogTo(offset: Int) {
    logArea.caretPosition = offset
    // 文档内容变化后，Swing 要到下一次布局才更新行高和视口尺寸，因此延后一帧精确定位。
    SwingUtilities.invokeLater {
      if (!logArea.isShowing) return@invokeLater
      try {
        val row = logArea.modelToView2D(offset) ?: return@invokeLater
        val viewport = logScrollPane.viewport
        val viewPosition = viewport.viewPosition
        val targetY = (row.y + row.height / 2 - viewport.extentSize.height / 2).toInt()
        viewport.viewPosition = Point(
          viewPosition.x,
          targetY.coerceIn(0, (viewport.viewSize.height - viewport.extentSize.height).coerceAtLeast(0)),
        )
      } catch (_: Exception) {
        logArea.scrollRectToVisible(logArea.bounds)
      }
    }
  }

  private fun restoreRealtimeView() {
    logArea.text = realtimeLogText
    logArea.caretPosition = logArea.document.length
    updateRealtimeStatusLabel()
    updateStatus()
  }

  private fun setRealtimeLogText(text: String) {
    realtimeLogText = text
    if (!JinBoEventBus.isReplayMode(configId)) logArea.text = text
  }

  private fun clearRealtimeStatus() {
    realtimeLiftStatus = null
    realtimeDirection = null
    if (!JinBoEventBus.isReplayMode(configId)) updateRealtimeStatusLabel()
  }

  private fun updateRealtimeStatusLabel() {
    updateStatusLabel(realtimeLiftStatus, realtimeDirection)
  }

  private fun updateStatusLabel(status: JinBoLiftStatus?, direction: JinBoLiftStatus?) {
    liftStatusLabel.text = "状态: ${status?.name ?: "--"}"
    directionLabel.text = "方向: ${direction?.name ?: "--"}"
  }

  private fun updateStatus() {
    if (JinBoEventBus.isReplayMode(configId)) return
    val totalEvent = eventCounter.values.sum()
    val eventSummary = eventCounter.entries.joinToString("，") { "${it.key}：${it.value}" }
    statusLabel.text = "事件总数：$totalEvent | 详情：$eventSummary"
  }

  private fun styleReplayToggle(toggle: JToggleButton, replaying: Boolean) {
    toggle.text = if (replaying) "● 回放中" else "回放"
    toggle.background = if (replaying) Color(25, 118, 210) else UIManager.getColor("Button.background")
    toggle.foreground = if (replaying) Color.WHITE else UIManager.getColor("Button.foreground")
    toggle.font = toggle.font.deriveFont(if (replaying) Font.BOLD else Font.PLAIN)
    toggle.isOpaque = true
    toggle.border = if (replaying) {
      BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(Color(13, 71, 161), 2),
        BorderFactory.createEmptyBorder(2, 7, 2, 7),
      )
    } else {
      UIManager.getBorder("Button.border") ?: BorderFactory.createEmptyBorder(4, 9, 4, 9)
    }
  }
}
