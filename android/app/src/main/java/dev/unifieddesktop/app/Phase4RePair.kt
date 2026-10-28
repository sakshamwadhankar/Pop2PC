package dev.unifieddesktop.app

/** B-126: a PC that has forgotten this phone asks again; the user must confirm a code. */
object Phase4RePair {
    fun wanted(paired: Boolean, alreadyPrompting: Boolean, control: String): Boolean =
        paired && !alreadyPrompting && control.trim() == "pair-request"
}
