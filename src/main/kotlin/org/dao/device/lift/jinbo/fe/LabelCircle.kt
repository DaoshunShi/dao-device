package org.dao.device.lift.jinbo.fe

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.dao.device.common.GuiEventListener
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoCommandService
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoReq
import org.dao.device.lift.jinbo.JinBoReqSource
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * 圆形内套文本，用于电梯内去指定楼层
 */
class LabelCircle(
  private val config: JinBoConfig,
  val floorIdx: Int,
  val labelStr: String,
  val circleR: Int,
  val labelSize: Int,
  private val source: JinBoReqSource,
  private val eventTopic: String,
) : ClickAnimatedPanel(),
  GuiEventListener {

  private var active = false

  init {
    JinBoEventBus.register(config.id, this)
  }

  override fun onClick() {
    JinBoCommandService.pressButton(config.id, JinBoReq(floorIdx, source))
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2d = g as Graphics2D

    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    withPressedTransform(g2d) {
      val centerX = width / 2
      val centerY = height / 2
      val isActive = active

      // 绘制圆圈
      g2d.color = if (isActive) Color.BLUE else Color.gray
      g2d.stroke = BasicStroke(3.0f)
      g2d.drawOval(centerX - circleR, centerY - circleR, circleR * 2, circleR * 2)
      if (isActive) {
        g2d.color = Color.ORANGE
        g2d.fillOval(centerX - circleR, centerY - circleR, circleR * 2, circleR * 2)
      }

      // 绘制文本
      g2d.color = if (isActive) Color.WHITE else Color.DARK_GRAY
      g2d.font = Font("Arial", Font.PLAIN, labelSize)
      val fontMetrics = g2d.fontMetrics
      val textWidth = fontMetrics.stringWidth(labelStr)
      val textHeight = fontMetrics.height
      g2d.drawString(labelStr, (width - textWidth) / 2, (height - textHeight) / 2 + fontMetrics.ascent)
    }
    paintPressedOverlay(g2d)
  }

  override fun onEvent(event: LiftEvent) {
    if (event.topic != eventTopic) return
    val reqs = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef<List<JinBoReq>>())
    active = reqs.any { it.destFloor == floorIdx }
    repaint()
  }
}
