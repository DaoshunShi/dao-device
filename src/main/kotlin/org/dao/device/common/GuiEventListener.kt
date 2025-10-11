package org.dao.device.common

import org.dao.device.lift.jinbo.fe.LiftEvent

interface GuiEventListener {
  fun onEvent(event: LiftEvent)
}