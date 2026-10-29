package dev.kouragita.pocket;

/**
 * The five entry points Rivium's embedded host exports. The Rust side
 * registers exactly these on load, so the signatures must not change.
 */
public final class RiviumBridge {
    static {
        System.loadLibrary("pocket");
    }

    public static native int nativeStart(String[] args);

    public static native int nativeStop(long timeoutMillis);

    public static native int nativeStatus();

    public static native String nativeLastError();

    public static native String nativeVersion();

    private RiviumBridge() {}
}
