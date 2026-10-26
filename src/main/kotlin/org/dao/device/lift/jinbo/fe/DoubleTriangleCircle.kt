package org.dao.device.lift.jinbo.fe

import org.dao.device.common.GuiEventListener
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import javax.swing.JPanel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 圆形内套双三角，用于内部开关电梯门
 */
class DoubleTriangleCircle(config: JinBoConfig, circleR: Int, triSize: Int, triRad: Double) :
  JPanel(),
  GuiEventListener {

  // 圆圈和三角形的属性
  private var circleRadius = circleR
  private var triangleSize = triSize
  private var rotationAngle = triRad

  // 颜色设置
  private val circleColor = Color.BLUE
  private val triangleColor = Color.RED
  private val backgroundColor = Color.WHITE

  init {
    background = backgroundColor

    JinBoEventBus.register(config.id, this)
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2d = g as Graphics2D

    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val centerX = width / 2
    val centerY = height / 2

    // 绘制圆圈
    g2d.color = circleColor
    g2d.stroke = BasicStroke(3.0f)
    g2d.drawOval(
      centerX - circleRadius,
      centerY - circleRadius,
      circleRadius * 2,
      circleRadius * 2,
    )

    // 计算三角形顶点
    val triangle = createTriangle(centerX, centerY, triangleSize, rotationAngle)

    // 绘制三角形
    g2d.color = triangleColor
    g2d.fillPolygon(triangle)
    g2d.color = Color.BLACK
    g2d.drawPolygon(triangle)
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
    // if (event.topic == "inside") {
    //   val list: List<JinBoReq> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
    //   active =
    //     list.any {
    //       it.destFloor == floorIdx &&
    //         ((up && it.type == JinBoLiftStatus.Up) || (!up && it.type == JinBoLiftStatus.Down))
    //     }
    //   repaint()
    // }
  }
}
