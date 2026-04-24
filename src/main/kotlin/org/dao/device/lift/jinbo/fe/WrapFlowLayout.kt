package org.dao.device.lift.jinbo.fe

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

class WrapFlowLayout(
  align: Int = LEFT,
  hgap: Int = 5,
  vgap: Int = 5,
) : FlowLayout(align, hgap, vgap) {

  override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

  override fun minimumLayoutSize(target: Container): Dimension {
    val minimum = layoutSize(target, false)
    minimum.width -= hgap + 1
    return minimum
  }

  private fun layoutSize(target: Container, preferred: Boolean): Dimension {
    synchronized(target.treeLock) {
      var targetWidth = target.size.width
      if (targetWidth == 0) {
        targetWidth = Int.MAX_VALUE
      }

      val insets = target.insets
      val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
      val maxWidth = targetWidth - horizontalInsetsAndGap

      val dim = Dimension(0, 0)
      var rowWidth = 0
      var rowHeight = 0

      val nmembers = target.componentCount
      for (i in 0 until nmembers) {
        val m: Component = target.getComponent(i)
        if (!m.isVisible) {
          continue
        }

        val d = if (preferred) m.preferredSize else m.minimumSize

        if (rowWidth + d.width > maxWidth) {
          addRow(dim, rowWidth, rowHeight)
          rowWidth = 0
          rowHeight = 0
        }

        if (rowWidth != 0) {
          rowWidth += hgap
        }

        rowWidth += d.width
        rowHeight = maxOf(rowHeight, d.height)
      }

      addRow(dim, rowWidth, rowHeight)

      dim.width += horizontalInsetsAndGap
      dim.height += insets.top + insets.bottom + vgap * 2

      val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
      if (scrollPane != null) {
        dim.width -= hgap + 1
      }

      return dim
    }
  }

  private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
    dim.width = maxOf(dim.width, rowWidth)
    if (dim.height > 0) {
      dim.height += vgap
    }
    dim.height += rowHeight
  }
}
