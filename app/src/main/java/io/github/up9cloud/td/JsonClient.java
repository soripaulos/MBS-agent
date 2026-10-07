package io.github.up9cloud.td;

/**
 * JNI surface of the prebuilt TDLib library (up9cloud/android-libtdjson, MIT). The package,
 * class and method names MUST stay exactly as they are: the native symbols in libtdjson.so
 * are bound to them (Java_io_github_up9cloud_td_JsonClient_*).
 *
 * The .so is not checked in; CI downloads it (see .github/workflows/build-debug.yml). When it
 * is missing, {@link #AVAILABLE} is false and the Telegram account feature reports itself as
 * unavailable instead of crashing.
 */
public final class JsonClient {
    public static final boolean AVAILABLE;
    public static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = null;
        try {
            System.loadLibrary("tdjson");
            ok = true;
        } catch (Throwable e) {
            err = String.valueOf(e.getMessage());
        }
        AVAILABLE = ok;
        LOAD_ERROR = err;
    }

    public static native int td_create_client_id();
    public static native void td_send(int client_id, String request);
    public static native String td_receive(double timeout);
    public static native String td_execute(String request);

    public interface LogMessageCallback {
        void call(int verbosity_level, String message);
    }
    public static native void td_set_log_message_callback(int max_verbosity_level, LogMessageCallback callback);

    public static native long td_json_client_create();
    public static native void td_json_client_send(long client, String request);
    public static native String td_json_client_receive(long client, double timeout);
    public static native String td_json_client_execute(long client, String request);
    public static native void td_json_client_destroy(long client);
}
