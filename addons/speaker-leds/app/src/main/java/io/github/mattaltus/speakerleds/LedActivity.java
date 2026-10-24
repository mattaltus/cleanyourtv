package io.github.mattaltus.speakerleds;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The card: shows whether the LEDs are held on and with which pattern. OK switches them on or off, Left/Right
 * pick the pattern. A "pattern" extra sets it from ADB, for setup.
 */
public class LedActivity extends Activity {
    static final String EXTRA_PATTERN = "pattern";

    private TextView text;
    private boolean supported;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this);
        text.setTextSize(28);
        text.setGravity(Gravity.CENTER);
        text.setPadding(96, 0, 96, 0);
        setContentView(text);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        supported = !getPackageManager().queryIntentServices(LedHoldService.ledIntent(), 0).isEmpty();
        if (supported) {
            String extra = getIntent().getStringExtra(EXTRA_PATTERN);
            if (extra != null) LedHoldService.setPattern(this, extra);
            if (LedHoldService.isEnabled(this)) LedHoldService.setEnabled(this, true);
        }
        show();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (supported) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    LedHoldService.setEnabled(this, !LedHoldService.isEnabled(this));
                    show();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    LedHoldService.setPattern(this, Patterns.step(LedHoldService.pattern(this), step));
                    show();
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void show() {
        if (!supported) {
            text.setText(R.string.unsupported);
            return;
        }
        String state = LedHoldService.isEnabled(this)
                ? getString(R.string.state_on, LedHoldService.pattern(this))
                : getString(R.string.state_off);
        text.setText(getString(R.string.screen, state));
    }
}
