package com.kennyb1201.kbstream.ui.simkl

/**
 * The words the V2 link-state offer is made of (spec 2026-11-16, F3).
 *
 * Its own object for the reason [SimklDisconnectCopy] is its own: the sentence
 * is a claim about the state, both screens draw it, and a test reads it. Only a
 * device that holds NO session is ever shown this, so every line here is written
 * for that device and says "another device" rather than implying a shared
 * session this one does not have.
 *
 * The name in [line] is a display name the account itself returns - never a
 * token, an id or an email - and it is the whole of what the link state carries.
 */
internal object SimklLinkOfferCopy {

    /**
     * The offer, one line, naming the account.
     *
     * Deliberately not a popup and not a nag: it sits above the button that acts
     * on it, on a screen the viewer opened themselves.
     */
    fun line(accountName: String): String = "Linked as $accountName on another device"

    /**
     * The action - the EXISTING device flow, started by this button.
     *
     * Shaped like the screen's other buttons (uppercase, like GENERATE CODE,
     * RETRY and RECONNECT), because it is the same kind of control: one tap, one
     * code, one approval. It is not a second sign-in path.
     */
    const val ACTION = "CONNECT THIS DEVICE"

    /**
     * What the offer does NOT do, said once so both surfaces agree.
     *
     * The per-device half is the part a viewer cannot guess: they just linked on
     * their phone, and the reasonable expectation is that the TV is now signed
     * in. It is not, and cannot be - it needs its own grant - and saying so here
     * is cheaper than a support question.
     */
    const val EXPLANATION =
        "Simkl sign-ins stay on the device they were made on, so this device needs " +
            "its own code. Your other device stays signed in."
}
