// Copyright (c) 2020 PSForever
package net.psforever.objects.avatar

import akka.actor.{Actor, ActorRef, Props}
import net.psforever.objects._
import net.psforever.objects.ballistics.{PlayerSource, ResolvedProjectile}
import net.psforever.objects.definition.ImplantDefinition
import net.psforever.objects.equipment._
import net.psforever.objects.inventory.{GridInventory, InventoryItem}
import net.psforever.objects.loadouts.Loadout
import net.psforever.objects.serverobject.containable.{Containable, ContainableBehavior}
import net.psforever.objects.vital.{PlayerSuicide, Vitality}
import net.psforever.objects.serverobject.{CommonMessages, PlanetSideServerObject}
import net.psforever.objects.serverobject.damage.Damageable
import net.psforever.objects.serverobject.mount.Mountable
import net.psforever.objects.serverobject.repair.Repairable
import net.psforever.objects.serverobject.terminals.Terminal
import net.psforever.objects.vital._
import net.psforever.objects.zones.Zone
import net.psforever.packet.game._
import net.psforever.packet.game.objectcreate.ObjectCreateMessageParent
import net.psforever.types._
import services.{RemoverActor, Service}
import services.avatar.{AvatarAction, AvatarServiceMessage}
import services.local.{LocalAction, LocalServiceMessage}

import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

class PlayerControl(player : Player) extends Actor
  with JammableBehavior
  with Damageable
  with ContainableBehavior {
  def JammableObject = player
  def DamageableObject = player
  def ContainerObject = player

  private [this] val log = org.log4s.getLogger(player.Name)
  private [this] val damageLog = org.log4s.getLogger(Damageable.LogChannel)

  // A collection of timers for each slot to trigger stamina drain on an interval
  val implantSlotStaminaDrainTimers = mutable.HashMap(0 -> Default.Cancellable, 1 -> Default.Cancellable, 2 -> Default.Cancellable)
  // control agency for the player's locker container (dedicated inventory slot #5)
  val lockerControlAgent : ActorRef = {
    val locker = player.Locker
    locker.Zone = player.Zone
    locker.Actor = context.actorOf(Props(classOf[LockerContainerControl], locker, player.Name), PlanetSideServerObject.UniqueActorName(locker))
  }

  override def postStop() : Unit = {
    lockerControlAgent ! akka.actor.PoisonPill
    player.Locker.Actor = Default.Actor
    implantSlotStaminaDrainTimers.values.foreach { _.cancel }
  }

  def receive : Receive = jammableBehavior
    .orElse(takesDamage)
    .orElse(containerBehavior)
    .orElse {
      case Player.ImplantActivation(slot: Int, status : Int) =>
        // todo: disable implants with stamina cost when changing armour type
        val implantSlot = player.ImplantSlot(slot)

        // Allow uninitialized implants to be deactivated in case they're stuck in a state where they are no longer active or initialized but still draining stamina (e.g. by EMP)
        if(status == 0 && (implantSlot.Active || !implantSlot.Initialized)) {
          // Cancel stamina drain timer
          implantSlotStaminaDrainTimers(slot).cancel()
          implantSlotStaminaDrainTimers(slot) = Default.Cancellable

          implantSlot.Active = false
          player.Zone.AvatarEvents ! AvatarServiceMessage(player.Zone.Id, AvatarAction.PlanetsideAttribute(player.GUID, 28, player.Implant(slot).id * 2)) // Deactivation sound / effect
          player.Zone.AvatarEvents ! AvatarServiceMessage(player.Name, AvatarAction.DeactivateImplantSlot(player.GUID, slot))
        } else if (status == 1 && implantSlot.Initialized && !player.Fatigued) {
          implantSlot.Installed match {
            case Some(implant: ImplantDefinition) =>
              if(implantSlot.Active) {
                // Some events such as zoning will reset the implant on the client side without sending a deactivation packet
                // But the implant will remain in an active state server side. For now, allow reactivation of the implant.
                // todo: Deactivate implants server side when actions like zoning happen. (Other actions?)
                log.warn(s"Implant $slot is already active, but activating again")
                implantSlotStaminaDrainTimers(slot).cancel()
                implantSlotStaminaDrainTimers(slot) = Default.Cancellable
              }
              implantSlot.Active = true

              if (implant.ActivationStaminaCost >= 0) {
                player.Stamina -= implant.ActivationStaminaCost // Activation stamina drain
              }

              if(implant.StaminaCost > 0 && implant.GetCostIntervalByExoSuit(player.ExoSuit) > 0) { // Ongoing stamina drain, if applicable
                implantSlotStaminaDrainTimers(slot) = context.system.scheduler.scheduleWithFixedDelay(0 seconds, implant.GetCostIntervalByExoSuit(player.ExoSuit) milliseconds, self, Player.DrainStamina(implant.StaminaCost))
              }

              player.Zone.AvatarEvents ! AvatarServiceMessage(player.Zone.Id, AvatarAction.PlanetsideAttribute(player.GUID, 28, player.Implant(slot).id * 2 + 1)) // Activation sound / effect
              player.Zone.AvatarEvents ! AvatarServiceMessage(player.Name, AvatarAction.ActivateImplantSlot(player.GUID, slot))
            case _ => ;
          }
        }
        else {
          log.warn(s"Can't handle ImplantActivation: Player GUID: ${player.GUID} Slot $slot Status: $status Initialized: ${implantSlot.Initialized} Active: ${implantSlot.Active} Fatigued: ${player.Fatigued}")
        }

      case Player.UninitializeImplant(slot: Int) =>
        PlayerControl.UninitializeImplant(player, slot)

      case Player.ImplantInitializationStart(slot: Int) =>
        val implantSlot = player.ImplantSlot(slot)
        if(implantSlot.Installed.isDefined) {
          if(implantSlot.Initialized) {
            PlayerControl.UninitializeImplant(player, slot)
          }

          // Start client side initialization timer
          player.Zone.AvatarEvents ! AvatarServiceMessage(player.Name, AvatarAction.SendResponse(player.GUID, ActionProgressMessage(slot + 6, 0)))

          // Callback after initialization timer to complete initialization
          implantSlot.InitializeTimer = context.system.scheduler.scheduleOnce(implantSlot.MaxTimer seconds, self, Player.ImplantInitializationComplete(slot))
        }

      case Player.ImplantInitializationComplete(slot: Int) =>
        val implantSlot = player.ImplantSlot(slot)
        if(implantSlot.Installed.isDefined) {
          player.Zone.AvatarEvents ! AvatarServiceMessage(player.Name, AvatarAction.SendResponse(player.GUID, AvatarImplantMessage(player.GUID, ImplantAction.Initialization, slot, 1)))
          implantSlot.Initialized = true
          if(implantSlot.InitializeTimer != Default.Cancellable) {
            implantSlot.InitializeTimer.cancel()
            implantSlot.InitializeTimer = Default.Cancellable
          }
        }

      case Player.DrainStamina(amount : Int) =>
        player.Stamina -= amount

      case Player.StaminaChanged(currentStamina : Int) =>
        if(currentStamina == 0) {
          player.Fatigued = true
          player.skipStaminaRegenForTurns += 4
          player.Implants.indices.foreach { slot => // Disable all implants
            self ! Player.ImplantActivation(slot, 0)
            player.Zone.AvatarEvents ! AvatarServiceMessage(player.Zone.Id, AvatarAction.SendResponseTargeted(player.GUID, AvatarImplantMessage(player.GUID, ImplantAction.OutOfStamina, slot, 1)))
          }
        } else if (player.Fatigued && currentStamina >= 20) {
          player.Fatigued = false
          player.Implants.indices.foreach { slot => // Re-enable all implants
            player.Zone.AvatarEvents ! AvatarServiceMessage(player.Zone.Id, AvatarAction.SendResponseTargeted(player.GUID, AvatarImplantMessage(player.GUID, ImplantAction.OutOfStamina, slot, 0)))
          }
        }
        player.Zone.AvatarEvents ! AvatarServiceMessage(player.Zone.Id, AvatarAction.PlanetsideAttributeSelf(player.GUID, 2, player.Stamina))

      case Player.Die() =>
        if(player.isAlive) {
          PlayerControl.DestructionAwareness(player, None)
        }

      case CommonMessages.Use(user, Some(item : Tool)) if item.Definition == GlobalDefinitions.medicalapplicator && player.isAlive =>
        //heal
        val originalHealth = player.Health
        val definition = player.Definition
        if(player.MaxHealth > 0 && originalHealth < player.MaxHealth &&
          user.Faction == player.Faction &&
          item.Magazine > 0 &&
          Vector3.Distance(user.Position, player.Position) < definition.RepairDistance) {
          val zone = player.Zone
          val events = zone.AvatarEvents
          val uname = user.Name
          val guid = player.GUID
          if(!(player.isMoving || user.isMoving)) { //only allow stationary heals
            val newHealth = player.Health = originalHealth + 10
            val magazine = item.Discharge
            events ! AvatarServiceMessage(uname, AvatarAction.SendResponse(Service.defaultPlayerGUID, InventoryStateMessage(item.AmmoSlot.Box.GUID, item.GUID, magazine.toLong)))
            events ! AvatarServiceMessage(zone.Id, AvatarAction.PlanetsideAttributeToAll(guid, 0, newHealth))
            player.History(HealFromEquipment(PlayerSource(player), PlayerSource(user), newHealth - originalHealth, GlobalDefinitions.medicalapplicator))
          }
          if(player != user) {
            //"Someone is trying to heal you"
            events ! AvatarServiceMessage(player.Name, AvatarAction.PlanetsideAttributeToAll(guid, 55, 1))
            //progress bar remains visible for all heal attempts
            events ! AvatarServiceMessage(uname, AvatarAction.SendResponse(Service.defaultPlayerGUID, RepairMessage(guid, player.Health * 100 / definition.MaxHealth)))
          }
        }

      case CommonMessages.Use(user, Some(item : Tool)) if item.Definition == GlobalDefinitions.medicalapplicator =>
        //revive
        if(user != player &&
          user.Faction == player.Faction &&
          user.isAlive && !user.isMoving &&
          !player.isAlive && !player.isBackpack &&
          item.Magazine >= 25) {
          sender ! CommonMessages.Progress(
            4,
            Players.FinishRevivingPlayer(player, user.Name),
            Players.RevivingTickAction(player, user, item)
          )
        }

      case CommonMessages.Use(user, Some(item : Tool)) if item.Definition == GlobalDefinitions.bank =>
        val originalArmor = player.Armor
        val definition = player.Definition
        if(player.MaxArmor > 0 && originalArmor < player.MaxArmor &&
          user.Faction == player.Faction &&
          item.AmmoType == Ammo.armor_canister && item.Magazine > 0 &&
          Vector3.Distance(user.Position, player.Position) < definition.RepairDistance) {
          val zone = player.Zone
          val events = zone.AvatarEvents
          val uname = user.Name
          val guid = player.GUID
          if(!(player.isMoving || user.isMoving)) { //only allow stationary repairs
            val newArmor = player.Armor = originalArmor + Repairable.Quality + RepairValue(item) + definition.RepairMod
            val magazine = item.Discharge
            events ! AvatarServiceMessage(uname, AvatarAction.SendResponse(Service.defaultPlayerGUID, InventoryStateMessage(item.AmmoSlot.Box.GUID, item.GUID, magazine.toLong)))
            events ! AvatarServiceMessage(zone.Id, AvatarAction.PlanetsideAttributeToAll(guid, 4, player.Armor))
            player.History(RepairFromEquipment(PlayerSource(player), PlayerSource(user), newArmor - originalArmor, GlobalDefinitions.bank))
          }
          if(player != user) {
            if(player.isAlive) {
              //"Someone is trying to repair you" gets strobed twice for visibility
              val msg = AvatarServiceMessage(player.Name, AvatarAction.PlanetsideAttributeToAll(guid, 56, 1))
              events ! msg
              import scala.concurrent.ExecutionContext.Implicits.global
              context.system.scheduler.scheduleOnce(250 milliseconds, events, msg)
            }
            //progress bar remains visible for all repair attempts
            events ! AvatarServiceMessage(uname, AvatarAction.SendResponse(Service.defaultPlayerGUID, RepairMessage(guid, player.Armor * 100 / player.MaxArmor)))
          }
        }

      case Terminal.TerminalMessage(_, msg, order) =>
        order match {
          case Terminal.BuyExosuit(exosuit, subtype) =>
            val time = System.currentTimeMillis
            var toDelete : List[InventoryItem] = Nil
            val originalSuit = player.ExoSuit
            val originalSubtype = Loadout.DetermineSubtype(player)
            val requestToChangeArmor = originalSuit != exosuit || originalSubtype != subtype
            val allowedToChangeArmor = Players.CertificationToUseExoSuit(player, exosuit, subtype) &&
              (if (exosuit == ExoSuitType.MAX) {
                if (time - player.GetLastUsedTime(exosuit, subtype) < 300000L) {
                  false
                }
                else {
                  player.SetLastUsedTime(exosuit, subtype, time)
                  true
                }
              }
              else {
                player.SetLastUsedTime(exosuit, subtype, time)
                true
              })
            val result = if (requestToChangeArmor && allowedToChangeArmor) {
              log.info(s"${player.Name} wants to change to a different exo-suit - $exosuit")
              player.SetLastUsedTime(exosuit, subtype, System.currentTimeMillis())
              val beforeHolsters = Players.clearHolsters(player.Holsters().iterator)
              val beforeInventory = player.Inventory.Clear()
              //change suit
              val originalArmor = player.Armor
              player.ExoSuit = exosuit //changes the value of MaxArmor to reflect the new exo-suit
              val toMaxArmor = player.MaxArmor
              if (originalSuit != exosuit || originalSubtype != subtype || originalArmor > toMaxArmor) {
                player.History(HealFromExoSuitChange(PlayerSource(player), exosuit))
                player.Armor = toMaxArmor
              }
              else {
                player.Armor = originalArmor
              }
              //ensure arm is down, even if it needs to go back up
              if (player.DrawnSlot != Player.HandsDownSlot) {
                player.DrawnSlot = Player.HandsDownSlot
              }
              val normalHolsters = if (originalSuit == ExoSuitType.MAX) {
                val (maxWeapons, normalWeapons) = beforeHolsters.partition(elem => elem.obj.Size == EquipmentSize.Max)
                toDelete ++= maxWeapons
                normalWeapons
              }
              else {
                beforeHolsters
              }
              //populate holsters
              val (afterHolsters, finalInventory) = if (exosuit == ExoSuitType.MAX) {
                (normalHolsters, Players.fillEmptyHolsters(List(player.Slot(4)).iterator, normalHolsters) ++ beforeInventory)
              }
              else if (originalSuit == exosuit) { //note - this will rarely be the situation
                (normalHolsters, Players.fillEmptyHolsters(player.Holsters().iterator, normalHolsters))
              }
              else {
                val (afterHolsters, toInventory) = normalHolsters.partition(elem => elem.obj.Size == player.Slot(elem.start).Size)
                afterHolsters.foreach({ elem => player.Slot(elem.start).Equipment = elem.obj })
                val remainder = Players.fillEmptyHolsters(player.Holsters().iterator, toInventory ++ beforeInventory)
                (
                  player.Holsters()
                    .zipWithIndex
                    .map { case (slot, i) => (slot.Equipment, i) }
                    .collect { case (Some(obj), index) => InventoryItem(obj, index) }
                    .toList,
                  remainder
                )
              }
              //put items back into inventory
              val (stow, drop) = if (originalSuit == exosuit) {
                (finalInventory, Nil)
              }
              else {
                val (a, b) = GridInventory.recoverInventory(finalInventory, player.Inventory)
                (a, b.map {
                  InventoryItem(_, -1)
                })
              }
              stow.foreach { elem =>
                player.Inventory.InsertQuickly(elem.start, elem.obj)
              }
              player.Zone.AvatarEvents ! AvatarServiceMessage(player.Zone.Id,
                AvatarAction.ChangeExosuit(player.GUID, exosuit, subtype, player.LastDrawnSlot, exosuit == ExoSuitType.MAX && requestToChangeArmor,
                  beforeHolsters.map { case InventoryItem(obj, _) => (obj, obj.GUID) }, afterHolsters,
                  beforeInventory.map { case InventoryItem(obj, _) => (obj, obj.GUID) }, stow, drop,
                  toDelete.map { case InventoryItem(obj, _) => (obj, obj.GUID) }
                )
              )
              true
            }
            else {
              false
            }
            player.Zone.AvatarEvents ! AvatarServiceMessage(player.Name, AvatarAction.TerminalOrderResult(msg.terminal_guid, msg.transaction_type, result))

          case Terminal.InfantryLoadout(exosuit, subtype, holsters, inventory) =>
            log.info(s"wants to change equipment loadout to their option #${msg.unk1 + 1}")
            val fallbackSubtype = 0
            val fallbackSuit = ExoSuitType.Standard
            val originalSuit = player.ExoSuit
            val originalSubtype = Loadout.DetermineSubtype(player)
            //sanitize exo-suit for change
            val dropPred = ContainableBehavior.DropPredicate(player)
            val oldHolsters = Players.clearHolsters(player.Holsters().iterator)
            val dropHolsters = oldHolsters.filter(dropPred)
            val oldInventory = player.Inventory.Clear()
            val dropInventory = oldInventory.filter(dropPred)
            val toDeleteOrDrop : List[InventoryItem] = (player.FreeHand.Equipment match {
              case Some(obj) =>
                val out = InventoryItem(obj, -1)
                player.FreeHand.Equipment = None
                if (dropPred(out)) {
                  List(out)
                }
                else {
                  Nil
                }
              case _ =>
                Nil
            }) ++ dropHolsters ++ dropInventory
            //a loadout with a prohibited exo-suit type will result in the fallback exo-suit type
            //imposed 5min delay on mechanized exo-suit switches
            val time = System.currentTimeMillis()
            val (nextSuit, nextSubtype) = if (Players.CertificationToUseExoSuit(player, exosuit, subtype) &&
              (if (exosuit == ExoSuitType.MAX) {
                if (time - player.GetLastUsedTime(exosuit, subtype) < 300000L) {
                  false
                }
                else {
                  player.SetLastUsedTime(exosuit, subtype, time)
                  true
                }
              }
              else {
                player.SetLastUsedTime(exosuit, subtype, time)
                true
              })) {
              (exosuit, subtype)
            }
            else {
              log.warn(s"no longer has permission to wear the exo-suit type $exosuit; will wear $fallbackSuit instead")
              player.SetLastUsedTime(fallbackSuit, fallbackSubtype, time)
              (fallbackSuit, fallbackSubtype)
            }
            //sanitize (incoming) inventory
            //TODO equipment permissions; these loops may be expanded upon in future
            val curatedHolsters = for {
              item <- holsters
              //id = item.obj.Definition.ObjectId
              //lastTime = player.GetLastUsedTime(id)
              if true
            } yield item
            val curatedInventory = for {
              item <- inventory
              //id = item.obj.Definition.ObjectId
              //lastTime = player.GetLastUsedTime(id)
              if true
            } yield item
            //update suit internally
            val originalArmor = player.Armor
            player.ExoSuit = nextSuit
            val toMaxArmor = player.MaxArmor
            if (originalSuit != nextSuit || originalSubtype != nextSubtype || originalArmor > toMaxArmor) {
              player.History(HealFromExoSuitChange(PlayerSource(player), nextSuit))
              player.Armor = toMaxArmor
            }
            else {
              player.Armor = originalArmor
            }
            //ensure arm is down, even if it needs to go back up
            if (player.DrawnSlot != Player.HandsDownSlot) {
              player.DrawnSlot = Player.HandsDownSlot
            }
            //a change due to exo-suit permissions mismatch will result in (more) items being re-arranged and/or dropped
            //dropped items are not registered and can just be forgotten
            val (afterHolsters, afterInventory) = if (nextSuit == exosuit) {
              (
                //melee slot preservation for MAX
                if (nextSuit == ExoSuitType.MAX) {
                  holsters.filter(_.start == 4)
                }
                else {
                  curatedHolsters.filterNot(dropPred)
                },
                curatedInventory.filterNot(dropPred)
              )
            }
            else {
              //our exo-suit type was hijacked by changing permissions; we shouldn't even be able to use that loadout(!)
              //holsters
              val leftoversForInventory = Players.fillEmptyHolsters(
                player.Holsters().iterator,
                (curatedHolsters ++ curatedInventory).filterNot(dropPred)
              )
              val finalHolsters = player.Holsters()
                .zipWithIndex
                .collect { case (slot, index) if slot.Equipment.nonEmpty => InventoryItem(slot.Equipment.get, index) }
                .toList
              //inventory
              val (finalInventory, _) = GridInventory.recoverInventory(leftoversForInventory, player.Inventory)
              (finalHolsters, finalInventory)
            }
            (afterHolsters ++ afterInventory).foreach { entry => entry.obj.Faction = player.Faction }
            toDeleteOrDrop.foreach { entry => entry.obj.Faction = PlanetSideEmpire.NEUTRAL }
            player.Zone.AvatarEvents ! AvatarServiceMessage(player.Zone.Id,
              AvatarAction.ChangeLoadout(player.GUID, nextSuit, nextSubtype, player.LastDrawnSlot, exosuit == ExoSuitType.MAX,
                oldHolsters.map { case InventoryItem(obj, _) => (obj, obj.GUID) }, afterHolsters,
                oldInventory.map { case InventoryItem(obj, _) => (obj, obj.GUID) }, afterInventory, toDeleteOrDrop)
            )
            player.Zone.AvatarEvents ! AvatarServiceMessage(player.Name, AvatarAction.TerminalOrderResult(msg.terminal_guid, msg.transaction_type, true))

          case _ => ; //terminal messages not handled here
        }

      case Zone.Ground.ItemOnGround(item, _, _) => ;
        val name = player.Name
        val zone = player.Zone
        val avatarEvents = zone.AvatarEvents
        val localEvents = zone.LocalEvents
        item match {
          case trigger : BoomerTrigger =>
            //dropped the trigger, no longer own the boomer; make certain whole faction is aware of that
            (zone.GUID(trigger.Companion), zone.Players.find { _.name == name}) match {
              case (Some(boomer : BoomerDeployable), Some(avatar)) =>
                val guid = boomer.GUID
                val factionChannel = boomer.Faction.toString
                if(avatar.Deployables.Remove(boomer)) {
                  boomer.Faction = PlanetSideEmpire.NEUTRAL
                  boomer.AssignOwnership(None)
                  avatar.Deployables.UpdateUIElement(boomer.Definition.Item).foreach { case (currElem, curr, maxElem, max) =>
                    avatarEvents ! AvatarServiceMessage(name, AvatarAction.PlanetsideAttributeToAll(Service.defaultPlayerGUID, maxElem, max))
                    avatarEvents ! AvatarServiceMessage(name, AvatarAction.PlanetsideAttributeToAll(Service.defaultPlayerGUID, currElem, curr))
                  }
                  localEvents ! LocalServiceMessage.Deployables(RemoverActor.AddTask(boomer, zone))
                  localEvents ! LocalServiceMessage(factionChannel,
                    LocalAction.DeployableMapIcon(Service.defaultPlayerGUID, DeploymentAction.Dismiss,
                      DeployableInfo(guid, DeployableIcon.Boomer, boomer.Position, PlanetSideGUID(0))
                    )
                  )
                  avatarEvents ! AvatarServiceMessage(factionChannel, AvatarAction.SetEmpire(Service.defaultPlayerGUID, guid, PlanetSideEmpire.NEUTRAL))
                }
              case _ => ; //pointless trigger? or a trigger being deleted?
            }
          case _ => ;
        }


      case Zone.Ground.CanNotDropItem(_, item, reason) =>
        log.warn(s"${player.Name} tried to drop a ${item.Definition.Name} on the ground, but it $reason")

      case Zone.Ground.ItemInHand(_) => ;

      case Zone.Ground.CanNotPickupItem(_, item_guid, reason) =>
        log.warn(s"${player.Name} failed to pick up an item ($item_guid) from the ground because $reason")

      case _ => ;
    }

  protected def TakesDamage : Receive = {
    case Vitality.Damage(applyDamageTo) =>
      if(player.isAlive) {
        val originalHealth = player.Health
        val originalArmor = player.Armor
        val originalCapacitor = player.Capacitor.toInt
        val cause = applyDamageTo(player)
        val health = player.Health
        val armor = player.Armor
        val capacitor = player.Capacitor.toInt
        val damageToHealth = originalHealth - health
        val damageToArmor = originalArmor - armor
        val damageToCapacitor = originalCapacitor - capacitor
        PlayerControl.HandleDamage(player, cause, damageToHealth, damageToArmor, damageToCapacitor)
        if(damageToHealth > 0 || damageToArmor > 0 || damageToCapacitor > 0) {
          damageLog.info(s"${player.Name}-infantry: BEFORE=$originalHealth/$originalArmor/$originalCapacitor, AFTER=$health/$armor/$capacitor, CHANGE=$damageToHealth/$damageToArmor/$damageToCapacitor")
        }
      }
  }

  /**
    * Start the jammered buzzing.
    * Although, as a rule, the jammering sound effect should last as long as the jammering status,
    * Infantry seem to hear the sound for a bit longer than the effect.
    * @see `JammableBehavior.StartJammeredSound`
    * @param target an object that can be affected by the jammered status
    * @param dur the duration of the timer, in milliseconds;
    *            by default, 30000
    */
  override def StartJammeredSound(target : Any, dur : Int) : Unit = target match {
    case obj : Player if !jammedSound =>
      obj.Zone.AvatarEvents ! AvatarServiceMessage(obj.Zone.Id, AvatarAction.PlanetsideAttributeToAll(obj.GUID, 27, 1))
      super.StartJammeredSound(obj, 3000)
    case _ => ;
  }

  /**
    * Perform a variety of tasks to indicate being jammered.
    * Deactivate implants (should also uninitialize them),
    * delay stamina regeneration for a certain number of turns,
    * and set the jammered status on specific holstered equipment.
    * @see `JammableBehavior.StartJammeredStatus`
    * @param target an object that can be affected by the jammered status
    * @param dur the duration of the timer, in milliseconds
    */
  override def StartJammeredStatus(target : Any, dur : Int) : Unit = target match {
    case obj : Player =>
      //TODO these features
//      val guid = obj.GUID
//      val zone = obj.Zone
//      val zoneId = zone.Id
//      val events = zone.AvatarEvents
      player.Implants.indices.foreach { slot => // Deactivate & uninitialize all implants
        player.Zone.AvatarEvents ! AvatarServiceMessage(player.Zone.Id, AvatarAction.PlanetsideAttribute(player.GUID, 28, player.Implant(slot).id * 2)) // Deactivation sound / effect
        self ! Player.ImplantActivation(slot, 0)
        PlayerControl.UninitializeImplant(player, slot)
      }

      obj.skipStaminaRegenForTurns = math.max(obj.skipStaminaRegenForTurns, 10)
      super.StartJammeredStatus(target, dur)
    case _ => ;
  }

  override def CancelJammeredStatus(target: Any): Unit = {
    player.Implants.indices.foreach { slot => // Start reinitializing all implants
      self ! Player.ImplantInitializationStart(slot)
    }

    super.CancelJammeredStatus(target)
  }

  /**
    * Stop the jammered buzzing.
    * @see `JammableBehavior.CancelJammeredSound`
    * @param target an object that can be affected by the jammered status
    */
  override def CancelJammeredSound(target : Any) : Unit = target match {
    case obj : Player if jammedSound =>
      obj.Zone.AvatarEvents ! AvatarServiceMessage(obj.Zone.Id, AvatarAction.PlanetsideAttributeToAll(obj.GUID, 27, 0))
      super.CancelJammeredSound(obj)
    case _ => ;
  }

  def RepairValue(item : Tool) : Int = if(player.ExoSuit != ExoSuitType.MAX) {
    item.FireMode.Modifiers.Damage0
  }
  else {
    item.FireMode.Modifiers.Damage3
  }

  def MessageDeferredCallback(msg : Any) : Unit = {
    msg match {
      case Containable.MoveItem(_, item, _) =>
        //momentarily put item back where it was originally
        val obj = ContainerObject
        obj.Find(item) match {
          case Some(slot) =>
            obj.Zone.AvatarEvents ! AvatarServiceMessage(
              player.Name,
              AvatarAction.SendResponse(Service.defaultPlayerGUID, ObjectAttachMessage(obj.GUID, item.GUID, slot))
            )
          case None => ;
        }
      case _ => ;
    }
  }

  def RemoveItemFromSlotCallback(item : Equipment, slot : Int) : Unit = {
    val obj = ContainerObject
    val zone = obj.Zone
    val name = player.Name
    val toChannel = if(obj.VisibleSlots.contains(slot) || obj.isBackpack) zone.Id else name
    val events = zone.AvatarEvents
    item.Faction = PlanetSideEmpire.NEUTRAL
    if(slot == obj.DrawnSlot) {
      obj.DrawnSlot = Player.HandsDownSlot
    }
    events ! AvatarServiceMessage(toChannel, AvatarAction.ObjectDelete(Service.defaultPlayerGUID, item.GUID))
  }

  def PutItemInSlotCallback(item : Equipment, slot : Int) : Unit = {
    val obj = ContainerObject
    val guid = obj.GUID
    val zone = obj.Zone
    val events = zone.AvatarEvents
    val name = player.Name
    val definition = item.Definition
    val msg = AvatarAction.SendResponse(
      Service.defaultPlayerGUID,
      ObjectCreateDetailedMessage(
        definition.ObjectId,
        item.GUID,
        ObjectCreateMessageParent(guid, slot),
        definition.Packet.DetailedConstructorData(item).get
      )
    )
    if(obj.isBackpack) {
      item.Faction = PlanetSideEmpire.NEUTRAL
      events ! AvatarServiceMessage(zone.Id, msg)
    }
    else {
      val faction = obj.Faction
      item.Faction = faction
      events ! AvatarServiceMessage(name, msg)
      if(obj.VisibleSlots.contains(slot)) {
        events ! AvatarServiceMessage(zone.Id, AvatarAction.EquipmentInHand(guid, guid, slot, item))
      }
      //handle specific types of items
      item match {
        case trigger : BoomerTrigger =>
          //pick up the trigger, own the boomer; make certain whole faction is aware of that
          (zone.GUID(trigger.Companion), zone.Players.find { _.name == name }) match {
            case (Some(boomer : BoomerDeployable), Some(avatar))
              if !boomer.OwnerName.contains(name) || boomer.Faction != faction =>
              val bguid = boomer.GUID
              val faction = player.Faction
              val factionChannel = faction.toString
              if(avatar.Deployables.Add(boomer)) {
                boomer.Faction = faction
                boomer.AssignOwnership(player)
                avatar.Deployables.UpdateUIElement(boomer.Definition.Item).foreach { case (currElem, curr, maxElem, max) =>
                  events ! AvatarServiceMessage(name, AvatarAction.PlanetsideAttributeToAll(Service.defaultPlayerGUID, maxElem, max))
                  events ! AvatarServiceMessage(name, AvatarAction.PlanetsideAttributeToAll(Service.defaultPlayerGUID, currElem, curr))
                }
                zone.LocalEvents ! LocalServiceMessage.Deployables(RemoverActor.ClearSpecific(List(boomer), zone))
                events ! AvatarServiceMessage(factionChannel, AvatarAction.SetEmpire(Service.defaultPlayerGUID, bguid, faction))
                zone.LocalEvents ! LocalServiceMessage(factionChannel,
                  LocalAction.DeployableMapIcon(Service.defaultPlayerGUID, DeploymentAction.Build,
                    DeployableInfo(bguid, DeployableIcon.Boomer, boomer.Position, boomer.Owner.getOrElse(PlanetSideGUID(0)))
                  )
                )
              }
            case _ => ; //pointless trigger?
          }
        case _ => ;
      }
    }
  }

  def SwapItemCallback(item : Equipment) : Unit = {
    val obj = ContainerObject
    val zone = obj.Zone
    zone.AvatarEvents ! AvatarServiceMessage(player.Name, AvatarAction.SendResponse(Service.defaultPlayerGUID, ObjectDetachMessage(obj.GUID, item.GUID, Vector3.Zero, 0f)))
  }
}

object PlayerControl {
  /**
    * na
    * @param target na
    */
  def HandleDamage(target : Player, cause : ResolvedProjectile, damageToHealth : Int, damageToArmor : Int, damageToCapacitor : Int) : Unit = {
    val targetGUID = target.GUID
    val zone = target.Zone
    val zoneId = zone.Id
    val events = zone.AvatarEvents
    val health = target.Health
    if(health > 0) {
      if(damageToCapacitor > 0) {
        events ! AvatarServiceMessage(target.Name, AvatarAction.PlanetsideAttributeSelf(targetGUID, 7, target.Capacitor.toLong))
      }
      if(damageToHealth > 0 || damageToArmor > 0) {
        target.History(cause)
        if(damageToHealth > 0) {
          events ! AvatarServiceMessage(zoneId, AvatarAction.PlanetsideAttributeToAll(targetGUID, 0, health))
        }
        if(damageToArmor > 0) {
          events ! AvatarServiceMessage(zoneId, AvatarAction.PlanetsideAttributeToAll(targetGUID, 4, target.Armor))
        }
        //activity on map
        zone.Activity ! Zone.HotSpot.Activity(cause.target, cause.projectile.owner, cause.hit_pos)
        //alert damage source
        DamageAwareness(target, cause)
      }
      if(Damageable.CanJammer(target, cause)) {
        target.Actor ! JammableUnit.Jammered(cause)
      }
    }
    else {
      if(damageToArmor > 0) {
        events ! AvatarServiceMessage(zoneId, AvatarAction.PlanetsideAttributeToAll(targetGUID, 4, target.Armor))
      }
      DestructionAwareness(target, Some(cause))
    }
  }

  /**
    * na
    * @param target na
    * @param cause na
    */
  def DamageAwareness(target : Player, cause : ResolvedProjectile) : Unit = {
    val zone = target.Zone
    zone.AvatarEvents ! AvatarServiceMessage(
      target.Name,
      cause.projectile.owner match {
        case pSource : PlayerSource => //player damage
          val name = pSource.Name
          zone.LivePlayers.find(_.Name == name).orElse(zone.Corpses.find(_.Name == name)) match {
            case Some(player) => AvatarAction.HitHint(player.GUID, target.GUID)
            case None => AvatarAction.SendResponse(Service.defaultPlayerGUID, DamageWithPositionMessage(10, pSource.Position))
          }
        case source => AvatarAction.SendResponse(Service.defaultPlayerGUID, DamageWithPositionMessage(10, source.Position))
      }
    )
  }

  /**
    * The player has lost all his vitality and must be killed.<br>
    * <br>
    * Shift directly into a state of being dead on the client by setting health to zero points,
    * whereupon the player will perform a dramatic death animation.
    * Stamina is also set to zero points.
    * If the player was in a vehicle at the time of demise, special conditions apply and
    * the model must be manipulated so it behaves correctly.
    * Do not move or completely destroy the `Player` object as its coordinates of death will be important.<br>
    * <br>
    * A maximum revive waiting timer is started.
    * When this timer reaches zero, the avatar will attempt to spawn back on its faction-specific sanctuary continent.
    * @param target na
    * @param cause na
    */
  def DestructionAwareness(target : Player, cause : Option[ResolvedProjectile]) : Unit = {
    val player_guid = target.GUID
    val pos = target.Position
    val respawnTimer = 300000 //milliseconds
    val zone = target.Zone
    val events = zone.AvatarEvents
    val nameChannel = target.Name
    val zoneChannel = zone.Id
    target.Die
    //unjam
    target.Actor ! JammableUnit.ClearJammeredSound()
    target.Actor ! JammableUnit.ClearJammeredStatus()
    events ! AvatarServiceMessage(nameChannel, AvatarAction.Killed(player_guid, target.VehicleSeated)) //align client interface fields with state
    zone.GUID(target.VehicleSeated) match {
      case Some(obj : Mountable) =>
        //boot cadaver from seat internally (vehicle perspective)
        obj.PassengerInSeat(target) match {
          case Some(index) =>
            obj.Seats(index).Occupant = None
          case _ => ;
        }
        //boot cadaver from seat on client
        events ! AvatarServiceMessage(nameChannel, AvatarAction.SendResponse(Service.defaultPlayerGUID,
          ObjectDetachMessage(obj.GUID, player_guid, target.Position, Vector3.Zero))
        )
        //make player invisible on client
        events ! AvatarServiceMessage(nameChannel, AvatarAction.PlanetsideAttributeToAll(player_guid, 29, 1))
        //only the dead player should "see" their own body, so that the death camera has something to focus on
        events ! AvatarServiceMessage(zoneChannel, AvatarAction.ObjectDelete(player_guid, player_guid))
      case _ => ;
    }
    events ! AvatarServiceMessage(zoneChannel, AvatarAction.PlanetsideAttributeToAll(player_guid, 0, 0)) //health
    events ! AvatarServiceMessage(nameChannel, AvatarAction.PlanetsideAttributeToAll(player_guid, 2, 0)) //stamina
    if(target.Capacitor > 0) {
      target.Capacitor = 0
      events ! AvatarServiceMessage(nameChannel, AvatarAction.PlanetsideAttributeToAll(player_guid, 7, 0)) // capacitor
    }
    val attribute = cause match {
      case Some(resolved) =>
        resolved.projectile.owner match {
          case pSource : PlayerSource =>
            val name = pSource.Name
            zone.LivePlayers.find(_.Name == name).orElse(zone.Corpses.find(_.Name == name)) match {
              case Some(player) => player.GUID
              case None => player_guid
            }
          case _ => player_guid
        }
      case _ => player_guid
    }
    events ! AvatarServiceMessage(
      nameChannel,
      AvatarAction.SendResponse(Service.defaultPlayerGUID, DestroyMessage(player_guid, attribute, Service.defaultPlayerGUID, pos)) //how many players get this message?
    )
    events ! AvatarServiceMessage(
      nameChannel,
      AvatarAction.SendResponse(Service.defaultPlayerGUID, AvatarDeadStateMessage(DeadState.Dead, respawnTimer, respawnTimer, pos, target.Faction, true))
    )
    //TODO other methods of death?
    val pentry = PlayerSource(target)
    (target.History.find({p => p.isInstanceOf[PlayerSuicide]}) match {
      case Some(PlayerSuicide(_)) =>
        None
      case _ =>
        cause.orElse { target.LastShot } match {
          case out @ Some(shot) =>
            if(System.nanoTime - shot.hit_time < (10 seconds).toNanos) {
              out
            }
            else {
              None //suicide
            }
          case None =>
            None //suicide
        }
    }) match {
      case Some(shot) =>
        events ! AvatarServiceMessage(zoneChannel, AvatarAction.DestroyDisplay(shot.projectile.owner, pentry, shot.projectile.attribute_to))
      case None =>
        events ! AvatarServiceMessage(zoneChannel, AvatarAction.DestroyDisplay(pentry, pentry, 0))
    }
  }

  def UninitializeImplant(player: Player, slot: Int): Unit = {
    val implantSlot = player.ImplantSlot(slot)

    implantSlot.Initialized = false
    if(implantSlot.InitializeTimer != Default.Cancellable) {
      implantSlot.InitializeTimer.cancel()
      implantSlot.InitializeTimer = Default.Cancellable
    }
    player.Zone.AvatarEvents ! AvatarServiceMessage(player.Zone.Id, AvatarAction.SendResponse(player.GUID, AvatarImplantMessage(player.GUID, ImplantAction.Initialization, slot, 0)))
  }
}
