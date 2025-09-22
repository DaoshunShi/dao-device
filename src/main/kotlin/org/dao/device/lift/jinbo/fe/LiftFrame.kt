package org.dao.device.lift.jinbo.fe

import org.apache.commons.lang3.time.DateFormatUtils
import org.dao.device.lift.jinbo.JinBoConfig
import java.awt.BorderLayout
import java.awt.Color
import java.awt.GridLayout
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
    defaultCloseOperation = EXIT_ON_CLOSE
    setSize(1000, 700)

    setupUi()
    setupScheduler()
  }

  private fun setupUi() {
    add(JinBoConfigPanel(config), BorderLayout.NORTH)

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
      add(JScrollPane(logArea), BorderLayout.CENTER)
      add(
        JPanel().apply {
          layout = BoxLayout(this, BoxLayout.X_AXIS)
          add(
            JButton("清除日志").apply {
              addActionListener {
                logArea.text = ""
                JinBoEventBus.fire(LiftEvent("清除日志", "清除日志按钮被点击"))
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
      val str = JinBoFetcher.fetch()
      // TODO 开关
      if (showLiftStatus) {
        logEvent(LiftEvent("定时器-电梯状态", str))
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
}