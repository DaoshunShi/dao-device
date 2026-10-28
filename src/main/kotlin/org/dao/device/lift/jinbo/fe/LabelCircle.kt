package org.dao.device.lift.jinbo.fe

import org.dao.device.common.GuiEventListener
import org.dao.device.lift.jinbo.JinBoBtnService
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoReq
import org.dao.device.lift.jinbo.JinBoReqSource
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 圆形内套文本，用于电梯内去指定楼层
 */
class LabelCircle(private val config: JinBoConfig, val floorIdx: Int, val labelStr: String, val circleR: Int, val labelSize: Int) :
  ClickAnimatedPanel(),
  GuiEventListener {

  init {
    JinBoEventBus.register(config.id, this)
  }

  override fun onClick() {
    JinBoBtnService.pressButtonLater(config.id, JinBoReq(floorIdx, JinBoReqSource.InDoor))
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2d = g as Graphics2D

    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    withPressedTransform(g2d) {
      val centerX = width / 2
      val centerY = height / 2
      val active = JinBoBtnService.isInsideActive(config.id, floorIdx)

      // 绘制圆圈
      g2d.color = if (active) Color.BLUE else Color.gray
      g2d.stroke = BasicStroke(3.0f)
      g2d.drawOval(centerX - circleR, centerY - circleR, circleR * 2, circleR * 2)
      if (active) {
        g2d.color = Color.ORANGE
        g2d.fillOval(centerX - circleR, centerY - circleR, circleR * 2, circleR * 2)
      }

      // 绘制文本
      g2d.color = if (active) Color.WHITE else Color.DARK_GRAY
      g2d.font = Font("Arial", Font.PLAIN, labelSize)
      val fontMetrics = g2d.fontMetrics
      val textWidth = fontMetrics.stringWidth(labelStr)
      val textHeight = fontMetrics.height
      g2d.drawString(labelStr, (width - textWidth) / 2, (height - textHeight) / 2 + fontMetrics.ascent)
    }
    paintPressedOverlay(g2d)
  }

  /**
   * 创建三角形
   * 顺时针转的
   */
  private fun createTriangle(centerX: Int, centerY: Int, size: Int, rotation: Double): Polygon {
    val xPoints = IntArray(3)
    val yPoints = IntArray(3)

    // 计算三角形的三个顶点
    for (i in 0 until 3) {
      val angle = rotation + 2 * PI * i / 3
      xPoints[i] = (centerX + size * cos(angle)).toInt()
      yPoints[i] = (centerY + size * sin(angle)).toInt()
    }

    return Polygon(xPoints, yPoints, 3)
  }

  override fun onEvent(event: LiftEvent) {
    // 旧事件可能在最新点击之后到达，按钮状态始终从 JinBoBtnService 读取。
    if (event.topic == "inside") repaint()
  }
}
