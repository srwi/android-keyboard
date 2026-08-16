/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futo.inputmethod.latin.inputlogic;

import android.os.SystemClock;

/**
 * Decides whether a new input (tap or swipe) starts a fresh word, extends the current
 * word-window, or commits a pending window word before starting a new one.
 *
 * <p>The arbiter is a small, dependency-light single-thread state holder (consumed on the IME
 * UI thread only). It is fed the wall-clock "now" of the new input and the previous input's
 * end time, plus the user-configured combine {@code gap} (ms; 0 = feature off ⇒ stock
 * behavior). Decision rules (in order):
 * <ul>
 *   <li>{@code gap <= 0}: always {@link Decision#START_FRESH} and no state is mutated — the
 *       consumer keeps stock behavior and the arbiter stays inert.</li>
 *   <li>No open window: {@link Decision#START_FRESH} (and a new window is opened with this
 *       input as its first unit).</li>
 *   <li>Open window and the new input started within {@code gap} of the previous input's end:
 *       {@link Decision#EXTEND}.</li>
 *   <li>Open window and the new input started {@code >= gap} after the previous input's end:
 *       {@link Decision#COMMIT_THEN_START}. The pending word is not committed by silence: it
 *       stays composing until the next input first commits it with one space, then begins a
 *       new window.</li>
 * </ul>
 *
 * <p>A "window" is the run of consecutive inputs that are part of one word. Whether the window
 * is decoded the stock way (letters in {@link org.futo.inputmethod.latin.WordComposer}) or as a
 * single swipe-unit is gated by {@link #windowHasSwipe()}: as soon as any swipe is in the
 * window, the consumer must feed the whole accumulated pointer path (taps as micro-swipes +
 * swipes) to the swipe decoder; until then, pure-tap windows append letters the stock way.
 *
 * <p><b>The gap timer arms only once a swipe is part of the word.</b> While a window is pure-tap
 * ({@link #windowHasSwipe()} is false), {@link #onStartInput} always returns
 * {@link Decision#EXTEND} — taps never start the timer, so typing slowly ("h a l") never commits
 * the word mid-typing. The timer reference ({@link #mLastInputEndTime}) is only maintained once
 * the window contains a swipe; from that point on the usual rules apply (within gap ⇒ extend,
 * past gap ⇒ {@link Decision#COMMIT_THEN_START}).
 *
 * <p><b>Arming model.</b> {@link #windowHasSwipe()} flips to true only when a swipe actually
 * completes ({@link #recordInputEnd(long, boolean)} with {@code isSwipe == true}), never at swipe
 * start. This gives the invariant {@code windowHasSwipe() ⇒ mLastInputEndTime != NO_PREVIOUS}:
 * a has-swipe window always has an armed timer, so a cancelled swipe (whose
 * {@code recordInputEnd} is never called) naturally leaves the window pure-tap with no stale
 * timer, with no special revert logic.
 *
 * <p>Consumer contract for {@link Decision#COMMIT_THEN_START}: the pending window's state
 * (including {@link #windowHasSwipe()}) stays observable so the consumer can pick the right
 * commit path (swipe-window-decoded vs normal typed-word auto-correction). After committing,
 * the consumer MUST call {@link #restartWindowForNewInput()} to close the pending window and
 * open a fresh one with the new input as its first unit, then call
 * {@link #recordInputEnd(long, boolean)} once the new input has completed.
 */
final class WordWindowArbiter {
    enum Decision { START_FRESH, EXTEND, COMMIT_THEN_START }

    /** Sentinel for "no previous input yet". */
    private static final long NO_PREVIOUS = -1L;

    private long mLastInputEndTime = NO_PREVIOUS;
    private boolean mWindowOpen = false;
    private boolean mWindowHasSwipe = false;

    private final LongSupplier mClock;

    /** A clock supplier so tests can drive the gap deterministically without Thread.sleep. */
    @FunctionalInterface
    interface LongSupplier {
        long now();
    }

    WordWindowArbiter() {
        this(() -> SystemClock.uptimeMillis());
    }

    WordWindowArbiter(final LongSupplier clock) {
        mClock = clock;
    }

    /**
     * Decide what the new input should do, measuring the gap against the arbiter's own clock.
     * Callers that already have a timestamp (e.g. a swipe's first down-event time) should use
     * {@link #onStartInput(int, long, boolean)} instead.
     *
     * @param gap combine gap in ms; {@code <= 0} ⇒ always {@link Decision#START_FRESH}.
     * @param isSwipe true if the new input is a swipe; false if a tap.
     * @return the decision; never null.
     */
    Decision onStartInput(final int gap, final boolean isSwipe) {
        return onStartInput(gap, mClock.now(), isSwipe);
    }

    /**
     * Variant with an explicit "now" for callers that already have a timestamp they want to use
     * (e.g. the swipe's first down-event time, {@code BatchInputArbiter.sGestureFirstDownTime}).
     */
    Decision onStartInput(final int gap, final long now, final boolean isSwipe) {
        if (gap <= 0) {
            // Feature off: every input starts fresh. Do NOT mutate state so the arbiter stays
            // inert and stock behavior is byte-identical to the no-feature build.
            return Decision.START_FRESH;
        }
        if (!mWindowOpen) {
            // First input of a new window — open it with this input as its first unit.
            mWindowOpen = true;
            return Decision.START_FRESH;
        }
        if (!mWindowHasSwipe) {
            // Pure-tap window so far: the timer has not been armed (it only arms once a swipe
            // completes). Taps — and the first swipe — always extend: silence between taps never
            // commits, no matter how slow the typing. Note we deliberately do NOT set hasSwipe
            // here even for a swipe: it flips to true only when the swipe completes, so a
            // cancelled first swipe never leaves a stale has-swipe state.
            return Decision.EXTEND;
        }
        // The window contains a completed swipe, so the timer is guaranteed armed (invariant:
        // windowHasSwipe() ⇒ mLastInputEndTime != NO_PREVIOUS).
        if ((now - mLastInputEndTime) < gap) {
            // Within the gap ⇒ this input extends the still-open window.
            return Decision.EXTEND;
        }
        // Gap exceeded ⇒ the pending window commits lazily on this, the next input. Do NOT
        // mutate here: the consumer must observe the pending window's windowHasSwipe() to
        // pick the correct commit path. After committing it calls
        // {@link #restartWindowForNewInput()} to open the new window.
        return Decision.COMMIT_THEN_START;
    }

    /**
     * Called by the consumer after a {@link Decision#COMMIT_THEN_START} has been handled: the
     * pending word was committed, and now the new input (whose start triggered the commit)
     * becomes the first unit of a fresh window. Equivalent to {@link #onWindowReset()} followed
     * by re-opening, in one call.
     */
    void restartWindowForNewInput() {
        mWindowOpen = true;
        mWindowHasSwipe = false;
        // Reset the timer reference: a fresh window starts unarmed. The consumer's
        // recordInputEnd() re-arms it once an input completes AND that input is a swipe (which
        // is also what flips hasSwipe back on).
        mLastInputEndTime = NO_PREVIOUS;
    }

    /**
     * Close the window unconditionally. Called at every normal commit site (space/separator/
     * external commit) — the user pressing space/separator always commits normally, so the next
     * input must start a fresh window. Also called when the input method is reset.
     */
    void onWindowReset() {
        mWindowOpen = false;
        mWindowHasSwipe = false;
        mLastInputEndTime = NO_PREVIOUS;
    }

    /**
     * Record the end time of the just-completed input so the next {@link #onStartInput} can
     * measure against the gap. For:
     * <ul>
     *   <li>a tap ({@code isSwipe == false}): the {@code now} of {@code onCodeInput} (visually a
     *       tap ends when it starts);</li>
     *   <li>a swipe ({@code isSwipe == true}): the up-event time of {@code onEndBatchInput}.</li>
     * </ul>
     * The consumer MUST call this once per input that should be counted toward the window.
     *
     * <p>A completed swipe is what turns the window into a has-swipe window and arms the timer;
     * taps only maintain the timer once the window already contains a swipe. A pure-tap window
     * therefore never arms the timer — slow tapping never commits (the first swipe always
     * extends a pure-tap prefix no matter how late it starts). A cancelled swipe never calls this
     * method, so it leaves the window pure-tap with no stale timer.
     */
    void recordInputEnd(final long now, final boolean isSwipe) {
        if (isSwipe) {
            mWindowHasSwipe = true;
        }
        if (mWindowHasSwipe) {
            mLastInputEndTime = now;
        }
    }

    boolean isWindowOpen() {
        return mWindowOpen;
    }

    /**
     * Whether the current window contains any swipe. Consumers gate "decode whole path as one
     * swipe-unit" on this: {@code false} ⇒ pure-tap window ⇒ stock letter-append via
     * {@link org.futo.inputmethod.latin.WordComposer}; {@code true} ⇒ taps within this window
     * must be synthesized as micro-swipe segments and the whole accumulated path re-decoded
     * together as one word.
     */
    boolean windowHasSwipe() {
        return mWindowHasSwipe;
    }
}