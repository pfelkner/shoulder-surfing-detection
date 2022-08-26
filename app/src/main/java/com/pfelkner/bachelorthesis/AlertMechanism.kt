package com.pfelkner.bachelorthesis

enum class AlertMechanism(val id: Int) {
    WARNING_SIGN(1),
    FLASHING_BORDERS(2),
    ATTACKER_IMAGE(3);

    companion object {
        fun fromInt(id: Int) = AlertMechanism.values().first { it.id == id }
    }
}

