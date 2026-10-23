package io.gpsz.zygote;

import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.vmtools.Hooks;

import java.lang.reflect.Method;

final class LegacyFrame {
    private static volatile boolean broken;

    static boolean hook(Method m, Frames.Tx tx) {
        if (broken) return false;
        try {
            Hooks.hook(m, Hooks.EntryPointType.DIRECT, (original, frame) -> {
                Frames.Fr f = new Frames.Fr() {
                    @Override
                    public Object get(int idx) {
                        return frame.accessor().getReference(idx == Frames.RET ? EmulatedStackFrame.RETURN_VALUE_IDX : idx);
                    }

                    @Override
                    public void set(int idx, Object value) {
                        frame.accessor().setValue(idx == Frames.RET ? EmulatedStackFrame.RETURN_VALUE_IDX : idx, value);
                    }
                };
                try {
                    tx.run(() -> Transformers.invokeExactNoChecks(original, frame), f);
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
            }, Hooks.EntryPointType.DIRECT);
            return true;
        } catch (LinkageError e) {
            broken = true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private LegacyFrame() { }
}
