package com.kennyb1201.kbstream.data.notifications

/**
 * When the app is allowed to ask for the runtime notification grant.
 *
 * Android 13 turned notifications into a runtime permission, and this app's two
 * alert features - "a new episode aired" and the guide's REMIND ME - are both on
 * by default, because each is already scoped to something the viewer asked for.
 * That combination has one bad shape: on a fresh install the features are on,
 * the checker runs, and every alert is dropped by the system with nothing on
 * screen to explain why. [NotificationCenter.canPost] refuses silently by
 * design, which is right for a background worker and wrong for the first launch.
 *
 * So the ask happens once, at launch, and only when there is something to ask
 * about. There is deliberately no "ask anyway" arm: a viewer who has both alerts
 * off has told the app they do not want notifications, and a permission prompt
 * for a feature they turned off is the definition of nagging.
 *
 * A pure object so the four conditions are read as one rule rather than as an
 * `if` inside an Activity, and so a device running Android 12 (where the
 * permission does not exist and is implicitly granted) can never be prompted.
 */
internal object NotificationPromptRules {

    /** The API level that introduced `POST_NOTIFICATIONS`. */
    const val POST_NOTIFICATIONS_SDK = 33

    /**
     * @param sdkInt the device's `Build.VERSION.SDK_INT`
     * @param granted whether `POST_NOTIFICATIONS` is currently held
     * @param alreadyAsked whether this install has already spent its one prompt
     * @param anyAlertEnabled whether either notification feature is switched on
     */
    fun shouldAsk(
        sdkInt: Int,
        granted: Boolean,
        alreadyAsked: Boolean,
        anyAlertEnabled: Boolean
    ): Boolean = sdkInt >= POST_NOTIFICATIONS_SDK &&
        !granted &&
        !alreadyAsked &&
        anyAlertEnabled
}
