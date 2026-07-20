package org.dao.device.lift.jinbo.fe

import org.apache.commons.lang3.time.DateFormatUtils
import org.dao.device.lift.jinbo.JinBoFrame
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JPanel

/**
 * 回放时间轴
 *
 * 只显示当前帧所在“自然整点小时”窗口 [hourStart, hourStart+1h)。
 * 当前帧跨小时时窗口整块切换。轨道横向占满整行。
 * 点击或拖动 → 映射到窗口内时间 → 回调 onSeek(ts)。
 */
class ReplayTimeline(
  private val onSeek: (ts: Long) -> Unit,
) : JPanel() {

  companion object {
    private const val HOUR_MS = 3600_000L
    private const val TICK_MS = 300_000L // 刻度间隔：5 分钟
  }

  private var frames: List<JinBoFrame> = emptyList()
  private var currentTs: Long = 0
  private var windowStart: Long = 0 // 当前可视小时窗起点（整点）

  init {
    background = Color.WHITE
    preferredSize = Dimension(0, 44)
    // 在 BoxLayout.Y_AXIS 中允许横向拉伸占满整行（高度锁定 44）
    maximumSize = Dimension(Int.MAX_VALUE, 44)
    val onMouse = { x: Int ->
      if (frames.isNotEmpty()) onSeek(xToTs(x))
    }
    addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) = onMouse(e.x)
      override fun mouseClicked(e: MouseEvent) = onMouse(e.x)
    })
    addMouseMotionListener(object : MouseMotionAdapter() {
      override fun mouseDragged(e: MouseEvent) = onMouse(e.x)
    })
  }

  /** 设置帧序列（打开回放时调用一次） */
  fun setFrames(frames: List<JinBoFrame>) {
    this.frames = frames
    currentTs = frames.firstOrNull()?.ts ?: 0
    windowStart = hourFloor(currentTs)
    repaint()
  }

  /** 更新游标到指定帧时间；跨小时则切换窗口 */
  fun setCurrent(ts: Long) {
    currentTs = ts
    windowStart = hourFloor(ts)
    repaint()
  }

  private fun hourFloor(ts: Long): Long = (ts / HOUR_MS) * HOUR_MS
  private val windowEnd get() = windowStart + HOUR_MS

  /** 时间 → x 像素（基于当前小时窗，占满整行） */
  private fun tsToX(ts: Long): Int {
    val ratio = ((ts - windowStart).toDouble() / HOUR_MS).coerceIn(0.0, 1.0)
    return (ratio * width).toInt()
  }

  /** x 像素 → 时间（点击/拖动，限定在窗口内） */
  private fun xToTs(x: Int): Long {
    val ratio = (x.toDouble() / width.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    return windowStart + (ratio * HOUR_MS).toLong()
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2 = g as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    if (frames.isEmpty()) {
      g2.color = Color.GRAY
      g2.drawString("无回放数据", 4, height / 2)
      return
    }

    val trackY = height / 2

    // 轨道基线（占满整行）
    g2.color = Color(210, 210, 210)
    g2.stroke = BasicStroke(2f)
    g2.drawLine(0, trackY, width, trackY)

    // 已播放段（窗口起点..当前）着深色
    g2.color = Color(120, 170, 240)
    g2.drawLine(0, trackY, tsToX(currentTs), trackY)

    // 窗口内每 5 分钟刻度 + 标签
    drawTicks(g2, trackY)

    // 游标
    val cx = tsToX(currentTs)
    g2.color = Color(220, 60, 60)
    g2.stroke = BasicStroke(2f)
    g2.drawLine(cx, trackY - 12, cx, trackY + 12)
    g2.fillOval(cx - 4, trackY - 4, 8, 8)

    // 游标时间标签
    g2.color = Color.DARK_GRAY
    val label = DateFormatUtils.format(currentTs, "HH:mm:ss")
    val lw = g2.fontMetrics.stringWidth(label)
    val lx = (cx - lw / 2).coerceIn(0, (width - lw).coerceAtLeast(0))
    g2.drawString(label, lx, trackY - 16)
  }

  /** 窗口内每 5 分钟画刻度；整点、半点标 HH:mm，其余标 mm */
  private fun drawTicks(g2: Graphics2D, trackY: Int) {
    g2.color = Color(160, 160, 160)
    g2.stroke = BasicStroke(1f)
    var t = windowStart
    while (t <= windowEnd) {
      val x = tsToX(t)
      g2.drawLine(x, trackY - 6, x, trackY + 6)
      val minute = ((t - windowStart) / 60_000L).toInt()
      val text = if (minute == 0 || minute == 60) {
        DateFormatUtils.format(t, "HH:mm")
      } else {
        DateFormatUtils.format(t, "mm")
      }
      val w = g2.fontMetrics.stringWidth(text)
      val tx = (x - w / 2).coerceIn(0, (width - w).coerceAtLeast(0))
      g2.drawString(text, tx, trackY + 20)
      t += TICK_MS
    }
  }
}
