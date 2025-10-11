package org.dao.device.lv.fe.op

import java.awt.Color
import java.awt.Graphics
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class LvSearchPanel : JPanel() {

  init {
    background = Color.ORANGE
    layout = BoxLayout(this, BoxLayout.X_AXIS)

    add(JLabel("搜索"))
    add(JButton("添加"))
  }

  override fun paintComponents(g: Graphics?) {
    super.paintComponents(g)
  }
}