package com.unciv.iaciv.orders

/**
 * An order the bridge applied at H1, kept as identifiers and names only (in UI mode each turn runs on a clone of
 * GameInfo: never keep game objects). [OrderChecker] compares it with the game state at H4.
 */
sealed class AppliedOrder {
    abstract val orderId: String
    abstract val orderType: String

    /** Id of a later order of the same decree that replaced this one (e.g. two research orders, two focuses for a city) */
    var supersededBy: String? = null

    /** [techs] = the research queue as set by the order */
    class Research(override val orderId: String, override val orderType: String, val techs: List<String>) : AppliedOrder()

    class FreeTech(override val orderId: String, val tech: String) : AppliedOrder() {
        override val orderType get() = "choose_free_tech"
    }

    class Policy(override val orderId: String, val policy: String) : AppliedOrder() {
        override val orderType get() = "adopt_policy"
    }

    /** [items] = the items actually applied, in the order requested */
    class Queue(override val orderId: String, val cityId: String, val mode: String, val items: List<String>) : AppliedOrder() {
        override val orderType get() = "set_production_queue"
    }

    class Focus(override val orderId: String, val cityId: String, val focus: String) : AppliedOrder() {
        override val orderType get() = "set_city_focus"
    }

    class Purchase(override val orderId: String, val cityId: String, val construction: String, val stat: String) : AppliedOrder() {
        override val orderType get() = "purchase"
    }

    class Toggle(override val orderId: String, val name: String, val disable: Boolean) : AppliedOrder() {
        override val orderType get() = if (disable) "disable_construction" else "enable_construction"
    }

    class Noop(override val orderId: String) : AppliedOrder() {
        override val orderType get() = "noop"
    }

    // region M3 diplomacy (civ ids, request and alert ids only)

    class DeclareWar(override val orderId: String, val target: String) : AppliedOrder() {
        override val orderType get() = "declare_war"
    }

    /** [requestId] = id of the request at the target (null for a city-state: peace applied at once) */
    class ProposePeace(override val orderId: String, val target: String, val requestId: String?) : AppliedOrder() {
        override val orderType get() = "propose_peace"
    }

    class OfferFriendship(override val orderId: String, val target: String) : AppliedOrder() {
        override val orderType get() = "offer_friendship"
    }

    class RespondFriendship(override val orderId: String, val proposer: String, val accept: Boolean) : AppliedOrder() {
        override val orderType get() = "respond_friendship"
    }

    /**
     * [decision] accept | refuse | counter; [fingerprint] = the accepted trade as we store it ([com.unciv.iaciv.diplomacy.DiplomacyIds.tradeFingerprint]);
     * [counterId] = id of our counter-proposal at [other]
     */
    class RespondTrade(
        override val orderId: String, val requestId: String, val other: String, val decision: String,
        val isPeace: Boolean, val fingerprint: String, val counterId: String?,
    ) : AppliedOrder() {
        override val orderType get() = "respond_trade"
    }

    class ProposeTrade(override val orderId: String, val target: String, val requestId: String) : AppliedOrder() {
        override val orderType get() = "propose_trade"
    }

    class Denounce(override val orderId: String, val target: String) : AppliedOrder() {
        override val orderType get() = "denounce"
    }

    class RespondDemand(override val orderId: String, val demander: String, val demand: String, val agree: Boolean) : AppliedOrder() {
        override val orderType get() = "respond_demand"
    }

    class MakeDemand(override val orderId: String, val target: String, val demand: String) : AppliedOrder() {
        override val orderType get() = "make_demand"
    }

    // endregion

    /** True if [later], applied after this order in the same decree, replaces it */
    fun isSupersededBy(later: AppliedOrder): Boolean = when (this) {
        is Research -> later is Research
        is Focus -> later is Focus && later.cityId == cityId
        is Queue -> later is Queue && later.cityId == cityId && later.mode == "replace"
        is Toggle -> later is Toggle && later.name == name
        else -> false
    }
}
