package org.dao.device.lv.fe.op

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField

class LvFilePanel : JPanel() {

  init {
    background = Color.RED
    layout = BoxLayout(this, BoxLayout.X_AXIS)

    add(JLabel("文件"))
    add(JButton("打开"))

    // val tabbedPane = JTabbedPane()
    // tabbedPane.addTab("文件1", null)
    // tabbedPane.addTab("文件2", null)
    // tabbedPane.addTab("文件3", null)
    // tabbedPane.preferredSize = Dimension(300, height)
    // tabbedPane.tabPlacement = JTabbedPane.BOTTOM
    // add(tabbedPane)

    add(Box.createHorizontalGlue()) // 添加 Glue，让 tabbedPane 左对齐
  }

  override fun paintComponent(g: Graphics?) {
    // super.paintComponent(g)
  }
}