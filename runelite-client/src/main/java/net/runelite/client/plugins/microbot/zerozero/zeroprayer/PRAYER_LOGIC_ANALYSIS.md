# Zero Prayer Plugin - Enhanced Logic & Flow Analysis

## Current Plugin Overview

The `AttackTimerMetronomePlugin` currently provides:
- **Offensive Prayer Management**: Automatically activates the best prayer based on player's attack style
- **Attack Timing**: Tracks attack cooldowns and manages prayer timing
- **Lazy Flicking**: Optimizes prayer activation timing to save prayer points

## Enhanced Plugin Requirements

### Core Functionality Extension
1. **Player Attack Analysis** (Current Implementation)
2. **Enemy Attack Detection** (New Implementation Required)
3. **Defensive Prayer Management** (New Implementation Required)

---

## 1. Player Attack Analysis (Current Implementation)

### Attack Style Detection
```java
private AttackStyle getAttackStyle() {
    // Current implementation analyzes:
    - Equipped weapon ID
    - Animation data
    - Powered staves detection
    - Varbit-based attack style
    - Cached attack style for performance
}
```

### Offensive Prayer Logic
```java
private Rs2PrayerEnum determineOffensivePrayer(AttackStyle attackStyle) {
    switch (attackStyle) {
        case ACCURATE/AGGRESSIVE/CONTROLLED/DEFENSIVE: // Melee
            return Rs2Prayer.getBestMeleePrayer();
        case RANGING/LONGRANGE: // Ranged
            return Rs2Prayer.getBestRangePrayer(); 
        case CASTING/DEFENSIVE_CASTING: // Magic
            return Rs2Prayer.getBestMagePrayer();
    }
}
```

### Prayer Timing Modes

#### Lazy Flick Mode
- Activates prayer 2 ticks before attack
- Deactivates prayer 1 tick after activation
- Saves prayer points through precise timing

#### Normal Mode
- Keeps prayers active while in combat
- Deactivates after 150 ticks (3 seconds) out of combat

---

## 2. Enemy Attack Detection (New Implementation Required)

### Target Identification System

#### Primary Targets
1. **NPCs/Monsters**
   - Get interacting NPC
   - Filter by health/and if me and target are in combat
   - Special NPCs handling (bosses, etc.)

2. **Players (PvP)**
   - Get interacting player
   - Monitor player equipment changes
   - Track animation sequences

#### Implementation Structure
```java
private Actor getCurrentTarget() {
    Player localPlayer = client.getLocalPlayer();
    Actor target = localPlayer.getInteracting();
    
    if (target instanceof NPC) {
        return validateNpcTarget((NPC) target);
    } else if (target instanceof Player) {
        return validatePlayerTarget((Player) target);
    }
    
    return null;
}
```

### Enemy Attack Style Detection

#### Animation-Based Detection
```java
@Subscribe
public void onAnimationChanged(AnimationChanged event) {
    Actor actor = event.getActor();
    
    // Skip if not our current target
    if (!isCurrentTarget(actor)) return;
    
    int animationId = actor.getAnimation();
    AttackType detectedAttack = analyzeEnemyAnimation(animationId, actor);
    
    if (detectedAttack != null) {
        activateDefensivePrayer(detectedAttack);
    }
}
```

#### Projectile-Based Detection
```java
@Subscribe
public void onProjectileMoved(ProjectileMoved event) {
    Projectile projectile = event.getProjectile();
    
    // Check if projectile is targeting player
    if (!isTargetingPlayer(projectile)) return;
    
    AttackType attackType = analyzeProjectile(projectile.getId());
    
    if (attackType != null) {
        activateDefensivePrayer(attackType);
    }
}
```

#### Equipment-Based Detection (PvP)
```java
private AttackType predictPlayerAttack(Player enemy) {
    PlayerComposition composition = enemy.getPlayerComposition();
    
    // Check equipped weapon
    int weaponId = composition.getEquipmentId(KitType.WEAPON);
    WeaponType weaponType = getWeaponType(weaponId);
    
    // Check animation for style confirmation
    int animationId = enemy.getAnimation();
    AnimationData animData = AnimationData.fromId(animationId);
    
    return determineAttackType(weaponType, animData);
}
```

### Enemy Database Structure

#### Animation Database
```java
public enum EnemyAnimationData {
    // Melee Animations
    GENERIC_MELEE_SLASH(401, AttackType.MELEE),
    GENERIC_MELEE_STAB(390, AttackType.MELEE),
    
    // Ranged Animations  
    GENERIC_BOW_ATTACK(426, AttackType.RANGED),
    CROSSBOW_ATTACK(4230, AttackType.RANGED),
    
    // Magic Animations
    STANDARD_SPELL_CAST(711, AttackType.MAGIC),
    ANCIENT_SPELL_CAST(1978, AttackType.MAGIC),
    
    // Boss-Specific Animations
    HUNLLEF_MAGE_ATTACK(8754, AttackType.MAGIC),
    HUNLLEF_RANGE_ATTACK(8755, AttackType.RANGED),
    
    // Player Equipment-Based
    PLAYER_WHIP_ATTACK(1658, AttackType.MELEE),
    PLAYER_STAFF_ATTACK(1167, AttackType.MAGIC);
}
```

#### Projectile Database
```java
public enum EnemyProjectileData {
    // Standard Projectiles
    ARROW_PROJECTILE(10, AttackType.RANGED),
    BOLT_PROJECTILE(9, AttackType.RANGED),
    
    // Magic Projectiles
    FIRE_BOLT_PROJECTILE(130, AttackType.MAGIC),
    ICE_BARRAGE_PROJECTILE(369, AttackType.MAGIC),
    
    // Boss-Specific Projectiles
    GAUNTLET_MAGE_PROJ(1707, AttackType.MAGIC),
    GAUNTLET_RANGE_PROJ(1711, AttackType.RANGED),
    
    // Special Projectiles
    DRAGON_FIRE_PROJ(393, AttackType.DRAGONFIRE),
    POISON_PROJ(164, AttackType.POISON);
}
```

---

## 3. Defensive Prayer Management (New Implementation)

### Core Defensive Logic
```java
private void activateDefensivePrayer(AttackType attackType) {
    Rs2PrayerEnum defensivePrayer = getDefensivePrayer(attackType);
    
    if (defensivePrayer != null) {
        // Switch to defensive prayer immediately
        Rs2Prayer.swapOverHeadPrayer(defensivePrayer);
        
        // Set deactivation timer if needed
        scheduleDefensivePrayerDeactivation();
    }
}

private Rs2PrayerEnum getDefensivePrayer(AttackType attackType) {
    switch (attackType) {
        case MELEE:
            return Rs2PrayerEnum.PROTECT_MELEE;
        case RANGED:
            return Rs2PrayerEnum.PROTECT_RANGE;
        case MAGIC:
            return Rs2PrayerEnum.PROTECT_MAGIC;
        default:
            return null;
    }
}
```

### Advanced Defensive Features

#### Prediction System
```java
private void handlePredictiveDefense(Actor enemy) {
    // Analyze enemy patterns
    AttackPattern pattern = analyzeAttackPattern(enemy);
    
    // Predict next attack based on:
    // - Previous attack sequence
    // - Equipment changes
    // - Special attack patterns
    AttackType predictedAttack = pattern.predictNextAttack();
    
    if (predictedAttack != null) {
        activateDefensivePrayer(predictedAttack);
    }
}
```

#### Priority System
```java
private enum DefensePriority {
    IMMEDIATE_THREAT(1),    // Projectile detected
    ANIMATION_DETECTED(2),  // Enemy animation started
    PREDICTIVE(3),          // Based on patterns
    EQUIPMENT_BASED(4);     // Based on enemy equipment
    
    private final int priority;
}
```

---

## 4. Integration Logic Flow

### Main Game Tick Handler
```java
@Subscribe
public void onGameTick(GameTick event) {
    // Current offensive prayer logic
    handleOffensivePrayers();
    
    // New defensive prayer logic
    handleDefensivePrayers();
    
    // Attack state management
    updateAttackState();
}

private void handleDefensivePrayers() {
    Actor currentTarget = getCurrentTarget();
    
    if (currentTarget == null) {
        // No target - maintain current defensive prayer or deactivate
        handleNoTargetDefense();
        return;
    }
    
    // Priority 1: Check for incoming projectiles
    if (hasIncomingProjectile()) {
        handleProjectileDefense();
        return;
    }
    
    // Priority 2: Check enemy animations
    if (hasEnemyAttackAnimation(currentTarget)) {
        handleAnimationDefense(currentTarget);
        return;
    }
    
    // Priority 3: Predictive defense
    if (config.enablePredictiveDefense()) {
        handlePredictiveDefense(currentTarget);
    }
}
```

### Configuration Options
```java
public interface EnhancedPrayerConfig extends Config {
    @ConfigItem(name = "Enable Defensive Prayers")
    default boolean enableDefensivePrayers() { return true; }
    
    @ConfigItem(name = "Predictive Defense")
    default boolean enablePredictiveDefense() { return false; }
    
    @ConfigItem(name = "PvP Mode")
    default boolean enablePvpMode() { return false; }
    
    @ConfigItem(name = "Boss-Specific Mode")
    default boolean enableBossMode() { return true; }
}
```

---

## 5. Performance Considerations

### Caching Strategy
```java
private class EnemyTracker {
    private Actor lastTarget;
    private AttackPattern cachedPattern;
    private long lastUpdateTime;
    
    public AttackType predictNextAttack(Actor enemy) {
        if (enemy != lastTarget || isStaleData()) {
            cachedPattern = analyzeAttackPattern(enemy);
            lastTarget = enemy;
            lastUpdateTime = System.currentTimeMillis();
        }
        
        return cachedPattern.getNextAttack();
    }
}
```

### Event Prioritization
1. **Projectile Events** - Immediate response required
2. **Animation Events** - Quick response needed  
3. **Equipment Events** - Predictive analysis
4. **Pattern Events** - Background analysis

---

## 6. Error Handling & Edge Cases

### Edge Case Scenarios
1. **Multi-Target Combat** - Handle switching between targets
2. **Rapid Attack Switches** - Handle quick style changes
3. **Network Lag** - Compensate for delayed events
4. **Prayer Point Depletion** - Graceful degradation
5. **Invalid Animations** - Fallback to equipment-based detection

### Safety Mechanisms
```java
private void safetyCheck() {
    // Prevent prayer spam
    if (isRecentPrayerChange()) return;
    
    // Check prayer points
    if (Rs2Prayer.isOutOfPrayer()) {
        disableAutoPrayers();
        return;
    }
    
    // Validate target still exists
    if (!isValidTarget(currentTarget)) {
        currentTarget = null;
    }
}
```

---

## 7. Testing & Validation

### Test Scenarios
1. **PvM Testing**
   - Various boss mechanics
   - Multi-style enemies
   - Special attack patterns

2. **PvP Testing**
   - Different weapon combinations
   - Style switching
   - Advanced combat techniques

3. **Performance Testing**
   - High-frequency combat
   - Multiple simultaneous enemies
   - Memory usage validation

### Debugging Features
```java
private void debugOutput() {
    if (config.enableDebugMode()) {
        log.info("Target: {} | Detected Attack: {} | Active Prayer: {}", 
                currentTarget, detectedAttackType, activePrayer);
    }
}
```

---

## 8. Implementation Priority

### Phase 1: Core Defensive System
1. Basic enemy animation detection
2. Simple defensive prayer switching  
3. Projectile-based defense

### Phase 2: Advanced Features
1. Predictive defense system
2. PvP-specific enhancements
3. Boss-specific optimizations

### Phase 3: Optimization
1. Performance improvements
2. Advanced caching
3. Machine learning patterns

This comprehensive system will provide both offensive and defensive prayer automation while maintaining the flexibility and performance of the current implementation.
