package net.runelite.client.plugins.microbot.zerozero.zeroprayer;

/*
 * Copyright (c) 2025, ZeroZero Prayer Plugin Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.kit.KitType;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages defensive prayer switching based on enemy attacks.
 * This class handles enemy attack detection and automatically activates
 * the appropriate defensive prayers.
 */
@Slf4j
@Singleton
public class DefensivePrayerManager
{
    @Inject
    private Client client;

    private final AttackTimerMetronomeConfig config;
    private Rs2PrayerEnum activeDefensivePrayer;
    private int defensivePrayerDeactivationTick = -1;
    private Actor currentTarget;
    
    // Combat state tracking
    private int outOfCombatTicks = 0;
    private static final int OUT_OF_COMBAT_TIMEOUT_TICKS = 25; // ~500ms (25 game ticks) - faster prayer deactivation
    private int lastCombatTick = -1;
    private boolean wasInCombatLastTick = false;
    
    // Pattern tracking for predictive defense
    private final Map<Actor, EnemyAttackPattern> enemyPatterns = new HashMap<>();
    
    // Priority levels for different detection methods
    private enum DetectionPriority {
        PROJECTILE,      // Highest priority - immediate threat
        ANIMATION,       // High priority - attack starting  
        PREDICTIVE,      // Medium priority - pattern based
        EQUIPMENT        // Lowest priority - equipment based
    }

    public DefensivePrayerManager(AttackTimerMetronomeConfig config, Client client)
    {
        this.config = config;
        this.client = client;
    }

    /**
     * Main method called each game tick to handle defensive prayer logic.
     */
    public void handleDefensivePrayers()
    {
        if (!config.enableDefensivePrayers()) {
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return;
        }

        // Check combat state and detect being attacked
        boolean isInCombat = isPlayerInCombat(localPlayer);
        boolean isBeingAttacked = isPlayerBeingAttacked(localPlayer);
        Actor target = getCurrentTarget();
        
        // Enhanced combat state tracking
        if (isInCombat || isBeingAttacked || target != null) {
            outOfCombatTicks = 0;
            lastCombatTick = client.getTickCount(); // Track last combat activity
            wasInCombatLastTick = true;
        } else {
            outOfCombatTicks++;
            
            // If we just left combat, immediately deactivate defensive prayers
            if (wasInCombatLastTick) {
                deactivateDefensivePrayer("combat ended");
                wasInCombatLastTick = false;
                handleNoTargetDefense();
                return;
            }
        }

        // Check for out of combat timeout - deactivate defensive prayers
        if (outOfCombatTicks >= OUT_OF_COMBAT_TIMEOUT_TICKS) {
            deactivateDefensivePrayer("out of combat timeout");
            handleNoTargetDefense();
            return;
        }

        // If no combat activity, handle deactivation
        if (!isInCombat && !isBeingAttacked && target == null) {
            handleDefensivePrayerDeactivation();
            return;
        }

        // Update current target tracking
        if (target != null && !target.equals(currentTarget)) {
            currentTarget = target;
            log.debug("New target detected: {}", getActorName(target));
        }

        // Priority 1: Check for incoming projectiles (highest priority)
        if (handleProjectileDefense()) {
            return;
        }

        // Priority 2: Check enemy animations (high priority)
        if (target != null && handleAnimationDefense(target)) {
            return;
        }

        // Priority 3: Check if any NPC is attacking us (even without our target)
        if (isBeingAttacked && handleBeingAttackedDefense()) {
            return;
        }

        // Priority 4: Predictive defense (medium priority)
        if (config.enablePredictiveDefense() && target != null && handlePredictiveDefense(target)) {
            return;
        }

        // Priority 5: Equipment-based defense for PvP (lowest priority)
        if (config.enablePvpMode() && target instanceof Player && handleEquipmentDefense((Player) target)) {
            return;
        }

        // Handle defensive prayer deactivation
        handleDefensivePrayerDeactivation();
    }

    /**
     * Handles projectile-based defense detection.
     * This has the highest priority as projectiles are immediate threats.
     */
    private boolean handleProjectileDefense()
    {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return false;

        for (Projectile projectile : client.getProjectiles()) {
            if (isProjectileTargetingPlayer(projectile, localPlayer)) {
                EnemyAttackType attackType = EnemyProjectileData.getAttackType(projectile.getId());
                
                if (attackType.hasDefensivePrayer()) {
                    log.debug("Projectile detected: ID={}, Type={}", projectile.getId(), attackType);
                    activateDefensivePrayer(attackType, DetectionPriority.PROJECTILE);
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Handles animation-based defense detection.
     * This checks if the current target is performing an attack animation.
     */
    private boolean handleAnimationDefense(Actor target)
    {
        int animationId = target.getAnimation();
        if (animationId == -1) return false;

        EnemyAttackType attackType = EnemyAnimationData.getAttackType(animationId);
        
        if (attackType.hasDefensivePrayer()) {
            log.debug("Enemy animation detected: Actor={}, Animation={}, Type={}", 
                     getActorName(target), animationId, attackType);
            activateDefensivePrayer(attackType, DetectionPriority.ANIMATION);
            return true;
        }
        
        return false;
    }

    /**
     * Handles predictive defense based on enemy attack patterns.
     * This is experimental and tries to predict the next attack based on patterns.
     */
    private boolean handlePredictiveDefense(Actor target)
    {
        EnemyAttackPattern pattern = enemyPatterns.computeIfAbsent(target, k -> new EnemyAttackPattern());
        
        EnemyAttackType predictedAttack = pattern.predictNextAttack();
        
        if (predictedAttack != null && predictedAttack.hasDefensivePrayer()) {
            log.debug("Predictive defense: Actor={}, Predicted={}", getActorName(target), predictedAttack);
            activateDefensivePrayer(predictedAttack, DetectionPriority.PREDICTIVE);
            return true;
        }
        
        return false;
    }

    /**
     * Handles equipment-based defense for PvP scenarios.
     * This analyzes the opponent's equipment to predict their attack style.
     */
    private boolean handleEquipmentDefense(Player enemy)
    {
        PlayerComposition composition = enemy.getPlayerComposition();
        if (composition == null) return false;

        // Get equipped weapon
        int weaponId = composition.getEquipmentId(KitType.WEAPON);
        EnemyAttackType predictedType = predictAttackTypeFromWeapon(weaponId);
        
        if (predictedType != null && predictedType.hasDefensivePrayer()) {
            log.debug("Equipment-based defense: Player={}, Weapon={}, Type={}", 
                     enemy.getName(), weaponId, predictedType);
            activateDefensivePrayer(predictedType, DetectionPriority.EQUIPMENT);
            return true;
        }
        
        return false;
    }

    /**
     * Handles the case when there's no current target.
     */
    private void handleNoTargetDefense()
    {
        currentTarget = null;
        // Clear enemy patterns when no target
        enemyPatterns.clear();
        
        // Handle defensive prayer deactivation
        handleDefensivePrayerDeactivation();
    }

    /**
     * Checks if the player is currently in combat (either attacking or being attacked).
     */
    private boolean isPlayerInCombat(Player localPlayer)
    {
        // Check if player is actively attacking something
        if (localPlayer.getInteracting() != null) {
            return true;
        }
        
        // Check if player is in combat state using utility
        return Rs2Combat.inCombat();
    }

    /**
     * Checks if the player is being attacked by any NPC or Player.
     */
    private boolean isPlayerBeingAttacked(Player localPlayer)
    {
        // Check all NPCs to see if any are targeting the player
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getInteracting() == localPlayer) {
                // Additional checks to ensure it's a valid threat
                if (!npc.isDead() && npc.getHealthRatio() != 0) {
                    return true;
                }
            }
        }
        
        // Check all Players to see if any are targeting the player (PvP)
        if (config.enablePvpMode()) {
            for (Player player : client.getTopLevelWorldView().players()) {
                if (player != null && player != localPlayer && player.getInteracting() == localPlayer) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Handles defense when the player is being attacked but doesn't have a specific target.
     */
    private boolean handleBeingAttackedDefense()
    {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return false;

        // Find who is attacking us and activate appropriate defense
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && npc.getInteracting() == localPlayer) {
                if (!npc.isDead() && npc.getHealthRatio() != 0) {
                    // Check animation
                    int animationId = npc.getAnimation();
                    if (animationId != -1) {
                        EnemyAttackType attackType = EnemyAnimationData.getAttackType(animationId);
                        if (attackType.hasDefensivePrayer()) {
                            log.debug("Being attacked by NPC: Actor={}, Animation={}, Type={}", 
                                     getActorName(npc), animationId, attackType);
                            activateDefensivePrayer(attackType, DetectionPriority.ANIMATION);
                            return true;
                        }
                    }
                    
                    // If no animation, use equipment-based prediction for the NPC
                    EnemyAttackType predictedType = predictNPCAttackType(npc);
                    if (predictedType != null && predictedType.hasDefensivePrayer()) {
                        log.debug("Being attacked by NPC (equipment-based): Actor={}, Type={}", 
                                 getActorName(npc), predictedType);
                        activateDefensivePrayer(predictedType, DetectionPriority.EQUIPMENT);
                        return true;
                    }
                }
            }
        }
        
        // Check PvP attacks
        if (config.enablePvpMode()) {
            for (Player player : client.getTopLevelWorldView().players()) {
                if (player != null && player != localPlayer && player.getInteracting() == localPlayer) {
                    int animationId = player.getAnimation();
                    if (animationId != -1) {
                        EnemyAttackType attackType = EnemyAnimationData.getAttackType(animationId);
                        if (attackType.hasDefensivePrayer()) {
                            log.debug("Being attacked by Player: Actor={}, Animation={}, Type={}", 
                                     getActorName(player), animationId, attackType);
                            activateDefensivePrayer(attackType, DetectionPriority.ANIMATION);
                            return true;
                        }
                    }
                    
                    // Use equipment-based prediction for the player
                    if (handleEquipmentDefense(player)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    /**
     * Predicts attack type for NPCs that don't have specific weapon equipment.
     */
    private EnemyAttackType predictNPCAttackType(NPC npc)
    {
        // Default fallback based on NPC characteristics
        // This is a simplified approach - could be expanded with NPC-specific data
        String npcName = npc.getName();
        if (npcName != null) {
            String lowerName = npcName.toLowerCase();
            
            // Magic users
            if (lowerName.contains("wizard") || lowerName.contains("mage") || 
                lowerName.contains("shaman") || lowerName.contains("witch")) {
                return EnemyAttackType.MAGIC;
            }
            
            // Ranged users  
            if (lowerName.contains("archer") || lowerName.contains("ranger") || 
                lowerName.contains("crossbow") || lowerName.contains("thrower")) {
                return EnemyAttackType.RANGED;
            }
            
            // Dragons often use multiple attack styles, default to magic for safety
            if (lowerName.contains("dragon")) {
                return EnemyAttackType.MAGIC;
            }
        }
        
        // Default to melee for most NPCs
        return EnemyAttackType.MELEE;
    }

    /**
     * Deactivates the current defensive prayer with logging.
     */
    private void deactivateDefensivePrayer(String reason)
    {
        if (activeDefensivePrayer != null && Rs2Prayer.isPrayerActive(activeDefensivePrayer)) {
            Rs2Prayer.toggle(activeDefensivePrayer, false);
            log.debug("Deactivated defensive prayer: {} (Reason: {})", activeDefensivePrayer.getName(), reason);
        }
        activeDefensivePrayer = null;
        defensivePrayerDeactivationTick = -1;
    }

    /**
     * Activates the appropriate defensive prayer for the given attack type.
     */
    private void activateDefensivePrayer(EnemyAttackType attackType, DetectionPriority priority)
    {
        Rs2PrayerEnum newPrayer = attackType.getDefensivePrayer();
        
        if (newPrayer == null || newPrayer.equals(activeDefensivePrayer)) {
            return;
        }

        // Check if we have prayer points
        if (Rs2Prayer.isOutOfPrayer()) {
            log.warn("Out of prayer points, cannot activate defensive prayer");
            return;
        }

        // Switch to the new defensive prayer
        Rs2Prayer.swapOverHeadPrayer(newPrayer);
        activeDefensivePrayer = newPrayer;
        defensivePrayerDeactivationTick = config.defensivePrayerDelay();
        
        log.debug("Activated defensive prayer: {} (Priority: {})", newPrayer.getName(), priority);
    }

    /**
     * Handles the deactivation of defensive prayers after the configured delay.
     */
    private void handleDefensivePrayerDeactivation()
    {
        if (defensivePrayerDeactivationTick > 0) {
            defensivePrayerDeactivationTick--;
        } else if (defensivePrayerDeactivationTick == 0) {
            // Deactivate defensive prayer
            if (activeDefensivePrayer != null && Rs2Prayer.isPrayerActive(activeDefensivePrayer)) {
                Rs2Prayer.toggle(activeDefensivePrayer, false);
                log.debug("Deactivated defensive prayer: {}", activeDefensivePrayer.getName());
            }
            activeDefensivePrayer = null;
            defensivePrayerDeactivationTick = -1;
        }
    }

    /**
     * Gets the current combat target.
     */
    private Actor getCurrentTarget()
    {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return null;
        
        Actor interacting = localPlayer.getInteracting();
        
        // Validate the target
        if (interacting == null) return null;
        
        // For NPCs, check if they're still valid
        if (interacting instanceof NPC) {
            NPC npc = (NPC) interacting;
            if (npc.isDead() || npc.getHealthRatio() == 0) return null;
        }
        
        // For Players, check if they're still in combat with us
        if (interacting instanceof Player) {
            Player player = (Player) interacting;
            if (player.getInteracting() != localPlayer && !Rs2Player.isInMulti()) {
                return null; // They're not targeting us in single combat
            }
        }
        
        return interacting;
    }

    /**
     * Checks if a projectile is targeting the player.
     */
    private boolean isProjectileTargetingPlayer(Projectile projectile, Player localPlayer)
    {
        Actor targetActor = projectile.getTargetActor();
        
        // Direct actor targeting
        if (targetActor == localPlayer) {
            return true;
        }
        
        // Check target position for AoE or ground-targeted projectiles
        if (targetActor == null) {
            // For projectiles without an actor target, check if they're targeting the player's location
            WorldPoint playerLocation = localPlayer.getWorldLocation();
            LocalPoint projectileTarget = projectile.getTarget();
            
            if (playerLocation != null && projectileTarget != null) {
                // Convert LocalPoint to WorldPoint for comparison
                WorldPoint projectileWorldPoint = WorldPoint.fromLocal(client, projectileTarget);
                if (projectileWorldPoint != null) {
                    // Check if projectile is targeting the player's tile or adjacent tiles
                    int distance = playerLocation.distanceTo(projectileWorldPoint);
                    return distance <= 1; // Allow for 1 tile radius to catch AoE attacks
                }
            }
        }
        
        return false;
    }

    /**
     * Predicts attack type based on equipped weapon for PvP scenarios.
     */
    private EnemyAttackType predictAttackTypeFromWeapon(int weaponId)
    {
        // This is a simplified weapon type detection
        // In practice, you'd want a more comprehensive weapon database
        
        if (weaponId == -1) return EnemyAttackType.MELEE; // Unarmed is melee
        
        // Check weapon type based on ID ranges or specific IDs
        // This would need to be expanded with a proper weapon database
        
        // Magic weapons (staves, wands)
        if (isStaffOrWand(weaponId)) {
            return EnemyAttackType.MAGIC;
        }
        
        // Ranged weapons (bows, crossbows, thrown)
        if (isBowOrCrossbow(weaponId)) {
            return EnemyAttackType.RANGED;
        }
        
        // Default to melee for most weapons
        return EnemyAttackType.MELEE;
    }

    /**
     * Helper method to check if a weapon is a staff or wand.
     */
    private boolean isStaffOrWand(int weaponId)
    {
        // This is a simplified check - you'd want to expand this with actual weapon IDs
        return weaponId >= 1381 && weaponId <= 1410; // Staff ID range (simplified)
    }

    /**
     * Helper method to check if a weapon is a bow or crossbow.
     */
    private boolean isBowOrCrossbow(int weaponId)
    {
        // This is a simplified check - you'd want to expand this with actual weapon IDs
        return (weaponId >= 837 && weaponId <= 861) || // Bow ID range (simplified)
               (weaponId >= 9174 && weaponId <= 9185);   // Crossbow ID range (simplified)
    }

    /**
     * Gets the name of an actor for logging purposes.
     */
    private String getActorName(Actor actor)
    {
        if (actor instanceof NPC) {
            return ((NPC) actor).getName();
        } else if (actor instanceof Player) {
            return ((Player) actor).getName();
        }
        return "Unknown";
    }

    /**
     * Gets the currently active defensive prayer.
     */
    public Rs2PrayerEnum getActiveDefensivePrayer()
    {
        return activeDefensivePrayer;
    }

    /**
     * Resets the defensive prayer manager state.
     */
    public void reset()
    {
        activeDefensivePrayer = null;
        defensivePrayerDeactivationTick = -1;
        currentTarget = null;
        outOfCombatTicks = 0;
        lastCombatTick = -1;
        wasInCombatLastTick = false;
        enemyPatterns.clear();
    }

    /**
     * Inner class for tracking enemy attack patterns (for predictive defense).
     */
    private static class EnemyAttackPattern
    {
        private EnemyAttackType lastAttack;
        private int consecutiveSameAttacks = 0;
        
        public void recordAttack(EnemyAttackType attackType)
        {
            if (attackType == lastAttack) {
                consecutiveSameAttacks++;
            } else {
                consecutiveSameAttacks = 1;
                lastAttack = attackType;
            }
        }
        
        public EnemyAttackType predictNextAttack()
        {
            // Very simple prediction logic - assume they'll continue with the same attack
            // In practice, you'd want more sophisticated pattern recognition
            if (lastAttack != null && consecutiveSameAttacks >= 2) {
                return lastAttack;
            }
            return null;
        }
    }
}
