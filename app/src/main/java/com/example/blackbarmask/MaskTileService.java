package com.example.blackbarmask;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 下拉控制中心里的快捷开关磁贴。 */
public class MaskTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (MaskService.running) {
            stopService(new Intent(this, MaskService.class));
            // 服务销毁时会刷新磁贴，这里先乐观地更新一次
            setState(false);
        } else {
            // 通过一个可见的 Activity 来启动前台服务，避免后台启动限制
            Intent i = new Intent(this, ToggleActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(
                        this, 0, i, PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(i);
            }
        }
    }

    private void refresh() {
        setState(MaskService.running);
    }

    private void setState(boolean on) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
