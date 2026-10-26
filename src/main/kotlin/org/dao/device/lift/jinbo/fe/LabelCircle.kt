package org.dao.device.lift.jinbo.fe

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.dao.device.common.GuiEventListener
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoBtnService
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoReq
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 圆形内套文本，用于电梯内去指定楼层
 */
class LabelCircle(config: JinBoConfig, val floorIdx: Int, val labelStr: String, val circleR: Int, val labelSize: Int) :
  ClickAnimatedPanel(),
  GuiEventListener {

  private var active = false

  init {
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        // 按下日志必须先于请求提交打印；不能等到请求拿到电梯锁后才输出。
        System.err.println("按钮按下: 电梯=${config.id}, 面板=LiftInsidePanel, 楼层=$floorIdx")
        JinBoBtnService.pressInDoor(config.id, floorIdx)
      }
    })

    JinBoEventBus.register(config.id, this)
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2d = g as Graphics2D

    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    withPressedTransform(g2d) {
      val centerX = width / 2
      val centerY = height / 2

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
    if (event.topic == "inside") {
      val list: List<JinBoReq> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
      active = list.any { it.destFloor == floorIdx }
      repaint()
    }
  }
}
