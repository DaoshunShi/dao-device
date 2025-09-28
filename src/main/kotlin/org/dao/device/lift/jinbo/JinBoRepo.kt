package org.dao.device.lift.jinbo

object JinBoRepo {
  fun load(): MutableMap<String, JinBoRuntime> {
    val lifts = listOf(
      JinBoRuntime(
        JinBoConfig(
          "A",
          8080,
          listOf(
            JinBoFloor(1, "L1", 0.0),
            JinBoFloor(2, "L2", 3.0),
            JinBoFloor(3, "L3", 6.0),
            JinBoFloor(4, "L4", 9.0),
          ),
          0.6,
          1 * 1000,
          1 * 1000,
          3 * 1000,
        ),
      ),
      // JinBoRuntime(
      //   JinBoConfig(
      //     "B",
      //     8081,
      //     listOf(
      //       JinBoFloor(1, "L1", 0.0),
      //       JinBoFloor(2, "L2", 3.0),
      //       JinBoFloor(3, "L3", 6.0),
      //       JinBoFloor(4, "L4", 9.0),
      //     ),
      //     0.6,
      //     1 * 1000,
      //     1 * 1000,
      //     3 * 1000,
      //   ),
      // ),
    )
    return lifts.associateBy { it.config.id }.toMutableMap()
  }

  fun persist() {
  }
}