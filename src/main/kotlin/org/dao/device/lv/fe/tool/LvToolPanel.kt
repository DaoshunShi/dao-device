package org.dao.device.lv.fe.tool

import org.dao.device.lift.jinbo.fe.JinBoEventBus
import org.dao.device.lift.jinbo.fe.LiftEvent
import java.awt.Color
import java.awt.Graphics
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class LvToolPanel : JPanel() {
  init {
    background = Color.PINK
    layout = BoxLayout(this, BoxLayout.Y_AXIS)

    add(JButton("染色"))
    add(JButton("上一个"))
    add(JButton("下一个"))
    add(
      JButton("换行").apply {
        addActionListener {
          JinBoEventBus.fire("换行", LiftEvent("换行", "换行"))
        }
      },
    )
    add(JButton("到顶部"))
    add(JButton("到底部"))
  }

  override fun paintComponent(g: Graphics?) {
    super.paintComponent(g)
  }
}