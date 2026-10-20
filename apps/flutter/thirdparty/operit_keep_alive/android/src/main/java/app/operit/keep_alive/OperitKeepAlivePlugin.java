package app.operit.keep_alive;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.PluginRegistry;
import java.util.HashSet;
import java.util.Set;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Connects the common lease protocol to the application-owned foreground Core service. */
public final class OperitKeepAlivePlugin implements FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware, PluginRegistry.NewIntentListener {
    private final Set<String> ownedLeases = new HashSet<>();
    private Context context;
    private MethodChannel channel;
    private Class<? extends Service> serviceType;
    private ActivityPluginBinding activityBinding;
    private final ArrayDeque<Map<String, Object>> pendingActions = new ArrayDeque<>();
    private boolean actionsReady;

    /** Attaches notification activation to the real activity independently of Core ownership. */
    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        activityBinding = binding;
        binding.addOnNewIntentListener(this);
        onNewIntent(binding.getActivity().getIntent());
    }

    /** Removes only this activity's listener during configuration changes. */
    @Override
    public void onDetachedFromActivityForConfigChanges() { onDetachedFromActivity(); }

    /** Reattaches activation routing after Android recreates its activity. */
    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) { onAttachedToActivity(binding); }

    /** Releases activity references without affecting the service notification. */
    @Override
    public void onDetachedFromActivity() {
        if (activityBinding != null) activityBinding.removeOnNewIntentListener(this);
        activityBinding = null;
    }

    /** Queues cold-start and existing-window activations until Dart installs its receiver. */
    @Override
    public boolean onNewIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(WorkNotificationPresenter.WORK_ID)) return false;
        String workId = intent.getStringExtra(WorkNotificationPresenter.WORK_ID);
        String encodedActivation = intent.getStringExtra(WorkNotificationPresenter.ACTIVATION);
        if (workId == null || workId.isEmpty() || encodedActivation == null || encodedActivation.isEmpty()) {
            throw new IllegalArgumentException("Work activation requires execution and route identity");
        }
        intent.removeExtra(WorkNotificationPresenter.WORK_ID);
        intent.removeExtra(WorkNotificationPresenter.ACTIVATION);
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("workId", workId);
        Map<String, Object> activation = new LinkedHashMap<>();
        try {
            org.json.JSONObject decoded = new org.json.JSONObject(encodedActivation);
            java.util.Iterator<String> keys = decoded.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = decoded.get(key);
                if (!(value instanceof String)) throw new IllegalArgumentException("Invalid work activation field");
                activation.put(key, value);
            }
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("Invalid work activation", error); }
        action.put("activation", activation);
        action.put("action", "open");
        pendingActions.addLast(action);
        flushActions();
        return true;
    }

    /** Delivers queued activations in order only after the application acknowledges readiness. */
    private void flushActions() {
        if (!actionsReady || channel == null) return;
        while (!pendingActions.isEmpty()) channel.invokeMethod("workAction", pendingActions.removeFirst());
    }

    /** Installs the shared channel without starting services before runtime storage is ready. */
    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        channel = new MethodChannel(binding.getBinaryMessenger(), "operit/keep_alive");
        channel.setMethodCallHandler(this);
    }

    /** Releases the engine channel while its independent Core service continues running. */
    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        KeepAliveServiceHost.forget(channel, ownedLeases);
        ownedLeases.clear();
        actionsReady = false;
        onDetachedFromActivity();
        channel.setMethodCallHandler(null);
        channel = null;
        context = null;
    }

    /** Requires exact nonempty protocol fields instead of deriving an application service name. */
    private String string(MethodCall call, String key) {
        Object value = call.argument(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException("Missing keep-alive argument: " + key);
        }
        return (String) value;
    }

    /** Dispatches lease requests and returns original service-start errors to Flutter. */
    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        try {
            switch (call.method) {
                case "initialize":
                    serviceType = Class.forName(string(call, "androidServiceClassName"), true,
                        context.getClassLoader()).asSubclass(Service.class);
                    KeepAliveServiceHost.observe(channel);
                    result.success(KeepAliveServiceHost.report());
                    return;
                case "workCapabilities":
                    result.success(KeepAliveServiceHost.workCapabilities());
                    return;
                case "workActionsReady":
                    actionsReady = true;
                    flushActions();
                    result.success(null);
                    return;
                case "publishWork":
                    throw new IllegalStateException("Android work presentation is owned by Core, not Flutter");
                case "acquire":
                    if (serviceType == null) throw new IllegalStateException("Keep-alive is not initialized");
                    ownedLeases.add(string(call, "id"));
                    KeepAliveServiceHost.acquire(context, serviceType,
                        string(call, "id"), string(call, "reason"), result);
                    return;
                case "release":
                    String released = string(call, "id");
                    result.success(KeepAliveServiceHost.release(released));
                    ownedLeases.remove(released);
                    return;
                default:
                    result.notImplemented();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            result.error("KEEP_ALIVE_ERROR", error.toString(), null);
        }
    }
}
