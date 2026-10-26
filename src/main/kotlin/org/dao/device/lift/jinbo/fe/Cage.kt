package org.dao.device.lift.jinbo.fe

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.dao.device.common.GuiEventListener
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoDoor
import org.dao.device.lift.jinbo.JinBoDoorStatus
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel
import kotlin.math.round

/**
 * 画电梯
 */
class Cage(
  private val config: JinBoConfig, // 电梯配置
  val lw: Int, // 梯厢宽度
  val lh: Int, // 梯厢高度
  h0: Double, // 电梯当前高度（米）
  status0: JinBoDoorStatus, // 电梯门状态
) : JPanel(),
  GuiEventListener {
  private var elevatorHeightMeters = h0 // 电梯当前高度（米）
  private var status = status0
  private var doors: List<JinBoDoor> = listOf(JinBoDoor(code = 0, status = status0))
  private var carWidth: Int = lw // 梯厢宽度随门数动态调整
  private val maxHeight: Double
  private val minHeight: Double

  init {
    // 计算电梯井的高度范围
    val floorHeights = config.floors.map { it.height }
    minHeight = floorHeights.minOrNull() ?: 0.0
    maxHeight = floorHeights.maxOrNull() ?: 0.0

    // 初始计算电梯位置
    updateHeight(h0)

    JinBoEventBus.register(config.id, this)
  }

  /**
   * 更新电梯高度（米）
   */
  private fun updateHeight(elevatorHeightMeters: Double) {
    this.elevatorHeightMeters = elevatorHeightMeters
    // 位置将在paintComponent中重新计算
  }

  /**
   * 计算电梯在面板中的像素位置
   */
  private fun calculateHPosition(): Int {
    if (height <= 0) {
      // println("[Cage DEBUG] Panel height is 0, cannot calculate position")
      return 0 // 面板未初始化
    }

    val heightRange = maxHeight - minHeight
    if (heightRange <= 0) {
      // println("[Cage DEBUG] Height range is $heightRange (min: $minHeight, max: $maxHeight)")
      return 0
    }

    // 将电梯高度归一化到 [0, 1] 范围
    val normalizedHeight = (elevatorHeightMeters - minHeight) / heightRange
    val pixelPosition = round(normalizedHeight * (height - lh)).toInt().coerceIn(0, height - lh)

    // 调试输出
    // println("[Cage DEBUG] calculateHPosition: height=$height, lh=$lh, elevatorHeight=$elevatorHeightMeters, normalized=$normalizedHeight, pixelPos=$pixelPosition")

    // 转换为面板中的位置（从底部开始）
    // 注意：面板坐标原点在左上角，所以需要从底部计算
    return pixelPosition
  }

  override fun paintComponent(g: Graphics?) {
    background = Color.WHITE

    val g2d = g as Graphics2D

    // 计算电梯当前位置
    val h = calculateHPosition()

    // 清除背景
    g2d.color = background
    g2d.fillRect(0, 0, width, height)

    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val centerX = width / 2
    val carX = centerX - carWidth / 2

    // 绘制文本
    g2d.color = Color.black
    g2d.font = Font("Arial", Font.PLAIN, 20)
    val text = statusLabel(status)
    val fontMetrics = g2d.fontMetrics
    val textWidth = fontMetrics.stringWidth(text)
    val textHeight = fontMetrics.height
    g2d.drawString(text, (width - textWidth) / 2, height - lh - h - textHeight / 2)

    // 门数大于 1 时按门号从左到右依次绘制，单门保持原有梯厢尺寸和样式。
    val doorWidth = (carWidth - (doors.size + 1) * DOOR_GAP) / doors.size
    doors.forEachIndexed { index, door ->
      val x = carX + DOOR_GAP + index * (doorWidth + DOOR_GAP)
      drawDoor(g2d, door, x, height - lh - h, doorWidth, lh)
    }

    // 绘制一条线：上边
    g2d.color = Color.LIGHT_GRAY
    g2d.drawLine(centerX, 0, centerX, height - lh - h)

    // 绘制一条线：下边
    g2d.drawLine(centerX, height - h, centerX, height)

    // 绘制楼层线条（根据实际楼层高度）：
    val heightRange = maxHeight - minHeight
    if (heightRange > 0) {
      // 按高度排序楼层（从低到高），过滤掉被禁用的楼层
      val sortedFloors = config.floors.filter { !it.disabled }.sortedBy { it.height }
      for (floor in sortedFloors) {
        // 计算楼层在面板中的位置
        val normalizedPosition = (floor.height - minHeight) / heightRange
        val y = height - round(normalizedPosition * (height - lh)).toInt()
        g2d.drawLine(0, y, width, y)

        // 可选：在楼层线上添加标签
        val label = floor.label
        // 重新获取fontMetrics以确保作用域正确
        val floorFontMetrics = g2d.fontMetrics
        val labelWidth = floorFontMetrics.stringWidth(label)
        g2d.drawString(label, width - labelWidth - 5, y - 2)
      }
    }
  }

  /** 按原梯厢样式绘制单扇门，并用文字标出该门的状态。 */
  private fun drawDoor(g2d: Graphics2D, door: JinBoDoor, x: Int, y: Int, width: Int, height: Int) {
    g2d.color = door.status.displayColor
    g2d.fillRect(x, y, width, height)

    g2d.color = when (door.status) {
      JinBoDoorStatus.OPEN -> Color.DARK_GRAY
      JinBoDoorStatus.ERROR -> Color.RED
      else -> Color.LIGHT_GRAY
    }
    g2d.drawRect(x, y, width, height)

    g2d.font = Font("Arial", Font.PLAIN, 10)
    val statusText = doorLabel(g2d, door, width)
    val fittedLabel = "${door.code} $statusText"
    val labelWidth = g2d.fontMetrics.stringWidth(fittedLabel)
    val labelHeight = g2d.fontMetrics.height
    val labelX = x + (width - labelWidth) / 2
    val labelY = y + (height + labelHeight) / 2 - 3
    g2d.color = when {
      door.status == JinBoDoorStatus.ERROR -> Color.WHITE
      door.status == JinBoDoorStatus.CLOSE -> Color.BLACK
      else -> Color.DARK_GRAY
    }
    g2d.drawString(fittedLabel, labelX, labelY)
  }

  /** 计算包含所有门的梯厢宽度，避免门数增加后每扇门被挤压。 */
  fun preferredCarWidth(): Int = PREFERRED_DOOR_WIDTH * doors.size + DOOR_GAP * (doors.size + 1)

  private fun statusLabel(status: JinBoDoorStatus): String = when (status) {
    JinBoDoorStatus.OPEN -> "Opened"
    JinBoDoorStatus.OPENING -> "Opening"
    JinBoDoorStatus.CLOSE -> "Closed"
    JinBoDoorStatus.CLOSING -> "Closing"
    JinBoDoorStatus.ERROR -> "Fault"
  }

  private fun doorLabel(g2d: Graphics2D, door: JinBoDoor, maxWidth: Int): String {
    val labels = when (door.status) {
      JinBoDoorStatus.OPEN -> listOf("Opened", "Open", "O")
      JinBoDoorStatus.OPENING -> listOf("Opening", "Open..", "O..")
      JinBoDoorStatus.CLOSE -> listOf("Closed", "Shut", "C")
      JinBoDoorStatus.CLOSING -> listOf("Closing", "Close..", "C..")
      JinBoDoorStatus.ERROR -> listOf("Fault", "Error", "E")
    }
    return labels.first { g2d.fontMetrics.stringWidth("${door.code} $it") <= maxWidth }
  }

  override fun onEvent(event: LiftEvent) {
    if (event.topic == "liftState") {
      try {
        val map: Map<String, Any> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
        // JsonHelper 按字段类型反序列化，height 为 Double，同时兼容手工状态/回放数据中的整数高度。
        val elevatorHeight = (map["h"] as Number).toDouble()
        // 调试输出
        // println("[Cage DEBUG] Received elevator height: $elevatorHeight meters, minHeight: $minHeight, maxHeight: $maxHeight")
        updateHeight(elevatorHeight)
        status = JinBoDoorStatus.valueOf(map["doorStatus"] as String)
        val rawDoors = map["doors"] as? List<*>
        doors = if (rawDoors.isNullOrEmpty()) {
          listOf(JinBoDoor(code = 0, status = status))
        } else {
          parseDoors(rawDoors).takeIf { it.isNotEmpty() } ?: listOf(JinBoDoor(code = 0, status = status))
        }
        carWidth = maxOf(lw, preferredCarWidth())
        repaint()
      } catch (e: Exception) {
        println("[Cage ERROR] Failed to process liftState event: ${e.message}")
        e.printStackTrace()
      }
    }
  }

  private fun parseDoors(rawDoors: List<*>): List<JinBoDoor> = rawDoors.mapNotNull { rawDoor ->
    val door = rawDoor as? Map<*, *> ?: return@mapNotNull null
    val code = (door["code"] as? Number)?.toInt() ?: return@mapNotNull null
    val doorStatus = (door["status"] as? String)?.let { runCatching { JinBoDoorStatus.valueOf(it) }.getOrNull() }
      ?: return@mapNotNull null
    JinBoDoor(
      code = code,
      status = doorStatus,
    )
  }

  override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
    val oldHeight = this.height
    super.setBounds(x, y, width, height)
    // 如果面板大小改变，重新计算电梯位置
    if (height != oldHeight && height > 0) {
      // 需要重新计算当前电梯高度的像素位置
      // 但当前电梯高度未知，需要从事件或状态获取
      // 暂时不处理，等待下一次事件更新
    }
  }

  private companion object {
    const val DOOR_GAP = 3
    const val PREFERRED_DOOR_WIDTH = 64
  }
}
