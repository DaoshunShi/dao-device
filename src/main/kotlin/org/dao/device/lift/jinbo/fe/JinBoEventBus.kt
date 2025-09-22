package org.dao.device.lift.jinbo.fe

import java.util.concurrent.CopyOnWriteArrayList

interface GuiEventListener {
  fun onEvent(event: LiftEvent)
}

object JinBoEventBus {
  var listener: List<GuiEventListener> = CopyOnWriteArrayList()

  fun fire(event: LiftEvent) {
    listener.forEach { it.onEvent(event) }
  }
}