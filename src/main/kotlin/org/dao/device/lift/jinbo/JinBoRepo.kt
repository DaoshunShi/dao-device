package org.dao.device.lift.jinbo

object JinBoRepo {
  fun load(): MutableMap<String, JinBoRuntime> {
    val jrA = JinBoRuntime(
      JinBoConfig(
        "A",
        listOf(JinBoFloor(1, "L1", 0.0), JinBoFloor(2, "L2", 3.0), JinBoFloor(3, "L3", 6.0), JinBoFloor(4, "L4", 9.0)),
        0.6,
        5 * 1000,
        10 * 1000,
      ),
    )
    return mutableMapOf(jrA.config.id to jrA)
  }

  fun persist() {
  }
}