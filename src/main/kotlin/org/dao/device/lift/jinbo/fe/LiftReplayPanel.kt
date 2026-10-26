package org.dao.device.lift.jinbo.fe

import org.apache.commons.lang3.time.DateFormatUtils
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoFrame
import org.dao.device.lift.jinbo.JinBoReplayer
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

/**
 * 回放控制行：上排为文件选择、播放/暂停、跳转、快退/快进和当前时间，下排为时间轴。
 *
 * 开启时接管帧驱动，关闭时释放回放器；窗口调度器通过 [replaying] 暂停实时请求。
 */
class LiftReplayPanel(
  private val config: JinBoConfig,
  private val parentWindow: () -> Window,
  private val logPanel: LiftEventLogPanel,
) : JPanel(BorderLayout()) {

  private var replayer: JinBoReplayer? = null
  private var replaying = false

  val isReplaying: Boolean get() = replaying

  private val playPauseBtn = JButton("▶ 播放")
  private val replayTimeLabel = JLabel("--:--:--")
  private val replayTimeline = ReplayTimeline { ts -> replayer?.seek(ts) }
  private val replayFileCombo = JComboBox<String>()
  private var suppressComboEvent = false

  init {
    isVisible = false
    alignmentX = LEFT_ALIGNMENT
    // 在 Y_AXIS 中横向拉满、纵向锁高，避免被居中或纵向拉伸
    maximumSize = Dimension(Int.MAX_VALUE, 90)
    buildControlRow()
  }

  /** 打开/关闭回放模式：打开时加载并停在首帧，关闭时释放回放器并清空日志。 */
  fun setReplayEnabled(on: Boolean) {
    if (on) {
      val files = JinBoReplayer.listReplayFiles()
      if (files.isEmpty()) {
        JOptionPane.showMessageDialog(parentWindow(), "没有可回放的记录（logs 下无 replay 日志）")
        return
      }
      replayer = JinBoReplayer(config.id).apply {
        onFrameChanged = { frames, index, clockTs -> onReplayFrame(frames, index, clockTs) }
      }
      replaying = true
      isVisible = true

      // 填充文件下拉（用 suppress 标志避免触发 switch）
      suppressComboEvent = true
      replayFileCombo.model = DefaultComboBoxModel(files.toTypedArray())
      val defaultFile = if (files.contains("replay.log")) "replay.log" else files.last()
      replayFileCombo.selectedItem = defaultFile
      suppressComboEvent = false

      loadReplayFile(defaultFile)
      revalidate()
    } else {
      replayer?.dispose()
      replayer = null
      replaying = false
      isVisible = false
      logPanel.clearLog()
      revalidate()
    }
  }

  /**
   * 构建回放控制行：上排（文件下拉 | 播放/暂停 | 快退 | 快进 | 时间标签）NORTH，时间轴 CENTER（占满整行）。
   */
  private fun buildControlRow() {
    val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
      add(JLabel("日志文件:"))
      add(
        replayFileCombo.apply {
          addActionListener {
            if (replaying && !suppressComboEvent) {
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
      add(
        JButton("⏪ 快退").apply {
          addActionListener { replayer?.rewind() }
        },
      )
      add(
        JButton("⏩ 快进").apply {
          addActionListener { replayer?.fastForward() }
        },
      )
      add(JLabel("当前时间:"))
      add(replayTimeLabel)
    }

    add(buttonRow, BorderLayout.NORTH)
    add(replayTimeline, BorderLayout.CENTER)
  }

  /** 加载指定回放文件到当前 replayer，刷新时间轴并停在首帧 */
  private fun loadReplayFile(fileName: String) {
    val r = replayer ?: return
    r.pause()
    playPauseBtn.text = "▶ 播放"
    val n = r.load(fileName)
    logPanel.clearLog()
    if (n == 0) {
      replayTimeline.setFrames(emptyList())
      JOptionPane.showMessageDialog(parentWindow(), "该文件中没有电梯 ${config.id} 的回放帧")
      return
    }
    replayTimeline.setFrames(r.allFrames)
    r.seekIndex(0)
  }

  /** 切换回放文件（下拉框选择时） */
  private fun switchReplayFile(fileName: String) {
    loadReplayFile(fileName)
  }

  private fun togglePlayPause() {
    val r = replayer ?: return
    if (r.isPlaying) {
      r.pause()
      playPauseBtn.text = "▶ 播放"
    } else {
      r.play()
      playPauseBtn.text = "⏸ 暂停"
    }
  }

  /** 相对当前虚拟时钟跳跃；重新计时，避免播放中下一 tick 仍按旧的毫秒偏移。 */
  private fun jumpReplayBy(offsetMs: Long) {
    val r = replayer ?: return
    r.seek(r.currentTs + offsetMs)
    if (r.isPlaying) {
      r.play()
    }
  }

  /**
   * 回放帧变化回调：更新时间标签、时间轴游标（用虚拟时钟逐秒）、日志三色分色（按帧 index）。
   */
  private fun onReplayFrame(frames: List<JinBoFrame>, index: Int, clockTs: Long) {
    replayTimeLabel.text = DateFormatUtils.format(Date(clockTs), "HH:mm:ss")
    replayTimeline.setCurrent(clockTs)
    logPanel.renderReplayLogs(frames, index)
    // 播放到末尾时，按钮回到“播放”
    if (replayer?.isPlaying != true) {
      playPauseBtn.text = "▶ 播放"
    }
  }
}
