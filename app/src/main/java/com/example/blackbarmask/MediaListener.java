package com.example.blackbarmask;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 借助“通知使用权”读取系统的媒体会话，知道哪个 App 正在播放。
 * 只读取包名和播放状态，不读取通知内容。
 */
public class MediaListener extends NotificationListenerService {
    private MediaSessionManager msm;
    private final List<MediaController> controllers = new ArrayList<>();
    private final List<MediaController.Callback> callbacks = new ArrayList<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            this::rebuild;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        ComponentName cn = new ComponentName(this, MediaListener.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn);
            rebuild(msm.getActiveSessions(cn));
        } catch (SecurityException ignored) { }
    }

    @Override
    public void onListenerDisconnected() {
        try {
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Exception ignored) { }
        clear();
        AutoMode.onMedia(this, new HashMap<>());
        super.onListenerDisconnected();
    }

    private void clear() {
        for (int i = 0; i < controllers.size(); i++) {
            try {
                controllers.get(i).unregisterCallback(callbacks.get(i));
            } catch (Exception ignored) { }
        }
        controllers.clear();
        callbacks.clear();
    }

    private void rebuild(List<MediaController> list) {
        clear();
        if (list != null) {
            for (MediaController mc : list) {
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onPlaybackStateChanged(PlaybackState state) { publish(); }
                    @Override public void onSessionDestroyed() {
                        int i = controllers.indexOf(mc);
                        if (i >= 0) {
                            controllers.remove(i);
                            callbacks.remove(i);
                        }
                        publish();
                    }
                };
                mc.registerCallback(cb);
                controllers.add(mc);
                callbacks.add(cb);
            }
        }
        publish();
    }

    private void publish() {
        Map<String, Boolean> m = new HashMap<>();
        for (MediaController mc : controllers) {
            PlaybackState s = mc.getPlaybackState();
            boolean playing = s != null && (s.getState() == PlaybackState.STATE_PLAYING
                    || s.getState() == PlaybackState.STATE_BUFFERING);
            String pkg = mc.getPackageName();
            m.put(pkg, playing || Boolean.TRUE.equals(m.get(pkg)));
        }
        AutoMode.onMedia(this, m);
    }
}
