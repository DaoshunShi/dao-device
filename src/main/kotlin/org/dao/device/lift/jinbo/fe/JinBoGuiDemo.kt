package org.dao.device.lift.jinbo.fe

import org.dao.device.lift.jinbo.JinBoDoorStatus
import org.dao.device.lift.jinbo.JinBoReq
import org.dao.device.lift.jinbo.JinBoReqSource
import org.dao.device.lift.jinbo.JinBoServer
import java.awt.*
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

// 创建外部框
fun createBasePanel(title: String, baseColor: Color, child: JPanel): JPanel = JPanel().apply {
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

fun liftOutsidePanel(): JPanel = JPanel().apply {
  layout = GridLayout(4, 1, 0, 5)

  val floorMap = mapOf(4 to "4F", 3 to "3F", 2 to "2F", 1 to "1F")
  for ((idx, lbl) in floorMap) {
    add(outSideFloorBtn(idx, lbl))
  }
}

/**
 * 外部楼层面版
 */
fun outSideFloorBtn(floorIdx: Int, floorLabel: String): JPanel = JPanel().apply {
  layout = GridLayout(1, 3, 0, 0)
  val label = JLabel(floorLabel, JLabel.CENTER).apply { font = font.deriveFont(Font.BOLD, 24f) }
  add(label)
  add(TriangleCircle(floorIdx, 20, 15, true))
  add(TriangleCircle(floorIdx, 20, 15, false))
}

/**
 * 电梯位置面版
 */
fun curPosiPanel(): JPanel = JPanel().apply {
  layout = BorderLayout()
  add(LiftAndDoor(20, 30, 4, 0.0, JinBoDoorStatus.CLOSE))
}

/**
 * 梯内面版
 */
fun liftInsidePanel(): JPanel = JPanel().apply {
  // val floorList = listOf("4", "3", "3", "1")
  val floors = mapOf(4 to "4F", 3 to "3F", 2 to "2F", 1 to "1F")

  layout = GridLayout(floors.size + 2, 1, 0, 5)
  for ((idx, lbl) in floors) {
    add(insideFloorBtn(idx, lbl))
  }
  add(
    JButton("开门").apply {
      addActionListener {
        JinBoServer.request(
          "A",
          JinBoReq(destFloor = JinBoServer.lifts["A"]?.curFloor ?: 0, source = JinBoReqSource.InDoor),
        )
      }
    },
  )
  add(
    JButton("关门").apply {
      addActionListener {
        JinBoServer.close("A")
      }
    },
  )
}

fun insideFloorBtn(floorIdx: Int, floorLabel: String): JPanel = JPanel().apply {
  layout = BorderLayout()
  // add(JButton(floorLabel))
  add(LabelCircle(floorIdx, floorLabel, 20, 20))
}

/**
 * 总控面版
 */
fun liftInsidePanel2(): JPanel = JPanel().apply {
  // val floorList = listOf("4", "3", "3", "1")
  val floors = mapOf(4 to "4F", 3 to "3F", 2 to "2F", 1 to "1F")

  layout = GridLayout(floors.size + 2, 1, 0, 5)
  for ((idx, lbl) in floors) {
    add(insideFloorBtn2(idx, lbl))
  }
  // add(JButton("开门"))
  // add(JButton("关门"))
}

fun insideFloorBtn2(floorIdx: Int, floorLabel: String): JPanel = JPanel().apply {
  layout = BorderLayout()
  // add(JButton(floorLabel))
  add(LabelCircle2(floorIdx, floorLabel, 20, 20))
}