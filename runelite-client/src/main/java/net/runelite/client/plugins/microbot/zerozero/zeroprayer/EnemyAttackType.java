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

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

/**
 * Enumeration representing different types of enemy attacks
 * and their corresponding defensive prayers.
 */
@Getter
@RequiredArgsConstructor
public enum EnemyAttackType
{
    MELEE(Rs2PrayerEnum.PROTECT_MELEE),
    RANGED(Rs2PrayerEnum.PROTECT_RANGE),
    MAGIC(Rs2PrayerEnum.PROTECT_MAGIC),
    UNKNOWN(null);

    private final Rs2PrayerEnum defensivePrayer;

    /**
     * Gets the appropriate defensive prayer for this attack type.
     * 
     * @return The defensive prayer to activate, or null if no prayer is needed
     */
    public Rs2PrayerEnum getDefensivePrayer()
    {
        return defensivePrayer;
    }

    /**
     * Checks if this attack type has a corresponding defensive prayer.
     * 
     * @return true if a defensive prayer exists for this attack type
     */
    public boolean hasDefensivePrayer()
    {
        return defensivePrayer != null;
    }
}
