package org.dao.device.lift.jinbo.fe

import org.apache.commons.lang3.time.DateFormatUtils
import org.dao.device.lift.jinbo.*
import java.awt.*
import java.util.*
import javax.swing.*
import javax.swing.Timer

class LiftFrame(val config: JinBoConfig) : JFrame("JinBo Lift Monitor") {

  private val logArea = JTextArea(10, 30).apply {
    isEditable = false
  }
  private val eventCounter = mutableMapOf<String, Int>()

  private val statusLabel = JLabel("状态: 等待事件...")

  private var showLiftStatus = false

  init {
    defaultCloseOperation = DISPOSE_ON_CLOSE
    setSize(1000, 700)

    setupUi()
    setupScheduler()
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
    }

    val cpLeft = createBasePanel("楼层", Color.PINK, liftOutsidePanel(config))
    val cpCenter = createBasePanel("当前位置", Color.WHITE, curPosiPanel(config))
    val cpRight = createBasePanel("梯内", Color.ORANGE, liftInsidePanel(config))
    val cpRight2 = createBasePanel("TCP", Color.GREEN, liftInsidePanel2(config))
    mainPanel.add(cpLeft)
    mainPanel.add(cpCenter)
    mainPanel.add(cpRight)
    mainPanel.add(cpRight2)
    add(mainPanel)

    // 创建日志区域
    val logPanel = JPanel(BorderLayout()).apply {
      add(JScrollPane(logArea), BorderLayout.CENTER)
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
            JCheckBox("打印电梯状态").apply {
              addActionListener { showLiftStatus = isSelected }
            },
          )
        },
        BorderLayout.SOUTH,
      )
      border = BorderFactory.createTitledBorder("事件日志")
    }
    add(logPanel, BorderLayout.SOUTH)
  }

  private fun setupScheduler() {
    Timer(100) {
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

    // 添加时间戳和日志消息
    val timestamp = DateFormatUtils.format(Date(), "HH:mm:ss.SSS")
    logArea.append("$timestamp| ${event.topic}:${event.msg}\n")

    // 自动滚动到底部
    logArea.caretPosition = logArea.document.length

    // 更新状态
    updateStatus()
  }

  private fun updateStatus() {
    val totalEvent = eventCounter.values.sum()
    val eventSummary = eventCounter.entries.joinToString("，") { "${it.key}：${it.value}" }
    statusLabel.text = "事件总数：$totalEvent | 详情：$eventSummary"
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
    layout = GridLayout(4, 1, 0, 5)

    val floorMap = mapOf(4 to "4F", 3 to "3F", 2 to "2F", 1 to "1F")
    for ((idx, lbl) in floorMap) {
      add(outSideFloorBtn(config, idx, lbl))
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
    add(LiftAndDoor(config, 20, 30, 4, 0.0, JinBoDoorStatus.CLOSE))
  }

  /**
   * 梯内面版
   */
  fun liftInsidePanel(config: JinBoConfig): JPanel = JPanel().apply {
    // val floorList = listOf("4", "3", "3", "1")
    val floors = mapOf(4 to "4F", 3 to "3F", 2 to "2F", 1 to "1F")

    layout = GridLayout(floors.size + 2, 1, 0, 5)
    for ((idx, lbl) in floors) {
      add(insideFloorBtn(config, idx, lbl))
    }
    add(
      JButton("开门").apply {
        addActionListener {
          JinBoServer.request(
            config.id,
            JinBoReq(destFloor = JinBoServer.lifts[config.id]?.curFloor ?: 0, source = JinBoReqSource.InDoor),
          )
        }
      },
    )
    add(
      JButton("关门").apply {
        addActionListener {
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
    val floors = mapOf(4 to "4F", 3 to "3F", 2 to "2F", 1 to "1F")

    layout = GridLayout(floors.size + 2, 1, 0, 5)
    for ((idx, lbl) in floors) {
      add(insideFloorBtn2(config, idx, lbl))
    }
  }

  fun insideFloorBtn2(config: JinBoConfig, floorIdx: Int, floorLabel: String): JPanel = JPanel().apply {
    layout = BorderLayout()
    // add(JButton(floorLabel))
    add(LabelCircle2(config, floorIdx, floorLabel, 20, 20))
  }
}