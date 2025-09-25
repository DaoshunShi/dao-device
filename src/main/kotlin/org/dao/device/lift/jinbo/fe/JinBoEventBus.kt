package org.dao.device.lift.jinbo.fe

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

interface GuiEventListener {
  fun onEvent(event: LiftEvent)
}

object JinBoEventBus {
  var listener: MutableMap<String, MutableList<GuiEventListener>> = ConcurrentHashMap()

  @Synchronized
  fun register(topic: String, l: GuiEventListener) {
    listener.getOrPut(topic) { CopyOnWriteArrayList() }.add(l)
  }

  fun fire(topic: String, event: LiftEvent) {
    listener[topic]?.forEach { it.onEvent(event) }
  }
}