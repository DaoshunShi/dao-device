package org.dao.device.lift.jinbo.gui

import org.dao.device.lift.jinbo.JinBoDoorStatus
import org.dao.device.lift.jinbo.JinBoServer
import java.awt.*
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.Timer

// fun main() {
//   // render()
//   val frame = LiftFrame()
//   frame.isVisible = true
// }

class LiftFrame : JFrame("JinBo Lift Monitor") {

  private val logArea = JTextArea(10, 30).apply {
    isEditable = false
  }
  private val eventCounter = mutableMapOf<String, Int>()

  private val statusLabel = JLabel("状态: 等待事件...")

  init {
    defaultCloseOperation = EXIT_ON_CLOSE
    setSize(1000, 600)

    setupUi()
    setupScheduler()
  }

  private fun setupUi() {
    val mainPanel = JPanel().apply {
      layout = GridLayout(1, 4, 5, 10)
      background = Color.LIGHT_GRAY
    }

    val cpLeft = createBasePanel("楼层", Color.PINK, liftOutsidePanel())
    val cpCenter = createBasePanel("当前位置", Color.WHITE, curPosiPanel())
    val cpRight = createBasePanel("梯内", Color.ORANGE, liftInsidePanel())
    val cpRight2 = createBasePanel("所有", Color.GREEN, liftInsidePanel2())
    mainPanel.add(cpLeft)
    mainPanel.add(cpCenter)
    mainPanel.add(cpRight)
    mainPanel.add(cpRight2)
    add(mainPanel)

    // 创建日志区域
    val logPanel = JPanel(BorderLayout()).apply {
      add(
        JButton("清除日志").apply {
          addActionListener {
            logArea.text = ""
            JinBoEventBus.fire(LiftEvent("清除日志", "清除日志按钮被点击"))
          }
        },
        BorderLayout.NORTH,
      )
      add(JScrollPane(logArea), BorderLayout.CENTER)
      // add(statusLabel, BorderLayout.SOUTH)

      add(statusLabel, BorderLayout.SOUTH)
      border = BorderFactory.createTitledBorder("事件日志")
    }
    add(logPanel, BorderLayout.SOUTH)
  }

  private fun setupScheduler() {
    Timer(100) {
      logEvent(LiftEvent("定时器时间", "获取当前电梯状态"))
      val str = JinBoFetcher.fetch()
      logEvent(LiftEvent("定时器时间", str))
    }.apply { start() }
  }

  private fun logEvent(event: LiftEvent) {
    // 更新事件计数器
    eventCounter[event.topic] = eventCounter.getOrDefault(event.topic, 0) + 1

    // 添加时间戳和日志消息
    val timestamp = System.currentTimeMillis()
    logArea.append("$timestamp| ${event.topic}:${event.msg}\n")

    // 自动滚动到底部
    logArea.caretPosition = logArea.document.length

    // 更新状态
    updateStatus()
  }

  private fun updateStatus() {
    val totalEvent = eventCounter.values.sum()
    val eventSummary = eventCounter.entries.joinToString("，") { "${it.key}：${it.value}" }
    statusLabel.text = "状态：总事件数 $totalEvent，详情：$eventSummary"
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

fun liftOutsidePanel(): JPanel = JPanel().apply {
  layout = GridLayout(4, 1, 0, 5)

  val floorMap = mapOf(4 to "4F", 3 to "3F", 2 to "2F", 1 to "1F")
  for ((idx, lbl) in floorMap) {
    add(outSideFloorBtn(idx, lbl))
  }
}

/**
 * 外部楼层面版
 */
fun outSideFloorBtn(floorIdx: Int, floorLabel: String): JPanel = JPanel().apply {
  layout = GridLayout(1, 3, 0, 0)
  val label = JLabel(floorLabel, JLabel.CENTER).apply { font = font.deriveFont(Font.BOLD, 24f) }
  add(label)
  add(TriangleCircle(floorIdx, 20, 15, true))
  add(TriangleCircle(floorIdx, 20, 15, false))
}

/**
 * 电梯位置面版
 */
fun curPosiPanel(): JPanel = JPanel().apply {
  layout = BorderLayout()
  add(LiftAndDoor(20, 30, 4, 0.0, JinBoDoorStatus.CLOSE))
}

/**
 * 梯内面版
 */
fun liftInsidePanel(): JPanel = JPanel().apply {
  // val floorList = listOf("4", "3", "3", "1")
  val floors = mapOf(4 to "4F", 3 to "3F", 2 to "2F", 1 to "1F")

  layout = GridLayout(floors.size + 2, 1, 0, 5)
  for ((idx, lbl) in floors) {
    add(insideFloorBtn(idx, lbl))
  }
  add(JButton("开门"))
  add(
    JButton("关门").apply {
      addActionListener {
        JinBoServer.close("A")
      }
    },
  )
}

fun insideFloorBtn(floorIdx: Int, floorLabel: String): JPanel = JPanel().apply {
  layout = BorderLayout()
  // add(JButton(floorLabel))
  add(LabelCircle(floorIdx, floorLabel, 20, 20))
}

/**
 * 总控面版
 */
fun liftInsidePanel2(): JPanel = JPanel().apply {
  // val floorList = listOf("4", "3", "3", "1")
  val floors = mapOf(4 to "4F", 3 to "3F", 2 to "2F", 1 to "1F")

  layout = GridLayout(floors.size + 2, 1, 0, 5)
  for ((idx, lbl) in floors) {
    add(insideFloorBtn2(idx, lbl))
  }
  // add(JButton("开门"))
  // add(JButton("关门"))
}

fun insideFloorBtn2(floorIdx: Int, floorLabel: String): JPanel = JPanel().apply {
  layout = BorderLayout()
  // add(JButton(floorLabel))
  add(LabelCircle2(floorIdx, floorLabel, 20, 20))
}