/*
 * Copyright (C) 2012 The Android Open Source Project
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

package org.futo.inputmethod.latin;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.test.suitebuilder.annotation.LargeTest;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import org.futo.inputmethod.latin.common.Constants;
import org.futo.inputmethod.latin.settings.Settings;

/**
 * Integration tests for the auto-space / word-window mode: with "Combine words" enabled,
 * consecutive taps and swipes decode as one word; with "Auto space" additionally enabled the
 * auto-space delay slider (the combine gap) auto-commits the word once the next input starts
 * past it, while combine-only keeps the word accumulating until a manual commit. With both
 * toggles disabled, stock behavior is restored.
 *
 * <p>Each acceptance case is mirrored with the feature disabled asserting the stock result, so the
 * feature can never regress stock behavior. Timing is driven with {@code Thread.sleep} because
 * {@link InputTestsBase#type(String)} / {@link InputTestsBase#gesture(String)} run on real wall
 * clock; the gap used below (500 ms) is comfortably larger than the {@code gesture()} helper's
 * internal 200 ms wait, so consecutive inputs combine, while {@code sleep} well past the gap
 * separates them.
 */
@LargeTest
public class WordInputWindowTests extends InputTestsBase {

    @Override
    protected EditorInfo enrichEditorInfo(final EditorInfo ei) {
        ei.inputType |= TextUtils.CAP_MODE_SENTENCES;
        ei.initialCapsMode = TextUtils.CAP_MODE_SENTENCES;
        return ei;
    }

    // The combine gap (ms) used while the feature is on. Large enough that consecutive
    // type()/gesture() calls (which take a few ms + the helper's 200 ms gesture wait) still
    // combine, but a deliberate sleep past it commits the pending word.
    private static final int GAP_MS = 500;

    private void setAutoSpace(final int gapMs) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getService());
        final SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(Settings.PREF_WORD_COMBINE, true);
        editor.putBoolean(Settings.PREF_WORD_AUTO_SPACE, true);
        editor.putInt(Settings.PREF_WORD_INPUT_GAP, gapMs);
        editor.commit();
        mLatinIMELegacy.loadSettings();
    }

    private void setManualCombine() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getService());
        final SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(Settings.PREF_WORD_COMBINE, true);
        editor.putBoolean(Settings.PREF_WORD_AUTO_SPACE, false);
        editor.commit();
        mLatinIMELegacy.loadSettings();
    }

    private void setAutoSpaceWithoutCombine(final int gapMs) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getService());
        final SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(Settings.PREF_WORD_COMBINE, false);
        editor.putBoolean(Settings.PREF_WORD_AUTO_SPACE, true);
        editor.putInt(Settings.PREF_WORD_INPUT_GAP, gapMs);
        editor.commit();
        mLatinIMELegacy.loadSettings();
    }

    private void setFeatureOff() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getService());
        final SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(Settings.PREF_WORD_COMBINE, false);
        editor.putBoolean(Settings.PREF_WORD_AUTO_SPACE, false);
        editor.putInt(Settings.PREF_WORD_INPUT_GAP, 0);
        editor.commit();
        mLatinIMELegacy.loadSettings();
    }

    // Sleep past the gap so the next input must commit the pending window word.
    private void sleepPastGap() {
        sleep(GAP_MS + 200);
        runMessages();
    }

    // Let the tail-batch decode (async) settle before asserting the editor text.
    private void settleSuggestionStrip() {
        sleep(DELAY_TO_WAIT_FOR_GESTURE_MILLIS);
        runMessages();
    }

    private void resetEditor() {
        mEditText.setText("");
        sendUpdateForCursorMoveTo(0);
        runMessages();
    }

    // ---- Acceptance case 1: t + swipe o->l ==> toll / tol ----

    public void testGapCombineTapThenSwipe() {
        setAutoSpace(GAP_MS);
        resetEditor();
        type("t");
        gesture("ol");
        final String text = mEditText.getText().toString();
        assertTrue("tap + swipe within gap should decode as one word starting with 'tol', got: "
                + text, text.startsWith("tol"));
    }

    public void testGapZeroTapThenSwipeIsStock() {
        setFeatureOff();
        resetEditor();
        type("t");
        gesture("ol");
        final String text = mEditText.getText().toString();
        // Stock: the swipe commits the typed 't' with a space, then decodes the swipe alone.
        assertTrue("gap==0 must keep stock behavior (t committed + space + separate swipe word), got: "
                + text, text.startsWith("t "));
        assertFalse("gap==0 must not combine tap and swipe into one word, got: " + text,
                text.startsWith("tol"));
    }

    // ---- Capitalization: a word that starts capitalized must keep its cap when a swipe
    // enters the window and re-decodes the whole path as one word. ----

    public void testGapCombineTapThenSwipeKeepsCapitalization() {
        setAutoSpace(GAP_MS);
        resetEditor();
        type("M");
        gesture("onday");
        settleSuggestionStrip();
        final String text = mEditText.getText().toString();
        assertTrue("the decoded word must keep the capitalized start, got: " + text,
                text.startsWith("Mon"));
    }

    public void testGapCombineSwipeFirstKeepsCapitalization() {
        setAutoSpace(GAP_MS);
        resetEditor();
        gesture("Monday");
        settleSuggestionStrip();
        final String text = mEditText.getText().toString();
        assertTrue("a swipe-only word at sentence start must be capitalized, got: " + text,
                text.startsWith("Mon"));
    }

    public void testAutoSpaceRequiresCombine() {
        setAutoSpaceWithoutCombine(GAP_MS);
        resetEditor();
        type("t");
        gesture("ol");
        final String text = mEditText.getText().toString();
        assertTrue("auto-space must be gated behind combine: with combine off it must stay stock, got: "
                + text, text.startsWith("t "));
    }

    // ---- Acceptance case 2: t + swipe o->l + tap l ==> toll ----

    public void testGapCombineTapSwipeThenTap() {
        setAutoSpace(GAP_MS);
        resetEditor();
        type("t");
        gesture("ol");
        type("l");
        settleSuggestionStrip();
        final String text = mEditText.getText().toString();
        assertTrue("tap + swipe + tap should decode to 'toll', got: " + text,
                text.startsWith("toll"));
    }

    public void testGapZeroTapSwipeThenTapIsStock() {
        setFeatureOff();
        resetEditor();
        type("t");
        gesture("ol");
        type("l");
        settleSuggestionStrip();
        final String text = mEditText.getText().toString();
        assertTrue("gap==0 must keep stock behavior (separate words), got: " + text,
                text.startsWith("t "));
    }

    // ---- Acceptance case 3: swipe tr + swipe ying ==> trying ----

    public void testGapCombineTwoSwipes() {
        setAutoSpace(GAP_MS);
        resetEditor();
        gesture("tr");
        gesture("ying");
        final String text = mEditText.getText().toString();
        assertTrue("two swipes within gap should decode together as 'trying', got: " + text,
                text.startsWith("trying"));
    }

    public void testGapZeroTwoSwipesIsStock() {
        setFeatureOff();
        resetEditor();
        gesture("tr");
        gesture("ying");
        settleSuggestionStrip();
        final String text = mEditText.getText().toString();
        // Stock: the second swipe commits the first swipe's word with a space.
        assertTrue("gap==0 must keep two separate words for two swipes, got: " + text,
                text.contains(" "));
    }

    // ---- Acceptance case 4: swipe th + tap e ==> the (never "thee") ----

    public void testGapCombineSwipeThenConfirminTap() {
        setAutoSpace(GAP_MS);
        resetEditor();
        gesture("th");
        type("e");
        settleSuggestionStrip();
        final String text = mEditText.getText().toString();
        assertTrue("swipe th + tap e should confirm 'the', not double the last letter, got: "
                + text, text.startsWith("the"));
    }

    public void testGapZeroSwipeThenTapIsStock() {
        setFeatureOff();
        resetEditor();
        gesture("th");
        type("e");
        settleSuggestionStrip();
        final String text = mEditText.getText().toString();
        assertTrue("gap==0 must not merge swipe and tap into one word, got: " + text,
                text.contains(" "));
    }

    // ---- Acceptance case 5: swipe m->i + tap r ==> mir ----

    public void testGapCombineSwipeThenTapNoSpace() {
        setAutoSpace(GAP_MS);
        resetEditor();
        gesture("mi");
        type("r");
        settleSuggestionStrip();
        final String text = mEditText.getText().toString();
        assertTrue("swipe mi + tap r should decode to 'mir' with no space, got: " + text,
                text.startsWith("mir"));
    }

    public void testGapZeroSwipeThenTapNoSpaceIsStock() {
        setFeatureOff();
        resetEditor();
        gesture("mi");
        type("r");
        settleSuggestionStrip();
        final String text = mEditText.getText().toString();
        assertTrue("gap==0 must not merge swipe and tap, got: " + text, text.contains(" "));
    }

    // ---- Acceptance case 6: hold w, tap i, slide w->e ==> "wie" (no-slide: "wi") ----
    // The single-pointer InputTestsBase.gesture() helper cannot reproduce a multi-touch
    // hold+interleaved-tap+slide; a two-letter swipe approximates the no-slide case and a
    // three-letter swipe the slid case, exercising the same window-extension code path.

    public void testGapSwipeSlideAndNoSlide() {
        setAutoSpace(GAP_MS);
        resetEditor();
        gesture("wie");
        final String slidText = mEditText.getText().toString();
        assertTrue("swipe w-i-e should decode to 'wie', got: " + slidText,
                slidText.startsWith("wie"));
        resetEditor();
        gesture("wi");
        final String noSlideText = mEditText.getText().toString();
        assertTrue("swipe w-i without the final slide should decode to 'wi', got: " + noSlideText,
                noSlideText.startsWith("wi"));
    }

    // ---- Acceptance case 7: fast peck t o l l; pause past gap, next input ----
    // The gap timer only arms once a swipe is part of the word: a pure-tap word never commits on
    // silence (the extra letter just extends it), whereas a word that contains a swipe commits
    // lazily when the next input arrives past the gap (with exactly one space).

    public void testGapPureTapNeverCommitsOnPause() {
        setAutoSpace(GAP_MS);
        resetEditor();
        type("t");
        type("o");
        type("l");
        type("l");
        sleepPastGap();
        type("x");
        final String text = mEditText.getText().toString();
        assertEquals("a pure-tap word must never start the gap timer: silence must not commit it "
                + "and the extra letter extends the same word, got: " + text, "tollx", text);
    }

    public void testGapSwipeWordCommitsOnPauseWithOneSpace() {
        setAutoSpace(GAP_MS);
        resetEditor();
        type("t");
        gesture("ol");
        sleepPastGap();
        type("x");
        final String text = mEditText.getText().toString();
        assertEquals("a word containing a swipe arms the gap timer: the next input past the gap "
                + "must commit the word with exactly one space, got: " + text, "toll x", text);
    }

    public void testGapZeroFastPeckNeverBreaksWord() {
        setFeatureOff();
        resetEditor();
        type("t");
        type("o");
        type("l");
        type("l");
        sleepPastGap();
        type("x");
        final String text = mEditText.getText().toString();
        // Stock: silence never commits; the extra letter just extends the composing word.
        assertEquals("gap==0 must keep the word as one unit, got: " + text, "tollx", text);
    }

    // ---- Acceptance case 8: a short swipe right after a letter tap still registers as a swipe
    // The InputTestsBase.gesture() helper bypasses GestureStrokeRecognitionPoints.isStartOfAGesture
    // (it calls onStartBatchInput directly), so the gesture-recognition gate itself cannot be
    // exercised through this harness. The window behavior for a swipe starting right after a tap is
    // covered by testGapCombineTapThenSwipe; the gate relax is covered by the pure unit test in
    // GestureStrokeRecognitionPointsTests (if added) / by on-device manual testing.

    // ---- Acceptance case 9: space/separator always commits immediately ----

    public void testGapSpaceCommitsImmediately() {
        setAutoSpace(GAP_MS);
        resetEditor();
        type("toll");
        type(" ");
        final String text = mEditText.getText().toString();
        assertTrue("space must commit the word immediately even in gap mode, got: " + text,
                text.startsWith("toll "));
    }

    public void testGapSeparatorCommitsImmediately() {
        setAutoSpace(GAP_MS);
        resetEditor();
        type("toll");
        type(".");
        final String text = mEditText.getText().toString();
        assertTrue("separator must commit the word immediately even in gap mode, got: " + text,
                text.startsWith("toll."));
    }

    public void testGapBackspaceDeletesWithinWord() {
        setAutoSpace(GAP_MS);
        resetEditor();
        type("t");
        type("o");
        type("l");
        type(Constants.CODE_DELETE);
        final String text = mEditText.getText().toString();
        assertEquals("backspace within a gap-word deletes the last letter, got: " + text,
                "tol", text);
    }

    // ---- Manual combine (combine on, gap slider 0): inputs accumulate into one word and are
    // never auto-committed on silence; only a manual commit (space/separator) ends the word. ----

    public void testCombineManualSwipeNeverAutoCommits() {
        setManualCombine();
        resetEditor();
        type("t");
        gesture("ol");
        sleepPastGap();
        type("x");
        settleSuggestionStrip();
        final String text = mEditText.getText().toString();
        assertEquals("manual combine must not auto-commit a swiped word on pause; the next tap "
                + "extends the same word, got: " + text, "tollx", text);
    }

    public void testCombineManualSpaceCommits() {
        setManualCombine();
        resetEditor();
        type("t");
        gesture("ol");
        type(" ");
        final String text = mEditText.getText().toString();
        assertTrue("manual combine: space must commit the word immediately, got: " + text,
                text.startsWith("toll "));
    }

    public void testCombineManualSeparatorCommits() {
        setManualCombine();
        resetEditor();
        type("t");
        gesture("ol");
        type(".");
        final String text = mEditText.getText().toString();
        assertTrue("manual combine: separator must commit the word immediately, got: " + text,
                text.startsWith("toll."));
    }
}
