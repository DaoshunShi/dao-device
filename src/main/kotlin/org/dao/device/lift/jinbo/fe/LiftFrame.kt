package org.dao.device.lift.jinbo.fe

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.apache.commons.lang3.time.DateFormatUtils
import org.dao.device.common.GuiEventListener
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.*
import java.awt.*
import java.util.*
import javax.swing.*
import javax.swing.Timer
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class LiftFrame(val config: JinBoConfig) : JFrame("JinBo Lift Monitor"), GuiEventListener {

  private val logArea = JTextPane().apply {
    isEditable = false
  }
  private val eventCounter = mutableMapOf<String, Int>()

  private val statusLabel = JLabel("状态: 等待事件...")

  // 回放相关
  private var replayer: JinBoReplayer? = null
  private var replaying = false
  private val replayControlRow = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isVisible = false
  }
  private val playPauseBtn = JButton("▶ 播放")
  private val replayTimeLabel = JLabel("--:--:--")
  private val replayTimeline = ReplayTimeline { ts -> replayer?.seek(ts) }

  // 回放日志三色
  private val colorPlayed = Color(140, 140, 140) // 已播放：灰
  private val colorCurrent = Color(20, 20, 20) // 当前帧：高亮（近黑加粗）
  private val colorFuture = Color(200, 200, 200) // 未播放：浅灰
  private val emergencyBanner = JLabel("当前处于急停状态", JLabel.CENTER).apply {
    isOpaque = true
    background = Color(200, 0, 0)
    foreground = Color.WHITE
    font = font.deriveFont(Font.BOLD, 15f)
    border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
    isVisible = false
  }

  private var showLiftStatus = false

  init {
    defaultCloseOperation = DISPOSE_ON_CLOSE
    setSize(1000, 700)

    setupUi()
    setupScheduler()
    JinBoEventBus.register(config.id, this)
    updateEmergencyBanner(JinBoServer.lifts[config.id]?.emergencyStopped == true)
  }

  override fun dispose() {
    super.dispose()
    JinBoServer.disposeLift(config.id)
  }

  private fun setupUi() {
    add(JinBoConfigPanel(config), BorderLayout.NORTH)

    val mainPanel = JPanel().apply {
      layout = GridLayout(1, 4, 5, 10)
      background = Color.LIGHT_GRAY
      border = BorderFactory.createEmptyBorder(5, 0, 0, 0)
    }

    val cpLeft = createBasePanel("楼层", Color.PINK, liftOutsidePanel(config))
    val cpCenter = createBasePanel("当前位置", Color.WHITE, curPosiPanel(config))
    val cpRight = createBasePanel("梯内", Color.ORANGE, liftInsidePanel(config))
    val cpRight2 = createBasePanel("TCP", Color.GREEN, liftInsidePanel2(config))
    mainPanel.add(cpLeft)
    mainPanel.add(cpCenter)
    mainPanel.add(cpRight)
    mainPanel.add(cpRight2)
    add(
      JPanel(BorderLayout()).apply {
        add(emergencyBanner, BorderLayout.NORTH)
        add(mainPanel, BorderLayout.CENTER)
      },
      BorderLayout.CENTER,
    )

    // 创建日志区域
    val logPanel = JPanel(BorderLayout()).apply {
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
            add(
              JButton("清除日志").apply {
                addActionListener {
                  logArea.text = ""
                  JinBoEventBus.fire(config.id, LiftEvent("清除日志", "清除日志按钮被点击"))
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
                addActionListener { toggleReplay(isSelected) }
              },
            )
            add(Box.createHorizontalStrut(10))
            add(
              JCheckBox("打印电梯状态").apply {
                addActionListener { showLiftStatus = isSelected }
              },
            )
          },
        )

        // 回放控制行（默认隐藏，开启回放后展开）
        buildReplayControlRow()
        add(replayControlRow)
      }
      add(bottom, BorderLayout.SOUTH)
      border = BorderFactory.createTitledBorder("事件日志")
    }
    add(logPanel, BorderLayout.SOUTH)
  }

  /**
   * 构建回放控制行：上排按钮（播放/暂停 | 快退 | 快进 | 时间标签），下方时间轴。
   */
  private fun buildReplayControlRow() {
    replayControlRow.removeAll()

    val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
      alignmentX = LEFT_ALIGNMENT
      add(
        playPauseBtn.apply {
          addActionListener { togglePlayPause() }
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

    replayTimeline.alignmentX = LEFT_ALIGNMENT
    replayControlRow.add(buttonRow)
    replayControlRow.add(replayTimeline)
  }

  /**
   * 打开/关闭回放模式。
   * 打开：暂停实时（跳过 fetch），加载帧并停在首帧。
   * 关闭：释放回放器，恢复实时。
   */
  private fun toggleReplay(on: Boolean) {
    if (on) {
      val r = JinBoReplayer(config.id)
      val n = r.load()
      if (n == 0) {
        JOptionPane.showMessageDialog(this, "没有可回放的记录（logs/replay.log 为空）")
        return
      }
      r.onFrameChanged = { frames, index -> onReplayFrame(frames, index) }
      replayer = r
      replaying = true
      replayControlRow.isVisible = true
      playPauseBtn.text = "▶ 播放"
      logArea.text = ""
      replayTimeline.setFrames(r.allFrames)
      r.seekIndex(0) // 渲染首帧
      revalidate()
    } else {
      replayer?.dispose()
      replayer = null
      replaying = false
      replayControlRow.isVisible = false
      logArea.text = ""
      revalidate()
    }
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

  /**
   * 回放帧变化回调：更新时间标签、时间轴游标、日志三色分色。
   */
  private fun onReplayFrame(frames: List<JinBoFrame>, index: Int) {
    val frame = frames.getOrNull(index) ?: return
    replayTimeLabel.text = DateFormatUtils.format(Date(frame.ts), "HH:mm:ss")
    replayTimeline.setCurrent(frame.ts)
    renderReplayLogs(frames, index)
    // 播放到末尾时，按钮回到“播放”
    if (replayer?.isPlaying != true) {
      playPauseBtn.text = "▶ 播放"
    }
  }

  /**
   * 按帧渲染回放日志并三色分色：
   * - 已播放帧(0..index-1)：灰
   * - 当前帧(index)：高亮加粗
   * - 未播放帧(index+1..)：浅灰
   * 每行前缀该帧时间戳，与实时日志格式一致。
   */
  private fun renderReplayLogs(frames: List<JinBoFrame>, index: Int) {
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

  private fun setupScheduler() {
    Timer(100) {
      // 回放模式下暂停实时拉取，界面由 replayer 驱动
      if (replaying) return@Timer
      val str = JinBoFetcher.fetch(config.id)
      // TODO 开关
      if (showLiftStatus) {
        logEvent(LiftEvent("${config.id}-状态", str))
      }
    }.apply { start() }
  }

  fun logEvent(event: LiftEvent) {
    // 更新事件计数器
    eventCounter[event.topic] = eventCounter.getOrDefault(event.topic, 0) + 1

    // 缓冲到录制器，等待归入当前回放帧
    JinBoRecorder.bufferLog(config.id, "${event.topic}:${event.msg}")

    // 添加时间戳和日志消息
    val timestamp = DateFormatUtils.format(Date(), "HH:mm:ss.SSS")
    appendLog("$timestamp| ${event.topic}:${event.msg}\n")

    // 更新状态
    updateStatus()
  }

  /** 向日志面板追加一行并滚动到底部（JTextPane 无 append，需操作 Document） */
  private fun appendLog(text: String) {
    val doc = logArea.styledDocument
    doc.insertString(doc.length, text, null)
    logArea.caretPosition = doc.length
  }

  private fun updateStatus() {
    val totalEvent = eventCounter.values.sum()
    val eventSummary = eventCounter.entries.joinToString("，") { "${it.key}：${it.value}" }
    statusLabel.text = "事件总数：$totalEvent | 详情：$eventSummary"
  }

  private fun updateEmergencyBanner(emergencyStopped: Boolean) {
    emergencyBanner.isVisible = emergencyStopped
  }

  override fun onEvent(event: LiftEvent) {
    if (event.topic != "liftState") {
      return
    }

    try {
      val map: Map<String, Any> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
      updateEmergencyBanner(map["emergencyStopped"] as? Boolean == true)
    } catch (_: Exception) {
    }
  }

  // 创建外部框
  fun createBasePanel(title: String, baseColor: Color, child: JPanel): JPanel = JPanel().apply {
    layout = BorderLayout()
    background = baseColor

    // 顶部标题栏
    val titlePanel = JPanel().apply {
      background = baseColor.darker()
      preferredSize = Dimension(0, 30)

      val titleLabel = JLabel(title).apply { foreground = Color.WHITE }
      add(titleLabel)
    }
    add(titlePanel, BorderLayout.NORTH)

    // 中心区域
    add(child, BorderLayout.CENTER)
  }

  fun liftOutsidePanel(config: JinBoConfig): JPanel = JPanel().apply {
    // 获取启用的楼层并按索引降序排列（顶层在上）
    val enabledFloors = config.floors
      .filter { !it.disabled }
      .sortedByDescending { it.index }

    // 动态设置网格布局：启用的楼层数 x 1
    layout = GridLayout(enabledFloors.size, 1, 0, 5)

    for (floor in enabledFloors) {
      add(outSideFloorBtn(config, floor.index, floor.label))
    }
  }

  /**
   * 外部楼层面版
   */
  fun outSideFloorBtn(config: JinBoConfig, floorIdx: Int, floorLabel: String): JPanel = JPanel().apply {
    layout = GridLayout(1, 3, 0, 0)
    val label = JLabel(floorLabel, JLabel.CENTER).apply { font = font.deriveFont(Font.BOLD, 24f) }
    add(label)
    add(TriangleCircle(config, floorIdx, 20, 15, true))
    add(TriangleCircle(config, floorIdx, 20, 15, false))
  }

  /**
   * 电梯位置面版
   */
  fun curPosiPanel(config: JinBoConfig): JPanel = JPanel().apply {
    layout = BorderLayout()
    // 使用电梯初始高度0.0米，事件会更新实际高度
    add(Cage(config, 20, 30, 0.0, JinBoDoorStatus.CLOSE))
  }

  /**
   * 梯内面版
   */
  fun liftInsidePanel(config: JinBoConfig): JPanel = JPanel().apply {
    // 获取启用的楼层并按索引降序排列（顶层在上）
    val enabledFloors = config.floors
      .filter { !it.disabled }
      .sortedByDescending { it.index }

    // 动态设置网格布局：启用的楼层数 + 2（开门/关门按钮） x 1
    layout = GridLayout(enabledFloors.size + 2, 1, 0, 5)

    for (floor in enabledFloors) {
      add(insideFloorBtn(config, floor.index, floor.label))
    }
    add(
      JButton("开门").apply {
        addActionListener {
          val floor = JinBoServer.lifts[config.id]?.curFloor ?: 0
          // 开门单独记 in-open，故 request 传 log=false 抑制默认 in-goto
          JinBoServer.request(config.id, JinBoReq(destFloor = floor, source = JinBoReqSource.InDoor), log = false)
          JinBoServer.logEvent(config.id, LiftEvent("in-open", "destFloor=$floor"))
        }
      },
    )
    add(
      JButton("关门").apply {
        addActionListener {
          JinBoServer.logEvent(config.id, LiftEvent("in-close", "关门按钮"))
          JinBoServer.close(config.id)
        }
      },
    )
  }

  fun insideFloorBtn(config: JinBoConfig, floorIdx: Int, floorLabel: String): JPanel = JPanel().apply {
    layout = BorderLayout()
    // add(JButton(floorLabel))
    add(LabelCircle(config, floorIdx, floorLabel, 20, 20))
  }

  /**
   * 总控面版
   */
  fun liftInsidePanel2(config: JinBoConfig): JPanel = JPanel().apply {
    // 获取启用的楼层并按索引降序排列（顶层在上）
    val enabledFloors = config.floors
      .filter { !it.disabled }
      .sortedByDescending { it.index }

    // 动态设置网格布局：启用的楼层数 + 2（预留空间） x 1
    layout = GridLayout(enabledFloors.size + 2, 1, 0, 5)

    for (floor in enabledFloors) {
      add(insideFloorBtn2(config, floor.index, floor.label))
    }
    add(
      JButton("关门").apply {
        addActionListener {
          JinBoServer.logEvent(config.id, LiftEvent("tcp-btn-close", "TCP栏关门按钮"))
          JinBoServer.close(config.id)
        }
      },
    )
  }

  fun insideFloorBtn2(config: JinBoConfig, floorIdx: Int, floorLabel: String): JPanel = JPanel().apply {
    layout = BorderLayout()
    // add(JButton(floorLabel))
    add(LabelCircle2(config, floorIdx, floorLabel, 20, 20))
  }
}
