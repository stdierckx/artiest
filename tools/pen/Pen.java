import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * One stylus, injected, at a pace you choose. Run with app_process as shell.
 *
 * `adb shell input stylus swipe` exists and takes a duration, and on this
 * tablet it ignores it: a swipe asked to take 2500 ms is delivered in about
 * 125. That is fine for drawing a line and useless for anything that has to be
 * *looked at* while the pen is down — the colour picker's ring, a drag
 * preview, a transform box — because the gesture is over before a screenshot
 * can be taken.
 *
 * So this sends the pointer stream itself, one MOVE per step with a real sleep
 * between them, the same way `Pinch` does and for the same reason. It also
 * carries pressure, which `input` does not: a pen that lands at pressure 1.0
 * and stays there is not what a brush's size curve is tuned against.
 *
 * Usage: Pen <x0> <y0> <x1> <y1> [steps] [stepMs] [pressure] [holdMs]
 *
 * Coordinates are display pixels, the screen as you are looking at it.
 * [holdMs] keeps the nib still at the far end before lifting, which is how a
 * gesture is held open long enough to be photographed.
 */
public final class Pen {

    public static void main(String[] args) throws Exception {
        float x0 = Float.parseFloat(args[0]);
        float y0 = Float.parseFloat(args[1]);
        float x1 = Float.parseFloat(args[2]);
        float y1 = Float.parseFloat(args[3]);
        int steps = args.length > 4 ? Integer.parseInt(args[4]) : 24;
        int stepMs = args.length > 5 ? Integer.parseInt(args[5]) : 16;
        float pressure = args.length > 6 ? Float.parseFloat(args[6]) : 0.6f;
        int holdMs = args.length > 7 ? Integer.parseInt(args[7]) : 0;

        // Android 14 moved the singleton. See Pinch for the whole story.
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
        send(inject, im, down, down, MotionEvent.ACTION_DOWN, x0, y0, pressure);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Thread.sleep(stepMs);
            send(inject, im, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    (float) (x0 + (x1 - x0) * t), (float) (y0 + (y1 - y0) * t), pressure);
        }
        // The hold is what a screenshot is taken during. MOVEs rather than
        // silence, because a pointer that stops sending is a pointer some
        // pipelines decide has gone away.
        long held = 0;
        while (held < holdMs) {
            Thread.sleep(Math.min(stepMs, holdMs - held));
            held += stepMs;
            send(inject, im, down, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, x1, y1, pressure);
        }
        send(inject, im, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x1, y1, 0f);
    }

    private static void send(
            Method inject, Object im, long downTime, long when,
            int action, float x, float y, float pressure) throws Exception {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_STYLUS;
        props[0] = p;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = pressure;
        c.size = 1f;
        coords[0] = c;
        MotionEvent event = MotionEvent.obtain(
                downTime, when, action, 1, props, coords,
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN, 0);
        inject.invoke(im, event, 2);
        event.recycle();
    }
}
