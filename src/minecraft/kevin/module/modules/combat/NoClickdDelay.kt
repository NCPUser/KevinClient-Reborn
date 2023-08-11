package kevin.module.modules.combat

import kevin.event.EventTarget
import kevin.event.MotionEvent
import kevin.module.IntegerValue
import kevin.module.Module
import kevin.module.ModuleCategory

class NoClickDelay : Module("NoClickDelay","No Delayes For Clicks.", category = ModuleCategory.COMBAT) {
  
    @EventTarget
    fun onMotion(event: MotionEvent) {
        if (mc.thePlayer != null && mc.theWorld != null) {
            mc.leftClickCounter = 0
        }
    }
}
