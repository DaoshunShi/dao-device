package org.dao.device.lv.fe.main

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class LvCenterPanel : JPanel() {
  init {
    background = Color.LIGHT_GRAY

    layout = BorderLayout()
    add(
      JScrollPane(
        JTextArea(10, 30).apply {
          isEditable = false
        },
      ),
      BorderLayout.CENTER,
    )
    border = BorderFactory.createTitledBorder("合并的日志")
  }

  override fun paintComponent(g: Graphics?) {
    super.paintComponent(g)
  }
}