package org.dao.device.lift.jinbo.fe

import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.enabledFloors
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/** 外部楼层面板，按楼层高度从顶层到底层排列上行/下行呼梯按钮。 */
class LiftOutsidePanel(config: JinBoConfig) : JPanel(BorderLayout()) {

  init {
    // 获取启用的楼层并按索引降序排列（顶层在上）
    val enabledFloors = config.enabledFloors()
    val topHeight = enabledFloors.maxOf { it.height }
    val bottomHeight = enabledFloors.minOf { it.height }

    // 动态设置网格布局：启用的楼层数 x 1
    add(
      JPanel(GridLayout(enabledFloors.size, 1, 0, 5)).apply {
        for (floor in enabledFloors) {
          add(
            outsideFloorRow(
              config,
              floor.index,
              floor.label,
              showUp = floor.height < topHeight,
              showDown = floor.height > bottomHeight,
            ),
          )
        }
      },
    )
  }

  /**
   * 外部楼层面版
   */
  private fun outsideFloorRow(
    config: JinBoConfig,
    floorIdx: Int,
    floorLabel: String,
    showUp: Boolean = true,
    showDown: Boolean = true,
  ): JPanel = JPanel().apply {
    layout = GridLayout(1, 3, 0, 0)
    val label = JLabel(floorLabel, JLabel.CENTER).apply { font = font.deriveFont(Font.BOLD, 24f) }
    add(label)
    // 顶层没有上行呼梯、底层没有下行呼梯，保留占位以对齐其他楼层。
    add(if (showUp) TriangleCircle(config, floorIdx, 20, 15, true) else JPanel())
    add(if (showDown) TriangleCircle(config, floorIdx, 20, 15, false) else JPanel())
  }
}

/** 带标题栏的电梯面板外框。 */
internal fun createLiftPanel(title: String, baseColor: Color, child: JPanel): JPanel = JPanel().apply {
  layout = BorderLayout()
  background = baseColor

  // 顶部标题栏
  val titlePanel = JPanel().apply {
    background = baseColor.darker()
    preferredSize = Dimension(0, 30)

    val titleLabel = JLabel(title).apply { foreground = Color.WHITE }
    add(titleLabel)
  }
  add(titlePanel, BorderLayout.NORTH)

  // 中心区域
  add(child, BorderLayout.CENTER)
}
