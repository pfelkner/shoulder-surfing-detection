package eu.sisik.backgroundcam

enum class AlertMechanism(val id: Int) {
    WARNING_SIGN(2131230938),
    FLASHING_BORDERS(2131230937),
    ATTACKER_IMAGE(2131230939);

    companion object {
        fun fromInt(id: Int) = AlertMechanism.values().first { it.id == id }
    }
}

