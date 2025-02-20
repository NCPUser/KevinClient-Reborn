/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
@file:Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")

package kevin.module.modules.combat

import kevin.event.*
import kevin.main.KevinClient
import kevin.module.*
import kevin.utils.*
import kevin.utils.PacketUtils.packetList
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.network.NetworkManager
import net.minecraft.network.Packet
import net.minecraft.network.ThreadQuickExitException
import net.minecraft.network.play.INetHandlerPlayClient
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S14PacketEntity
import net.minecraft.util.AxisAlignedBB
import org.lwjgl.opengl.GL11.*

class BackTrack: Module("BackTrack", "Lets you attack people in their previous locations", category = ModuleCategory.COMBAT) {
    private val minDistance: FloatValue = object : FloatValue("MinDistance", 2.9f, 2f, 4f) {
        override fun onChanged(oldValue: Float, newValue: Float) {
            if (newValue > maxDistance.get()) set(maxDistance.get())
        }
    }
    private val maxDistance: FloatValue = object : FloatValue("MaxDistance", 5f, 2f, 6f) {
        override fun onChanged(oldValue: Float, newValue: Float) {
            if (newValue < minDistance.get()) set(minDistance.get())
        }
    }
    private val minTime = IntegerValue("MinTime", 100, 0, 500)
    private val maxTime = IntegerValue("MaxTime", 200, 0, 1000)
    private val smartPacket = BooleanValue("Smart", true)
    private val minAttackReleaseRange = FloatValue("MinAttackReleaseRange", 3.2F, 2f, 6f)

    private val onlyKillAura = BooleanValue("OnlyKillAura", true)
    private val onlyPlayer = BooleanValue("OnlyPlayer", true)
    private val resetOnVelocity = BooleanValue("ResetOnVelocity", true)
    private val resetOnLagging = BooleanValue("ResetOnLagging", true)
    private val rangeCheckMode = ListValue("RangeCheckMode", arrayOf("RayCast", "DirectDistance"), "DirectDistance")

    private val espMode = ListValue("ESPMode", arrayOf("FullBox", "OutlineBox", "NormalBox", "OtherOutlineBox", "OtherFullBox", "None"), "Box")

    private val storagePackets = ArrayList<Packet<INetHandlerPlayClient>>()
    private val storageEntities = ArrayList<Entity>()

    private val killAura: KillAura by lazy { KevinClient.moduleManager.getModule(KillAura::class.java) }
//    private var currentTarget : EntityLivingBase? = null
    private var timer = MSTimer()
    private var attacked : Entity? = null

    var needFreeze = false

//    @EventTarget
    // for safety, see in met.minecraft.network.NetworkManager
    fun onPacket(event: PacketEvent) {
        mc.thePlayer ?: return
        val packet = event.packet
        if (packet.javaClass.name.contains("net.minecraft.network.play.server.", true)) {
            if (packet is S14PacketEntity) {
                val entity = packet.getEntity(mc.theWorld!!)?: return
                if (onlyPlayer.get() && entity !is EntityPlayer) return
                entity.serverPosX += packet.func_149062_c().toInt()
                entity.serverPosY += packet.func_149061_d().toInt()
                entity.serverPosZ += packet.func_149064_e().toInt()
                val x = entity.serverPosX.toDouble() / 32.0
                val y = entity.serverPosY.toDouble() / 32.0
                val z = entity.serverPosZ.toDouble() / 32.0
                if (!onlyKillAura.get() || killAura.state || needFreeze) {
                    val afterBB = AxisAlignedBB(x - 0.4F, y - 0.1F, z - 0.4F, x + 0.4F, y + 1.9F, z + 0.4F)
                    var afterRange: Double
                    var beforeRange: Double
                    if (rangeCheckMode equal "RayCast") {
                        afterRange = afterBB.getLookingTargetRange(mc.thePlayer!!)
                        beforeRange = entity.getLookDistanceToEntityBox()
                        if (afterRange == Double.MAX_VALUE) {
                            val eyes = mc.thePlayer!!.getPositionEyes(1F)
                            afterRange = getNearestPointBB(eyes, afterBB).distanceTo(eyes) + 0.075
                        }
                        if (beforeRange == Double.MAX_VALUE) beforeRange = mc.thePlayer!!.getDistanceToEntityBox(entity) + 0.075
                    } else {
                        val eyes = mc.thePlayer!!.getPositionEyes(1F)
                        afterRange = getNearestPointBB(eyes, afterBB).distanceTo(eyes)
                        beforeRange = mc.thePlayer!!.getDistanceToEntityBox(entity)
                    }

                    if (beforeRange <= minDistance.get()) {
                        if (afterRange in minDistance.get()..maxDistance.get() && (!smartPacket.get() || rangeCheckMode equal "RayCast" || afterRange > beforeRange + 0.02)) {
                            if (!needFreeze) {
                                timer.reset()
                                needFreeze = true
                            }
                            if (!storageEntities.contains(entity)) storageEntities.add(entity)
                            event.cancelEvent()
                            return
                        }
                    } else {
                        if (smartPacket.get()) {
                            if (afterRange <= beforeRange) {
                                if (needFreeze) releasePackets()
                            }
                        }
                    }
                }
                if (needFreeze) {
                    if (!storageEntities.contains(entity)) storageEntities.add(entity)
                    event.cancelEvent()
                    return
                }
                if (!event.isCancelled && !needFreeze) {
                    KevinClient.eventManager.callEvent(EntityMovementEvent(entity))
                    val f = if (packet.func_149060_h()) (packet.func_149066_f() * 360).toFloat() / 256.0f else entity.rotationYaw
                    val f1 = if (packet.func_149060_h()) (packet.func_149063_g() * 360).toFloat() / 256.0f else entity.rotationPitch
                    entity.setPositionAndRotation2(x, y, z, f, f1, 3, false)
                    entity.onGround = packet.onGround
                }
                event.cancelEvent()
//                storageEntities.add(entity)
            } else {
                if ((packet is S12PacketEntityVelocity && resetOnVelocity.get()) || (packet is S08PacketPlayerPosLook && resetOnLagging.get())) {
                    storagePackets.add(packet as Packet<INetHandlerPlayClient>)
                    event.cancelEvent()
                    releasePackets()
                    return
                }
                if (needFreeze && !event.isCancelled) {
                    storagePackets.add(packet as Packet<INetHandlerPlayClient>)
                    event.cancelEvent()
                }
            }
        } else if (packet is C02PacketUseEntity) {
            if (packet.action == C02PacketUseEntity.Action.ATTACK && needFreeze) {
                attacked = packet.getEntityFromWorld(mc.theWorld!!)
            }
        }
    }

    @EventTarget fun onMotion(event: MotionEvent) {
        if (event.eventState == EventState.PRE) return
        if (needFreeze) {
            if (timer.hasTimePassed(maxTime.get().toLong())) {
                releasePackets()
                return
            }
            if (storageEntities.isNotEmpty()) {
                var release = false // for-each
                for (entity in storageEntities) {
                    val x = entity.serverPosX.toDouble() / 32.0
                    val y = entity.serverPosY.toDouble() / 32.0
                    val z = entity.serverPosZ.toDouble() / 32.0
                    val entityBB = AxisAlignedBB(x - 0.4F, y -0.1F, z - 0.4F, x + 0.4F, y + 1.9F, z + 0.4F)
                    var range = entityBB.getLookingTargetRange(mc.thePlayer!!)
                    if (range == Double.MAX_VALUE) {
                        val eyes = mc.thePlayer!!.getPositionEyes(1F)
                        range = getNearestPointBB(eyes, entityBB).distanceTo(eyes) + 0.075
                    }
                    if (range <= minDistance.get()) {
                        release = true
                        break
                    }
                    val entity1 = attacked
                    if (entity1 != entity) continue
                    if (timer.hasTimePassed(minTime.get().toLong())) {
                        if (range >= minAttackReleaseRange.get()) {
                            release = true
                            break
                        }
                    }
                }
                if (release) releasePackets()
            }
        }
    }

    @EventTarget fun onWorld(event: WorldEvent) {
        attacked = null
        storageEntities.clear()
        if (event.worldClient == null) storagePackets.clear()
    }

    @EventTarget fun onRender3D(event: Render3DEvent) {
        if (espMode equal "None" || !needFreeze) return

        var outline = false
        var filled = false
        var other = false
        when (espMode.get()) {
            "NormalBox" -> {
                outline = true
                filled = true
            }
            "FullBox" -> {
                filled = true
            }
            "OtherOutlineBox" -> {
                other = true
                outline = true
            }
            "OtherFullBox" -> {
                other = true
                filled = true
            }
            else -> {
                outline = true
            }
        }

        // pre draw
        glPushMatrix()
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_BLEND)
        glDisable(GL_TEXTURE_2D)
        glDisable(GL_DEPTH_TEST)

        glDepthMask(false)

        if (outline) {
            glLineWidth(1f)
            glEnable(GL_LINE_SMOOTH)
        }
        // drawing
        val renderManager = mc.renderManager
        for (entity in storageEntities) {
            val x = entity.serverPosX.toDouble() / 32.0 - renderManager.renderPosX
            val y = entity.serverPosY.toDouble() / 32.0 - renderManager.renderPosY
            val z = entity.serverPosZ.toDouble() / 32.0 - renderManager.renderPosZ
            if (other) {
                if (outline) {
                    RenderUtils.glColor(32, 200, 32, 255)
                    RenderUtils.otherDrawOutlinedBoundingBox(entity.rotationYawHead, x, y, z, entity.width / 2.0 + 0.1, entity.height + 0.1)
                }
                if (filled) {
                    RenderUtils.glColor(32, 255, 32, 35)
                    RenderUtils.otherDrawBoundingBox(entity.rotationYawHead, x, y, z, entity.width / 2.0 + 0.1, entity.height + 0.1)
                }
            } else {
                val bb = AxisAlignedBB(x - 0.4F, y, z - 0.4F, x + 0.4F, y + 1.9F, z + 0.4F)
                if (outline) {
                    RenderUtils.glColor(32, 200, 32, 255)
                    RenderUtils.drawSelectionBoundingBox(bb)
                }
                if (filled) {
                    RenderUtils.glColor(32, 255, 32, if (outline) 26 else 35)
                    RenderUtils.drawFilledBox(bb)
                }
            }
        }

        // post draw
        glColor4f(1.0f, 1.0f, 1.0f, 1.0f)
        glDepthMask(true)
        if (outline) {
            glDisable(GL_LINE_SMOOTH)
        }
        glDisable(GL_BLEND)
        glEnable(GL_TEXTURE_2D)
        glEnable(GL_DEPTH_TEST)
        glPopMatrix()
    }

    fun releasePackets() {
        attacked = null
        val netHandler: INetHandlerPlayClient = mc.netHandler
        if (storagePackets.isEmpty()) return
        while (storagePackets.isNotEmpty()) {
            storagePackets.removeAt(0).let{
                try {
                    val packetEvent = PacketEvent(it ?: return)
                    if (!packetList.contains(it)) KevinClient.eventManager.callEvent(packetEvent)
                    if (!packetEvent.isCancelled) it.processPacket(netHandler)
                } catch (_: ThreadQuickExitException) { }
            }
        }
        while (storageEntities.isNotEmpty()) {
            storageEntities.removeAt(0).let { entity ->
                if (!entity.isDead) {
                    val x = entity.serverPosX.toDouble() / 32.0
                    val y = entity.serverPosY.toDouble() / 32.0
                    val z = entity.serverPosZ.toDouble() / 32.0
                    entity.setPosition(x, y, z)
                }
            }
        }
        needFreeze = false
    }

    fun update() {}

    init {
        NetworkManager.backTrack = this
    }
//    val target: EntityLivingBase?
//    get() = if (onlyKillAura.get()) {
//        if (killAura.target is EntityLivingBase) killAura.target as EntityLivingBase?
//        else null
//    } else currentTarget
}

//data class DataEntityPosStorage(val entity: EntityLivingBase, var modifiedTick: Int = 0)