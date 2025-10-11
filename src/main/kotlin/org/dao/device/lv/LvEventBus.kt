package org.dao.device.lv

import org.dao.device.common.GuiEventListener
import org.dao.device.lift.jinbo.fe.LiftEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.forEach

object LvEventBus {
  var listener: MutableMap<String, MutableList<GuiEventListener>> = ConcurrentHashMap()

  @Synchronized
  fun register(topic: String, l: GuiEventListener) {
    listener.getOrPut(topic) { CopyOnWriteArrayList() }.add(l)
  }

  fun fire(topic: String, event: LiftEvent) {
    listener[topic]?.forEach { it.onEvent(event) }
  }
}