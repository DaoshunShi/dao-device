package org.dao.device.lift.jinbo.fe

import org.dao.device.common.GuiEventListener
import org.dao.device.lift.jinbo.event.LiftEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object JinBoEventBus {
  var listener: MutableMap<String, MutableList<GuiEventListener>> = ConcurrentHashMap()
  private val replayedTopics = ConcurrentHashMap.newKeySet<String>()

  @Synchronized
  fun register(topic: String, l: GuiEventListener) {
    listener.getOrPut(topic) { CopyOnWriteArrayList() }.add(l)
  }

  fun fire(topic: String, event: LiftEvent) {
    listener[topic]?.forEach { it.onEvent(event) }
  }

  /** 回放期间，实时事件只更新仿真和录制，不刷新已切换到回放数据的界面。 */
  fun fireRealtime(topic: String, event: LiftEvent) {
    if (topic !in replayedTopics) fire(topic, event)
  }

  /** 回放事件始终投递；回放模式由 LiftReplayPanel 在加载成功后开启。 */
  fun fireReplay(topic: String, event: LiftEvent) {
    fire(topic, event)
  }

  fun setReplayMode(topic: String, enabled: Boolean) {
    if (enabled) replayedTopics.add(topic) else replayedTopics.remove(topic)
  }

  fun isReplayMode(topic: String): Boolean = topic in replayedTopics
}
