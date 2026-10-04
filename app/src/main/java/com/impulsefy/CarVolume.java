package com.impulsefy;

import android.content.Context;
import android.media.AudioAttributes;
import android.util.Log;
import java.lang.reflect.Method;

/** Optional Haval media-volume API, supplied by the car's ts.framework library. */
final class CarVolume {
    private final Object manager;
    private final Method read, write;
    private final int group, min, max;
    private int current;

    private CarVolume(Context context) throws ReflectiveOperationException {
        Class<?> type = Class.forName("ts.car.audio.AudioExtManager");
        manager = type.getMethod("getInstance", Context.class).invoke(null, context.getApplicationContext());
        group = (Integer) type.getMethod("getVolumeGroupIdForUsage", int.class).invoke(manager, AudioAttributes.USAGE_MEDIA);
        if (group < 0) throw new IllegalStateException("Media volume group unavailable");
        read = type.getMethod("getGroupVolume", int.class);
        write = type.getMethod("setGroupVolume", int.class, int.class, int.class);
        min = (Integer) type.getMethod("getGroupMinVolume", int.class).invoke(manager, group);
        max = (Integer) type.getMethod("getGroupMaxVolume", int.class).invoke(manager, group);
        current = (Integer) read.invoke(manager, group);
        if (min < 0 || max <= min || current < min || current > max) throw new IllegalStateException("Invalid media volume range");
        // Verify write access at the existing level before replacing software volume.
        if (!(Boolean) write.invoke(manager, group, current, 0)) throw new IllegalStateException("Media volume write denied");
    }

    static CarVolume create(Context context) {
        try {
            CarVolume volume = new CarVolume(context);
            Log.i("ImpulsefyVolume", "Native media group=" + volume.group + " range=" + volume.min + ".." + volume.max);
            return volume;
        } catch (ClassNotFoundException unavailable) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            Log.w("ImpulsefyVolume", "Native media volume unavailable", unavailable);
            return null;
        }
    }

    int max() { return max - min; }
    int get() {
        try {
            int value = (Integer) read.invoke(manager, group);
            if (value >= min && value <= max) current = value;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return current - min;
    }
    boolean set(int value) {
        try {
            int next = min + Math.max(0, Math.min(max(), value));
            if (!(Boolean) write.invoke(manager, group, next, 0)) return false;
            current = next;
            return true;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            Log.w("ImpulsefyVolume", "Native media volume write failed", unavailable);
            return false;
        }
    }
}
