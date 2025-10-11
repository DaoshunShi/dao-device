package org.dao.device.lv.fe.line

import java.awt.Color
import java.awt.Graphics
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

class LvLinePanel : JPanel() {
  init {
    background = Color.GREEN
    layout = BoxLayout(this, BoxLayout.X_AXIS)

    add(JLabel(" 时间轴"))
  }

  override fun paintComponent(g: Graphics?) {
    super.paintComponent(g)
  }
}