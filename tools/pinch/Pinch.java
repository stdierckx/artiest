import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * A two-finger gesture, injected. Run with app_process as the shell user.
 *
 * `adb shell input` injects one pointer and a canvas gesture takes two, so
 * until this existed there was no way to drive a pinch from a script — which
 * is exactly how a canvas that zoomed without telling its overlays shipped.
 * Writing to /dev/input is not the way in either: the shell is in the `input`
 * group and SELinux still refuses the write.
 *
 * So it does what /system/bin/input itself does — hands a `MotionEvent` to the
 * platform's own injector — reached by reflection because the holder is
 * hidden. The approach is the one scrcpy uses; none of its code is here.
 *
 * Usage: Pinch <cx> <cy> <r0> <r1> [steps] [stepMs] [angleDeg]
 *
 * Two contacts start [r0] apart on a line through (cx, cy) and end [r1] apart,
 * turned by [angleDeg] on the way: a spread zooms in, a squeeze zooms out, and
 * an angle twists. Coordinates are display pixels, the screen as you see it.
 */
public final class Pinch {

    public static void main(String[] args) throws Exception {
        float cx = Float.parseFloat(args[0]);
        float cy = Float.parseFloat(args[1]);
        float r0 = Float.parseFloat(args[2]);
        float r1 = Float.parseFloat(args[3]);
        int steps = args.length > 4 ? Integer.parseInt(args[4]) : 24;
        int stepMs = args.length > 5 ? Integer.parseInt(args[5]) : 16;
        double turn = args.length > 6 ? Math.toRadians(Double.parseDouble(args[6])) : 0.0;

        // Android 14 moved the singleton: InputManager.getInstance() now
        // asserts on a global context this process does not have, so the
        // holder to ask is InputManagerGlobal. Older builds keep the old one.
        Object im;
        Method inject;
        try {
            Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
            Method get = global.getDeclaredMethod("getInstance");
            get.setAccessible(true);
            im = get.invoke(null);
            inject = global.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (Throwable fallback) {
            Method get = InputManager.class.getDeclaredMethod("getInstance");
            get.setAccessible(true);
            im = get.invoke(null);
            inject = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        inject.setAccessible(true);

        long down = SystemClock.uptimeMillis();
        send(inject, im, down, down, MotionEvent.ACTION_DOWN, 1, cx, cy, r0, 0.0);
        send(inject, im, down, down,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, cx, cy, r0, 0.0);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            float r = (float) (r0 + (r1 - r0) * t);
            Thread.sleep(stepMs);
            send(inject, im, down, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, 2, cx, cy, r, turn * t);
        }
        long up = SystemClock.uptimeMillis();
        send(inject, im, down, up,
                MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, cx, cy, r1, turn);
        send(inject, im, down, up, MotionEvent.ACTION_UP, 1, cx, cy, r1, turn);
    }

    /** Two contacts on a line through (cx, cy), [r] apart from it, turned by [a]. */
    private static void send(
            Method inject, Object im, long downTime, long when,
            int action, int count, float cx, float cy, float r, double a) throws Exception {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        float dx = (float) (Math.cos(a) * r);
        float dy = (float) (Math.sin(a) * r);
        for (int i = 0; i < count; i++) {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            p.id = i;
            p.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = p;
            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            float s = i == 0 ? -1f : 1f;
            c.x = cx + dx * s;
            c.y = cy + dy * s;
            c.pressure = 1f;
            c.size = 1f;
            coords[i] = c;
        }
        MotionEvent event = MotionEvent.obtain(
                downTime, when, action, count, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        // 2 is INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH, which is what
        // /system/bin/input uses: it keeps the stream in order.
        inject.invoke(im, event, 2);
        event.recycle();
    }
}
