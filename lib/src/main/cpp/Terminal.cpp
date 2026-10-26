/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "Terminal.h"
#include "mutf8.h"
#include <cstring>
#include <algorithm>
#include <vector>

#ifdef __ANDROID__
#  include <android/log.h>
#  define LOG_TAG "TermNative"
#  define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#  define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#  include <cstdio>
#  define LOG_TAG "TermNative"
#  define LOGD(...) do { fprintf(stderr, "[D/" LOG_TAG "] " __VA_ARGS__); fputc('\n', stderr); } while(0)
#  define LOGE(...) do { fprintf(stderr, "[E/" LOG_TAG "] " __VA_ARGS__); fputc('\n', stderr); } while(0)
#endif

#define JNI_CHECK_EXCEPTION_RETURN(env, retval) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); return (retval); } } while (0)
#define JNI_CHECK_EXCEPTION_RETURN_VOID(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); return; } } while (0)
#define JNI_CHECK_EXCEPTION(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); } } while (0)

// Thread-local CharArray/ByteArray pool to eliminate per-frame allocations
// Each thread gets reusable arrays sized to handle typical cell runs
static thread_local jcharArray tls_charArray = nullptr;
static thread_local jsize tls_charArraySize = 0;
static thread_local jbyteArray tls_metaArray = nullptr;
static thread_local jsize tls_metaArraySize = 0;

// Terminal implementation
Terminal::Terminal(JNIEnv* env, jobject callbacks, int rows, int cols, bool enableAltScreen, const char* xtversion)
    : mRows(rows), mCols(cols) {

    LOGD("Terminal constructor: rows=%d, cols=%d, altscreen=%d", rows, cols, enableAltScreen);

    // Get JavaVM for callback invocations from any thread
    env->GetJavaVM(&mJavaVM);

    // Borrow the callbacks object for the duration of construction: the
    // vterm_screen_reset below fires libvterm callbacks into Java, and the
    // JNI argument keeps it alive until return. No global reference is taken —
    // that ref is what rooted the Kotlin<->native cycle upstream #369.
    CallbackScope scope(*this, env, callbacks);

    // Cache method IDs
    ScopedLocalRef<jclass> callbacksClass(env, env->GetObjectClass(callbacks));
    mDamageMethod = env->GetMethodID(callbacksClass, "damage", "(IIII)I");
    if (!mDamageMethod || env->ExceptionCheck()) {
        LOGE("Failed to find damage method");
        env->ExceptionClear();
    }
    mMoverectMethod = env->GetMethodID(callbacksClass, "moverect",
        "(Lorg/connectbot/terminal/TermRect;Lorg/connectbot/terminal/TermRect;)I");
    if (!mMoverectMethod) {
        LOGE("Failed to find moverect method");
    }
    mMoveCursorMethod = env->GetMethodID(callbacksClass, "moveCursor",
        "(Lorg/connectbot/terminal/CursorPosition;Lorg/connectbot/terminal/CursorPosition;Z)I");
    if (!mMoveCursorMethod) {
        LOGE("Failed to find moveCursor method");
    }
    mSetTermPropMethod = env->GetMethodID(callbacksClass, "setTermProp",
        "(ILorg/connectbot/terminal/TerminalProperty;)I");
    if (!mSetTermPropMethod) {
        LOGE("Failed to find setTermProp method");
    }
    mBellMethod = env->GetMethodID(callbacksClass, "bell", "()I");
    if (!mBellMethod) {
        LOGE("Failed to find bell method");
    }
    mPushScrollbackMethod = env->GetMethodID(callbacksClass, "pushScrollbackLine",
        "(I[Lorg/connectbot/terminal/ScreenCell;Z)I");
    if (!mPushScrollbackMethod) {
        LOGE("Failed to find pushScrollbackLine method");
    }
    mPushScrollbackRegionMethod = env->GetMethodID(callbacksClass, "pushScrollbackLine",
        "(I[Lorg/connectbot/terminal/ScreenCell;ZI)I");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        mPushScrollbackRegionMethod = nullptr;
    }
    mPopScrollbackMethod = env->GetMethodID(callbacksClass, "popScrollbackLine",
        "(I[Lorg/connectbot/terminal/ScreenCell;)I");
    if (!mPopScrollbackMethod) {
        LOGE("Failed to find popScrollbackLine method");
    }
    mClearScrollbackMethod = env->GetMethodID(callbacksClass, "clearScrollback", "()I");
    if (!mClearScrollbackMethod) {
        LOGE("Failed to find clearScrollback method");
    }
    mKeyboardInputMethod = env->GetMethodID(callbacksClass, "onKeyboardInput", "([B)I");
    if (!mKeyboardInputMethod) {
        LOGE("Failed to find onKeyboardInput method");
    }
    mOscSequenceMethod = env->GetMethodID(callbacksClass, "onOscSequence", "(ILjava/lang/String;II)I");
    if (!mOscSequenceMethod) {
        LOGE("Failed to find onOscSequence method");
    }
    mImageFragmentMethod = env->GetMethodID(callbacksClass, "onImageFragment", "(Z[BZZII)J");
    if (!mImageFragmentMethod) {
        LOGE("Failed to find onImageFragment method");
    }
    mImageEditMethod = env->GetMethodID(callbacksClass, "onImageEdit", "(IIIIIII)V");
    if (!mImageEditMethod) {
        LOGE("Failed to find onImageEdit method");
    }
    mImageQueryMethod = env->GetMethodID(callbacksClass, "onImageQuery", "(I)V");
    if (!mImageQueryMethod) {
        LOGE("Failed to find onImageQuery method");
    }

    // Cache CellRun class and field IDs
    ScopedLocalRef<jclass> cellRunLocal(env, env->FindClass("org/connectbot/terminal/CellRun"));
    mCellRunClass = (jclass)env->NewGlobalRef(cellRunLocal);

    mFgRedField = env->GetFieldID(mCellRunClass, "fgRed", "I");
    mFgGreenField = env->GetFieldID(mCellRunClass, "fgGreen", "I");
    mFgBlueField = env->GetFieldID(mCellRunClass, "fgBlue", "I");
    mBgRedField = env->GetFieldID(mCellRunClass, "bgRed", "I");
    mBgGreenField = env->GetFieldID(mCellRunClass, "bgGreen", "I");
    mBgBlueField = env->GetFieldID(mCellRunClass, "bgBlue", "I");
    mBoldField = env->GetFieldID(mCellRunClass, "bold", "Z");
    mUnderlineField = env->GetFieldID(mCellRunClass, "underline", "I");
    mItalicField = env->GetFieldID(mCellRunClass, "italic", "Z");
    mBlinkField = env->GetFieldID(mCellRunClass, "blink", "Z");
    mReverseField = env->GetFieldID(mCellRunClass, "reverse", "Z");
    mStrikeField = env->GetFieldID(mCellRunClass, "strike", "Z");
    mFontField = env->GetFieldID(mCellRunClass, "font", "I");
    mDwlField = env->GetFieldID(mCellRunClass, "dwl", "Z");
    mDhlField = env->GetFieldID(mCellRunClass, "dhl", "I");
    mCharsField = env->GetFieldID(mCellRunClass, "chars", "[C");
    mRunLengthField = env->GetFieldID(mCellRunClass, "runLength", "I");
    mCellCountField = env->GetFieldID(mCellRunClass, "cellCount", "I");
    mCellMetaField = env->GetFieldID(mCellRunClass, "cellMeta", "[B");
    mImageIdField = env->GetFieldID(mCellRunClass, "imageId", "I");
    mPlacementIdField = env->GetFieldID(mCellRunClass, "placementId", "I");

    // Cache all callback-related classes and methods to avoid repeated FindClass/GetMethodID
    LOGD("Caching callback classes and methods...");

    // TermRect
    ScopedLocalRef<jclass> termRectLocal(env, env->FindClass("org/connectbot/terminal/TermRect"));
    mTermRectClass = (jclass)env->NewGlobalRef(termRectLocal);
    mTermRectConstructor = env->GetMethodID(mTermRectClass, "<init>", "(IIII)V");

    // CursorPosition
    ScopedLocalRef<jclass> cursorPosLocal(env, env->FindClass("org/connectbot/terminal/CursorPosition"));
    mCursorPositionClass = (jclass)env->NewGlobalRef(cursorPosLocal);
    mCursorPositionConstructor = env->GetMethodID(mCursorPositionClass, "<init>", "(II)V");

    // ScreenCell
    ScopedLocalRef<jclass> screenCellLocal(env, env->FindClass("org/connectbot/terminal/ScreenCell"));
    mScreenCellClass = (jclass)env->NewGlobalRef(screenCellLocal);
    mScreenCellConstructor = env->GetMethodID(mScreenCellClass, "<init>",
        "(CLjava/util/List;IIIIIIZZIZZI)V");

    // ArrayList
    ScopedLocalRef<jclass> arrayListLocal(env, env->FindClass("java/util/ArrayList"));
    mArrayListClass = (jclass)env->NewGlobalRef(arrayListLocal);
    mArrayListConstructor = env->GetMethodID(mArrayListClass, "<init>", "()V");
    mArrayListAdd = env->GetMethodID(mArrayListClass, "add", "(Ljava/lang/Object;)Z");

    // Character
    ScopedLocalRef<jclass> charLocal(env, env->FindClass("java/lang/Character"));
    mCharacterClass = (jclass)env->NewGlobalRef(charLocal);
    mCharacterValueOf = env->GetStaticMethodID(mCharacterClass, "valueOf", "(C)Ljava/lang/Character;");

    // TerminalProperty classes
    ScopedLocalRef<jclass> boolLocal(env, env->FindClass("org/connectbot/terminal/TerminalProperty$BoolValue"));
    mTerminalPropertyBoolClass = (jclass)env->NewGlobalRef(boolLocal);
    mTerminalPropertyBoolConstructor = env->GetMethodID(mTerminalPropertyBoolClass, "<init>", "(Z)V");

    ScopedLocalRef<jclass> intLocal(env, env->FindClass("org/connectbot/terminal/TerminalProperty$IntValue"));
    mTerminalPropertyIntClass = (jclass)env->NewGlobalRef(intLocal);
    mTerminalPropertyIntConstructor = env->GetMethodID(mTerminalPropertyIntClass, "<init>", "(I)V");

    ScopedLocalRef<jclass> stringLocal(env, env->FindClass("org/connectbot/terminal/TerminalProperty$StringValue"));
    mTerminalPropertyStringClass = (jclass)env->NewGlobalRef(stringLocal);
    mTerminalPropertyStringConstructor = env->GetMethodID(mTerminalPropertyStringClass, "<init>", "(Ljava/lang/String;)V");

    ScopedLocalRef<jclass> colorLocal(env, env->FindClass("org/connectbot/terminal/TerminalProperty$ColorValue"));
    mTerminalPropertyColorClass = (jclass)env->NewGlobalRef(colorLocal);
    mTerminalPropertyColorConstructor = env->GetMethodID(mTerminalPropertyColorClass, "<init>", "(III)V");

    LOGD("All callback classes and methods cached successfully");

    // Create VTerm instance
    mVt = vterm_new(mRows, mCols);
    if (!mVt) {
        LOGE("Failed to create VTerm instance");
        return;
    }

    vterm_set_utf8(mVt, 1);

    // Set up output handler for keyboard input
    vterm_output_set_callback(mVt, termOutput, this);

    // Get screen and set up callbacks
    mVts = vterm_obtain_screen(mVt);
    if (!mVts) return;
    if (enableAltScreen) {
        vterm_screen_enable_altscreen(mVts, 1);
    }

    // #479: rewrap existing lines when the terminal is resized instead of
    // truncating them. libvterm defaults this OFF, so a shrink permanently
    // discarded everything past the new width — and pinch-zoom resizes the
    // terminal, so zooming in cut every visible line to the narrower width and
    // zooming back out left dead space where the text used to be. It was not
    // scrolled out of view; it was gone. Reflow makes the resize a re-layout,
    // which is what a user expects a zoom to be.
    vterm_screen_enable_reflow(mVts, true);
    // Upstream 0.3.12 (ca693f06) disabled reflow: "until scrollback, wide cells
    // and metadata survive resizing". We keep it for #479's zoom contract and
    // take only the resize-ordering fix above; re-watch upstream before flipping.

    // Initialize callback structure as member variable so it doesn't go out of scope.
    // These callbacks run while mLock may be held by the native entrypoint that
    // triggered libvterm. Callback implementations must not synchronously call
    // back into Terminal methods; post/defer work that needs native state.
    mScreenCallbacks = {
        .damage = termDamage,
        .moverect = termMoverect,
        .movecursor = termMovecursor,
        .settermprop = termSettermprop,
        .bell = termBell,
        .resize = nullptr,  // We handle resize explicitly
        .sb_pushline = nullptr,
        .sb_popline = termSbPopline,
        .sb_clear = termSbClear,
        .edit = termImageEdit,
        .scroll = termImageScroll,
        .clear_images = termImageClear,
        .image_resize = termImageResize,
        .sb_pushline_sr = termSbPushline
    };
    vterm_screen_set_callbacks(mVts, &mScreenCallbacks, this);

    // Set up OSC fallback handlers for shell integration. These follow the same
    // no-synchronous-reentry rule as screen callbacks above.
    VTermState* state = vterm_obtain_state(mVt);
    VTermStateFallbacks fallbacks = {
        .control = termControlFallback,
        .csi = termCsiFallback,
        .osc = termOscFallback,
        .dcs = nullptr,
        .apc = termApcFallback,
        .pm = nullptr,
        .sos = nullptr
    };
    mStateFallbacks = fallbacks;
    vterm_state_set_unrecognised_fallbacks(state, &mStateFallbacks, this);

    // XTVERSION identity (CSI > 0 q). Hosting terminal's identity, e.g.
    // "Haven(5.89.18)"; nullptr keeps libvterm's own "libvterm(0.3)" reply.
    if (xtversion && xtversion[0]) {
        vterm_state_set_xtversion(state, xtversion);
    }

    // Set up selection callbacks for OSC 52 clipboard support. These follow the
    // same no-synchronous-reentry rule as screen callbacks above.
    // Note: libvterm handles base64 decoding internally
    mSelectionCallbacks = {
        .set = termSelectionSet,
        .query = termSelectionQuery
    };
    vterm_state_set_selection_callbacks(state, &mSelectionCallbacks, this,
        mSelectionBuffer, SELECTION_BUFFER_SIZE);
    LOGD("Selection callbacks registered for OSC 52 clipboard support");

    // Configure damage merging
    vterm_screen_set_damage_merge(mVts, VTERM_DAMAGE_SCROLL);

    vterm_screen_reset(mVts, 1);

    LOGD("Terminal initialized successfully");
}

Terminal::~Terminal() {
    LOGD("Terminal destructor");

    std::scoped_lock lock(mLock);

    if (mVt) {
        vterm_free(mVt);
        mVt = nullptr;
    }

    // Release global references. mCallbacks is deliberately absent: it was
    // never a global reference (see CallbackScope), so there is nothing to
    // delete — and deleting the callbacks ref is exactly what the cycle fix
    // removed. Only the class refs (which don't root Kotlin instances) remain.
    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        if (mCellRunClass) {
            env->DeleteGlobalRef(mCellRunClass);
            mCellRunClass = nullptr;
        }
        // Clean up cached callback classes
        if (mTermRectClass) env->DeleteGlobalRef(mTermRectClass);
        if (mCursorPositionClass) env->DeleteGlobalRef(mCursorPositionClass);
        if (mScreenCellClass) env->DeleteGlobalRef(mScreenCellClass);
        if (mArrayListClass) env->DeleteGlobalRef(mArrayListClass);
        if (mCharacterClass) env->DeleteGlobalRef(mCharacterClass);
        if (mTerminalPropertyBoolClass) env->DeleteGlobalRef(mTerminalPropertyBoolClass);
        if (mTerminalPropertyIntClass) env->DeleteGlobalRef(mTerminalPropertyIntClass);
        if (mTerminalPropertyStringClass) env->DeleteGlobalRef(mTerminalPropertyStringClass);
        if (mTerminalPropertyColorClass) env->DeleteGlobalRef(mTerminalPropertyColorClass);
    }
}

// Input handling - KEY METHOD
int Terminal::writeInput(const uint8_t* data, size_t length) {
    std::scoped_lock lock(mLock);

    if (!mVt) {
        LOGE("writeInput: VTerm not initialized");
        return 0;
    }

    // Feed data to libvterm for processing
    size_t written = vterm_input_write(mVt, (const char*)data, length);

    // Flush screen state to trigger callbacks
    vterm_screen_flush_damage(mVts);

    return static_cast<int>(written);
}

// Resize
int Terminal::resize(int rows, int cols) {
    std::scoped_lock lock(mLock);

    // Scrollback callbacks during resize still inspect the old lineinfo array.
    if (mVt) {
        vterm_set_size(mVt, rows, cols);
    }
    mRows = rows;
    mCols = cols;
    if (mVt) {
        vterm_screen_flush_damage(mVts);
    }

    return 0;
}

// Color configuration
int Terminal::setPaletteColors(const uint32_t* colors, int count) {
    std::scoped_lock lock(mLock);

    if (!mVt) {
        LOGE("setPaletteColors: VTerm not initialized");
        return -1;
    }

    VTermState* state = vterm_obtain_state(mVt);
    if (!state) {
        LOGE("setPaletteColors: Failed to obtain VTermState");
        return -1;
    }

    // Only set ANSI colors (0-15)
    int colorCount = std::min(count, 16);

    for (int i = 0; i < colorCount; i++) {
        VTermColor vtColor;

        // Convert ARGB to RGB (libvterm uses RGB, Android uses ARGB 0xAARRGGBB)
        vterm_color_rgb(&vtColor,
                       (colors[i] >> 16) & 0xFF,  // Red
                       (colors[i] >> 8) & 0xFF,   // Green
                       colors[i] & 0xFF);         // Blue

        vterm_state_set_palette_color(state, i, &vtColor);
    }

    return colorCount;
}

int Terminal::setBoldHighbright(int enabled) {
    std::scoped_lock lock(mLock);

    if (!mVt) {
        LOGE("setBoldHighbright: VTerm not initialized");
        return -1;
    }

    VTermState* state = vterm_obtain_state(mVt);
    if (!state) {
        LOGE("setBoldHighbright: Failed to obtain VTermState");
        return -1;
    }

    vterm_state_set_bold_highbright(state, enabled);
    return 0;
}

int Terminal::setAmbiwidth(int enabled) {
    std::scoped_lock lock(mLock);
    vterm_set_ambiwidth(enabled);
    return 0;
}

int Terminal::setCustomWideChars(const uint32_t* chars, int count) {
    std::scoped_lock lock(mLock);
    vterm_set_custom_wide_chars(chars, count);
    return 0;
}

int Terminal::setDefaultColors(uint32_t fgColor, uint32_t bgColor) {
    std::scoped_lock lock(mLock);

    if (!mVt) {
        LOGE("setDefaultColors: VTerm not initialized");
        return -1;
    }

    VTermScreen* screen = vterm_obtain_screen(mVt);
    if (!screen) {
        LOGE("setDefaultColors: Failed to obtain VTermScreen");
        return -1;
    }

    // Convert ARGB to RGB for foreground
    VTermColor vtFg;
    vterm_color_rgb(&vtFg,
                   (fgColor >> 16) & 0xFF,  // Red
                   (fgColor >> 8) & 0xFF,   // Green
                   fgColor & 0xFF);         // Blue

    // Convert ARGB to RGB for background
    VTermColor vtBg;
    vterm_color_rgb(&vtBg,
                   (bgColor >> 16) & 0xFF,  // Red
                   (bgColor >> 8) & 0xFF,   // Green
                   bgColor & 0xFF);         // Blue

    vterm_screen_set_default_colors(screen, &vtFg, &vtBg);

    return 0;
}

// Keyboard input handlers
bool Terminal::dispatchKey(int modifiers, int key) {
    std::scoped_lock lock(mLock);

    if (!mVt) {
        return false;
    }

    VTermModifier mod = VTERM_MOD_NONE;
    if (modifiers & 1) mod = (VTermModifier)(mod | VTERM_MOD_SHIFT);
    if (modifiers & 2) mod = (VTermModifier)(mod | VTERM_MOD_ALT);
    if (modifiers & 4) mod = (VTermModifier)(mod | VTERM_MOD_CTRL);

    vterm_keyboard_key(mVt, (VTermKey)key, mod);
    return true;
}

bool Terminal::dispatchCharacter(int modifiers, int codepoint) {
    std::scoped_lock lock(mLock);

    if (!mVt) {
        return false;
    }

    VTermModifier mod = VTERM_MOD_NONE;
    if (modifiers & 1) mod = (VTermModifier)(mod | VTERM_MOD_SHIFT);
    if (modifiers & 2) mod = (VTermModifier)(mod | VTERM_MOD_ALT);
    if (modifiers & 4) mod = (VTermModifier)(mod | VTERM_MOD_CTRL);

    vterm_keyboard_unichar(mVt, codepoint, mod);
    return true;
}

// Cell run retrieval
int Terminal::getCellRun(JNIEnv* env, int row, int col, jobject runObject) {
    std::scoped_lock lock(mLock);

    if (!mVts || row < 0 || row >= mRows || col < 0 || col >= mCols) {
        return 0;
    }

    // Get first cell
    VTermPos pos = { row, col };
    VTermScreenCell cell;
    vterm_screen_get_cell(mVts, pos, &cell);

    // Collect cells with same attributes. `runLength` is the UTF-16 write
    // cursor into chars[] and `runColumns` the number of screen columns the
    // run covers; they differ once a cell emits a surrogate pair (a >U+FFFF
    // glyph contributes 2 jchars but 1 column) or is double-width. Kotlin's
    // updateLine bounds its col walk by the field handed back and advances it
    // by each cell's width, so it must carry runColumns.
    int runLength = 0;
    int runColumns = 0;
    int cellCount = 0;
    jchar chars[257];  // Max 256 UTF-16 units + trailing NUL sentinel
    jbyte cellMeta[256];

    // A Kitty unicode-placeholder cell (cell char U+10EEEE) is a marker for an
    // inline-image slice (#583). Its identity comes from the raw foreground
    // colour (image id) and chars[14] (placement id), so it must never merge
    // into a run with neighbouring cells: each placeholder gets its own run.
    const bool imageCell = cell.chars[0] == 0x10eeee;
    jint imageId = 0;
    jint placementId = 0;
    if (imageCell) {
        const VTermColor& color = cell.fg;
        if (VTERM_COLOR_IS_DEFAULT_FG(&color)) {
            imageId = 0;
        } else if (VTERM_COLOR_IS_INDEXED(&color)) {
            imageId = color.indexed.idx;
        } else {
            imageId = (color.rgb.red << 16) | (color.rgb.green << 8) | color.rgb.blue;
        }
        placementId = cell.chars[14];
    }

    for (int c = col; c < mCols && runLength < 256 && cellCount < 256; c++) {
        VTermPos currentPos = { row, c };
        VTermScreenCell currentCell;
        vterm_screen_get_cell(mVts, currentPos, &currentCell);

        // Check if attributes match (skip for first cell). Placeholder cells
        // are always isolated so their per-cell ids survive the run merge.
        if (c > col && (!cellStyleEqual(cell, currentCell) ||
                        imageCell != (currentCell.chars[0] == 0x10eeee))) {
            break;
        }
        if (imageCell && c > col) {
            break;
        }

        int cellUtf16Len = 0;
        if (currentCell.chars[0] == 0) {
            cellUtf16Len = 1;
        } else {
            for (int i = 0; i < VTERM_MAX_CHARS_PER_CELL && currentCell.chars[i]; i++) {
                cellUtf16Len += currentCell.chars[i] > 0xFFFF ? 2 : 1;
            }
        }
        if (runLength + cellUtf16Len > 256) {
            break;
        }

        // Add character(s) to run
        if (currentCell.chars[0] == 0) {
            chars[runLength++] = ' ';
        } else {
            for (int i = 0; i < VTERM_MAX_CHARS_PER_CELL && currentCell.chars[i]; i++) {
                uint32_t codepoint = currentCell.chars[i];

                if (codepoint <= 0xFFFF) {
                    chars[runLength++] = (jchar)codepoint;
                } else {
                    codepoint -= 0x10000;
                    chars[runLength++] = (jchar)(0xD800 + (codepoint >> 10));
                    chars[runLength++] = (jchar)(0xDC00 + (codepoint & 0x3FF));
                }
            }
        }

        const int effectiveWidth = imageCell ? 1 : currentCell.width;
        cellMeta[cellCount++] = static_cast<jbyte>(((effectiveWidth & 0x3) << 6) | (cellUtf16Len & 0x3F));

        runColumns += currentCell.width;

        // Skip next column if this is a wide character
        if (currentCell.width == 2) {
            c++;
        }
    }

    // Resolve colors
    uint8_t fgRed, fgGreen, fgBlue;
    uint8_t bgRed, bgGreen, bgBlue;
    resolveColor(cell.fg, fgRed, fgGreen, fgBlue);
    resolveColor(cell.bg, bgRed, bgGreen, bgBlue);

    // Set fields
    env->SetIntField(runObject, mFgRedField, fgRed);
    env->SetIntField(runObject, mFgGreenField, fgGreen);
    env->SetIntField(runObject, mFgBlueField, fgBlue);
    env->SetIntField(runObject, mBgRedField, bgRed);
    env->SetIntField(runObject, mBgGreenField, bgGreen);
    env->SetIntField(runObject, mBgBlueField, bgBlue);

    env->SetBooleanField(runObject, mBoldField, cell.attrs.bold);
    env->SetIntField(runObject, mUnderlineField, cell.attrs.underline);
    env->SetBooleanField(runObject, mItalicField, cell.attrs.italic);
    env->SetBooleanField(runObject, mBlinkField, cell.attrs.blink);
    env->SetBooleanField(runObject, mReverseField, cell.attrs.reverse);
    env->SetBooleanField(runObject, mStrikeField, cell.attrs.strike);
    env->SetIntField(runObject, mFontField, cell.attrs.font);
    env->SetBooleanField(runObject, mDwlField, cell.attrs.dwl);
    env->SetIntField(runObject, mDhlField, cell.attrs.dhl);

    // Inline-image ids (#583): nonzero only when this run is an isolated
    // Kitty unicode-placeholder cell. Kotlin side decodes the image/placement.
    env->SetIntField(runObject, mImageIdField, imageId);
    env->SetIntField(runObject, mPlacementIdField, placementId);

    // Always append a NUL sentinel past the copied UTF-16 region so no caller
    // can pick up stale tail data left in the pooled thread-local array.
    const int charCount = runLength;
    chars[charCount] = 0;
    runLength = imageCell ? 1 : runColumns;

    const jsize neededChars = charCount + 1;
    if (tls_charArray == nullptr || tls_charArraySize < neededChars) {
        if (tls_charArray != nullptr) {
            env->DeleteGlobalRef(tls_charArray);
        }

        jsize newSize = ((neededChars + 63) / 64) * 64;
        ScopedLocalRef<jcharArray> localArray(env, env->NewCharArray(newSize));
        tls_charArray = (jcharArray)env->NewGlobalRef(localArray);
        tls_charArraySize = newSize;

        LOGD("Allocated thread-local CharArray: size=%d", newSize);
    }

    const jsize neededMeta = cellCount > 0 ? cellCount : 1;
    if (tls_metaArray == nullptr || tls_metaArraySize < neededMeta) {
        if (tls_metaArray != nullptr) {
            env->DeleteGlobalRef(tls_metaArray);
        }

        jsize newSize = ((neededMeta + 63) / 64) * 64;
        ScopedLocalRef<jbyteArray> localMeta(env, env->NewByteArray(newSize));
        tls_metaArray = (jbyteArray)env->NewGlobalRef(localMeta);
        tls_metaArraySize = newSize;
    }

    env->SetCharArrayRegion(tls_charArray, 0, neededChars, chars);
    env->SetObjectField(runObject, mCharsField, tls_charArray);

    if (cellCount > 0) {
        env->SetByteArrayRegion(tls_metaArray, 0, cellCount, cellMeta);
    }
    env->SetIntField(runObject, mCellCountField, cellCount);
    env->SetObjectField(runObject, mCellMetaField, tls_metaArray);

    env->SetIntField(runObject, mRunLengthField, runLength);

    return runLength;
}

// #749: fetch every style run of one row under a single lock hold.
// getCellRun() locks per call, so a writeInput() from the SSH reader thread
// can land between two runs of the same row rebuild; the Kotlin side then
// splices pre- and post-write cells into one cached row while the native
// model stays correct - permanent intra-row corruption (merged words,
// transposed chars, eaten leading spaces). This is a pure read:
// vterm_screen_get_cell fires no callbacks, so holding mLock across the
// whole row cannot invert against the damage path (which only takes mLock
// from writeInput/resize, whose callbacks never call back into native).
int Terminal::getRowRuns(JNIEnv* env, int row, jobjectArray runObjects) {
    std::scoped_lock lock(mLock);

    const jsize maxRuns = runObjects ? env->GetArrayLength(runObjects) : 0;
    if (!mVts || row < 0 || row >= mRows || maxRuns <= 0) {
        return 0;
    }

    // Reusable char/meta arrays per run slot, grown on demand. Safe to reuse
    // across calls on the same thread because updateLine consumes every run of
    // a row before the next getRowRuns call.
    static thread_local std::vector<jcharArray> tls_runArrays;
    static thread_local std::vector<jsize> tls_runArraySizes;
    static thread_local std::vector<jbyteArray> tls_runMetaArrays;
    static thread_local std::vector<jsize> tls_runMetaSizes;

    int filledRuns = 0;
    int col = 0;
    while (col < mCols && filledRuns < maxRuns) {
        VTermPos pos = { row, col };
        VTermScreenCell cell;
        vterm_screen_get_cell(mVts, pos, &cell);

        const bool imageCell = cell.chars[0] == 0x10eeee;
        jint imageId = 0;
        jint placementId = 0;
        if (imageCell) {
            const VTermColor& color = cell.fg;
            if (VTERM_COLOR_IS_DEFAULT_FG(&color)) {
                imageId = 0;
            } else if (VTERM_COLOR_IS_INDEXED(&color)) {
                imageId = color.indexed.idx;
            } else {
                imageId = (color.rgb.red << 16) | (color.rgb.green << 8) | color.rgb.blue;
            }
            placementId = cell.chars[14];
        }

        // Pass 1: measure the run (UTF-16 units, logical cells, and screen columns).
        int utf16Len = 0;
        int runColumns = 0;
        int cellCount = 0;
        for (int c = col; c < mCols; c++) {
            VTermPos currentPos = { row, c };
            VTermScreenCell currentCell;
            vterm_screen_get_cell(mVts, currentPos, &currentCell);
            if (c > col && (!cellStyleEqual(cell, currentCell) ||
                            imageCell != (currentCell.chars[0] == 0x10eeee))) {
                break;
            }
            if (imageCell && c > col) {
                break;
            }
            if (currentCell.chars[0] == 0) {
                utf16Len += 1;  // empty cell renders as a space
            } else {
                for (int i = 0; i < VTERM_MAX_CHARS_PER_CELL && currentCell.chars[i]; i++) {
                    utf16Len += currentCell.chars[i] > 0xFFFF ? 2 : 1;
                }
            }
            cellCount += 1;
            runColumns += currentCell.width;
            if (currentCell.width == 2) {
                c++;
            }
        }
        const int endCol = col + runColumns;

        // Grow the slot's arrays if needed (+1 char headroom for NUL sentinel).
        const jsize neededChars = utf16Len + 1;
        const jsize neededMeta = cellCount > 0 ? cellCount : 1;
        if (static_cast<size_t>(filledRuns) >= tls_runArrays.size()) {
            tls_runArrays.push_back(nullptr);
            tls_runArraySizes.push_back(0);
            tls_runMetaArrays.push_back(nullptr);
            tls_runMetaSizes.push_back(0);
        }
        if (tls_runArrays[filledRuns] == nullptr || tls_runArraySizes[filledRuns] < neededChars) {
            if (tls_runArrays[filledRuns] != nullptr) {
                env->DeleteGlobalRef(tls_runArrays[filledRuns]);
            }
            jsize newSize = ((neededChars + 63) / 64) * 64;
            ScopedLocalRef<jcharArray> localArray(env, env->NewCharArray(newSize));
            tls_runArrays[filledRuns] = (jcharArray)env->NewGlobalRef(localArray);
            tls_runArraySizes[filledRuns] = newSize;
        }
        if (tls_runMetaArrays[filledRuns] == nullptr || tls_runMetaSizes[filledRuns] < neededMeta) {
            if (tls_runMetaArrays[filledRuns] != nullptr) {
                env->DeleteGlobalRef(tls_runMetaArrays[filledRuns]);
            }
            jsize newSize = ((neededMeta + 63) / 64) * 64;
            ScopedLocalRef<jbyteArray> localMeta(env, env->NewByteArray(newSize));
            tls_runMetaArrays[filledRuns] = (jbyteArray)env->NewGlobalRef(localMeta);
            tls_runMetaSizes[filledRuns] = newSize;
        }

        // Pass 2: fill the char array and exact per-cell boundary/width metadata.
        jchar* scratch = new jchar[neededChars];
        jbyte* metaScratch = new jbyte[neededMeta];
        int w = 0;
        int m = 0;
        for (int c = col; c < endCol; c++) {
            VTermPos currentPos = { row, c };
            VTermScreenCell currentCell;
            vterm_screen_get_cell(mVts, currentPos, &currentCell);
            const int cellStart = w;
            if (currentCell.chars[0] == 0) {
                scratch[w++] = ' ';
            } else {
                for (int i = 0; i < VTERM_MAX_CHARS_PER_CELL && currentCell.chars[i]; i++) {
                    uint32_t codepoint = currentCell.chars[i];
                    if (codepoint <= 0xFFFF) {
                        scratch[w++] = (jchar)codepoint;
                    } else {
                        codepoint -= 0x10000;
                        scratch[w++] = (jchar)(0xD800 + (codepoint >> 10));
                        scratch[w++] = (jchar)(0xDC00 + (codepoint & 0x3FF));
                    }
                }
            }
            const int cellUtf16Len = w - cellStart;
            const int effectiveWidth = imageCell ? 1 : currentCell.width;
            metaScratch[m++] = static_cast<jbyte>(((effectiveWidth & 0x3) << 6) | (cellUtf16Len & 0x3F));
            if (currentCell.width == 2) {
                c++;
            }
        }
        // Always terminate with NUL so pooled tail chars from earlier longer runs
        // cannot bleed into shorter runs.
        scratch[w] = 0;
        env->SetCharArrayRegion(tls_runArrays[filledRuns], 0, w + 1, scratch);
        if (m > 0) {
            env->SetByteArrayRegion(tls_runMetaArrays[filledRuns], 0, m, metaScratch);
        }
        delete[] scratch;
        delete[] metaScratch;

        jobject runObject = env->GetObjectArrayElement(runObjects, filledRuns);

        uint8_t fgRed, fgGreen, fgBlue;
        uint8_t bgRed, bgGreen, bgBlue;
        resolveColor(cell.fg, fgRed, fgGreen, fgBlue);
        resolveColor(cell.bg, bgRed, bgGreen, bgBlue);

        env->SetIntField(runObject, mFgRedField, fgRed);
        env->SetIntField(runObject, mFgGreenField, fgGreen);
        env->SetIntField(runObject, mFgBlueField, fgBlue);
        env->SetIntField(runObject, mBgRedField, bgRed);
        env->SetIntField(runObject, mBgGreenField, bgGreen);
        env->SetIntField(runObject, mBgBlueField, bgBlue);
        env->SetBooleanField(runObject, mBoldField, cell.attrs.bold);
        env->SetIntField(runObject, mUnderlineField, cell.attrs.underline);
        env->SetBooleanField(runObject, mItalicField, cell.attrs.italic);
        env->SetBooleanField(runObject, mBlinkField, cell.attrs.blink);
        env->SetBooleanField(runObject, mReverseField, cell.attrs.reverse);
        env->SetBooleanField(runObject, mStrikeField, cell.attrs.strike);
        env->SetIntField(runObject, mFontField, cell.attrs.font);
        env->SetBooleanField(runObject, mDwlField, cell.attrs.dwl);
        env->SetIntField(runObject, mDhlField, cell.attrs.dhl);
        env->SetIntField(runObject, mImageIdField, imageId);
        env->SetIntField(runObject, mPlacementIdField, placementId);
        env->SetObjectField(runObject, mCharsField, tls_runArrays[filledRuns]);
        env->SetIntField(runObject, mCellCountField, m);
        env->SetObjectField(runObject, mCellMetaField, tls_runMetaArrays[filledRuns]);
        // Same split as getCellRun: an image placeholder covers one cell but
        // expands to several UTF-16 chars; a normal run's field is the column
        // count Kotlin's updateLine walks by.
        env->SetIntField(runObject, mRunLengthField, imageCell ? 1 : runColumns);

        env->DeleteLocalRef(runObject);
        filledRuns++;
        col = endCol;
    }

    // Pack line continuation flags under the SAME mLock hold as the cells so
    // updateLine() never makes a second unlocked JNI call for softWrapped, and
    // cross-chunk autowrap onto `row` can update `row - 1`'s softWrapped flag.
    int flags = 0;
    VTermState* state = mVt ? vterm_obtain_state(mVt) : nullptr;
    if (state) {
        if (row > 0 && row < mRows) {
            const VTermLineInfo* thisInfo = vterm_state_get_lineinfo(state, row);
            if (thisInfo && thisInfo->continuation != 0) {
                flags |= (1 << 29);
            }
        }
        if (row + 1 < mRows) {
            const VTermLineInfo* nextInfo = vterm_state_get_lineinfo(state, row + 1);
            if (nextInfo && nextInfo->continuation != 0) {
                flags |= (1 << 30);
            }
        }
    }

    return filledRuns | flags;
}

// Callback implementations
int Terminal::termDamage(VTermRect rect, void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeDamage(rect.start_row, rect.end_row, rect.start_col, rect.end_col);
    return 1;
}

int Terminal::termMoverect(VTermRect dest, VTermRect src, void* user) {
    auto* term = static_cast<Terminal*>(user);
    return term->invokeMoverect(dest, src);
}

int Terminal::termMovecursor(VTermPos pos, VTermPos oldpos, int visible, void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeMoveCursor(pos.row, pos.col, oldpos.row, oldpos.col, visible != 0);
    return 1;
}

int Terminal::termSettermprop(VTermProp prop, VTermValue* val, void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeSetTermProp(prop, val);
    return 1;
}

int Terminal::termBell(void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeBell();
    return 1;
}

int Terminal::termSbPushline(int cols, const VTermScreenCell* cells, bool softWrapped, int scrollEndRow, void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokePushScrollbackLine(cols, cells, softWrapped, scrollEndRow);
    return 1;
}

int Terminal::termSbPopline(int cols, VTermScreenCell* cells, void* user) {
    auto* term = static_cast<Terminal*>(user);
    return term->invokePopScrollbackLine(cols, cells);
}

int Terminal::termSbClear(void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeClearScrollback();
    return 1;
}

void Terminal::termOutput(const char* s, size_t len, void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeKeyboardOutput(s, len);
}

// OSC sequence fallback handler
// Handles fragmented OSC sequences by accumulating data across callbacks
int Terminal::termOscFallback(int command, VTermStringFragment frag, void* user) {
    auto* term = static_cast<Terminal*>(user);

    if (command == 1337) {
        if (frag.initial) {
            // Keep ordinary OSC 1337 traffic on the original fast path. A short
            // first fragment remains ambiguous and is safely accumulated by the
            // image parser. (Ported from upstream 0.3.9; #583)
            static constexpr const char* prefixes[] = {
                "File=", "MultipartFile=", "FilePart=", "FileEnd", "Capabilities"
            };
            term->mOsc1337Image = frag.len == 0;
            for (const char* prefix : prefixes) {
                if (term->mOsc1337Image) break;
                const size_t prefixLen = std::strlen(prefix);
                if (std::memcmp(frag.str, prefix, std::min(frag.len, prefixLen)) == 0) {
                    term->mOsc1337Image = true;
                    break;
                }
            }
        }
        if (term->mOsc1337Image && term->imageFragment(false, frag) >= 0) return 1;
    }

    // Start of a new OSC sequence
    if (frag.initial) {
        term->mOscData.clear();
        term->mOscCommand = command;
        // For OSC 8 (hyperlinks), save cursor position at the START of the sequence
        // because libvterm processes text before calling callbacks, causing the
        // cursor position at frag.final to reflect the position AFTER the hyperlink text
        if (command == 8) {
            VTermState* state = vterm_obtain_state(term->mVt);
            vterm_state_get_cursorpos(state, &term->mOscCursorPos);
        }
    }

    // Accumulate fragment data
    if (frag.len > 0) {
        term->mOscData.append(frag.str, frag.len);
    }

    // When we have the final fragment, send complete payload to Java
    if (frag.final) {
        int cursorRow, cursorCol;
        if (term->mOscCommand == 8) {
            // For OSC 8, use the position saved at frag.initial
            cursorRow = term->mOscCursorPos.row;
            cursorCol = term->mOscCursorPos.col;
        } else {
            // For other OSC commands, get current cursor position
            VTermState* state = vterm_obtain_state(term->mVt);
            VTermPos cursorPos;
            vterm_state_get_cursorpos(state, &cursorPos);
            cursorRow = cursorPos.row;
            cursorCol = cursorPos.col;
        }

        int result = term->invokeOscSequence(term->mOscCommand, term->mOscData, cursorRow, cursorCol);
        term->mOscData.clear();
        term->mOscCommand = -1;
        return result;
    }

    return 1;  // Indicate we're handling this (continue accumulating)
}

jlong Terminal::imageFragment(bool kitty, VTermStringFragment frag) {
    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return 0;
    VTermPos pos{};
    vterm_state_get_cursorpos(vterm_obtain_state(mVt), &pos);
    jlong result = 0;
    size_t offset = 0;
    do {
        const jsize count = static_cast<jsize>(std::min<size_t>(4096, frag.len - offset));
        ScopedLocalRef<jbyteArray> bytes(env, env->NewByteArray(count));
        if (!bytes.get()) return 0;
        if (count) env->SetByteArrayRegion(bytes, 0, count, (const jbyte*)frag.str + offset);
        JNI_CHECK_EXCEPTION_RETURN(env, 0);
        result = env->CallLongMethod(mCallbacks, mImageFragmentMethod, kitty, bytes.get(),
            frag.initial && offset == 0, frag.final && offset + count == frag.len, pos.row, pos.col);
        JNI_CHECK_EXCEPTION_RETURN(env, 0);
        offset += count;
    } while (offset < frag.len);
    if (result >= 0) mImageTracking = true;
    if (result > 0) {
        int rows = (result >> 32) & 0xffff;
        int cols = (result >> 1) & 0xffff;
        mReservingImage = true;
        vterm_state_place_image(vterm_obtain_state(mVt), rows, cols, result & 1);
        mReservingImage = false;
    }
    return result;
}

int Terminal::termApcFallback(VTermStringFragment frag, void* user) {
    static_cast<Terminal*>(user)->imageFragment(true, frag);
    return 1;
}

int Terminal::termControlFallback(unsigned char control, void* user) {
    if (control != 0x18) return 0;
    return static_cast<Terminal*>(user)->imageEdit(4, {});
}

int Terminal::termCsiFallback(const char* leader, const long args[], int argcount, const char* intermed, char command, void* user) {
    if (command != 't' || (leader && *leader) || (intermed && *intermed) || argcount != 1 ||
        (args[0] != 14 && args[0] != 16 && args[0] != 18)) return 0;
    auto* term = static_cast<Terminal*>(user);
    JNIEnv* env = term->mEnv;
    if (!env || env->ExceptionCheck()) return 0;
    env->CallVoidMethod(term->mCallbacks, term->mImageQueryMethod, static_cast<jint>(args[0]));
    return 1;
}

int Terminal::termImageResize(int buffer, int delta, int rows, int cols, void* user) {
    return static_cast<Terminal*>(user)->imageEdit(3, {buffer, rows, cols, 0}, delta, 0);
}

int Terminal::imageEdit(int kind, VTermRect rect, int downward, int rightward) {
    if (!mImageTracking) return 1;
    if (mReservingImage && kind != 1) return 1;
    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return 0;
    env->CallVoidMethod(mCallbacks, mImageEditMethod, kind, rect.start_row, rect.end_row,
        rect.start_col, rect.end_col, downward, rightward);
    return 1;
}

int Terminal::termImageEdit(VTermRect rect, void* user) {
    return static_cast<Terminal*>(user)->imageEdit(0, rect);
}

int Terminal::termImageScroll(VTermRect rect, int downward, int rightward, void* user) {
    return static_cast<Terminal*>(user)->imageEdit(1, rect, downward, rightward);
}

int Terminal::termImageClear(void* user) {
    return static_cast<Terminal*>(user)->imageEdit(2, {});
}

// OSC 52 selection set callback - receives base64-decoded clipboard data from libvterm
int Terminal::termSelectionSet(VTermSelectionMask mask, VTermStringFragment frag, void* user) {
    auto* term = static_cast<Terminal*>(user);

    LOGD("termSelectionSet: mask=%04X, len=%d, initial=%d, final=%d",
         mask, static_cast<int>(frag.len), frag.initial, frag.final);

    // Accumulate data across fragments
    if (frag.initial) {
        term->mSelectionData.clear();
    }

    if (frag.len > 0) {
        term->mSelectionData.append(frag.str, frag.len);
    }

    // When we have the final fragment, send to Java via OSC sequence callback
    if (frag.final && !term->mSelectionData.empty()) {
        LOGD("termSelectionSet: final data length=%d", static_cast<int>(term->mSelectionData.size()));
        // Use OSC 52 command number and pass the decoded data as payload
        // Format: selection;data - but since libvterm already decoded base64, we pass raw data
        std::string payload = "c;" + term->mSelectionData;  // 'c' for clipboard
        // Cursor position not relevant for OSC 52, pass 0,0
        term->invokeOscSequence(52, payload, 0, 0);
        term->mSelectionData.clear();
    }

    return 1;
}

// OSC 52 selection query callback - we don't support clipboard read for security
int Terminal::termSelectionQuery(VTermSelectionMask mask, void* user) {
    LOGD("termSelectionQuery: mask=%04X (ignored for security)", mask);
    // Return 0 to indicate we don't support clipboard read
    return 0;
}

// Java callback invocations
void Terminal::invokeDamage(int startRow, int endRow, int startCol, int endCol) {
    if (!mDamageMethod) {
        return;
    }

    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return;

    env->CallIntMethod(mCallbacks, mDamageMethod, startRow, endRow, startCol, endCol);
    JNI_CHECK_EXCEPTION(env);
}

int Terminal::invokeMoverect(VTermRect dest, VTermRect src) {
    if (!mMoverectMethod) {
        return 0;
    }

    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return 0;

    ScopedLocalRef<jobject> destObj(env, env->NewObject(mTermRectClass, mTermRectConstructor,
        dest.start_row, dest.end_row, dest.start_col, dest.end_col));
    ScopedLocalRef<jobject> srcObj(env, env->NewObject(mTermRectClass, mTermRectConstructor,
        src.start_row, src.end_row, src.start_col, src.end_col));

    jint result = env->CallIntMethod(mCallbacks, mMoverectMethod, destObj.get(), srcObj.get());
    JNI_CHECK_EXCEPTION_RETURN(env, 0);
    return result;
}

void Terminal::invokeMoveCursor(int row, int col, int oldRow, int oldCol, bool visible) {
    if (!mMoveCursorMethod) {
        return;
    }

    if (mCursorBatchActive) {
        mCursorPosition.row = row;
        mCursorPosition.col = col;
        mCursorOldPosition.row = oldRow;
        mCursorOldPosition.col = oldCol;
        mCursorVisible = visible;
        mCursorPending = true;
        return;
    }

    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return;

    ScopedLocalRef<jobject> posObj(env, env->NewObject(mCursorPositionClass, mCursorPositionConstructor, row, col));
    ScopedLocalRef<jobject> oldPosObj(env, env->NewObject(mCursorPositionClass, mCursorPositionConstructor, oldRow, oldCol));

    env->CallIntMethod(mCallbacks, mMoveCursorMethod, posObj.get(), oldPosObj.get(), visible);
    JNI_CHECK_EXCEPTION(env);
}

void Terminal::invokeSetTermProp(VTermProp prop, VTermValue* val) {
    if (!mSetTermPropMethod) {
        return;
    }

    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return;

    ScopedLocalRef<jobject> propValue(env, nullptr);

    switch (vterm_get_prop_type(prop)) {
        case VTERM_VALUETYPE_BOOL:
            propValue = ScopedLocalRef<jobject>(env, env->NewObject(mTerminalPropertyBoolClass, mTerminalPropertyBoolConstructor, val->boolean));
            break;

        case VTERM_VALUETYPE_INT:
            propValue = ScopedLocalRef<jobject>(env, env->NewObject(mTerminalPropertyIntClass, mTerminalPropertyIntConstructor, val->number));
            break;

        case VTERM_VALUETYPE_STRING:
            if (val->string.str) {
                // Decoded to UTF-16 for the same reason as the OSC payload below:
                // NewStringUTF wants *modified* UTF-8 and ART aborts the whole
                // process on anything else. This path carries VTERM_PROP_TITLE and
                // VTERM_PROP_ICONNAME — a window title, set by the remote shell on
                // every prompt — so the bytes are entirely the remote's choice.
                //
                // Two ways the old code aborted, and the second is ordinary rather
                // than exotic:
                //
                //   * modified UTF-8 has no 4-byte form, encoding supplementary
                //     characters as a CESU-8 surrogate pair instead. A single emoji
                //     in a title is a 4-byte sequence and is therefore not valid
                //     input to NewStringUTF at all.
                //   * mutf8_to_utf8 converted *away* from modified UTF-8 and then
                //     handed the result to the function that requires it, so it
                //     worked only for the ASCII subset where the two coincide.
                //
                // Sized from string.len rather than a NUL scan, so an embedded NUL
                // truncates nothing.
                const std::u16string titleUtf16 =
                    utf8_to_utf16_lossy(val->string.str, val->string.len);
                ScopedLocalRef<jstring> str(
                    env,
                    env->NewString(reinterpret_cast<const jchar*>(titleUtf16.data()),
                                   static_cast<jsize>(titleUtf16.size())));
                if (str.get()) {
                    propValue = ScopedLocalRef<jobject>(env, env->NewObject(mTerminalPropertyStringClass, mTerminalPropertyStringConstructor, str.get()));
                }
            }
            break;

        case VTERM_VALUETYPE_COLOR: {
            uint8_t r, g, b;
            resolveColor(val->color, r, g, b);
            propValue = ScopedLocalRef<jobject>(env, env->NewObject(mTerminalPropertyColorClass, mTerminalPropertyColorConstructor, r, g, b));
            break;
        }

        case VTERM_N_VALUETYPES:
            break;
    }

    if (propValue.get()) {
        env->CallIntMethod(mCallbacks, mSetTermPropMethod, prop, propValue.get());
        JNI_CHECK_EXCEPTION(env);
    }
}

void Terminal::invokeBell() {
    if (!mBellMethod) {
        return;
    }

    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return;

    env->CallIntMethod(mCallbacks, mBellMethod);
    JNI_CHECK_EXCEPTION(env);
}

void Terminal::invokePushScrollbackLine(int cols, const VTermScreenCell* cells, bool softWrapped, int scrollEndRow) {
    if (!mPushScrollbackMethod && !mPushScrollbackRegionMethod) {
        return;
    }

    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return;

    // Build a list to hold actual cells (excluding fullwidth placeholders)
    std::vector<ScopedLocalRef<jobject>> screenCells;

#define CELL_JNI_EXCEPTION_CONTINUE(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); continue; } } while (0)
#define CELL_JNI_EXCEPTION_BREAK(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); break; } } while (0)

    for (int i = 0; i < cols; i++) {
        const VTermScreenCell& cell = cells[i];

        // Get the primary character and handle surrogate pairs
        jchar primaryChar = ' ';
        ScopedLocalRef<jobject> combiningList(env, env->NewObject(mArrayListClass, mArrayListConstructor));

        if (cell.chars[0] != 0) {
            uint32_t codepoint = cell.chars[0];

            if (codepoint <= 0xFFFF) {
                // BMP character - fits in single jchar
                primaryChar = (jchar)codepoint;
            } else {
                // Surrogate pair needed for codepoints > U+FFFF
                codepoint -= 0x10000;
                primaryChar = (jchar)(0xD800 + (codepoint >> 10));  // High surrogate
                jchar lowSurrogate = (jchar)(0xDC00 + (codepoint & 0x3FF));  // Low surrogate

                ScopedLocalRef<jobject> lowSurrogateObj(env, env->CallStaticObjectMethod(mCharacterClass, mCharacterValueOf, lowSurrogate));
                CELL_JNI_EXCEPTION_CONTINUE(env);  // Orphaned high surrogate; skip cell
                env->CallBooleanMethod(combiningList, mArrayListAdd, lowSurrogateObj.get());
                CELL_JNI_EXCEPTION_CONTINUE(env);  // Inconsistent combining list; skip cell
            }

            // Add any actual combining characters (chars[1] onwards)
            for (int j = 1; j < VTERM_MAX_CHARS_PER_CELL && cell.chars[j] != 0; j++) {
                uint32_t combiningCodepoint = cell.chars[j];

                if (combiningCodepoint <= 0xFFFF) {
                    ScopedLocalRef<jobject> charObj(env, env->CallStaticObjectMethod(mCharacterClass, mCharacterValueOf, (jchar)combiningCodepoint));
                    CELL_JNI_EXCEPTION_BREAK(env);
                    env->CallBooleanMethod(combiningList, mArrayListAdd, charObj.get());
                    CELL_JNI_EXCEPTION_BREAK(env);
                } else {
                    // Combining character is also a surrogate pair
                    combiningCodepoint -= 0x10000;
                    jchar highSurr = (jchar)(0xD800 + (combiningCodepoint >> 10));
                    jchar lowSurr = (jchar)(0xDC00 + (combiningCodepoint & 0x3FF));

                    ScopedLocalRef<jobject> highObj(env, env->CallStaticObjectMethod(mCharacterClass, mCharacterValueOf, highSurr));
                    CELL_JNI_EXCEPTION_BREAK(env);
                    env->CallBooleanMethod(combiningList, mArrayListAdd, highObj.get());
                    CELL_JNI_EXCEPTION_BREAK(env);

                    ScopedLocalRef<jobject> lowObj(env, env->CallStaticObjectMethod(mCharacterClass, mCharacterValueOf, lowSurr));
                    CELL_JNI_EXCEPTION_BREAK(env);
                    env->CallBooleanMethod(combiningList, mArrayListAdd, lowObj.get());
                    CELL_JNI_EXCEPTION_BREAK(env);
                }
            }
        }

        // Resolve colors
        uint8_t fgRed, fgGreen, fgBlue;
        uint8_t bgRed, bgGreen, bgBlue;
        resolveColor(cell.fg, fgRed, fgGreen, fgBlue);
        resolveColor(cell.bg, bgRed, bgGreen, bgBlue);

        // Create ScreenCell object using cached class/constructor
        // Signature: (CLjava/util/List;IIIIIIZZIZZI)V
        // Parameters: char, combiningChars, fgRed, fgGreen, fgBlue, bgRed, bgGreen, bgBlue,
        //             bold, italic, underline, reverse, strike, width
        ScopedLocalRef<jobject> screenCell(env, env->NewObject(mScreenCellClass, mScreenCellConstructor,
            primaryChar,                    // char
            combiningList.get(),            // combiningChars: List<Char>
            (jint)fgRed,                    // fgRed
            (jint)fgGreen,                  // fgGreen
            (jint)fgBlue,                   // fgBlue
            (jint)bgRed,                    // bgRed
            (jint)bgGreen,                  // bgGreen
            (jint)bgBlue,                   // bgBlue
            (jboolean)cell.attrs.bold,      // bold (Z)
            (jboolean)cell.attrs.italic,    // italic (Z)
            (jint)cell.attrs.underline,     // underline (I)
            (jboolean)cell.attrs.reverse,   // reverse (Z)
            (jboolean)cell.attrs.strike,    // strike (Z)
            (jint)cell.width                // width (I)
        ));

        screenCells.push_back(std::move(screenCell));

        // Skip next cell if this is a fullwidth character
        if (cell.width == 2) {
            i++;  // Skip the placeholder cell
        }
    }

#undef CELL_JNI_EXCEPTION_CONTINUE
#undef CELL_JNI_EXCEPTION_BREAK

    // Create array with actual cell count
    int actualCells = screenCells.size();
    ScopedLocalRef<jobjectArray> actualCellArray(env, env->NewObjectArray(actualCells, mScreenCellClass, nullptr));
    for (int i = 0; i < actualCells; i++) {
        env->SetObjectArrayElement(actualCellArray, i, screenCells[i].get());
    }

    // Call the Java callback with actual cell count, soft wrap status, and scroll region bound
    if (mPushScrollbackRegionMethod) {
        env->CallIntMethod(
            mCallbacks,
            mPushScrollbackRegionMethod,
            actualCells,
            actualCellArray.get(),
            (jboolean)softWrapped,
            (jint)scrollEndRow
        );
    } else {
        env->CallIntMethod(mCallbacks, mPushScrollbackMethod, actualCells, actualCellArray.get(), (jboolean)softWrapped);
    }
    JNI_CHECK_EXCEPTION(env);
}

int Terminal::invokePopScrollbackLine(int cols, VTermScreenCell* cells) {
    if (!mPopScrollbackMethod) {
        return 0;
    }

    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return 0;

    ScopedLocalRef<jclass> screenCellClass(env, env->FindClass("org/connectbot/terminal/ScreenCell"));
    if (!screenCellClass.get()) {
        LOGE("Failed to find ScreenCell class");
        return 0;
    }

    ScopedLocalRef<jobjectArray> cellArray(env, env->NewObjectArray(cols, screenCellClass, nullptr));
    if (!cellArray.get()) {
        LOGE("Failed to create cell array");
        return 0;
    }

    jint result = env->CallIntMethod(mCallbacks, mPopScrollbackMethod, cols, cellArray.get());
    JNI_CHECK_EXCEPTION_RETURN(env, 0);

    if (result == 0) {
        return 0;
    }

    // Get field IDs for ScreenCell
    jfieldID charField = env->GetFieldID(screenCellClass, "char", "C");
    jfieldID combiningCharsField = env->GetFieldID(screenCellClass, "combiningChars", "Ljava/util/List;");
    jfieldID fgRedField = env->GetFieldID(screenCellClass, "fgRed", "I");
    jfieldID fgGreenField = env->GetFieldID(screenCellClass, "fgGreen", "I");
    jfieldID fgBlueField = env->GetFieldID(screenCellClass, "fgBlue", "I");
    jfieldID bgRedField = env->GetFieldID(screenCellClass, "bgRed", "I");
    jfieldID bgGreenField = env->GetFieldID(screenCellClass, "bgGreen", "I");
    jfieldID bgBlueField = env->GetFieldID(screenCellClass, "bgBlue", "I");
    jfieldID boldField = env->GetFieldID(screenCellClass, "bold", "Z");
    jfieldID italicField = env->GetFieldID(screenCellClass, "italic", "Z");
    jfieldID underlineField = env->GetFieldID(screenCellClass, "underline", "I");
    jfieldID reverseField = env->GetFieldID(screenCellClass, "reverse", "Z");
    jfieldID strikeField = env->GetFieldID(screenCellClass, "strike", "Z");
    jfieldID widthField = env->GetFieldID(screenCellClass, "width", "I");

    ScopedLocalRef<jclass> listClass(env, env->FindClass("java/util/List"));
    jmethodID listSize = env->GetMethodID(listClass, "size", "()I");
    jmethodID listGet = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
    ScopedLocalRef<jclass> charClass(env, env->FindClass("java/lang/Character"));
    jmethodID charValue = env->GetMethodID(charClass, "charValue", "()C");

    // Convert Java ScreenCell array to VTermScreenCell
    for (int i = 0; i < cols; i++) {
        ScopedLocalRef<jobject> screenCell(env, env->GetObjectArrayElement(cellArray, i));
        if (!screenCell.get()) {
            // Initialize empty cell
            VTermScreenCell& cell = cells[i];
            cell.chars[0] = ' ';
            for (int j = 1; j < VTERM_MAX_CHARS_PER_CELL; j++) {
                cell.chars[j] = 0;
            }
            cell.width = 1;
            cell.attrs.bold = 0;
            cell.attrs.italic = 0;
            cell.attrs.underline = 0;
            cell.attrs.reverse = 0;
            cell.attrs.strike = 0;
            vterm_color_rgb(&cell.fg, 192, 192, 192);
            vterm_color_rgb(&cell.bg, 0, 0, 0);
            continue;
        }

        VTermScreenCell& cell = cells[i];
        cell.attrs = {};

        // Collect UTF-16 code units from primaryChar + combiningChars and recombine
        // surrogate pairs back into UTF-32 codepoints for libvterm.
        std::vector<jchar> utf16Units;
        utf16Units.push_back(env->GetCharField(screenCell, charField));

        ScopedLocalRef<jobject> combiningList(env, env->GetObjectField(screenCell, combiningCharsField));
        if (combiningList.get()) {
            jint listLen = env->CallIntMethod(combiningList, listSize);
            JNI_CHECK_EXCEPTION_RETURN(env, 0);
            for (int j = 0; j < listLen; j++) {
                ScopedLocalRef<jobject> charObj(env, env->CallObjectMethod(combiningList, listGet, j));
                JNI_CHECK_EXCEPTION_RETURN(env, 0);
                if (charObj.get()) {
                    jchar ch = env->CallCharMethod(charObj, charValue);
                    JNI_CHECK_EXCEPTION_RETURN(env, 0);
                    utf16Units.push_back(ch);
                }
            }
        }

        int charIndex = 0;
        for (size_t u = 0; u < utf16Units.size() && charIndex < VTERM_MAX_CHARS_PER_CELL; ) {
            jchar hi = utf16Units[u];
            if (hi >= 0xD800 && hi <= 0xDBFF && (u + 1) < utf16Units.size()) {
                jchar lo = utf16Units[u + 1];
                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                    cell.chars[charIndex++] = 0x10000u +
                        (((static_cast<uint32_t>(hi) - 0xD800u) << 10) |
                         (static_cast<uint32_t>(lo) - 0xDC00u));
                    u += 2;
                    continue;
                }
            }
            cell.chars[charIndex++] = static_cast<uint32_t>(hi);
            u += 1;
        }
        // Fill remaining with zeros
        for (; charIndex < VTERM_MAX_CHARS_PER_CELL; charIndex++) {
            cell.chars[charIndex] = 0;
        }

        // Get colors
        uint8_t fgRed = env->GetIntField(screenCell, fgRedField);
        uint8_t fgGreen = env->GetIntField(screenCell, fgGreenField);
        uint8_t fgBlue = env->GetIntField(screenCell, fgBlueField);
        uint8_t bgRed = env->GetIntField(screenCell, bgRedField);
        uint8_t bgGreen = env->GetIntField(screenCell, bgGreenField);
        uint8_t bgBlue = env->GetIntField(screenCell, bgBlueField);
        vterm_color_rgb(&cell.fg, fgRed, fgGreen, fgBlue);
        vterm_color_rgb(&cell.bg, bgRed, bgGreen, bgBlue);

        // Get attributes
        cell.attrs.bold = env->GetBooleanField(screenCell, boldField);
        cell.attrs.italic = env->GetBooleanField(screenCell, italicField);
        cell.attrs.underline = env->GetIntField(screenCell, underlineField);
        cell.attrs.reverse = env->GetBooleanField(screenCell, reverseField);
        cell.attrs.strike = env->GetBooleanField(screenCell, strikeField);
        cell.width = env->GetIntField(screenCell, widthField);
    }

    return result;
}

void Terminal::invokeClearScrollback() {
    if (!mClearScrollbackMethod) {
        return;
    }

    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return;

    env->CallIntMethod(mCallbacks, mClearScrollbackMethod);
    JNI_CHECK_EXCEPTION(env);
}

void Terminal::beginCursorBatch() {
    mCursorBatchActive = true;
    mCursorPending = false;
}

void Terminal::finishCursorBatch() {
    mCursorBatchActive = false;
    if (!mCursorPending) return;

    mCursorPending = false;
    invokeMoveCursor(
        mCursorPosition.row,
        mCursorPosition.col,
        mCursorOldPosition.row,
        mCursorOldPosition.col,
        mCursorVisible);
}

void Terminal::invokeKeyboardOutput(const char* data, size_t len) {
    if (!mKeyboardInputMethod) {
        return;
    }

    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) return;

    ScopedLocalRef<jbyteArray> array(env, env->NewByteArray(len));
    env->SetByteArrayRegion(array, 0, len, reinterpret_cast<const jbyte*>(data));

    env->CallIntMethod(mCallbacks, mKeyboardInputMethod, array.get());
    JNI_CHECK_EXCEPTION(env);
}

int Terminal::invokeOscSequence(int command, const std::string& payload, int cursorRow, int cursorCol) {
    // The payload is NEVER logged. It is arbitrary data from the remote end and
    // routinely carries secrets: OSC 52 is the clipboard, so termSelectionSet
    // hands the *decoded* copied text through here — a password out of a
    // manager, a token, a private key — and OSC 3008 carries user, hostname and
    // cwd. Logging it wrote all of that to logcat in plaintext, where `adb
    // logcat`, a bug report, or a user pasting a log into an issue picks it up
    // (GlassHaven/Haven#518).
    //
    // Command and length are kept: they are what makes the log useful for
    // sequencing and truncation bugs, and neither reveals content.
    LOGD("invokeOscSequence: command=%d, payload len=%zu, cursor=(%d,%d)",
         command, payload.length(), cursorRow, cursorCol);

    if (!mOscSequenceMethod) {
        LOGE("invokeOscSequence: mOscSequenceMethod is null");
        return 0;
    }

    JNIEnv* env = mEnv;
    if (!env || env->ExceptionCheck()) {
        LOGE("invokeOscSequence: no active CallbackScope");
        return 0;
    }

    // Decoded to UTF-16 rather than handed to NewStringUTF, which aborts the
    // whole process on bytes that are not valid modified UTF-8. An OSC payload
    // is whatever the remote program emitted — a window title from a non-UTF-8
    // Windows console is not obliged to be valid UTF-8, and a terminal that
    // dies on one is worse than a terminal that shows U+FFFD.
    //
    // Sized from the string rather than c_str(), so a payload containing an
    // embedded NUL is passed through whole instead of being silently truncated.
    const std::u16string payloadUtf16 = utf8_to_utf16_lossy(payload.data(), payload.size());
    ScopedLocalRef<jstring> payloadStr(
        env,
        env->NewString(reinterpret_cast<const jchar*>(payloadUtf16.data()),
                       static_cast<jsize>(payloadUtf16.size())));
    if (!payloadStr.get()) {
        LOGE("Failed to create jstring for OSC payload");
        return 0;
    }

    jint result = env->CallIntMethod(mCallbacks, mOscSequenceMethod, command, payloadStr.get(), cursorRow, cursorCol);
    JNI_CHECK_EXCEPTION_RETURN(env, 0);
    LOGD("invokeOscSequence: Java callback returned %d", result);

    return result;
}

// Line info retrieval
bool Terminal::getLineContinuation(int row) {
    std::scoped_lock lock(mLock);

    if (!mVt || row < 0 || row >= mRows) {
        return false;
    }

    VTermState* state = vterm_obtain_state(mVt);
    if (!state) {
        return false;
    }

    const VTermLineInfo* lineInfo = vterm_state_get_lineinfo(state, row);
    if (!lineInfo) {
        return false;
    }

    return lineInfo->continuation != 0;
}

// Helper functions
bool Terminal::cellStyleEqual(const VTermScreenCell& a, const VTermScreenCell& b) {
    return memcmp(&a.fg, &b.fg, sizeof(VTermColor)) == 0 &&
           memcmp(&a.bg, &b.bg, sizeof(VTermColor)) == 0 &&
           a.attrs.bold == b.attrs.bold &&
           a.attrs.underline == b.attrs.underline &&
           a.attrs.italic == b.attrs.italic &&
           a.attrs.blink == b.attrs.blink &&
           a.attrs.reverse == b.attrs.reverse &&
           a.attrs.strike == b.attrs.strike &&
           a.attrs.font == b.attrs.font &&
           a.attrs.dwl == b.attrs.dwl &&
           a.attrs.dhl == b.attrs.dhl;
}

void Terminal::resolveColor(const VTermColor& color, uint8_t& r, uint8_t& g, uint8_t& b) {
    if (VTERM_COLOR_IS_INDEXED(&color)) {
        // Get color from palette
        VTermColor resolved;
        VTermState* state = vterm_obtain_state(mVt);
        vterm_state_get_palette_color(state, color.indexed.idx, &resolved);
        r = resolved.rgb.red;
        g = resolved.rgb.green;
        b = resolved.rgb.blue;
    } else if (VTERM_COLOR_IS_RGB(&color)) {
        r = color.rgb.red;
        g = color.rgb.green;
        b = color.rgb.blue;
    } else if (VTERM_COLOR_IS_DEFAULT_FG(&color)) {
        // Get configured default foreground from libvterm
        VTermState* state = vterm_obtain_state(mVt);
        VTermColor fg, bg;
        vterm_state_get_default_colors(state, &fg, &bg);
        r = fg.rgb.red;
        g = fg.rgb.green;
        b = fg.rgb.blue;
    } else if (VTERM_COLOR_IS_DEFAULT_BG(&color)) {
        // Get configured default background from libvterm
        VTermState* state = vterm_obtain_state(mVt);
        VTermColor fg, bg;
        vterm_state_get_default_colors(state, &fg, &bg);
        r = bg.rgb.red;
        g = bg.rgb.green;
        b = bg.rgb.blue;
    } else {
        // Fallback
        r = g = b = 128;
    }
}

// JNI function implementations
extern "C" {

JNIEXPORT jlong JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeInit(JNIEnv* env, jobject /* thiz */, jobject callbacks,
                                                       jboolean enableAltScreen, jstring xtversion) {
    const char* xtversionChars = nullptr;
    if (xtversion != nullptr) {
        xtversionChars = env->GetStringUTFChars(xtversion, nullptr);
    }
    auto* term = new Terminal(env, callbacks, 24, 80, enableAltScreen, xtversionChars);
    if (xtversionChars != nullptr) {
        env->ReleaseStringUTFChars(xtversion, xtversionChars);
    }
    return reinterpret_cast<jlong>(term);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeDestroy(JNIEnv* /* env */, jobject /* thiz */, jlong ptr) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    delete term;
    return 0;
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeWriteInputBuffer(JNIEnv* env, jobject /* thiz */,
                                                                   jlong ptr, jobject callbacks,
                                                                   jobject buffer, jint length) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);
    const auto* data = static_cast<const uint8_t*>(
        env->GetDirectBufferAddress(buffer));
    if (!data) {
        return 0;
    }
    return term->writeInput(data, length);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeWriteInputArray(JNIEnv* env, jobject /* thiz */,
                                                                  jlong ptr, jobject callbacks,
                                                                  jbyteArray data, jint offset, jint length) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    // GetByteArrayElements returns null on OOM (with a pending exception), and
    // the caller's offset/length are untrusted — validate both against the
    // array bounds before reading `bytes + offset` for `length`. (#23)
    if (bytes == nullptr) {
        return 0;
    }
    jsize arrayLen = env->GetArrayLength(data);
    if (offset < 0 || length < 0 || offset > arrayLen || length > arrayLen - offset) {
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return 0;
    }
    int result = term->writeInput(
        reinterpret_cast<const uint8_t*>(bytes + offset), length);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeResize(JNIEnv* env, jobject /* thiz */,
                                                         jlong ptr, jobject callbacks,
                                                         jint rows, jint cols) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);
    return term->resize(rows, cols);
}

JNIEXPORT jboolean JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeDispatchKey(JNIEnv* env, jobject /* thiz */,
                                                              jlong ptr, jobject callbacks,
                                                              jint modifiers, jint key) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);
    return term->dispatchKey(modifiers, key);
}

JNIEXPORT jboolean JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeDispatchCharacter(JNIEnv* env, jobject /* thiz */,
                                                                    jlong ptr, jobject callbacks,
                                                                    jint modifiers, jint character) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);
    return term->dispatchCharacter(modifiers, character);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeGetCellRun(JNIEnv* env, jobject /* thiz */,
                                                             jlong ptr, jobject callbacks,
                                                             jint row, jint col, jobject runObject) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);
    return term->getCellRun(env, row, col, runObject);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeGetRowRuns(JNIEnv* env, jobject /* thiz */,
                                                             jlong ptr, jobject callbacks,
                                                             jint row, jobjectArray runObjects) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);
    return term->getRowRuns(env, row, runObjects);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetPaletteColors(JNIEnv* env, jobject /* thiz */,
                                                                   jlong ptr, jobject callbacks,
                                                                   jintArray colors, jint count) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);

    // Get array elements
    jint* colorData = env->GetIntArrayElements(colors, nullptr);
    if (!colorData) {
        LOGE("nativeSetPaletteColors: Failed to get array elements");
        return -1;
    }

    // Convert to uint32_t array and call native method
    int result = term->setPaletteColors(reinterpret_cast<const uint32_t*>(colorData), count);

    // Release array (JNI_ABORT = don't copy back, read-only)
    env->ReleaseIntArrayElements(colors, colorData, JNI_ABORT);

    return result;
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetDefaultColors(JNIEnv* env, jobject /* thiz */,
                                                                   jlong ptr, jobject callbacks,
                                                                   jint fgColor, jint bgColor) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);
    return term->setDefaultColors(static_cast<uint32_t>(fgColor), static_cast<uint32_t>(bgColor));
}

JNIEXPORT jboolean JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeGetLineContinuation(JNIEnv* env, jobject /* thiz */,
                                                                       jlong ptr, jobject callbacks,
                                                                       jint row) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);
    return term->getLineContinuation(row);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetBoldHighbright(JNIEnv* env, jobject /* thiz */,
                                                                     jlong ptr, jobject callbacks,
                                                                     jboolean enabled) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    Terminal::CallbackScope scope(*term, env, callbacks);
    return term->setBoldHighbright(enabled ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetAmbiwidth(JNIEnv* /* env */, jobject /* thiz */,
                                                                jlong ptr, jboolean enabled) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    if (term) {
        return term->setAmbiwidth(enabled ? 1 : 0);
    }
    vterm_set_ambiwidth(enabled ? 1 : 0);
    return 0;
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetCustomWideChars(JNIEnv* env, jobject /* thiz */,
                                                                    jlong ptr, jintArray wideChars) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    if (!wideChars) {
        if (term) return term->setCustomWideChars(nullptr, 0);
        vterm_set_custom_wide_chars(nullptr, 0);
        return 0;
    }
    jsize len = env->GetArrayLength(wideChars);
    if (len <= 0) {
        const uint32_t empty = 0;
        if (term) return term->setCustomWideChars(&empty, 0);
        vterm_set_custom_wide_chars(&empty, 0);
        return 0;
    }
    jint* elements = env->GetIntArrayElements(wideChars, nullptr);
    if (!elements) {
        return -1;
    }
    int rc = 0;
    if (term) {
        rc = term->setCustomWideChars(reinterpret_cast<const uint32_t*>(elements), len);
    } else {
        vterm_set_custom_wide_chars(reinterpret_cast<const uint32_t*>(elements), len);
    }
    env->ReleaseIntArrayElements(wideChars, elements, JNI_ABORT);
    return rc;
}

} // extern "C"
