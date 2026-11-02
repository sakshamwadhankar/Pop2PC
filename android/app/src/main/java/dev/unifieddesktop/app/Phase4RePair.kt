package dev.unifieddesktop.app

/** B-126: a PC that has forgotten this phone asks again; the user must confirm a code. */
object Phase4RePair {
    fun wanted(paired: Boolean, alreadyPrompting: Boolean, control: String): Boolean =
        paired && !alreadyPrompting && control.trim() == "pair-request"

    /**
     * B-115/B-126: a pending prompt always means PAIRING, even on a session that is still
     * paired. The re-pair prompt must not leave the old trust in force, or a PC could start
     * video/input before the user taps Yes.
     */
    fun gate(paired: Boolean, prompting: Boolean): Phase4Protocol.Gate = when {
        prompting -> Phase4Protocol.Gate.PAIRING
        paired -> Phase4Protocol.Gate.PAIRED
        else -> Phase4Protocol.Gate.UNPAIRED
    }
}
