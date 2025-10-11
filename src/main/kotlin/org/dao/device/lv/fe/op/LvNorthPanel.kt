package org.dao.device.lv.fe.op

import java.awt.GridLayout
import javax.swing.JPanel

class LvNorthPanel : JPanel() {

  init {
    layout = GridLayout(3, 1, 0, 5)
    add(LvFilePanel())
    add(LvSearchPanel())
    add(LvFilterPanel())
  }
}