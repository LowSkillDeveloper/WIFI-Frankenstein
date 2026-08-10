package com.lsd.wififrankenstein.ui.internetblocking.scanner









enum class SniBlockVerdict {

    SNI_BLOCKED,


    IP_BLOCKED,


    INCONCLUSIVE
}




object SniBlockDecision {

    fun classify(
        targetReset: Boolean,
        targetProgressed: Boolean,
        benignReset: Boolean,
        benignProgressed: Boolean
    ): SniBlockVerdict {
        if (targetReset && benignProgressed) return SniBlockVerdict.SNI_BLOCKED
        if (targetReset && benignReset) return SniBlockVerdict.IP_BLOCKED
        return SniBlockVerdict.INCONCLUSIVE
    }
}
