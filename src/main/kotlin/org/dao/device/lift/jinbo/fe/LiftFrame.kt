package org.dao.device.lift.jinbo.fe

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.dao.device.common.GuiEventListener
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoRecorder
import org.dao.device.lift.jinbo.JinBoServer
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

class LiftFrame(val config: JinBoConfig) : JFrame("JinBo Lift Monitor"), GuiEventListener {

  private val emergencyBanner = JLabel("当前处于急停状态", JLabel.CENTER).apply {
    isOpaque = true
    background = Color(200, 0, 0)
    foreground = Color.WHITE
    font = font.deriveFont(Font.BOLD, 15f)
    border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
    isVisible = false
  }

  private var showLiftStatus = false
  private lateinit var replayPanel: LiftReplayPanel
  private lateinit var eventLogPanel: LiftEventLogPanel

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

    val cpLeft = createLiftPanel("楼层", Color.PINK, LiftOutsidePanel(config))
    val cpCenter = createLiftPanel("当前位置", Color.WHITE, LiftPositionPanel(config))
    val cpRight = createLiftPanel("梯内", Color.ORANGE, LiftInsidePanel(config))
    val cpRight2 = createLiftPanel("TCP", Color.GREEN, LiftTcpPanel(config))
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

    eventLogPanel = LiftEventLogPanel(configId = config.id, onShowLiftStatusChanged = { showLiftStatus = it })
    replayPanel = LiftReplayPanel(config = config, parentWindow = { this }, logPanel = eventLogPanel)
    eventLogPanel.attachReplayPanel(replayPanel)
    add(eventLogPanel, BorderLayout.SOUTH)
  }

  private fun setupScheduler() {
    Timer(100) {
      // 回放模式下暂停实时拉取，界面由 replayer 驱动
      if (replayPanel.isReplaying) return@Timer
      val str = JinBoFetcher.fetch(config.id)
      // TODO 开关
      if (showLiftStatus) {
        logEvent(LiftEvent("${config.id}-状态", str))
      }
    }.apply { start() }
  }

  fun logEvent(event: LiftEvent) {
    // 仿真核心和 HTTP 请求不应依赖 Swing 是否可用；没有显示窗口时不构造 GUI。
    if (!isDisplayable) return

    // 缓冲到录制器，等待归入当前回放帧
    JinBoRecorder.bufferLog(config.id, "${event.topic}:${event.msg}")

    eventLogPanel.appendLog(event)
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
}
