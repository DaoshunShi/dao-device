package org.dao.device.lv

import org.dao.device.lv.fe.LogViewerFrame

object LogViewerService {
  val frame = LogViewerFrame()

  fun init() {
    frame.isVisible = true
  }
}