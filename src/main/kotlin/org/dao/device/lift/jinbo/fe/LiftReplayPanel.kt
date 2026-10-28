package org.dao.device.lift.jinbo.fe

import org.apache.commons.lang3.time.DateFormatUtils
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoFrame
import org.dao.device.lift.jinbo.JinBoReplayPosition
import org.dao.device.lift.jinbo.JinBoReplayService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import java.util.Date
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

private val PLAYBACK_RATES = arrayOf(0.2, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0)

/**
 * 回放控制行：上排为文件选择、播放/暂停、跳转、播放倍速和当前时间，下排为时间轴。
 *
 * 开启时接管帧驱动，关闭时释放回放器；窗口调度器通过事件总线判断回放状态。
 */
class LiftReplayPanel(
  private val config: JinBoConfig,
  private val parentWindow: () -> Window,
) : JPanel(BorderLayout()) {

  private val replayService = JinBoReplayService(config.id)

  val isReplaying: Boolean get() = replayService.isReplaying

  private val playPauseBtn = JButton("▶ 播放")
  private val replayTimeLabel = JLabel("--:--:--")
  private val replayTimeline = ReplayTimeline { ts -> replayService.seek(ts) }
  private val replayFileCombo = JComboBox<String>()
  private val playbackRateCombo = JComboBox<Double>(PLAYBACK_RATES)
  private var suppressComboEvent = false
  private var onReplayStateChanged: ((Boolean) -> Unit)? = null
  private var onFrameRendered: ((List<JinBoFrame>, JinBoReplayPosition) -> Unit)? = null
  private var replayMode = false
  private var activeFile: String? = null

  internal val loadedFrameCount: Int get() = replayService.frameCount
  internal val timelineFrameCount: Int get() = replayTimeline.frameCount
  internal val timelineHeight: Int get() = replayTimeline.height

  init {
    isVisible = false
    alignmentX = LEFT_ALIGNMENT
    // 在 Y_AXIS 中横向拉满、纵向锁高，避免被居中或纵向拉伸；最小高度同时固定住时间条。
    maximumSize = Dimension(Int.MAX_VALUE, 90)
    minimumSize = Dimension(0, 90)
    buildControlRow()
  }

  /** 打开/关闭回放模式：打开时加载并停在首帧，关闭时恢复实时日志视图。 */
  fun setReplayEnabled(on: Boolean) {
    if (on) {
      if (replayMode) return

      // 只列出当前电梯有帧的文件；共用日志里可能有其他电梯的数据。
      val files = replayService.listFiles()
      if (files.isEmpty()) {
        deactivate()
        JOptionPane.showMessageDialog(parentWindow(), "logs 下没有电梯 ${config.id} 的回放记录")
        return
      }

      // 填充文件下拉（用 suppress 标志避免触发 switch）
      suppressComboEvent = true
      replayFileCombo.model = DefaultComboBoxModel(files.toTypedArray())
      // replay.log 是当前仍在追加的文件；优先打开它，否则打开最新轮转文件。
      val defaultFile = if (files.contains("replay.log")) "replay.log" else files.last()
      replayFileCombo.selectedItem = defaultFile
      suppressComboEvent = false
      replayFileCombo.isEnabled = true

      replayService.onFrameChanged = { frames, index, clockTs -> onReplayFrame(frames, index, clockTs) }
      if (!loadReplayFile(defaultFile)) {
        replayService.onFrameChanged = null
        replayService.close()
        onReplayStateChanged?.invoke(false)
        return
      }
      activeFile = defaultFile
      replayMode = true
      isVisible = true
      onFrameRendered?.invoke(replayService.allFrames, replayService.currentPosition)
      revalidate()
      doLayout()
      repaint()
      onReplayStateChanged?.invoke(true)
    } else {
      deactivate()
      revalidate()
    }
  }

  private fun deactivate() {
    replayMode = false
    activeFile = null
    replayService.onFrameChanged = null
    replayService.close()
    JinBoEventBus.setReplayMode(config.id, false)
    isVisible = false
    showEmptyReplay("无回放数据")
    onReplayStateChanged?.invoke(false)
  }

  private fun showEmptyReplay(message: String) {
    playPauseBtn.isEnabled = false
    replayFileCombo.isEnabled = false
    replayTimeline.setEmptyMessage(message)
    replayTimeline.setFrames(emptyList())
    replayTimeline.isEnabled = false
    playbackRateCombo.isEnabled = false
    replayTimeLabel.text = "--:--:--"
  }

  /** 通知开关和窗口回放模式状态，避免控件与实际数据源不一致。 */
  fun onReplayStateChanged(callback: (Boolean) -> Unit) {
    onReplayStateChanged = callback
  }

  fun onFrameRendered(callback: (frames: List<JinBoFrame>, position: JinBoReplayPosition) -> Unit) {
    onFrameRendered = callback
  }

  /** 构建回放控制行：上排（文件下拉 | 播放/暂停 | 时间跳转 | 播放倍速 | 时间标签）NORTH，时间轴 CENTER。 */
  private fun buildControlRow() {
    val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
      add(JLabel("日志文件:"))
      add(
        replayFileCombo.apply {
          addActionListener {
            if (isReplaying && !suppressComboEvent) {
              (selectedItem as? String)?.let { switchReplayFile(it) }
            }
          }
        },
      )
      add(
        playPauseBtn.apply {
          addActionListener { togglePlayPause() }
        },
      )
      add(
        JButton("-10s").apply {
          addActionListener { jumpReplayBy(-10_000L) }
        },
      )
      add(
        JButton("-1s").apply {
          addActionListener { jumpReplayBy(-1_000L) }
        },
      )
      add(
        JButton("+1s").apply {
          addActionListener { jumpReplayBy(1_000L) }
        },
      )
      add(
        JButton("+10s").apply {
          addActionListener { jumpReplayBy(10_000L) }
        },
      )
      add(JLabel("倍速:"))
      add(
        playbackRateCombo.apply {
          selectedItem = 1.0
          addActionListener { replayService.setPlaybackRate(selectedItem as Double) }
        },
      )
      add(JLabel("当前时间:"))
      add(replayTimeLabel)
    }

    add(buttonRow, BorderLayout.NORTH)
    add(replayTimeline, BorderLayout.CENTER)
  }

  /** 加载指定回放文件到当前 replayer，刷新时间轴并停在首帧 */
  private fun loadReplayFile(fileName: String): Boolean {
    val n = replayService.openFile(fileName)
    if (n == 0) {
      JOptionPane.showMessageDialog(parentWindow(), "$fileName 中没有电梯 ${config.id} 的回放帧")
      return false
    }
    replayFileCombo.isEnabled = true
    playbackRateCombo.isEnabled = true
    playPauseBtn.isEnabled = true
    playPauseBtn.text = "▶ 播放"
    replayTimeline.isEnabled = true
    replayTimeline.setFrames(replayService.allFrames)
    replayService.seekFirstFrame()
    return true
  }

  /** 切换回放文件（下拉框选择时） */
  private fun switchReplayFile(fileName: String) {
    if (loadReplayFile(fileName)) {
      activeFile = fileName
    } else {
      suppressComboEvent = true
      replayFileCombo.selectedItem = activeFile
      suppressComboEvent = false
    }
  }

  private fun togglePlayPause() {
    if (!isReplaying) return
    if (replayService.playOrPause()) {
      playPauseBtn.text = "⏸ 暂停"
    } else {
      playPauseBtn.text = "▶ 播放"
    }
  }

  /** 相对当前虚拟时钟跳跃；重新计时，避免播放中下一 tick 仍按旧的毫秒偏移。 */
  private fun jumpReplayBy(offsetMs: Long) {
    replayService.jumpBy(offsetMs)
  }

  /**
   * 回放帧变化回调：更新时间标签、时间轴游标（使用虚拟时钟）、日志三色分色（按帧 index）。
   */
  private fun onReplayFrame(frames: List<JinBoFrame>, index: Int, clockTs: Long) {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater { onReplayFrame(frames, index, clockTs) }
      return
    }
    replayTimeLabel.text = DateFormatUtils.format(Date(clockTs), "HH:mm:ss")
    replayTimeline.setCurrent(clockTs)
    onFrameRendered?.invoke(frames, JinBoReplayPosition(index, clockTs))
    // 播放到末尾时，按钮回到“播放”
    if (!replayService.isPlaying) {
      playPauseBtn.text = "▶ 播放"
    }
  }
}
