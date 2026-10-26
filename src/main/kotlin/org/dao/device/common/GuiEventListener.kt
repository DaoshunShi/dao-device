package org.dao.device.common

import org.dao.device.lift.jinbo.event.LiftEvent

interface GuiEventListener {
  fun onEvent(event: LiftEvent)
}
