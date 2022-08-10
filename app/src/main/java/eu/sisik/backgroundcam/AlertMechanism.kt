package eu.sisik.backgroundcam

enum class AlertMechanism(val id: Int) {
    WARNING_SIGN(2131231044),
    FLASHING_BORDERS(2131231043),
    ATTACKER_IMAGE(2131231045);

    companion object {
        fun fromInt(id: Int) = AlertMechanism.values().first { it.id == id }
    }
}

