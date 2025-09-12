package org.dao.device.lift.jinbo.gui

import org.dao.device.lift.jinbo.JinBoRuntime
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.util.Random
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel

fun main() {
  JinBoGuiService.twoButtonPanel()
}

object JinBoGuiService {
  private var c = 0

  private val frame = JFrame("仿真-金博电梯")

  fun render(lr: JinBoRuntime) {
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
  }

  fun twoButtonPanel() {
    val frame = JFrame("2 Button panel")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

    val panel = LiftBody()
    frame.contentPane.add(BorderLayout.CENTER, panel)

    val button1 = JButton("change color")
    button1.addActionListener {
      panel.onEvent(LiftEvent("change color", ""))
      // panel.repaint()
    }
    frame.contentPane.add(BorderLayout.SOUTH, button1)

    val label = JLabel("I'm a label")
    label.setSize(400, 500)
    frame.contentPane.add(BorderLayout.WEST, label)

    val button2 = JButton("change label")
    button2.addActionListener {
      label.text = "I've been clicked! ${c++}"
    }
    frame.contentPane.add(BorderLayout.EAST, button2)

    frame.setSize(500, 500)
    frame.isVisible = true
  }
}

class GuiJinBoLift : JPanel() {
  override fun paintComponent(g: Graphics) {
  }
}

class LiftBody :
  JPanel(),
  GuiEventListener {

  private var x = 0
  private var y = 0

  override fun onEvent(event: LiftEvent) {
    print("receive event")
    val random = Random()
    x = random.nextInt(100)
    y = random.nextInt(100)
    repaint()
  }

  override fun paintComponent(g: Graphics) {
    val random = Random()
    g.color = Color(random.nextInt(255), random.nextInt(255), random.nextInt(255))
    g.fillRect(x, y, 100, 100)
  }
}