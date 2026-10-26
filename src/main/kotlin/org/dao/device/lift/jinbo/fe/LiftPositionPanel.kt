package org.dao.device.lift.jinbo.fe

import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoDoorStatus
import java.awt.BorderLayout
import javax.swing.JPanel

/** 电梯当前位置面板，由 Cage 订阅状态事件并绘制梯厢。 */
class LiftPositionPanel(config: JinBoConfig) : JPanel(BorderLayout()) {

  init {
    // 使用电梯初始高度0.0米，事件会更新实际高度
    add(Cage(config, 64, 44, 0.0, JinBoDoorStatus.CLOSE))
  }
}
