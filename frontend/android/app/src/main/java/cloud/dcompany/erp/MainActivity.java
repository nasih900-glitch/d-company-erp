package cloud.dcompany.erp;

import android.os.Bundle;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    /**
     * Force light status/navigation bar icons.
     *
     * The app's UI is always dark, but @capacitor/status-bar decides icon
     * colour from the *device's* night-mode setting whenever it can't
     * resolve an explicit style (StatusBar.getStyleForTheme() returns
     * "LIGHT" unless UI_MODE_NIGHT_YES). On a tablet running the system in
     * light mode that yields dark icons on our near-black bar — the clock
     * and battery become invisible, which was verified on an Android 15
     * tablet emulator (dumpsys reported mLastAppearance=LIGHT_STATUS_BARS).
     *
     * Setting it here, after super.onCreate() so it wins over the plugin's
     * own load-time call, makes the appearance independent of whatever
     * theme the device happens to be in.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyDarkSystemBars();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Posted to the view queue: the Capacitor bridge re-applies the
        // plugin's own style once the WebView is up, which lands after
        // onCreate/onResume return. Posting puts us last.
        getWindow().getDecorView().post(this::applyDarkSystemBars);
    }

    private void applyDarkSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
            getWindow(),
            getWindow().getDecorView()
        );
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
    }
}
