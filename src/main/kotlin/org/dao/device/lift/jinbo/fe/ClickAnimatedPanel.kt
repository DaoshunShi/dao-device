package org.dao.device.lift.jinbo.fe

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.Timer

/** 自定义楼层按钮的点击动画：按下时缩小并高亮，松开后立即恢复。 */
abstract class ClickAnimatedPanel : JPanel() {
  private var pressed = false
  private var releaseTimer: Timer? = null

  init {
    isOpaque = true
    addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) {
        if (e.button != MouseEvent.BUTTON1) return
        setPressed(true)
        startReleaseTimer()
        // 应用切换后 mouseClicked 可能丢失；mousePressed 是更可靠的左键点击入口。
        onClick()
      }

      override fun mouseReleased(e: MouseEvent) {
        if (e.button == MouseEvent.BUTTON1) {
          stopReleaseTimer()
          setPressed(false)
        }
      }

      override fun mouseExited(e: MouseEvent) = release()
    })
  }

  protected fun isPressed(): Boolean = pressed

  /** 自定义按钮的点击处理，[java.awt.event.MouseEvent] 的按下回调中调用。 */
  protected abstract fun onClick()

  /** 绘制本次动画的按压轮廓，调用方在绘制完按钮内容后调用。 */
  protected fun paintPressedOverlay(g: Graphics2D) {
    if (!pressed) return
    g.color = PRESSED_COLOR
    g.stroke = BasicStroke(PRESSED_STROKE_WIDTH)
    g.drawOval(2, 2, width - 4, height - 4)
  }

  /** 在缩放后的坐标系中绘制按钮，按压时会以按钮中心为基准缩小。 */
  protected fun withPressedTransform(g: Graphics2D, paint: () -> Unit) {
    val transform = g.transform
    if (pressed) {
      g.scale(PRESSED_SCALE, PRESSED_SCALE)
      g.translate(
        (1.0 / PRESSED_SCALE - 1.0) * width / 2,
        (1.0 / PRESSED_SCALE - 1.0) * height / 2,
      )
    }
    paint()
    g.transform = transform
  }

  /** 部分合成事件可能没有 mouseReleased；保证按压态不会永久残留。 */
  private fun startReleaseTimer() {
    stopReleaseTimer()
    releaseTimer = Timer(PRESSED_DURATION_MS) { release() }.apply {
      isRepeats = false
      start()
    }
  }

  private fun stopReleaseTimer() {
    releaseTimer?.stop()
    releaseTimer = null
  }

  private fun release() {
    stopReleaseTimer()
    setPressed(false)
  }

  private fun setPressed(value: Boolean) {
    if (pressed == value) return
    pressed = value
    repaint()
  }

  private companion object {
    const val PRESSED_DURATION_MS = 120
    const val PRESSED_SCALE = 0.88
    const val PRESSED_STROKE_WIDTH = 2f
    val PRESSED_COLOR = Color(30, 136, 229, 120)
  }
}
