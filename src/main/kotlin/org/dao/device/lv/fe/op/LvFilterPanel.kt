package org.dao.device.lv.fe.op

import java.awt.Color
import java.awt.Graphics
import java.awt.Panel
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JTextField

class LvFilterPanel : Panel() {
  init {
    background = Color.YELLOW
    layout = BoxLayout(this, BoxLayout.X_AXIS)

    add(JLabel("过滤"))
    add(JButton("添加"))
  }

  override fun paintComponents(g: Graphics?) {
    super.paintComponents(g)
  }
}