package org.dao.device.lv.fe

import org.dao.device.lv.fe.line.LvLinePanel
import org.dao.device.lv.fe.main.LvCenterPanel
import org.dao.device.lv.fe.op.LvNorthPanel
import org.dao.device.lv.fe.tool.LvToolPanel
import java.awt.BorderLayout
import javax.swing.JFrame

class LogViewerFrame : JFrame("Log Viewer") {

  init {
    defaultCloseOperation = DISPOSE_ON_CLOSE
    setSize(1920, 1080)
    setUi()
  }

  fun setUi() {
    layout = BorderLayout()
    // 顶部栏
    add(LvNorthPanel(), BorderLayout.NORTH)

    // 左侧栏
    add(LvToolPanel(), BorderLayout.WEST)

    // 中间栏
    add(LvCenterPanel(), BorderLayout.CENTER)

    // 底部栏
    add(LvLinePanel(), BorderLayout.SOUTH)
  }
}